package space.deytt.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficActivityWindowTest {
    @Test
    fun keepsParticlesVisibleAcrossBriefGapsAfterRealTraffic() {
        val window = TrafficActivityWindow(gracePeriodMs = 3_500L)

        assertFalse(window.observe(rxBytes = 1_000L, txBytes = 2_000L, nowElapsedMs = 100L))
        assertTrue(window.observe(rxBytes = 1_700L, txBytes = 2_000L, nowElapsedMs = 800L))
        assertTrue(window.observe(rxBytes = 1_700L, txBytes = 2_000L, nowElapsedMs = 4_000L))
        assertFalse(window.observe(rxBytes = 1_700L, txBytes = 2_000L, nowElapsedMs = 4_301L))
    }

    @Test
    fun unsupportedCountersAndResetDoNotLeaveStaleTrafficVisible() {
        val window = TrafficActivityWindow(gracePeriodMs = 3_500L)
        window.observe(rxBytes = 1_000L, txBytes = 2_000L, nowElapsedMs = 100L)
        assertTrue(window.observe(rxBytes = 1_100L, txBytes = 2_000L, nowElapsedMs = 200L))

        assertFalse(window.observe(rxBytes = -1L, txBytes = -1L, nowElapsedMs = 300L))
        assertFalse(window.observe(rxBytes = 1_100L, txBytes = 2_000L, nowElapsedMs = 400L))

        window.observe(rxBytes = 1_100L, txBytes = 2_000L, nowElapsedMs = 500L)
        assertTrue(window.observe(rxBytes = 1_200L, txBytes = 2_000L, nowElapsedMs = 600L))
        window.reset()
        assertFalse(window.observe(rxBytes = 1_200L, txBytes = 2_000L, nowElapsedMs = 700L))
    }
}
