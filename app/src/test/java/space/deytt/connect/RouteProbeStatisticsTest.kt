package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteProbeStatisticsTest {
    @Test
    fun medianRejectsOneSlowOutlier() {
        assertEquals(182L, RouteProbeStatistics.medianLatencyMillis(listOf(180L, 1_500L, 182L)))
    }

    @Test
    fun medianUsesAvailableResponsesWhenOneSampleTimedOut() {
        assertEquals(241L, RouteProbeStatistics.medianLatencyMillis(listOf(240L, 242L)))
    }

    @Test
    fun noSuccessfulSamplesHaveNoLatency() {
        assertNull(RouteProbeStatistics.medianLatencyMillis(emptyList()))
    }
}
