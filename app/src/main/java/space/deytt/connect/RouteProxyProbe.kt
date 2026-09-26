package space.deytt.connect

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class RouteProbeMethod(val wireValue: String, val title: String) {
    HEAD("HEAD", "HEAD"),
    GET("GET", "GET"),
}

object RouteProbePreferences {
    private const val PREFS = "route_probe"
    private const val METHOD = "method"

    fun method(context: Context): RouteProbeMethod = runCatching {
        RouteProbeMethod.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(METHOD, "HEAD") ?: "HEAD",
        )
    }.getOrDefault(RouteProbeMethod.HEAD)

    fun saveMethod(context: Context, method: RouteProbeMethod) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(METHOD, method.name)
            .apply()
    }
}

data class RouteProbeSession(
    val port: Int,
    val username: String,
    val password: String,
) {
    fun configuration(config: String, routeTag: String): String =
        RouteProxyProbe.configuration(config, routeTag, port, username, password)
}

/** Runs a bounded GET/HEAD check through a loopback-only sing-box HTTP proxy. */
object RouteProxyProbe {
    const val TIMEOUT_MILLIS = 10_000
    const val QUICK_DOWNLOAD_MILLIS = 1_000
    const val PROBE_URL = "https://cp.cloudflare.com/generate_204"
    private const val PROBE_INBOUND = "deytt-route-probe"
    private const val PROBE_HOST = "cp.cloudflare.com"
    private const val PROBE_PORT = 443
    private const val PROBE_PATH = "/generate_204"
    private const val HTTP_OK = 200
    private const val HTTP_NO_CONTENT = 204
    private const val DOWNLOAD_HOST = "deytt.space"
    private const val DOWNLOAD_PATH = "/api/tg/mobile/probe/download"
    private const val MAX_HTTP_LINE_BYTES = 8_192
    private const val MAX_HTTP_HEADERS = 64
    private const val MAX_HTTP_HEADER_BYTES = 32_768
    private val HTTP_STATUS_LINE = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s.*)?$")

    fun newSession(): RouteProbeSession {
        val port = ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            socket.localPort
        }
        val entropy = java.util.UUID.randomUUID().toString().replace("-", "")
        return RouteProbeSession(port, "d${entropy.take(14)}", entropy)
    }

    fun configuration(
        config: String,
        routeTag: String,
        port: Int,
        username: String,
        password: String,
    ): String {
        require(port in 1..65535) { "Некорректный локальный порт проверки" }
        require(username.isNotBlank() && password.isNotBlank()) { "Не заданы локальные данные проверки" }
        val selected = ProfileRoutes.select(config, routeTag)
        val root = JSONObject(selected)
        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", PROBE_INBOUND)
            .put("listen", "127.0.0.1")
            .put("listen_port", port)
            .put("users", JSONArray().put(
                JSONObject().put("username", username).put("password", password),
            ))
        // The diagnostic core routes a single authenticated loopback proxy and
        // must never create a device TUN or expose subscription inbounds.
        root.put("inbounds", JSONArray().put(inbound))
        return root.toString()
    }

    /** One core with isolated authenticated inbounds; no reload while a request is in flight. */
    fun batchConfiguration(config: String, sessions: Map<String, RouteProbeSession>): String {
        require(sessions.isNotEmpty() && sessions.size <= 16) { "Invalid diagnostic batch size" }
        require(sessions.values.map { it.port }.distinct().size == sessions.size) { "Duplicate diagnostic port" }
        sessions.forEach { (tag, session) ->
            require(session.port in 1..65535 && session.username.isNotBlank() && session.password.isNotBlank())
            ProfileRoutes.select(config, tag) // Reject internal, unknown or removed routes.
        }
        val root = JSONObject(config)
        val inbounds = JSONArray()
        val rules = JSONArray()
        sessions.entries.forEachIndexed { index, (tag, session) ->
            val inboundTag = "$PROBE_INBOUND-$index"
            inbounds.put(JSONObject().put("type", "mixed").put("tag", inboundTag)
                .put("listen", "127.0.0.1").put("listen_port", session.port)
                .put("users", JSONArray().put(JSONObject()
                    .put("username", session.username).put("password", session.password))))
            rules.put(JSONObject().put("inbound", JSONArray().put(inboundTag))
                .put("action", "route").put("outbound", tag))
        }
        root.put("inbounds", inbounds)
        val routing = root.getJSONObject("route")
        val existing = routing.optJSONArray("rules")
        repeat(existing?.length() ?: 0) { rules.put(existing!!.get(it)) }
        routing.put("rules", rules).put("final", sessions.keys.first())
        // Resolve test and endpoint names on the underlying network. Otherwise
        // one failed selected route's DNS detour would fail every candidate.
        val dnsServers = root.optJSONObject("dns")?.optJSONArray("servers")
        repeat(dnsServers?.length() ?: 0) { index ->
            // A direct outbound has no dialer options: libbox 1.14 rejects it
            // as a DNS detour. Omitting detour selects the direct dialer.
            dnsServers!!.optJSONObject(index)?.remove("detour")
        }
        return root.toString()
    }

    fun measureProxy(
        session: RouteProbeSession,
        method: RouteProbeMethod,
        timeoutMillis: Int = TIMEOUT_MILLIS,
    ): Long {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var totalMeasurement = 0L
        repeat(2) {
            val remaining = remainingTimeout(deadline)
            val started = SystemClock.elapsedRealtime()
            val proxySocket = Socket()
            var tlsSocket: SSLSocket? = null
            try {
                proxySocket.connect(InetSocketAddress("127.0.0.1", session.port), remaining)
                val authorization = Base64.encodeToString(
                    "${session.username}:${session.password}".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
                val connectRequest = """
                    CONNECT $PROBE_HOST:$PROBE_PORT HTTP/1.1
                    Host: $PROBE_HOST:$PROBE_PORT
                    Proxy-Authorization: Basic $authorization

                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                proxySocket.getOutputStream().apply {
                    write(connectRequest.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val proxyStatus = readHttpStatus(proxySocket, proxySocket.getInputStream(), deadline)
                if (proxyStatus != HTTP_OK) throw IOException("Локальный прокси ответил HTTP $proxyStatus")

                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(proxySocket, PROBE_HOST, PROBE_PORT, true) as SSLSocket
                tlsSocket = tls
                tls.soTimeout = remainingTimeout(deadline)
                tls.startHandshake()
                val hostnameVerified = javax.net.ssl.HttpsURLConnection
                    .getDefaultHostnameVerifier()
                    .verify(PROBE_HOST, tls.session)
                if (!hostnameVerified) throw SSLPeerUnverifiedException("TLS hostname verification failed")

                val httpsRequest = buildString {
                    append(method.wireValue).append(' ').append(PROBE_PATH).append(" HTTP/1.1\r\n")
                    append("Host: ").append(PROBE_HOST).append("\r\n")
                    append("User-Agent: deytt-connect/1\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("Connection: close\r\n\r\n")
                }
                tls.outputStream.apply {
                    write(httpsRequest.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val status = readHttpStatus(tls, tls.inputStream, deadline)
                if (status != HTTP_NO_CONTENT) throw IOException("Проверочный сервер ответил HTTP $status")
                totalMeasurement += SystemClock.elapsedRealtime() - started
            } finally {
                runCatching { tlsSocket?.close() }
                runCatching { proxySocket.close() }
            }
        }
        return totalMeasurement / 2L
    }

    /** Measures bytes returned during a bounded authenticated download sample. */
    fun measureDownload(
        session: RouteProbeSession,
        token: String,
        sampleMillis: Int = QUICK_DOWNLOAD_MILLIS,
        maximumBytes: Long = 32L * 1024L * 1024L,
    ): Long {
        require(token.length in 32..256 && token.none { it == '\r' || it == '\n' })
        require(sampleMillis in 1_000..5_000)
        val connectDeadline = SystemClock.elapsedRealtime() + 8_000L
        val socket = openTlsTunnel(session, DOWNLOAD_HOST, connectDeadline)
        try {
            socket.soTimeout = remainingTimeout(connectDeadline)
            socket.outputStream.apply {
                val request = "GET $DOWNLOAD_PATH HTTP/1.1\r\n" +
                    "Host: $DOWNLOAD_HOST\r\n" +
                    "User-Agent: deytt-connect/${BuildConfig.VERSION_NAME}\r\n" +
                    "X-TG-App-Token: $token\r\n" +
                    "Accept-Encoding: identity\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val response = readHttpResponse(socket, socket.inputStream, connectDeadline)
            if (response.status != HTTP_OK) throw IOException("Сервер скорости ответил HTTP ${response.status}")

            val sampleDeadline = SystemClock.elapsedRealtime() + sampleMillis
            val sampleStarted = SystemClock.elapsedRealtime()
            val body = socket.inputStream
            val transferEncoding = response.headers["transfer-encoding"].orEmpty()
            val contentLength = response.headers["content-length"]?.toLongOrNull()
            val received = if (transferEncoding.contains("chunked", ignoreCase = true)) {
                countChunkedBody(socket, body, sampleDeadline, maximumBytes)
            } else {
                countBody(socket, body, sampleDeadline, minOf(contentLength ?: maximumBytes, maximumBytes))
            }
            check(received > 0L) { "Сервер скорости не передал данные" }
            val elapsed = (SystemClock.elapsedRealtime() - sampleStarted).coerceAtLeast(1L)
            return received * 1_000L / elapsed
        } finally {
            runCatching { socket.close() }
        }
    }

    internal fun parseHttpStatusCode(statusLine: String): Int {
        val match = HTTP_STATUS_LINE.matchEntire(statusLine)
            ?: throw IOException("Некорректная строка HTTP-статуса")
        return match.groupValues[1].toInt()
    }

    private data class HttpResponse(val status: Int, val headers: Map<String, String>)

    private fun readHttpStatus(socket: Socket, input: InputStream, deadline: Long): Int =
        readHttpResponse(socket, input, deadline).status

    private fun readHttpResponse(socket: Socket, input: InputStream, deadline: Long): HttpResponse {
        val status = parseHttpStatusCode(readHttpLine(socket, input, deadline))
        var headerBytes = 0
        val headers = linkedMapOf<String, String>()
        repeat(MAX_HTTP_HEADERS) {
            val line = readHttpLine(socket, input, deadline)
            headerBytes += line.length + 2
            if (headerBytes > MAX_HTTP_HEADER_BYTES) throw IOException("Слишком длинные HTTP-заголовки")
            if (line.isEmpty()) return HttpResponse(status, headers)
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        throw IOException("Слишком много HTTP-заголовков")
    }

    private fun openTlsTunnel(session: RouteProbeSession, host: String, deadline: Long): SSLSocket {
        val proxySocket = Socket()
        try {
            proxySocket.connect(InetSocketAddress("127.0.0.1", session.port), remainingTimeout(deadline))
            val authorization = Base64.encodeToString(
                "${session.username}:${session.password}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            val request = "CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\nProxy-Authorization: Basic $authorization\r\n\r\n"
            proxySocket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val status = readHttpStatus(proxySocket, proxySocket.getInputStream(), deadline)
            if (status != HTTP_OK) throw IOException("Локальный прокси ответил HTTP $status")
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(proxySocket, host, 443, true) as SSLSocket
            tls.soTimeout = remainingTimeout(deadline)
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                tls.close()
                throw SSLPeerUnverifiedException("TLS hostname verification failed")
            }
            return tls
        } catch (error: Throwable) {
            runCatching { proxySocket.close() }
            throw error
        }
    }

    private fun countChunkedBody(socket: Socket, input: InputStream, deadline: Long, maximumBytes: Long): Long {
        var total = 0L
        val buffer = ByteArray(8 * 1024)
        while (total < maximumBytes) {
            val line = try {
                readHttpLine(socket, input, deadline).substringBefore(';').trim()
            } catch (timeout: SocketTimeoutException) {
                if (total > 0L) return total else throw timeout
            }
            val chunkSize = line.toLongOrNull(16) ?: throw IOException("Некорректный HTTP chunk")
            if (chunkSize == 0L) return total
            val allowed = minOf(chunkSize, maximumBytes - total)
            var remaining = allowed
            while (remaining > 0L) {
                val timeout = deadline - SystemClock.elapsedRealtime()
                if (timeout <= 0L) {
                    if (total > 0L) return total
                    throw SocketTimeoutException("Скоростная проверка превысила лимит времени")
                }
                socket.soTimeout = timeout.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val count = try {
                    input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                } catch (timeout: SocketTimeoutException) {
                    if (total > 0L) return total else throw timeout
                }
                if (count < 0) throw IOException("Поток скорости завершился раньше ожидаемого")
                if (count > 0) {
                    total += count
                    remaining -= count
                }
            }
            if (allowed < chunkSize) return total
            try {
                readHttpLine(socket, input, deadline)
            } catch (timeout: SocketTimeoutException) {
                if (total > 0L) return total else throw timeout
            }
        }
        return total
    }

    private fun countBody(socket: Socket, input: InputStream, deadline: Long, maximumBytes: Long): Long {
        var total = 0L
        val buffer = ByteArray(8 * 1024)
        while (total < maximumBytes) {
            val remainingTime = deadline - SystemClock.elapsedRealtime()
            if (remainingTime <= 0L) {
                if (total > 0L) break
                throw SocketTimeoutException("Скоростная проверка превысила лимит времени")
            }
            socket.soTimeout = remainingTime.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val count = try {
                input.read(buffer, 0, minOf(buffer.size.toLong(), maximumBytes - total).toInt())
            } catch (timeout: SocketTimeoutException) {
                if (total > 0L) break else throw timeout
            }
            if (count < 0) break
            if (count > 0) total += count
        }
        return total
    }

    private fun readHttpLine(socket: Socket, input: InputStream, deadline: Long): String {
        val line = StringBuilder()
        repeat(MAX_HTTP_LINE_BYTES) {
            socket.soTimeout = remainingTimeout(deadline)
            when (val value = input.read()) {
                -1 -> throw IOException("HTTP-соединение закрыто до ответа")
                '\n'.code -> return line.toString()
                '\r'.code -> Unit
                else -> line.append(value.toChar())
            }
        }
        throw IOException("Слишком длинная строка HTTP")
    }

    private fun remainingTimeout(deadline: Long): Int {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) throw SocketTimeoutException("Проверка маршрута превысила 10 секунд")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun measureThroughSystemVpn(
        method: RouteProbeMethod,
        timeoutMillis: Int = TIMEOUT_MILLIS,
    ): Long {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var lastMeasurement: Long? = null
        repeat(2) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("Проверка маршрута превысила 10 секунд")
            val started = SystemClock.elapsedRealtime()
            val connection = URL(PROBE_URL).openConnection(Proxy.NO_PROXY) as HttpsURLConnection
            try {
                connection.requestMethod = method.wireValue
                connection.connectTimeout = remaining
                connection.readTimeout = remaining
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Connection", "close")
                val status = connection.responseCode
                if (status != 204) throw IOException("Проверочный сервер ответил HTTP $status")
                lastMeasurement = SystemClock.elapsedRealtime() - started
            } finally {
                connection.disconnect()
            }
        }
        return lastMeasurement ?: throw IOException("Проверка маршрута не получила ответа")
    }

    /** A bounded sample through the current Android VPN. Caller verifies its route. */
    fun measureSystemDownload(token: String, stillCurrent: () -> Boolean, sampleMillis: Int = QUICK_DOWNLOAD_MILLIS): Long {
        require(sampleMillis in 1_000..5_000)
        require(token.length in 32..256 && token.none { it == '\r' || it == '\n' })
        val connection = URL("https://$DOWNLOAD_HOST$DOWNLOAD_PATH")
            .openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("X-TG-App-Token", token)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            check(stillCurrent()) { "Route changed during measurement" }
            check(connection.responseCode == HTTP_OK) { "Download endpoint unavailable" }
            val started = SystemClock.elapsedRealtime()
            val deadline = started + sampleMillis
            var received = 0L
            val buffer = ByteArray(32 * 1024)
            connection.inputStream.use { input ->
                while (SystemClock.elapsedRealtime() < deadline && received < 32L * 1024 * 1024) {
                    check(stillCurrent() && !Thread.currentThread().isInterrupted) { "Route changed during measurement" }
                    connection.readTimeout = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt()
                    val count = try { input.read(buffer) } catch (error: SocketTimeoutException) {
                        if (received > 0) break else throw error
                    }
                    if (count < 0) break
                    received += count
                }
            }
            check(stillCurrent() && received > 0) { "Measurement did not complete" }
            return received * 1_000L / (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
        } finally {
            connection.disconnect()
        }
    }

    fun isApplicationRoutedByTunnel(config: String, packageName: String): Boolean {
        val inbounds = JSONObject(config).optJSONArray("inbounds") ?: return false
        val tun = (0 until inbounds.length())
            .asSequence()
            .mapNotNull(inbounds::optJSONObject)
            .firstOrNull { it.optString("type") == "tun" } ?: return false
        val included = tun.optJSONArray("include_package")
        if (included != null && included.length() > 0 &&
            (0 until included.length()).none { included.optString(it) == packageName }
        ) return false
        val excluded = tun.optJSONArray("exclude_package") ?: return true
        return (0 until excluded.length()).none { excluded.optString(it) == packageName }
    }
}

object RouteProbeClient {
    const val ACTION_RESULT = "space.deytt.connect.action.ROUTE_PROBE_RESULT"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_ROUTE_TAG = "route_tag"
    const val EXTRA_MILLISECONDS = "milliseconds"
    const val EXTRA_ERROR = "probe_error"
    const val EXTRA_COMPLETE = "complete"
    const val EXTRA_STAGE = "probe_stage"
    const val EXTRA_BYTES_PER_SECOND = "bytes_per_second"
    const val EXTRA_TOKEN = "session_token"
    @Volatile
    private var activeRequestId: String? = null

    private var completionReceiverRegistered = false

    @Synchronized
    private fun ensureCompletionReceiver(context: Context) {
        if (completionReceiverRegistered) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getBooleanExtra(EXTRA_COMPLETE, false)) {
                    clear(app, intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty())
                }
            }
        }
        // Cancellation acknowledgements must survive Activity.onStop and recreation.
        // Keep the receiver for the application process lifetime, without retaining an Activity.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, IntentFilter(ACTION_RESULT), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, IntentFilter(ACTION_RESULT))
        }
        completionReceiverRegistered = true
    }

    fun start(
        context: Context,
        config: String,
        routeTags: List<String>,
        method: RouteProbeMethod,
        token: String?,
        latencyOnly: Boolean = false,
    ): String {
        require(routeTags.isNotEmpty()) { "Не выбраны маршруты для проверки" }
        require(token == null || token.length in 32..256) { "Invalid Telegram session" }
        check(VpnService.prepare(context) == null) { "Нужно системное разрешение VPN для диагностики маршрутов" }
        check(activeRequestId == null) { "Проверка маршрутов уже выполняется" }
        ensureCompletionReceiver(context)
        val requestId = java.util.UUID.randomUUID().toString()
        val intent = Intent(context, RouteProbeVpnService::class.java)
            .setAction(ConnectVpnService.ACTION_ROUTE_PROBE)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra("config", config)
            .putStringArrayListExtra("route_tags", ArrayList(routeTags))
            .putExtra("method", method.name)
            .putExtra(EXTRA_TOKEN, if (latencyOnly) "" else token.orEmpty())
        activeRequestId = requestId
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (error: Throwable) {
            activeRequestId = null
            throw error
        }
        return requestId
    }

    fun isRunning(context: Context): Boolean = activeRequestId != null

    fun clear(context: Context, requestId: String) {
        if (activeRequestId == requestId) activeRequestId = null
    }

    fun cancel(context: Context) {
        if (activeRequestId == null) return
        context.startService(Intent(context, RouteProbeVpnService::class.java)
            .setAction(ConnectVpnService.ACTION_CANCEL_ROUTE_PROBE))
    }
}
