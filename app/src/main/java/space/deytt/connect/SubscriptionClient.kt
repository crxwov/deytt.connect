package space.deytt.connect

import android.net.Uri
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ImportedSubscription(
    val url: String,
    val summary: ProfileSummary,
)

object SubscriptionClient {
    fun import(context: android.content.Context, rawUrl: String): ImportedSubscription {
        val url = normalizeUrl(rawUrl)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "deytt-connect/0.1")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Сервер подписки ответил HTTP $responseCode")
            }
            val content = connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
            val summary = ProfileValidator.validate(content)
            SubscriptionStore(context).saveValidated(content)
            return ImportedSubscription(url, summary)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeUrl(rawUrl: String): String {
        val uri = Uri.parse(rawUrl.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Для защиты токена нужна HTTPS-ссылка на подписку"
        }
        require(!uri.host.isNullOrBlank()) { "Укажите полную HTTPS-ссылку на подписку" }

        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name != "format") {
                builder.appendQueryParameter(name, uri.getQueryParameter(name).orEmpty())
            }
        }
        builder.appendQueryParameter("format", "singbox")
        return builder.build().toString()
    }
}

