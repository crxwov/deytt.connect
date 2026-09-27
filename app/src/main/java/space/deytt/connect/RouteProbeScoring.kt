package space.deytt.connect

data class RouteProbeSample(
    val routeId: String,
    val latencyMillis: Long?,
    val downloadBytesPerSecond: Long?,
)

enum class RouteGrade { GOOD, MEDIUM, POOR, UNRATED }

object RouteProbeScoring {
    /** Ranks whichever HTTP latency samples are available while the full scan is still running. */
    fun gradeByLatency(samples: List<RouteProbeSample>): Map<String, RouteGrade> {
        val measured = samples.filter { it.latencyMillis?.let(RouteProbeStatistics::isValidLatencyMillis) == true }
        if (measured.isEmpty()) return samples.associate { it.routeId to RouteGrade.UNRATED }
        val fastest = measured.minOf { it.latencyMillis!! }
        val slowest = measured.maxOf { it.latencyMillis!! }
        val unique = measured.sortedBy { it.routeId }
        return samples.associate { sample ->
            val latency = sample.latencyMillis?.takeIf(RouteProbeStatistics::isValidLatencyMillis)
            val grade = when {
                latency == null -> RouteGrade.UNRATED
                fastest == slowest && latency == fastest ->
                    if (sample.routeId == unique.firstOrNull()?.routeId) RouteGrade.GOOD else RouteGrade.MEDIUM
                latency == fastest -> RouteGrade.GOOD
                latency == slowest -> RouteGrade.POOR
                else -> RouteGrade.MEDIUM
            }
            sample.routeId to grade
        }
    }

    /** Compares only complete HTTP latency + download samples from one country scan. */
    fun grade(samples: List<RouteProbeSample>): Map<String, RouteGrade> {
        val complete = samples.filter(::isComplete)
        if (complete.size < 2) return samples.associate { it.routeId to RouteGrade.UNRATED }
        val ranked = ranked(complete)

        val grades = ranked.mapIndexed { index, (id, _) ->
            val percentile = index.toDouble() / ranked.size
            id to when {
                index == 0 -> RouteGrade.GOOD
                percentile >= 2.0 / 3.0 -> RouteGrade.POOR
                else -> RouteGrade.MEDIUM
            }
        }.toMap().toMutableMap()
        samples.filter { it.routeId !in grades }.forEach { grades[it.routeId] = RouteGrade.UNRATED }
        return grades
    }

    fun bestRouteId(samples: List<RouteProbeSample>): String? =
        samples.filter(::isComplete).takeIf { it.size >= 2 }?.let(::ranked)?.firstOrNull()?.first

    private fun isComplete(sample: RouteProbeSample): Boolean =
        sample.latencyMillis?.let(RouteProbeStatistics::isValidLatencyMillis) == true &&
            sample.downloadBytesPerSecond != null && sample.downloadBytesPerSecond > 0

    private fun ranked(samples: List<RouteProbeSample>): List<Pair<String, Double>> {
        val latencies = samples.map { it.latencyMillis!!.toDouble() }
        val speeds = samples.map { it.downloadBytesPerSecond!!.toDouble() }
        fun scale(value: Double, values: List<Double>, lowerIsBetter: Boolean): Double {
            val minimum = values.minOrNull() ?: return .5
            val maximum = values.maxOrNull() ?: return .5
            if (maximum == minimum) return .5
            val normalized = (value - minimum) / (maximum - minimum)
            return if (lowerIsBetter) 1.0 - normalized else normalized
        }
        return samples.map { sample ->
            val score = (
                scale(sample.latencyMillis!!.toDouble(), latencies, lowerIsBetter = true) +
                    scale(sample.downloadBytesPerSecond!!.toDouble(), speeds, lowerIsBetter = false)
                ) / 2.0
            sample.routeId to score
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
    }
}
