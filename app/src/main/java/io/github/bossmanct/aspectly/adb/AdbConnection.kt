package io.github.bossmanct.aspectly.adb

import android.content.Context
import io.github.bossmanct.aspectly.log.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates discovery, pairing and connection.
 *
 * Pairing happens once, ever. After that the device trusts [AdbKeyManager]'s key and
 * [connect] works unattended — which is what makes automatic restore after a reboot
 * possible without asking the user for anything.
 */
object AdbConnection {

    /**
     * [pairingPort] is supplied by the user rather than discovered. mDNS discovery
     * hands control to a system device picker on this Android version, and the pairing
     * dialog regenerates both its port and its code every time it is opened — so
     * anything requiring a round trip through a picker invalidates the values it was
     * looking for.
     */
    suspend fun pair(context: Context, pairingPort: Int, pairingCode: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val port = pairingPort
                ActivityLog.record(context, "PAIR attempting on port $port")
                val ok = AspectlyAdbManager.getInstance(context)
                    .pair(AspectlyAdbManager.LOOPBACK, port, pairingCode)
                if (!ok) error("Device rejected the pairing code")
                ActivityLog.record(context, "PAIR success")
                "Paired on port $port"
            }.onFailure { ActivityLog.record(context, "PAIR failed: ${it.message}") }
        }

    suspend fun connect(context: Context, connectPort: Int): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val port = connectPort
                ActivityLog.record(context, "CONNECT attempting on port $port")
                val ok = AspectlyAdbManager.getInstance(context)
                    .connect(AspectlyAdbManager.LOOPBACK, port)
                if (!ok) error("Connection refused")
                ActivityLog.record(context, "CONNECT success")
                "Connected on port $port"
            }.onFailure { ActivityLog.record(context, "CONNECT failed: ${it.message}") }
        }

    private const val PREFS = "aspectly_adb"
    private const val KEY_LAST_PORT = "last_connect_port"

    /**
     * Connects without asking the user anything. This is what runs after a reboot.
     *
     * Scans loopback for listening ports, then tries an ADB handshake against each —
     * a TCP accept only proves something is there, not that it is adbd.
     */
    suspend fun autoConnect(context: Context): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val preferred = prefs.getInt(KEY_LAST_PORT, -1).takeIf { it > 0 }

                var probed = 0

                val scanStart = System.currentTimeMillis()
                val port = AdbPortScanner.findPort(preferred) { candidate ->
                    probed++
                    ActivityLog.record(context, "SCAN probing $candidate")
                    // Reset before *every* probe, not just once at the start. A failed
                    // connect leaves the manager holding a dead socket, so without this
                    // the first wrong port poisons every candidate after it — including
                    // the correct one.
                    AspectlyAdbManager.reset()
                    runCatching {
                        AspectlyAdbManager.getInstance(context)
                            .connect(AspectlyAdbManager.LOOPBACK, candidate)
                    }.getOrDefault(false)
                } ?: error("No adbd found on loopback (probed $probed listening port(s))")
                val scanMs = System.currentTimeMillis() - scanStart

                prefs.edit().putInt(KEY_LAST_PORT, port).apply()
                ActivityLog.record(context, "AUTOCONNECT success on $port in ${scanMs}ms")
                "Auto-connected on $port (${scanMs}ms, probed $probed)"
            }.onFailure { ActivityLog.record(context, "AUTOCONNECT failed: ${it.message}") }
        }

    fun isConnected(context: Context): Boolean =
        runCatching { AspectlyAdbManager.getInstance(context).isConnected }.getOrDefault(false)

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        runCatching { AspectlyAdbManager.getInstance(context).disconnect() }
        ActivityLog.record(context, "DISCONNECT")
    }
}
