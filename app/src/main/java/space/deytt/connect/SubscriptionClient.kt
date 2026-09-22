package space.deytt.connect

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class ImportedSubscription(
    val url: String,
    val summary: ProfileSummary,
)

object SubscriptionClient {
    private const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024

    fun import(context: android.content.Context, rawUrl: String): ImportedSubscription {
        val url = normalizeUrl(rawUrl)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "deytt-connect/0.2.1")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Сервер подписки ответил HTTP $responseCode")
            }
            val content = connection.inputStream.use(::readLimitedUtf8)
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

    private fun readLimitedUtf8(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_SUBSCRIPTION_BYTES) {
                throw IOException("Подписка слишком большая")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
