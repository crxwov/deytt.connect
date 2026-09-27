package space.deytt.connect

internal object RouteProbeStatistics {
    /** Median dampens isolated slow HTTPS responses without hiding a route that answered. */
    fun medianLatencyMillis(samples: List<Long>): Long? {
        val sorted = samples.filter { it >= 0L }.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2L
    }
}
