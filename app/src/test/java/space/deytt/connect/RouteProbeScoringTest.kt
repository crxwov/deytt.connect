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
    fun tiedMeasurementsStillHaveExactlyOneDeterministicBestRoute() {
        val samples = listOf("one", "two", "three").map { RouteProbeSample(it, 100, 500_000) }
        val grades = RouteProbeScoring.grade(samples)

        assertEquals("one", RouteProbeScoring.bestRouteId(samples))
        assertEquals(RouteGrade.GOOD, grades["one"])
        assertEquals(1, grades.values.count { it == RouteGrade.GOOD })
        assertEquals(RouteGrade.MEDIUM, grades["three"])
        assertEquals(RouteGrade.POOR, grades["two"])
    }

    @Test
    fun ranksPartialLatencySamplesAsTheyArrive() {
        val first = listOf(RouteProbeSample("first", 70L, null))
        assertEquals(RouteGrade.GOOD, RouteProbeScoring.gradeByLatency(first)["first"])

        val updated = listOf(
            RouteProbeSample("first", 70L, null),
            RouteProbeSample("second", 110L, null),
            RouteProbeSample("third", 90L, null),
            RouteProbeSample("waiting", null, null),
        )
        val grades = RouteProbeScoring.gradeByLatency(updated)
        assertEquals(RouteGrade.GOOD, grades["first"])
        assertEquals(RouteGrade.MEDIUM, grades["third"])
        assertEquals(RouteGrade.POOR, grades["second"])
        assertEquals(RouteGrade.UNRATED, grades["waiting"])
    }

    @Test
    fun equalLatencySamplesAreNotMarkedAsWorst() {
        val grades = RouteProbeScoring.gradeByLatency(
            listOf(RouteProbeSample("first", 80L, null), RouteProbeSample("second", 80L, null)),
        )
        assertEquals(RouteGrade.GOOD, grades["first"])
        assertEquals(RouteGrade.MEDIUM, grades["second"])
    }
}
