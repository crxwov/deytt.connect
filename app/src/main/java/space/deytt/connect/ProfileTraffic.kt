package space.deytt.connect

import org.json.JSONObject

/** Lifetime transfer and billed quota are different counters and must stay labelled separately. */
internal data class ProfileTraffic(val totalBytes: Long, val quotaUsedBytes: Long, val quotaLimitBytes: Long) {
    companion object {
        fun from(subscription: JSONObject): ProfileTraffic {
            val quotaUsed = subscription.optLong("traffic_used_bytes", 0L).coerceAtLeast(0L)
            return ProfileTraffic(
                subscription.optLong("traffic_total_bytes", quotaUsed).coerceAtLeast(0L),
                quotaUsed,
                subscription.optLong("traffic_limit_bytes", 0L).coerceAtLeast(0L),
            )
        }
    }
}
