package io.github.bossmanct.aspectly.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Finds adbd's listening port by scanning loopback.
 *
 * Android randomises the wireless debugging port on every boot, so it cannot be
 * stored. The obvious answer — mDNS — is no longer silent: since Android 16,
 * `NsdManager` discovery either requires local network permission or routes the user
 * through a system device picker. Neither works for restoring settings unattended.
 *
 * Scanning 127.0.0.1 sidesteps that. Loopback is not "the local network", so no
 * permission applies, no packet leaves the device, and nothing for a picker to ask
 * about.
 *
 * Three stages, cheapest first:
 *  1. Non-blocking TCP sweep — a blocking `Socket.connect()` holds a thread until it
 *     resolves, so thousands of ports would need thousands of threads. NIO holds the
 *     whole batch in flight on one.
 *  2. TLS probe — see [speaksTls]. This is the stage that actually mattered.
 *  3. Full ADB handshake, on the one or two survivors.
 */
object AdbPortScanner {

    /** Batched to stay well clear of the per-process file descriptor limit. */
    private const val BATCH = 600
    private const val SELECT_TIMEOUT_MS = 350L
    private const val TLS_PROBE_MS = 400

    private const val LIKELY_START = 32_768
    private const val LIKELY_END = 49_152

    suspend fun findPort(
        preferred: Int? = null,
        validate: suspend (Int) -> Boolean,
    ): Int? = withContext(Dispatchers.IO) {
        if (preferred != null && validate(preferred)) return@withContext preferred

        val (low, high) = ephemeralRange()
        val seen = HashSet<Int>()

        for (band in listOf(LIKELY_START..LIKELY_END, low..high)) {
            for (batch in band.filter { seen.add(it) }.chunked(BATCH)) {
                for (port in connectable(batch).filter(::speaksTls)) {
                    if (validate(port)) return@withContext port
                }
            }
        }
        null
    }

    /** Fires every connect in [ports] concurrently and returns those that completed. */
    private fun connectable(ports: List<Int>): List<Int> {
        val open = ArrayList<Int>()
        Selector.open().use { selector ->
            val channels = ports.mapNotNull { port ->
                runCatching {
                    SocketChannel.open().apply {
                        configureBlocking(false)
                        register(selector, SelectionKey.OP_CONNECT, port)
                        connect(InetSocketAddress(AspectlyAdbManager.LOOPBACK, port))
                    }
                }.getOrNull()
            }

            val deadline = System.currentTimeMillis() + SELECT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (selector.select(50) == 0) {
                    if (selector.keys().none { it.isValid }) break
                    continue
                }
                val iterator = selector.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()
                    val channel = key.channel() as SocketChannel
                    // finishConnect throws on refusal, which is the common case.
                    if (runCatching { channel.finishConnect() }.getOrDefault(false)) {
                        open.add(key.attachment() as Int)
                    }
                    key.cancel()
                    runCatching { channel.close() }
                }
            }
            channels.forEach { runCatching { it.close() } }
        }
        return open
    }

    /**
     * The stage that fixed this.
     *
     * A full ADB connect against a wrong port blocks for over a minute — it opens a TLS
     * socket and waits for a handshake that never arrives. Measured at 85 seconds on a
     * single wrong port, which made two earlier versions of this scanner look like a
     * concurrency problem when they were not.
     *
     * adbd's connect port speaks TLS and almost nothing else on loopback does. An SSL
     * exception still proves the peer answered at the TLS layer; a timeout or reset
     * proves it did not.
     */
    private fun speaksTls(port: Int): Boolean = runCatching {
        val factory = SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(AcceptAllCerts), null) }
            .socketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(AspectlyAdbManager.LOOPBACK, port), TLS_PROBE_MS)
            socket.soTimeout = TLS_PROBE_MS
            socket.startHandshake()
        }
        true
    }.getOrElse { it is SSLException }

    /** The scan only identifies ports; trust is established later by the real handshake. */
    private object AcceptAllCerts : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** The kernel's actual range, when readable — narrows the sweep on odd devices. */
    private fun ephemeralRange(): Pair<Int, Int> = runCatching {
        val parts = File("/proc/sys/net/ipv4/ip_local_port_range")
            .readText().trim().split(Regex("\\s+"))
        parts[0].toInt() to parts[1].toInt()
    }.getOrDefault(32_768 to 60_999)
}
