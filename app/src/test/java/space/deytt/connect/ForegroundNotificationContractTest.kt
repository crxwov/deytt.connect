package space.deytt.connect

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationContractTest {
    @Test
    fun activeNotificationMustBeOngoingAndNotAutoCancelable() {
        val flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        assertTrue(ForegroundNotificationContract.isOngoing(flags))
        assertFalse(ForegroundNotificationContract.isAutoCancelable(flags))
        assertFalse(ForegroundNotificationContract.isUserClearable(flags))
    }

    @Test
    fun enginesUseDistinctStableNotificationIds() {
        assertNotEquals(
            ForegroundNotificationContract.LIBBOX_NOTIFICATION_ID,
            ForegroundNotificationContract.AWG_NOTIFICATION_ID,
        )
        assertEquals(42, ForegroundNotificationContract.LIBBOX_NOTIFICATION_ID)
        assertEquals(44, ForegroundNotificationContract.AWG_NOTIFICATION_ID)
    }
}
