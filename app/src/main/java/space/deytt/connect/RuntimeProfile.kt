package space.deytt.connect

import org.json.JSONObject

/** Adds Android-only paths without persisting device-specific data in the subscription. */
object RuntimeProfile {
    fun withPrivateCacheFile(config: String, absoluteCachePath: String): String {
        require(absoluteCachePath.startsWith('/')) { "Путь к cache-file должен быть абсолютным" }
        val root = try {
            JSONObject(config)
        } catch (error: Exception) {
            throw IllegalArgumentException("Сохранённый профиль повреждён", error)
        }
        val experimental = root.optJSONObject("experimental") ?: JSONObject().also {
            root.put("experimental", it)
        }
        val cacheFile = experimental.optJSONObject("cache_file") ?: JSONObject().also {
            experimental.put("cache_file", it)
        }
        cacheFile.put("enabled", true)
        cacheFile.put("path", absoluteCachePath)
        cacheFile.put("store_fakeip", false)
        cacheFile.put("store_dns", false)
        return root.toString()
    }
}
