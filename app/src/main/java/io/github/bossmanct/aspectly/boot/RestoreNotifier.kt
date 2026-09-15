package io.github.bossmanct.aspectly.boot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.bossmanct.aspectly.MainActivity
import io.github.bossmanct.aspectly.R

/**
 * Tells the user why their settings are not applied, instead of failing silently.
 *
 * Aspectly's configuration does not survive a reboot — Samsung clears the aspect ratio
 * override at boot — and on a default Samsung device it often cannot reapply it,
 * because Auto Blocker disables USB debugging and wireless debugging goes down with it.
 *
 * Silently doing nothing would mean the user discovers it when a video starts cropping
 * again and has no idea why. Naming the exact toggle and linking straight to it is the
 * difference between a tool that breaks and one that asks for help.
 */
object RestoreNotifier {

    private const val CHANNEL_ID = "aspectly-restore"
    private const val NOTIFICATION_ID = 1

    fun notifyBlocked(context: Context, reason: Prerequisite) {
        if (reason == Prerequisite.NONE) return
        if (!canPost(context)) return

        ensureChannel(context)

        val intent = when (reason) {
            Prerequisite.USB_DEBUGGING_OFF,
            Prerequisite.WIRELESS_DEBUGGING_OFF,
            -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

            Prerequisite.NONE -> Intent(context, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Aspectly: ${reason.title}")
            .setContentText(reason.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason.detail))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Settings not applied",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shown when Aspectly cannot reapply your settings after a restart."
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
