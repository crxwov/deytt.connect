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
}
