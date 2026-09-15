package io.github.bossmanct.aspectly.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.bossmanct.aspectly.log.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reapplies the user's configuration after a restart.
 *
 * Nothing Aspectly sets survives a reboot — Samsung clears the aspect ratio override
 * and letterbox style resets itself — so this is what makes the app feel like it "just
 * works" rather than something to re-do every time.
 *
 * It can fail legitimately: wireless debugging only runs on Wi-Fi, so a phone that
 * boots on cellular has no adbd to reach. That is a wait, not an error, and the UI
 * should say so.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Hand off rather than restoring here. A receiver gets ~10 seconds and
                // one shot, and adbd is reliably not up that early.
                ActivityLog.record(appContext, "BOOT queueing restore")
                RestoreWorker.enqueue(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
