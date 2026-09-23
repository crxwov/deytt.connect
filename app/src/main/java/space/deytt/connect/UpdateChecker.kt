package space.deytt.connect

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

data class ReleaseInfo(val tag: String, val pageUrl: String, val apkUrl: String?)

internal object ReleaseUrlPolicy {
    fun isOfficialPage(raw: String): Boolean = runCatching {
        val url = URL(raw)
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("github.com", ignoreCase = true)
    }.getOrDefault(false)
}

object UpdateChecker {
    // GitHub's /releases/latest deliberately excludes prereleases. The public
    // Android channel is currently debug-prerelease based, so inspect the
    // ordered release list instead and ignore only drafts.
    private const val RELEASES_URL = "https://api.github.com/repos/crxwov/deytt.connect/releases?per_page=20"

    fun latest(): ReleaseInfo {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
        }
        return try {
            check(connection.responseCode in 200..299) { "GitHub ответил HTTP ${connection.responseCode}" }
            val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val json = (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .firstOrNull { !it.optBoolean("draft", true) }
                ?: error("GitHub не вернул опубликованных релизов")
            val pageUrl = json.optString("html_url")
            check(ReleaseUrlPolicy.isOfficialPage(pageUrl)) { "GitHub вернул неподтверждённую ссылку на релиз" }
            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
                ?.takeIf { it.startsWith("https://github.com/", ignoreCase = true) }
            ReleaseInfo(
                tag = json.optString("tag_name").ifBlank { "unknown" },
                pageUrl = pageUrl,
                apkUrl = apk,
            )
        } finally {
            connection.disconnect()
        }
    }
}
