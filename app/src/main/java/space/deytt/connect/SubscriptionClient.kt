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
import org.json.JSONObject

data class ImportedSubscription(
    val url: String,
    val summary: ProfileSummary,
    val metadata: SubscriptionMetadata,
    val awg31Available: Boolean,
    val warnings: List<String> = emptyList(),
)

object SubscriptionClient {
    private const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024

    fun import(
        context: android.content.Context, rawUrl: String, onStage: (String) -> Unit = {},
    ): ImportedSubscription {
        val headers = SubscriptionRequestIdentity.headers(context)
        val coreBudget = SubscriptionRequestBudget(60_000)
        var optionalBudget: SubscriptionRequestBudget? = null
        try {
            return import(context, rawUrl, SubscriptionHttpTransport { url, accept ->
                val budget = if (accept == "application/json") coreBudget else {
                    optionalBudget ?: SubscriptionRequestBudget(20_000).also { optionalBudget = it }
                }
                requestUrlConnection(url, accept, headers, budget)
            }, onStage, onBeforeCommit = {
                if (TelegramSessionStore.read(context) != headers["X-TG-App-Token"]) {
                    throw SubscriptionCancelledException()
                }
            })
        } catch (error: SubscriptionHttpFailure) {
            if (error.statusCode == 401 && error.code == "session_expired") {
                TelegramSessionStore.clearIfMatches(context, headers["X-TG-App-Token"])
            }
            throw error
        }
    }

    internal fun import(
        context: android.content.Context,
        rawUrl: String,
        transport: SubscriptionHttpTransport,
        onStage: (String) -> Unit = {},
        onBeforeCommit: () -> Unit = {},
    ): ImportedSubscription {
        val baseUrl = normalizeBaseUrl(rawUrl)
        val url = withFormat(baseUrl, "singbox")
        onStage("download")
        val response = request(url, "application/json", required = true, transport = transport)!!
        val content = response.body
        onStage("validate")
        val summary = validateDownloadedProfile(content)
        val metadata = SubscriptionMetadata.parse(response.profileTitle, response.userInfo)
        val awgStore = AwgProfileStore(context)
        onStage("awg")
        val awgResults = listOf(fetchAwgProfiles(baseUrl, "amneziawg31", "31", transport))
        // An optional AWG endpoint must not make a valid core subscription unusable.
        // Keep a last-known-good family when its gateway is temporarily failing.
        fun mergedProfiles(previousAwgProfiles: List<AwgProfile>) = awgResults.flatMap { result ->
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
        checkCancellation()
        onStage("save")
        val warnings = awgResults.mapNotNull { it.warning }.toMutableList()
        val awgProfiles = synchronized(AtomicSubscriptionFile.lock) {
            checkCancellation()
            onBeforeCommit()
            val settings = context.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE)
            val previousUrl = settings.getString("subscription_url", null)
            // Read ownership and last-good files under the commit lock: another
            // completed import must not be replaced by an older fallback snapshot.
            val sameSubscription = previousUrl != null && runCatching {
                normalizeBaseUrl(previousUrl) == baseUrl
            }.getOrDefault(false)
            val profiles = mergedProfiles(if (sameSubscription) awgStore.profiles() else emptyList())
            // Invalidate ownership before the multi-file commit. After a process/disk
            // failure no old account's AWG config may be reused for a new source.
            if (!settings.edit().remove("subscription_url").commit()) throw SubscriptionStorageException()
            try {
                SubscriptionStore(context).commitValidated(content, awgStore, profiles)
            } catch (error: IOException) {
                throw SubscriptionStorageException()
            }
            if (runCatching { SubscriptionMetadataStore(context).save(metadata) }.isFailure) {
                warnings += "Маршруты сохранены, но сведения о тарифе не обновились. Повторите обновление позже."
            }
            val edit = settings.edit().putString("subscription_url", baseUrl)
            if (warnings.isEmpty()) edit.remove("subscription_warning")
            else edit.putString("subscription_warning", warnings.joinToString("\n"))
            if (!edit.commit()) throw SubscriptionStorageException()
            profiles
        }
        return ImportedSubscription(
            baseUrl,
            summary,
            metadata,
            awgProfiles.any { it.version == "31" },
            warnings = warnings,
        )
    }

