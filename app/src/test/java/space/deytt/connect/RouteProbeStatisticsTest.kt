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

    @Test
    fun latencySummaryUsesCurrentSuccessfulRoutesAndRoundsAverage() {
        assertEquals(
            RouteLatencySummary(bestMillis = 60L, averageMillis = 82L, worstMillis = 103L),
            RouteProbeStatistics.latencySummary(listOf(60L, -1L, 83L, 103L)),
        )
    }

    @Test
    fun latencySummaryIsEmptyUntilThereIsAValidResponse() {
        assertNull(RouteProbeStatistics.latencySummary(listOf(-1L)))
    }

    @Test
    fun latencySummaryRejectsDurationsLongerThanTheProbeDeadline() {
        assertEquals(
            RouteLatencySummary(bestMillis = 100L, averageMillis = 100L, worstMillis = 100L),
            RouteProbeStatistics.latencySummary(listOf(100L, 3_561_414L)),
        )
    }
}
