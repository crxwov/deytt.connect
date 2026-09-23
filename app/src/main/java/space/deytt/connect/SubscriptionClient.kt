package space.deytt.connect

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONArray

data class ImportedSubscription(
    val url: String,
    val summary: ProfileSummary,
    val metadata: SubscriptionMetadata,
    val awg15Available: Boolean,
    val awg31Available: Boolean,
    val warnings: List<String> = emptyList(),
)

object SubscriptionClient {
    private const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
    private val defaultTransport = SubscriptionHttpTransport { url, accept ->
        requestUrlConnection(url, accept)
    }

    fun import(context: android.content.Context, rawUrl: String): ImportedSubscription {
        return import(context, rawUrl, defaultTransport)
    }

    internal fun import(
        context: android.content.Context,
        rawUrl: String,
        transport: SubscriptionHttpTransport,
    ): ImportedSubscription {
        val baseUrl = normalizeBaseUrl(rawUrl)
        val url = withFormat(baseUrl, "singbox")
        val response = request(url, "application/json", required = true, transport = transport)!!
        val content = response.body
        val summary = ProfileValidator.validate(content)
        val metadata = SubscriptionMetadata.parse(response.profileTitle, response.userInfo)
        val awgStore = AwgProfileStore(context)
        val previousAwgProfiles = awgStore.profiles()
        val awgResults = listOf(
            fetchAwgProfiles(baseUrl, "amneziawg", "15", transport),
            fetchAwgProfiles(baseUrl, "amneziawg31", "31", transport),
        )
        // An optional AWG endpoint must not make a valid core subscription unusable.
        // Keep a last-known-good family when its gateway is temporarily failing.
        val awgProfiles = awgResults.flatMap { result ->
            val previousFamily = previousAwgProfiles.filter { it.version == result.version }
            when (result.state) {
                AwgFetchState.NOT_AVAILABLE,
                AwgFetchState.TRANSIENT_FAILURE -> previousFamily
                AwgFetchState.PARTIAL_FAILURE -> {
                    val stale = previousFamily.filter { profile ->
                        result.failedIds.contains(profile.id.substringAfter(':', profile.id))
                    }
                    (result.profiles + stale).distinctBy(AwgProfile::id)
                }
                else -> result.profiles
            }
        }.filter { profile ->
            // A corrupt last-known-good file must never make the required
            // sing-box import fail; simply omit that optional profile.
            runCatching { AwgProfileStore.validate(profile.config) }.isSuccess
        }
        SubscriptionStore(context).saveValidated(content)
        SubscriptionMetadataStore(context).save(metadata)
        awgStore.save(awgProfiles)
        return ImportedSubscription(
            baseUrl,
            summary,
            metadata,
            awgProfiles.any { it.version == "15" },
            awgProfiles.any { it.version == "31" },
            warnings = awgResults.mapNotNull { it.warning },
        )
    }

    internal data class SubscriptionResponse(
        val body: String,
        val profileTitle: String?,
        val userInfo: String?,
        val awgServers: String?,
    )

    internal enum class AwgFetchState { AVAILABLE, NOT_AVAILABLE, PARTIAL_FAILURE, TRANSIENT_FAILURE }

    internal data class AwgFetchResult(
        val version: String,
        val profiles: List<AwgProfile>,
        val state: AwgFetchState,
        val warning: String? = null,
        val failedIds: Set<String> = emptySet(),
    )

    internal data class SubscriptionHttpResponse(
        val statusCode: Int,
        val body: String = "",
        val profileTitle: String? = null,
        val userInfo: String? = null,
        val awgServers: String? = null,
    )

    internal fun interface SubscriptionHttpTransport {
        @Throws(IOException::class)
        fun get(url: String, accept: String): SubscriptionHttpResponse
    }

    internal fun requestForTest(
        url: String,
        accept: String,
        required: Boolean,
        transport: SubscriptionHttpTransport,
    ): SubscriptionResponse? = request(url, accept, required, transport)

    internal fun normalizeBaseUrlForTest(rawUrl: String): String = normalizeBaseUrl(rawUrl)