    internal fun validateDownloadedProfile(content: String): ProfileSummary = try {
        ProfileValidator.validate(content).also {
            // The UI requires an automatic route. Check that contract before
            // replacing the last-good file, not in the success screen afterward.
            RouteCatalog.from(content, emptyList())
        }
    } catch (error: Exception) {
        throw SubscriptionPayloadException()
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
        val retryAfterMillis: Long? = null,
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
        transport: SubscriptionHttpTransport,
    ): SubscriptionResponse? {
        var lastError: IOException? = null
        repeat(SubscriptionRetryPolicy.MAX_ATTEMPTS) { attempt ->
            checkCancellation()
            try {
                return requestOnce(url, accept, required, transport)
            } catch (error: IOException) {
                lastError = error
                if (attempt < SubscriptionRetryPolicy.MAX_ATTEMPTS - 1 && SubscriptionRetryPolicy.shouldRetry(error)) {
                    val serverDelay = (error as? SubscriptionHttpFailure)?.retryAfterMillis
                    // Long rate limits should return control to the user, never
                    // hammer the server or occupy a worker for minutes.
                    if (serverDelay != null && serverDelay > 5_000) throw error
                    try {
                        Thread.sleep(maxOf(SubscriptionRetryPolicy.delayMillis(attempt), serverDelay ?: 0))
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw SubscriptionCancelledException()
                    }
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
            val code = runCatching { JSONObject(response.body).let { it.optString("error").ifBlank { it.optString("detail") } } }.getOrDefault("")
            throw SubscriptionHttpFailure(responseCode, detail, code, response.retryAfterMillis)
        }
        if (responseCode != 200) throw SubscriptionPayloadException()
        return SubscriptionResponse(
            body = response.body,
            profileTitle = response.profileTitle,
            userInfo = response.userInfo,
            awgServers = response.awgServers,
        )
    }

    private fun requestUrlConnection(
        url: String, accept: String, headers: Map<String, String>, budget: SubscriptionRequestBudget,
    ): SubscriptionHttpResponse {
        budget.check()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = minOf(10_000, budget.remainingMillis())
            readTimeout = minOf(12_000, budget.remainingMillis())
            useCaches = false
            // A subscription URL is a bearer token. Do not let the HTTP stack
            // silently forward it to an untrusted host.
            instanceFollowRedirects = false
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            setRequestProperty("Accept", accept)
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", "deytt-connect/${BuildConfig.VERSION_NAME}")
        }

        return budget.withConnection(connection) {
            val responseCode = connection.responseCode
            SubscriptionHttpResponse(
                statusCode = responseCode,
                body = if (responseCode in 200..299) {
                    connection.inputStream.use { readLimitedUtf8(it, budget) }
                } else {
                    // Status must remain authoritative if a proxy sends a broken
                    // error body; never turn an access denial into a retryable EOF.
                    try {
                        connection.errorStream?.use { readLimitedUtf8(it, budget) }.orEmpty()
                    } catch (error: IOException) {
                        budget.check()
                        ""
                    }
                },
                profileTitle = connection.getHeaderField("Profile-Title"),
                userInfo = connection.getHeaderField("Subscription-Userinfo"),
                awgServers = connection.getHeaderField("X-Deytt-Awg-Servers"),
                retryAfterMillis = parseRetryAfter(connection.getHeaderField("Retry-After")),
            )
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
        require(uri.port == -1 || uri.port == 443) { "Ссылка на подписку имеет недопустимый порт" }
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
        transport: SubscriptionHttpTransport,
    ): AwgFetchResult {
        return try {
            val first = request(
                withFormat(baseUrl, format),
                "text/plain",
                required = false,
                transport = transport,
            )
                ?: return AwgFetchResult(version, emptyList(), AwgFetchState.NOT_AVAILABLE)
            val servers = try { JSONArray(first.awgServers ?: "[]") } catch (error: Exception) {
                throw SubscriptionPayloadException()
            }
            if (servers != null && servers.length() > 16) throw SubscriptionPayloadException()
            val advertisedIds = mutableSetOf<String>()
            if (servers != null) for (index in 0 until servers.length()) {
                val item = servers.optJSONObject(index) ?: throw SubscriptionPayloadException()
                val id = item.optString("id").trim()
                if (id.isEmpty() || id.length > 128 || !advertisedIds.add(id)) throw SubscriptionPayloadException()
            }
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
                        // The manifest response is itself a valid config for the
                        // first advertised server. Keep it as the authoritative
                        // fallback instead of throwing it away and requiring a
                        // second request that can independently fail with 502/EOF.
                        if (index == 0 && runCatching {
                                val profile = AwgProfile(
                                    id = "awg$version:$id",
                                    version = version,
                                    label = server.optString("label", id),
                                    shortLabel = server.optString("short_label", id.uppercase()),
                                    config = first.body,
                                )
                                AwgProfileStore.validate(profile.config)
                                add(profile)
                            }.isSuccess
                        ) continue
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
                            if (isAccessFailure(error) || error is SubscriptionCancelledException) throw error
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
                    warning = awgWarning(version, failedIds.size, availableCount = 0, temporary = failedRequestsAreTransient),
                    failedIds = failedIds,
                )
            } else if (failedIds.isNotEmpty()) {
                AwgFetchResult(
                    version = version,
                    profiles = profiles,
                    state = AwgFetchState.PARTIAL_FAILURE,
                    warning = awgWarning(version, failedIds.size, availableCount = profiles.size, temporary = failedRequestsAreTransient),
                    failedIds = failedIds,
                )
            } else {
                AwgFetchResult(version, profiles, AwgFetchState.AVAILABLE)
            }
        } catch (error: Exception) {
            if (isAccessFailure(error) || error is SubscriptionCancelledException) throw error
            AwgFetchResult(
                version = version,
                profiles = emptyList(),
                state = AwgFetchState.TRANSIENT_FAILURE,
                warning = awgWarning(version, error),
            )
        }
    }

