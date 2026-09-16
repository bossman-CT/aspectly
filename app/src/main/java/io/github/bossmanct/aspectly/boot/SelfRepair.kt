package io.github.bossmanct.aspectly.boot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.github.bossmanct.aspectly.log.ActivityLog

/**
 * Turns wireless debugging back on after a restart, so Aspectly can reach adbd without
 * the user flipping switches.
 *
 * **The problem this solves.** Samsung's Auto Blocker disables USB debugging at boot;
 * wireless debugging silently depends on USB debugging; so after every restart adbd is
 * not running and nothing can be restored. Retrying does not help — the daemon does not
 * exist. Measured on hardware: five retries over four minutes, all correctly failing.
 *
 * **Why it needs a privileged permission.** Writing `adb_enabled` requires
 * `WRITE_SECURE_SETTINGS`, which users cannot grant. Aspectly grants it to itself over
 * the ADB channel it already holds, once, with consent — and unlike the ADB connection,
 * the grant survives a reboot. That asymmetry is the whole point.
 *
 * This is deliberately opt-in and separately revocable. A user who declines keeps a
 * working app that needs thirty seconds of attention after each restart.
 */
object SelfRepair {

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Enables whichever debugging toggle is off. Order matters: wireless debugging is
     * refused while USB debugging is off, so USB goes first.
     */
    suspend fun enableDebugging(context: Context): Result<String> {
        if (!isGranted(context)) {
            return Result.failure(IllegalStateException("Self-repair not enabled"))
        }

        return runCatching {
            val resolver = context.contentResolver
            val changed = mutableListOf<String>()

            if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
                changed += "USB debugging"
            }
            if (Settings.Global.getInt(resolver, ADB_WIFI, 0) != 1) {
                Settings.Global.putInt(resolver, ADB_WIFI, 1)
                changed += "wireless debugging"
            }

            val summary = if (changed.isEmpty()) {
                "already on"
            } else {
                "turned on ${changed.joinToString(" and ")}"
            }
            ActivityLog.record(context, "SELF-REPAIR $summary")
            summary
        }.onFailure { ActivityLog.record(context, "SELF-REPAIR failed: ${it.message}") }
    }

    private const val ADB_WIFI = "adb_wifi_enabled"
}
