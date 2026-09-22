package space.deytt.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProfileTest {
    @Test
    fun addsAnExplicitPrivateCachePathWithoutChangingTheRoute() {
        val result = RuntimeProfile.withPrivateCacheFile(
            """{"route":{"final":"🇪🇺 автоподбор"},"outbounds":[]}""",
            "/data/user/0/space.deytt.connect/no_backup/sing-box/cache.db",
        )
        val root = JSONObject(result)
        val cache = root.getJSONObject("experimental").getJSONObject("cache_file")

        assertEquals("🇪🇺 автоподбор", root.getJSONObject("route").getString("final"))
        assertEquals("/data/user/0/space.deytt.connect/no_backup/sing-box/cache.db", cache.getString("path"))
        assertTrue(cache.getBoolean("enabled"))
        assertFalse(cache.getBoolean("store_fakeip"))
        assertFalse(cache.getBoolean("store_dns"))
    }
}
