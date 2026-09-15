package io.github.bossmanct.aspectly.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Aspectly's ADB client identity.
 *
 * Connections target loopback (127.0.0.1) rather than the device's LAN address: adbd
 * binds all interfaces, so talking to ourselves works, the traffic never touches the
 * network, and it keeps working on Wi-Fi with client isolation.
 */
class AspectlyAdbManager private constructor(
    private val appContext: Context,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey = AdbKeyManager.privateKey(appContext)

    override fun getCertificate(): Certificate = AdbKeyManager.certificate(appContext)

    override fun getDeviceName(): String = "Aspectly"

    companion object {
        const val LOOPBACK = "127.0.0.1"

        @Volatile
        private var instance: AspectlyAdbManager? = null

        fun getInstance(context: Context): AspectlyAdbManager =
            instance ?: synchronized(this) {
                instance ?: AspectlyAdbManager(context.applicationContext)
                    .also { instance = it }
            }

        /**
         * Discards the manager so the next call builds a fresh one.
         *
         * A connection that dies mid-sequence leaves the manager holding a dead socket,
         * and every subsequent connect against it fails — including against the correct
         * port. Without this, one broken command sequence poisons the app until
         * restart.
         */
        fun reset() = synchronized(this) {
            runCatching { instance?.disconnect() }
            instance = null
        }
    }
}
