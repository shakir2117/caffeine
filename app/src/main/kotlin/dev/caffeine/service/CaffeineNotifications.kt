package dev.caffeine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.caffeine.R
import dev.caffeine.core.CaffeineState
import dev.caffeine.ui.DurationFormat
import dev.caffeine.ui.MainActivity

object CaffeineNotifications {

    const val CHANNEL_ID = "caffeine_status"
    const val ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW, // silent, no heads-up
        ).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** True when the FGS notification will actually be visible (permission + channel + app toggle). */
    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Ongoing notification with the minutes still left and a Stop action.
     * The small countdown in the header is the system chronometer for the same deadline.
     * [countdownEndsAtMillis] is that deadline on the wall clock, kept stable across updates
     * so the countdown is not restarted when the minutes text changes.
     */
    fun build(
        context: Context,
        active: CaffeineState.Active?,
        nowElapsed: Long,
        countdownEndsAtMillis: Long? = null,
    ): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = PendingIntent.getForegroundService(context, 0, CaffeineService.stopIntent(context), flags)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
        val remaining = active?.remainingMillis(nowElapsed)
        val text = when {
            active == null -> context.getString(R.string.notification_restoring)
            remaining == null -> context.getString(R.string.indefinitely)
            else -> DurationFormat.remaining(context, remaining)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_caffeine)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .addAction(0, context.getString(R.string.action_stop), stopIntent)
            // Android 12 delays FGS notifications by up to 10 s for short services; show ours at once.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (remaining != null) {
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                    setWhen(countdownEndsAtMillis ?: (System.currentTimeMillis() + remaining))
                    setShowWhen(true)
                } else {
                    setShowWhen(false)
                }
            }
            .build()
    }
}
