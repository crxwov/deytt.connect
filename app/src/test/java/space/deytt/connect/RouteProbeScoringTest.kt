package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteProbeScoringTest {
    @Test
    fun ranksBalancedLatencyAndDownloadWithinCountry() {
        val grades = RouteProbeScoring.grade(
            listOf(
                RouteProbeSample("fast", 20, 900_000),
                RouteProbeSample("middle", 50, 600_000),
                RouteProbeSample("slow", 100, 200_000),
            ),
        )
        assertEquals(RouteGrade.GOOD, grades["fast"])
        assertEquals(RouteGrade.MEDIUM, grades["middle"])
        assertEquals(RouteGrade.POOR, grades["slow"])
    }

    @Test
    fun leavesSingleOrIncompleteCandidateUnrated() {
        val grades = RouteProbeScoring.grade(
            listOf(
                RouteProbeSample("one", 22, 400_000),
                RouteProbeSample("failed", null, null),
            ),
        )
        assertEquals(RouteGrade.UNRATED, grades["one"])
        assertEquals(RouteGrade.UNRATED, grades["failed"])
    }
    @Test
    fun doesNotRankFailedZeroOrNegativeSamples() {
        val samples = listOf(RouteProbeSample("zero", 1, 0), RouteProbeSample("negative", -1, 100))
        assertEquals(null, RouteProbeScoring.bestRouteId(samples))
        assertEquals(setOf(RouteGrade.UNRATED), RouteProbeScoring.grade(samples).values.toSet())
    }

    @Test
    fun equalMeasurementsReceiveEqualGrades() {
        val samples = listOf("one", "two", "three").map { RouteProbeSample(it, 100, 500_000) }
        assertEquals(1, RouteProbeScoring.grade(samples).values.toSet().size)
    }
}
