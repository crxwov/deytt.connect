package space.deytt.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTrafficTest {
    @Test fun lifetimeTrafficDoesNotReplaceResettableQuota() {
        val traffic = ProfileTraffic.from(JSONObject().put("traffic_total_bytes", 318_000_000_000L)
            .put("traffic_used_bytes", 12_000L).put("traffic_limit_bytes", 100_000L))
        assertEquals(318_000_000_000L, traffic.totalBytes)
        assertEquals(12_000L, traffic.quotaUsedBytes)
        assertEquals(100_000L, traffic.quotaLimitBytes)
    }

    @Test fun olderServerUsesKnownQuotaRatherThanInventingLifetimeCounter() {
        assertEquals(42L, ProfileTraffic.from(JSONObject().put("traffic_used_bytes", 42L)).totalBytes)
    }

    @Test fun missingAndNegativeCountersAreSafe() {
        assertEquals(ProfileTraffic(0L, 0L, 0L), ProfileTraffic.from(JSONObject()
            .put("traffic_total_bytes", -1L).put("traffic_used_bytes", -2L).put("traffic_limit_bytes", JSONObject.NULL)))
    }
}