    private fun isAccessFailure(error: Exception): Boolean =
        error is SubscriptionHttpFailure && error.statusCode in setOf(400, 401, 403, 409)

    private fun awgWarning(version: String, failedCount: Int, availableCount: Int, temporary: Boolean): String {
        val name = "AmneziaWG 3.1"
        return if (temporary) {
            if (availableCount == 0) {
                "$name: $failedCount ${if (failedCount == 1) "сервер" else "сервера"} временно недоступ${if (failedCount == 1) "ен" else "ны"}. Доступных точек нет, обновите подписку позже."
            } else {
                "$name: $failedCount ${if (failedCount == 1) "сервер" else "сервера"} временно недоступ${if (failedCount == 1) "ен" else "ны"}. Доступные точки добавлены."
            }
        } else {
            if (availableCount == 0) {
                "$name пока недоступен. Основная подписка добавлена, повторите обновление позже."
            } else {
                "$name: часть точек не обновилась. Доступные точки добавлены, повторите позже."
            }
        }
    }

    private fun awgWarning(version: String, error: Exception): String {
        val name = "AmneziaWG 3.1"
        val reason = SubscriptionRetryPolicy.safeFailureSummary(error)
        return if ((error as? IOException)?.let(SubscriptionRetryPolicy::shouldRetry) == true) {
            "$name: дополнительные профили временно недоступны ($reason). Основная подписка добавлена, обновите её позже."
        } else {
            "$name не удалось обновить ($reason). Основная подписка добавлена, повторите попытку позже."
        }
    }

    internal fun parseRetryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim()?.takeIf { it.length <= 64 } ?: return null
        raw.toLongOrNull()?.let { return it.coerceIn(0, 86_400) * 1_000 }
        return runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).apply {
                isLenient = false
                timeZone = java.util.TimeZone.getTimeZone("GMT")
            }.parse(raw)?.time?.minus(nowMillis)?.coerceIn(0, 86_400_000)
        }.getOrNull()
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw SubscriptionCancelledException()
    }

    private fun readLimitedUtf8(stream: InputStream, budget: SubscriptionRequestBudget): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            budget.check()
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_SUBSCRIPTION_BYTES) {
                throw SubscriptionPayloadException()
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
