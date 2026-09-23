package space.deytt.connect

import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

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
        val awgProfiles = fetchAwgProfiles(baseUrl, "amneziawg", "15") +
            fetchAwgProfiles(baseUrl, "amneziawg31", "31")
        // Validate every response before replacing any part of the last-known-good bundle.
        awgProfiles.forEach { AwgProfileStore.validate(it.config) }
        SubscriptionStore(context).saveValidated(content)
        SubscriptionMetadataStore(context).save(metadata)
        AwgProfileStore(context).save(awgProfiles)
        return ImportedSubscription(
            url,
            summary,
            metadata,
            awgProfiles.any { it.version == "15" },
            awgProfiles.any { it.version == "31" },
        )
    }

    private data class Response(
        val body: String,
        val profileTitle: String?,
        val userInfo: String?,
        val awgServers: String?,
    )

    private fun request(url: String, accept: String, required: Boolean): Response? {
        var lastError: IOException? = null
        repeat(SubscriptionRetryPolicy.MAX_ATTEMPTS) { attempt ->
            try {
                return requestOnce(url, accept, required)
            } catch (error: IOException) {
                lastError = error
                if (attempt < SubscriptionRetryPolicy.MAX_ATTEMPTS - 1 && SubscriptionRetryPolicy.shouldRetry(error)) {
                    Thread.sleep(SubscriptionRetryPolicy.delayMillis(attempt))
                } else {
                    throw error
                }
            }
        }
        throw lastError ?: IOException("Не удалось получить подписку")
    }

    private fun requestOnce(url: String, accept: String, required: Boolean): Response? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            // A subscription URL is a bearer token. Do not let the HTTP stack
            // silently forward it to an untrusted host.
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
        }

        try {
            val responseCode = connection.responseCode
            if (!required && responseCode == 404) return null
            if (responseCode in 300..399) {
                throw SubscriptionHttpFailure(
                    responseCode,
                    "Сервер подписки вернул недопустимое перенаправление",
                )
            }
            if (responseCode !in 200..299) {
                val detail = when (responseCode) {
                    502, 503, 504 -> "Сервер подписки временно недоступен (HTTP $responseCode)"
                    else -> "Сервер подписки ответил HTTP $responseCode"
                }
                throw SubscriptionHttpFailure(responseCode, detail)
            }
            return Response(
                body = connection.inputStream.use(::readLimitedUtf8),
                profileTitle = connection.getHeaderField("Profile-Title"),
                userInfo = connection.getHeaderField("Subscription-Userinfo"),
                awgServers = connection.getHeaderField("X-Deytt-Awg-Servers"),
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
        require(SubscriptionHostPolicy.isAllowed(uri.host)) {
            "Ссылка должна вести на официальный домен deytt.space"
        }

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

    private fun fetchAwgProfiles(baseUrl: String, format: String, version: String): List<AwgProfile> {
        val first = request(withFormat(baseUrl, format), "text/plain", required = false) ?: return emptyList()
        val servers = runCatching { JSONArray(first.awgServers ?: "[]") }.getOrNull()
        if (servers == null || servers.length() == 0) {
            return listOf(AwgProfile("awg$version", version, "Основной", "AWG", first.body))
        }
        return buildList {
            for (index in 0 until servers.length()) {
                val server = servers.optJSONObject(index) ?: continue
                val id = server.optString("id").trim()
                if (id.isBlank()) continue
                val url = withFormat(baseUrl, format).toUri().buildUpon()
                    .appendQueryParameter("server_id", id)
                    .build()
                    .toString()
                val response = request(url, "text/plain", required = true) ?: continue
                add(
                    AwgProfile(
                        id = "awg$version:$id",
                        version = version,
                        label = server.optString("label", id),
                        shortLabel = server.optString("short_label", id.uppercase()),
                        config = response.body,
                    ),
                )
            }
        }
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
