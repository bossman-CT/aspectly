package io.github.bossmanct.aspectly.boot

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.bossmanct.aspectly.config.Restore
import io.github.bossmanct.aspectly.log.ActivityLog
import java.util.concurrent.TimeUnit

/**
 * Reapplies the user's configuration after a restart, retrying until adbd is actually
 * reachable.
 *
 * The first version of this ran directly in the boot receiver and failed on a real
 * reboot — it woke, scanned, and gave up in twelve seconds because adbd was not up yet:
 *
 * ```
 * 13:18:49  BOOT restore starting
 * 13:19:01  AUTOCONNECT failed: No adbd found on loopback
 * ```
 *
 * Nothing was wrong with the restore itself; it simply asked too early and only once.
 * Wireless debugging needs Wi-Fi, Wi-Fi is not up when `BOOT_COMPLETED` fires, and on
 * this device the toggle has proven capable of disappearing for minutes at a time.
 *
 * WorkManager fits because it will not start the job until the network constraint is
 * satisfied, and it survives the process dying between attempts — neither of which a
 * broadcast receiver can do.
 */
class RestoreWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ActivityLog.record(applicationContext, "RESTORE attempt ${runAttemptCount + 1}")

        // Retrying is pointless when a toggle is off — adbd is not running and will not
        // start on its own. Tell the user which one instead of burning attempts.
        val blocked = Prerequisite.check(applicationContext)
        if (blocked != Prerequisite.NONE) {
            ActivityLog.record(applicationContext, "RESTORE blocked: ${blocked.title}")
            RestoreNotifier.notifyBlocked(applicationContext, blocked)
            return Result.retry()
        }

        return Restore.run(applicationContext).fold(
            onSuccess = {
                RestoreNotifier.clear(applicationContext)
                Result.success()
            },
            onFailure = {
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    ActivityLog.record(
                        applicationContext,
                        "RESTORE giving up after ${runAttemptCount + 1} attempts",
                    )
                    Result.failure()
                } else {
                    Result.retry()
                }
            },
        )
    }

    companion object {
        private const val WORK_NAME = "aspectly-restore"
        private const val MAX_ATTEMPTS = 10

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setConstraints(
                    Constraints.Builder()
                        // adbd only listens while wireless debugging is on, which
                        // requires Wi-Fi — so there is no point running before then.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    30,
                    TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
