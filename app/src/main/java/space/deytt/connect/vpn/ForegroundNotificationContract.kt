package space.deytt.connect

import android.app.Notification

/** Shared assertions for the two service-owned foreground notification paths. */
object ForegroundNotificationContract {
    const val LIBBOX_NOTIFICATION_ID = 42
    const val AWG_NOTIFICATION_ID = 44

    fun isOngoing(flags: Int): Boolean = flags and Notification.FLAG_ONGOING_EVENT != 0

    fun isAutoCancelable(flags: Int): Boolean = flags and Notification.FLAG_AUTO_CANCEL != 0

    fun isUserClearable(flags: Int): Boolean = flags and Notification.FLAG_NO_CLEAR == 0
}
