package space.deytt.connect

import android.content.Context

/** Short-lived real samples bound to the current signed-in session. */
object RouteQualityStore {
    private const val MAX_AGE_MS = 15 * 60 * 1_000L
    fun write(context: Context, sample: RouteProbeSample) {
        val latency = sample.latencyMillis?.takeIf(RouteProbeStatistics::isValidLatencyMillis) ?: return
        val speed = sample.downloadBytesPerSecond?.takeIf { it > 0L } ?: return
        context.getSharedPreferences("route_quality", Context.MODE_PRIVATE).edit()
            .putLong("${sample.routeId}:latency", latency)
            .putLong("${sample.routeId}:speed", speed)
            .putLong("${sample.routeId}:time", System.currentTimeMillis())
            .putString("${sample.routeId}:session", sessionFingerprint(context)).apply()
    }
    fun read(context: Context, routeId: String): RouteProbeSample? {
        val prefs = context.getSharedPreferences("route_quality", Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - prefs.getLong("$routeId:time", 0)
        if (age !in 0..MAX_AGE_MS || prefs.getString("$routeId:session", null) != sessionFingerprint(context)) return null
        val latency = prefs.getLong("$routeId:latency", -1).takeIf(RouteProbeStatistics::isValidLatencyMillis)
        val speed = prefs.getLong("$routeId:speed", -1)
        return if (speed > 0) RouteProbeSample(routeId, latency, speed) else null
    }
    private fun sessionFingerprint(context: Context): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(TelegramSessionStore.read(context).orEmpty().toByteArray())
        .joinToString("") { "%02x".format(it) }
}
