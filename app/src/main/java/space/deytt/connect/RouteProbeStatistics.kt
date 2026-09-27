package space.deytt.connect

import kotlin.math.roundToLong

internal data class RouteLatencySummary(
    val bestMillis: Long,
    val averageMillis: Long,
    val worstMillis: Long,
)

internal object RouteProbeStatistics {
    const val MAX_VALID_LATENCY_MILLIS = 8_000L

    fun isValidLatencyMillis(sample: Long): Boolean = sample in 0L..MAX_VALID_LATENCY_MILLIS

    /** Median dampens isolated slow HTTPS responses without hiding a route that answered. */
    fun medianLatencyMillis(samples: List<Long>): Long? {
        val sorted = samples.filter(::isValidLatencyMillis).sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2L
    }

    fun latencySummary(samples: List<Long>): RouteLatencySummary? {
        val valid = samples.filter(::isValidLatencyMillis)
        if (valid.isEmpty()) return null
        return RouteLatencySummary(
            bestMillis = valid.minOrNull()!!,
            averageMillis = valid.map(Long::toDouble).average().roundToLong(),
            worstMillis = valid.maxOrNull()!!,
        )
    }
}
