package io.github.bossmanct.aspectly.config

import android.content.Context
import io.github.bossmanct.aspectly.adb.BarSide

/**
 * What the user asked for, stored on device.
 *
 * This is the only record that survives a reboot. Samsung wipes
 * `OVERRIDE_MIN_ASPECT_RATIO_LARGE` at boot and letterbox style resets itself, so
 * after a restart the system knows nothing and this file knows everything — which is
 * what makes "reconnect and put it back" possible.
 */
class AspectlyConfig(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aspectly_config", Context.MODE_PRIVATE)

    var forcedApps: Set<String>
        get() = prefs.getStringSet(KEY_FORCED, emptySet()).orEmpty()
        private set(value) = prefs.edit().putStringSet(KEY_FORCED, value).apply()

    var barSide: BarSide
        get() = runCatching { BarSide.valueOf(prefs.getString(KEY_BAR, null) ?: "") }
            .getOrDefault(BarSide.LEFT)
        set(value) = prefs.edit().putString(KEY_BAR, value.name).apply()

    /** Pairing is once per device, so the setup fields collapse away after it succeeds. */
    var hasPaired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRED, value).apply()

    fun isForced(packageName: String): Boolean = packageName in forcedApps

    fun setForced(packageName: String, forced: Boolean) {
        forcedApps = if (forced) forcedApps + packageName else forcedApps - packageName
    }

    /** The global reset. Forgets everything so nothing is reapplied on next boot. */
    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_FORCED = "forced_apps"
        const val KEY_BAR = "bar_side"
        const val KEY_PAIRED = "has_paired"
    }
}
