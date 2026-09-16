package io.github.bossmanct.aspectly.config

import android.content.Context
import io.github.bossmanct.aspectly.adb.AdbConnection
import io.github.bossmanct.aspectly.adb.AdbSession
import io.github.bossmanct.aspectly.adb.AdbShell
import io.github.bossmanct.aspectly.adb.AspectlyCommands
import io.github.bossmanct.aspectly.log.ActivityLog

/**
 * Puts the user's configuration back after a reboot.
 *
 * Samsung repopulates the `OVERRIDE_MIN_ASPECT_RATIO_LARGE` override map at boot,
 * discarding user entries, and letterbox style resets itself — so a restart returns
 * the device to stock. That is a genuinely useful safety property (anything that goes
 * wrong is one restart from clean), but it means the settings have to be reapplied
 * every time.
 */
object Restore {

    suspend fun run(context: Context): Result<String> = AdbSession.exclusive {
        runLocked(context)
    }

    suspend fun clearAll(context: Context): Result<String> = AdbSession.exclusive {
        clearAllLocked(context)
    }

    private suspend fun runLocked(context: Context): Result<String> {
        val config = AspectlyConfig(context)
        val apps = config.forcedApps
        if (apps.isEmpty()) {
            return Result.success("Nothing configured")
        }

        AdbConnection.autoConnect(context).onFailure { return Result.failure(it) }

        var applied = 0
        apps.forEach { packageName ->
            AdbShell.runAll(context, AspectlyCommands.applyAspectRatio(packageName))
                .onSuccess { applied++ }
        }
        AdbShell.run(context, AspectlyCommands.setBarPosition(config.barSide))

        ActivityLog.record(context, "RESTORE reapplied $applied of ${apps.size} app(s)")
        return Result.success("Restored $applied of ${apps.size} app(s)")
    }

    /** Returns every app to stock and forgets the configuration. */
    private suspend fun clearAllLocked(context: Context): Result<String> {
        val config = AspectlyConfig(context)
        val apps = config.forcedApps

        AdbConnection.autoConnect(context).onFailure { return Result.failure(it) }

        apps.forEach { packageName ->
            AdbShell.runAll(context, AspectlyCommands.clearAspectRatio(packageName))
        }
        AdbShell.run(context, AspectlyCommands.clearBarPosition())
        config.clear()

        ActivityLog.record(context, "RESET cleared ${apps.size} app(s)")
        return Result.success("Reset ${apps.size} app(s) to default")
    }
}
