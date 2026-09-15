package io.github.bossmanct.aspectly.boot

import android.content.Context
import android.provider.Settings

/**
 * Why Aspectly cannot reach adbd right now.
 *
 * These are readable by any app without permission, which is what makes an honest
 * failure message possible: instead of "couldn't connect", the app can say exactly
 * which toggle is off and send the user straight to it.
 */
enum class Prerequisite(val title: String, val detail: String) {

    /**
     * The one nobody would guess. Wireless debugging silently depends on USB debugging
     * being enabled — with no cable involved — and Samsung's Auto Blocker disables USB
     * debugging at boot by default, taking wireless down with it.
     */
    USB_DEBUGGING_OFF(
        title = "USB debugging is off",
        detail = "Wireless debugging needs it enabled, even with no cable attached. " +
            "Samsung's Auto Blocker turns it off when your phone restarts, which is " +
            "why your settings stopped applying.",
    ),

    WIRELESS_DEBUGGING_OFF(
        title = "Wireless debugging is off",
        detail = "Aspectly reaches your phone through it. Turning it back on is all " +
            "that's needed — your pairing is still valid.",
    ),

    NONE(
        title = "",
        detail = "",
    ),
    ;

    companion object {
        fun check(context: Context): Prerequisite {
            val resolver = context.contentResolver
            val usb = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0)
            if (usb != 1) return USB_DEBUGGING_OFF

            val wireless = Settings.Global.getInt(resolver, "adb_wifi_enabled", 0)
            if (wireless != 1) return WIRELESS_DEBUGGING_OFF

            return NONE
        }
    }
}
