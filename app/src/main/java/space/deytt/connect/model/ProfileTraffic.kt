package space.deytt.connect

import android.content.Context
import org.json.JSONObject

/**
 * Lifetime transfer and billed quota counters.
 * Combines server-side stats (from Happ / Xray / subscription link)
 * with real local DateConnect tunnel transfer.
 */
internal data class ProfileTraffic(val totalBytes: Long, val quotaUsedBytes: Long, val quotaLimitBytes: Long) {
    companion object {
        fun from(subscription: JSONObject): ProfileTraffic {
            val quotaUsed = subscription.optLong("traffic_used_bytes", 0L).coerceAtLeast(0L)
            val remoteTotal = subscription.optLong("traffic_total_bytes", quotaUsed).coerceAtLeast(0L)
            return ProfileTraffic(
                remoteTotal,
                quotaUsed,
                subscription.optLong("traffic_limit_bytes", 0L).coerceAtLeast(0L),
            )
        }

        fun from(context: Context, subscription: JSONObject?): ProfileTraffic {
            if (subscription == null) {
                val metadata = SubscriptionMetadataStore(context).read()
                val localTotal = LocalTrafficRecorder.getTotalBytes(context)
                val combined = metadata.usedBytes + localTotal
                return ProfileTraffic(combined, metadata.usedBytes, metadata.totalBytes)
            }
            val quotaUsed = subscription.optLong("traffic_used_bytes", 0L).coerceAtLeast(0L)
            val metadata = SubscriptionMetadataStore(context).read()
            val remoteTotal = maxOf(
                subscription.optLong("traffic_total_bytes", quotaUsed).coerceAtLeast(0L),
                metadata.usedBytes
            )
            val localTotal = LocalTrafficRecorder.getTotalBytes(context)
            val combinedTotal = remoteTotal + localTotal

            return ProfileTraffic(
                combinedTotal,
                quotaUsed,
                subscription.optLong("traffic_limit_bytes", 0L).coerceAtLeast(0L),
            )
        }
    }
}
