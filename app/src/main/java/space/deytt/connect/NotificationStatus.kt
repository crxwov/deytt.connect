package space.deytt.connect

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.annotation.SuppressLint

/** Small, user-visible connection feedback shared by both tunnel engines. */
object NotificationStatus {
    private const val CHANNEL_ID = "connection-events"
    private const val NOTIFICATION_ID = 43

    @SuppressLint("NewApi")
    fun showConnected(context: Context, persistent: Boolean = false) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Состояние подключения",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Подтверждение успешного подключения"
                },
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            43,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
                .setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle("deytt./connect")
                .setContentText("Подключение активно")
                .setContentIntent(openApp)
                .setCategory(Notification.CATEGORY_STATUS)
        if (persistent) {
            builder.setOngoing(true)
        } else {
            builder.setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setTimeoutAfter(6_000)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
