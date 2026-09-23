package space.deytt.connect

import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class ImportedSubscription(
    val url: String,
    val summary: ProfileSummary,
    val metadata: SubscriptionMetadata,
    val awg15Available: Boolean,
    val awg31Available: Boolean,
)

object SubscriptionClient {
    private const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024

    fun import(context: android.content.Context, rawUrl: String): ImportedSubscription {
        val baseUrl = normalizeBaseUrl(rawUrl)
        val url = withFormat(baseUrl, "singbox")
        val response = request(url, "application/json", required = true)!!
        val content = response.body
        val summary = ProfileValidator.validate(content)
        val metadata = SubscriptionMetadata.parse(response.profileTitle, response.userInfo)
        val awg15 = request(withFormat(baseUrl, "amneziawg"), "text/plain", required = false)?.body
        val awg31 = request(withFormat(baseUrl, "amneziawg31"), "text/plain", required = false)?.body
        // Validate every response before replacing any part of the last-known-good bundle.
        AwgProfileStore.validate(awg15)
        AwgProfileStore.validate(awg31)
        SubscriptionStore(context).saveValidated(content)
        SubscriptionMetadataStore(context).save(metadata)
        AwgProfileStore(context).save(awg15, awg31)
        return ImportedSubscription(url, summary, metadata, awg15 != null, awg31 != null)
    }

    private data class Response(
        val body: String,
        val profileTitle: String?,
        val userInfo: String?,
    )

    private fun request(url: String, accept: String, required: Boolean): Response? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
        }

        try {
            val responseCode = connection.responseCode
            if (!required && responseCode == 404) return null
            if (responseCode !in 200..299) {
                throw IOException("Сервер подписки ответил HTTP $responseCode")
            }
            return Response(
                body = connection.inputStream.use(::readLimitedUtf8),
                profileTitle = connection.getHeaderField("Profile-Title"),
                userInfo = connection.getHeaderField("Subscription-Userinfo"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeBaseUrl(rawUrl: String): String {
        val uri = rawUrl.trim().toUri()
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
        return builder.build().toString()
    }

    private fun withFormat(baseUrl: String, format: String): String = baseUrl.toUri()
        .buildUpon()
        .appendQueryParameter("format", format)
        .build()
        .toString()

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
