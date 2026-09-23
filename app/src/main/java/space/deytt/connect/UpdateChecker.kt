package space.deytt.connect

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class ReleaseInfo(val tag: String, val pageUrl: String, val apkUrl: String?)

object UpdateChecker {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/crxwov/deytt.connect/releases/latest"

    fun latest(): ReleaseInfo {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
        }
        return try {
            check(connection.responseCode in 200..299) { "GitHub ответил HTTP ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
                ?.takeIf(String::isNotBlank)
            ReleaseInfo(
                tag = json.optString("tag_name").ifBlank { "unknown" },
                pageUrl = json.optString("html_url"),
                apkUrl = apk,
            )
        } finally {
            connection.disconnect()
        }
    }
}
