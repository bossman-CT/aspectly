package io.github.bossmanct.aspectly.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds the port adbd is listening on.
 *
 * Android randomises the wireless debugging port on every boot, so it cannot be
 * stored and reused. Instead adbd advertises itself over mDNS, which is how desktop
 * `adb` auto-discovers devices — and we can listen for the same broadcast from on the
 * device itself.
 *
 * Two distinct services:
 *  - [CONNECT] is advertised whenever wireless debugging is on.
 *  - [PAIRING] exists only while the pairing dialog is open, on a different port, and
 *    disappears when it closes. The six-digit code is never broadcast — discovery is
 *    public, authorisation is not.
 */
object AdbDiscovery {

    const val CONNECT = "_adb-tls-connect._tcp"
    const val PAIRING = "_adb-tls-pairing._tcp"

    suspend fun findPort(
        context: Context,
        serviceType: String,
        timeoutMs: Long = 10_000,
    ): Int? = withTimeoutOrNull(timeoutMs) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        suspendCancellableCoroutine { cont ->
            val listener = discoveryListener(nsd, cont)
            try {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: IllegalArgumentException) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
        }
    }

    private fun discoveryListener(
        nsd: NsdManager,
        cont: CancellableContinuation<Int?>,
    ) = object : NsdManager.DiscoveryListener {

        override fun onServiceFound(service: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(resolved.port)
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            })
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            if (cont.isActive) cont.resume(null)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onServiceLost(service: NsdServiceInfo) = Unit
    }
}
