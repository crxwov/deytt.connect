package space.deytt.connect

import org.junit.Assert.*
import org.junit.Test

class OpenFluxLeaseScheduleTest {
    private val now = 1_000_000L

    @Test fun freshLeaseUsesNormalRenewalInterval() {
        assertEquals(600_000L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 1800, now))
        assertFalse(OpenFluxLeaseSchedule.needsRefreshBeforeStart(now + 1800, now))
    }

    @Test fun connectingTwentyFiveMinutesIntoLeaseRenewsBeforeExpiry() {
        val expiry = now + 1800
        val connectAt = now + 25 * 60
        assertEquals(180_000L, OpenFluxLeaseSchedule.renewalDelayMillis(expiry, connectAt))
        assertFalse(OpenFluxLeaseSchedule.needsRefreshBeforeStart(expiry, connectAt))
        assertTrue(connectAt * 1000 + OpenFluxLeaseSchedule.renewalDelayMillis(expiry, connectAt) < expiry * 1000)
    }

    @Test fun retryAlsoRespectsRemainingMargin() {
        assertEquals(60_000L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 1800, now, retry = true))
        assertEquals(30_000L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 150, now, retry = true))
        assertEquals(60_000L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 120, now, retry = true))
        assertEquals(60_000L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 90, now, retry = true))
        assertEquals(30_000L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 30, now, retry = true))
        assertEquals(0L, OpenFluxLeaseSchedule.renewalDelayMillis(now, now, retry = true))
    }

    @Test fun renewedLeaseResetsScheduleFromNewDeadline() {
        assertEquals(30_000L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 150, now))
        assertEquals(600_000L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 1800, now))
    }

    @Test fun nearExpiryAdmissionRefreshesAndNeverSchedulesAfterExpiration() {
        assertFalse(OpenFluxLeaseSchedule.needsRefreshBeforeStart(now + 121, now))
        assertTrue(OpenFluxLeaseSchedule.needsRefreshBeforeStart(now + 120, now))
        assertTrue(OpenFluxLeaseSchedule.needsRefreshBeforeStart(now + 1, now))
        assertTrue(OpenFluxLeaseSchedule.needsRefreshBeforeStart(now - 1, now))
        assertEquals(0L, OpenFluxLeaseSchedule.renewalDelayMillis(now + 1, now))
        assertEquals(0L, OpenFluxLeaseSchedule.renewalDelayMillis(now - 1, now))
    }
}