    internal fun fetchAwgProfilesForTest(
        baseUrl: String,
        format: String,
        version: String,
        transport: SubscriptionHttpTransport,
    ): AwgFetchResult = fetchAwgProfiles(baseUrl, format, version, transport)

    private fun request(
        url: String,
        accept: String,
        required: Boolean,
        transport: SubscriptionHttpTransport = defaultTransport,
    ): SubscriptionResponse? {
        var lastError: IOException? = null
        repeat(SubscriptionRetryPolicy.MAX_ATTEMPTS) { attempt ->
            try {
                return requestOnce(url, accept, required, transport)
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

    private fun requestOnce(
        url: String,
        accept: String,
        required: Boolean,
        transport: SubscriptionHttpTransport,
    ): SubscriptionResponse? {
        val response = transport.get(url, accept)
        val responseCode = response.statusCode
        if (!required && responseCode == 404) return null
        if (responseCode in 300..399) {
            throw SubscriptionHttpFailure(
                responseCode,
                "Сервер подписки вернул недопустимое перенаправление",
            )
        }
        if (responseCode !in 200..299) {
            val detail = when (responseCode) {
                401, 403 -> "Ссылка на подписку недействительна или срок её действия истёк"
                404 -> "Ссылка на подписку не найдена"
                502, 503, 504 -> "Сервер подписки временно недоступен (HTTP $responseCode)"
                else -> "Сервер подписки ответил HTTP $responseCode"
            }
            throw SubscriptionHttpFailure(responseCode, detail)
        }
        return SubscriptionResponse(
            body = response.body,
            profileTitle = response.profileTitle,
            userInfo = response.userInfo,
            awgServers = response.awgServers,
        )
    }

    private fun requestUrlConnection(url: String, accept: String): SubscriptionHttpResponse {
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
            return SubscriptionHttpResponse(
                statusCode = responseCode,
                body = if (responseCode in 200..299) {
                    connection.inputStream.use(::readLimitedUtf8)
                } else {
                    ""
                },
                profileTitle = connection.getHeaderField("Profile-Title"),
                userInfo = connection.getHeaderField("Subscription-Userinfo"),
                awgServers = connection.getHeaderField("X-Deytt-Awg-Servers"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeBaseUrl(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrElse {
            throw IllegalArgumentException("Укажите полную HTTPS-ссылку на подписку")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Для защиты токена нужна HTTPS-ссылка на подписку"
        }
        require(!uri.host.isNullOrBlank()) { "Укажите полную HTTPS-ссылку на подписку" }
        require(uri.userInfo == null) { "Ссылка на подписку имеет недопустимый формат" }
        require(SubscriptionHostPolicy.isAllowed(uri.host)) {
            "Ссылка должна вести на официальный домен deytt.space"
        }

        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val separator = pair.indexOf('=')
            val name = decodeQueryComponent(if (separator >= 0) pair.substring(0, separator) else pair)
            if (name.equals("format", ignoreCase = true) || name.equals("server_id", ignoreCase = true)) null else pair
        }.joinToString("&").ifBlank { null }
        return buildUrl(uri, query)
    }

    private fun withFormat(baseUrl: String, format: String): String {
        val uri = URI(baseUrl)
        val query = listOfNotNull(uri.rawQuery, encodedQueryParameter("format", format))
            .joinToString("&")
        return buildUrl(uri, query)
    }

    private fun appendQueryParameter(url: String, name: String, value: String): String {
        val uri = URI(url)
        val query = listOfNotNull(uri.rawQuery, encodedQueryParameter(name, value)).joinToString("&")
        return buildUrl(uri, query)
    }

    private fun buildUrl(uri: URI, query: String?): String = buildString {
        append(uri.scheme).append("://").append(uri.rawAuthority).append(uri.rawPath.orEmpty())
        if (!query.isNullOrBlank()) append('?').append(query)
    }

    private fun encodedQueryParameter(name: String, value: String): String =
        "${encodeQueryComponent(name)}=${encodeQueryComponent(value)}"

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decodeQueryComponent(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrElse {
            throw IllegalArgumentException("Ссылка на подписку имеет недопустимый формат")
        }

    private fun fetchAwgProfiles(
        baseUrl: String,
        format: String,
        version: String,
        transport: SubscriptionHttpTransport = defaultTransport,
    ): AwgFetchResult {
        return try {
            val first = request(
                withFormat(baseUrl, format),
                "text/plain",
                required = false,
                transport = transport,
            )
                ?: return AwgFetchResult(version, emptyList(), AwgFetchState.NOT_AVAILABLE)
            val servers = runCatching { JSONArray(first.awgServers ?: "[]") }.getOrNull()
            val failedIds = mutableSetOf<String>()
            var failedRequestsAreTransient = true
            val profiles = if (servers == null || servers.length() == 0) {
                listOf(AwgProfile("awg$version", version, "Основной", "AWG", first.body)).also {
                    it.forEach { profile -> AwgProfileStore.validate(profile.config) }
                }
            } else {
                buildList {
                    for (index in 0 until servers.length()) {
                        val server = servers.optJSONObject(index) ?: continue
                        val id = server.optString("id").trim()
                        if (id.isBlank()) continue
                        val url = appendQueryParameter(withFormat(baseUrl, format), "server_id", id)
                        try {
                            val response = request(
                                url,
                                "text/plain",
                                required = true,
                                transport = transport,
                            )
                            if (response == null) {
                                failedIds += id
                                failedRequestsAreTransient = false
                            } else {
                                val profile = AwgProfile(
                                    id = "awg$version:$id",
                                    version = version,
                                    label = server.optString("label", id),
                                    shortLabel = server.optString("short_label", id.uppercase()),
                                    config = response.body,
                                )
                                AwgProfileStore.validate(profile.config)
                                add(profile)
                            }
                        } catch (error: Exception) {
                            failedIds += id
                            if ((error as? IOException)?.let(SubscriptionRetryPolicy::shouldRetry) != true) {
                                failedRequestsAreTransient = false
                            }
                        }
                    }
                }
            }
            if (servers != null && servers.length() > 0 && profiles.isEmpty() && failedIds.isEmpty()) {
                throw IOException("Сервер не вернул ни одного профиля AmneziaWG")
            }
            if (profiles.isEmpty() && failedIds.isNotEmpty()) {
                AwgFetchResult(
                    version = version,
                    profiles = emptyList(),
                    state = AwgFetchState.PARTIAL_FAILURE,
                    warning = awgWarning(version, failedIds.size, temporary = failedRequestsAreTransient),
                    failedIds = failedIds,
                )
            } else if (failedIds.isNotEmpty()) {
                AwgFetchResult(
                    version = version,
                    profiles = profiles,
                    state = AwgFetchState.PARTIAL_FAILURE,
                    warning = awgWarning(version, failedIds.size, temporary = failedRequestsAreTransient),
                    failedIds = failedIds,
                )
            } else {
                AwgFetchResult(version, profiles, AwgFetchState.AVAILABLE)
            }
        } catch (error: Exception) {
            AwgFetchResult(
                version = version,
                profiles = emptyList(),
                state = AwgFetchState.TRANSIENT_FAILURE,
                warning = awgWarning(version, error),
            )
        }
    }

    private fun awgWarning(version: String, failedCount: Int, temporary: Boolean): String {
        val name = "AmneziaWG ${if (version == "31") "3.1" else "1.5"}"
        return if (temporary) {
            "$name: $failedCount ${if (failedCount == 1) "сервер" else "сервера"} временно недоступ${if (failedCount == 1) "ен" else "ны"}. Остальные добавлены."
        } else {
            "$name пока не обновился. Основная подписка добавлена, повторите обновление позже."
        }
    }

    private fun awgWarning(version: String, error: Exception): String {
        val name = "AmneziaWG ${if (version == "31") "3.1" else "1.5"}"
        return if ((error as? IOException)?.let(SubscriptionRetryPolicy::shouldRetry) == true) {
            "$name: дополнительные профили временно недоступны. Основная подписка добавлена, повторите обновление позже."
        } else {
            "$name не удалось обновить. Основная подписка добавлена, повторите обновление позже."
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
