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
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
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
    val socks5: Boolean = false,
) {
    fun configuration(config: String, routeTag: String): String =
        RouteProxyProbe.configuration(config, routeTag, port, username, password)
}

internal data class LatencyProbeEndpoint(
    val host: String,
    val path: String,
)

/** Excludes TCP slow-start ramp-up and initial pre-buffered bytes for steady-state speed. */
internal class DownloadThroughputTracker(
    private val startedAtMillis: Long,
    private val warmupMillis: Long = RouteProxyProbe.DOWNLOAD_WARMUP_MILLIS,
    private val minSteadyMillis: Long = RouteProxyProbe.DOWNLOAD_MIN_STEADY_MILLIS,
) {
    private var warmupEndedAt: Long? = null
    private var warmupBytes: Long = 0L

    fun record(totalBytes: Long, nowMillis: Long) {
        if (warmupEndedAt == null && nowMillis - startedAtMillis >= warmupMillis) {
            warmupEndedAt = nowMillis
            warmupBytes = totalBytes
        }
    }

    fun bytesPerSecond(totalBytes: Long, nowMillis: Long): Long {
        if (totalBytes <= 0L) return 0L
        val steadyStart = warmupEndedAt
        if (steadyStart != null) {
            val steadyElapsed = nowMillis - steadyStart
            val steadyBytes = totalBytes - warmupBytes
            if (steadyElapsed >= minSteadyMillis && steadyBytes > 0L) {
                return steadyBytes * 1_000L / steadyElapsed
            }
        }
        val totalElapsed = (nowMillis - startedAtMillis).coerceAtLeast(1L)
        return totalBytes * 1_000L / totalElapsed
    }
}

/** Runs a bounded GET/HEAD check through a loopback-only proxy (sing-box HTTP or AWG SOCKS5). */
object RouteProxyProbe {
    const val TIMEOUT_MILLIS = 5_000
    const val QUICK_DOWNLOAD_MILLIS = 1_000
    const val HOME_DOWNLOAD_MILLIS = 2_500
    const val LATENCY_SAMPLE_COUNT = 2
    internal const val DOWNLOAD_WARMUP_MILLIS = 400L
    internal const val DOWNLOAD_MIN_STEADY_MILLIS = 250L
    private const val DOWNLOAD_PROGRESS_INTERVAL_MILLIS = 250L
    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    private const val DOWNLOAD_SOCKET_RCVBUF_BYTES = 256 * 1024
    private const val SO_TIMEOUT_UPDATE_INTERVAL_MILLIS = 150L
    private const val MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024
    private const val DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 4_000
    private const val SYSTEM_DOWNLOAD_CLEANUP_MILLIS = 1_000L
    private val systemVpnProbeExecutor = ThreadPoolExecutor(
        0,
        4,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { task -> Thread(task, "deytt-route-probe").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private const val PROBE_INBOUND = "deytt-route-probe"
    // Neither host is bypassed by SplitTunnelProfile direct rules (cp.cloudflare.com)
    // or pinned to route:DE (googleDomains), so probes always traverse the selected tunnel.
    internal val LATENCY_ENDPOINTS = listOf(
        LatencyProbeEndpoint("deytt.space", "/generate_204"),
        LatencyProbeEndpoint("speed.cloudflare.com", "/__down?bytes=0"),
    )
    private const val PROBE_PORT = 443
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
        RuntimeProfile.sanitizeOutbounds(root.optJSONArray("outbounds"))
        RuntimeProfile.sanitizeOutbounds(root.optJSONArray("endpoints"))
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
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val dnsServers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        var hasLocalDns = false
        repeat(dnsServers.length()) { index ->
            val server = dnsServers.optJSONObject(index) ?: return@repeat
            server.remove("detour")
            if (server.optString("tag") == "local-dns") {
                hasLocalDns = true
            }
        }
        if (!hasLocalDns) {
            dnsServers.put(JSONObject().put("type", "local").put("tag", "local-dns"))
        }
        dns.put("rules", JSONArray())
        dns.put("final", "local-dns")
        return root.toString()
    }

    fun measureProxy(
        session: RouteProbeSession,
        method: RouteProbeMethod,
        timeoutMillis: Int = TIMEOUT_MILLIS,
        cancellation: RouteProbeCancellation? = null,
        onSample: (index: Int, milliseconds: Long?) -> Unit = { _, _ -> },
    ): Long {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val primaryDeadline = SystemClock.elapsedRealtime() + (timeoutMillis * .65).toLong()
        val primary = LATENCY_ENDPOINTS[0]
        return try {
            measureProxyOnEndpoint(session, primary, method, primaryDeadline, cancellation) { index, sample ->
                if (sample != null) onSample(index, sample)
            }
        } catch (failure: IOException) {
            cancellation?.check()
            if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() >= deadline) throw failure
            measureProxyOnEndpoint(session, LATENCY_ENDPOINTS[1], method, deadline, cancellation, onSample)
        }
    }

    private fun measureProxyOnEndpoint(
        session: RouteProbeSession,
        endpoint: LatencyProbeEndpoint,
        method: RouteProbeMethod,
        deadline: Long,
        cancellation: RouteProbeCancellation?,
        onSample: (index: Int, milliseconds: Long?) -> Unit,
    ): Long {
        val samples = ArrayList<Long>(LATENCY_SAMPLE_COUNT)
        var lastFailure: Exception? = null
        var tls: SSLSocket? = null
        var input: BufferedInputStream? = null
        fun closeConnection() {
            runCatching { tls?.close() }
            tls?.let { cancellation?.release(it) }
            tls = null
            input = null
        }
        try {
            for (index in 1..LATENCY_SAMPLE_COUNT) {
                cancellation?.check()
                if (SystemClock.elapsedRealtime() >= deadline) break
                try {
                    val activeTls = tls?.takeIf { !it.isClosed && it.isConnected } ?: run {
                        closeConnection()
                        openTlsTunnel(session, endpoint.host, deadline, cancellation).also { opened ->
                            tls = opened
                            input = BufferedInputStream(opened.inputStream, 8 * 1024)
                        }
                    }
                    val activeInput = input ?: BufferedInputStream(activeTls.inputStream, 8 * 1024).also { input = it }
                    val isLast = index == LATENCY_SAMPLE_COUNT
                    val sample = measureProbeHttpRoundTrip(
                        activeTls,
                        activeInput,
                        method,
                        deadline,
                        keepAlive = !isLast,
                        endpoint = endpoint,
                    )
                    if (RouteProbeStatistics.isValidLatencyMillis(sample)) {
                        samples += sample
                        onSample(index, sample)
                    }
                } catch (failure: Exception) {
                    cancellation?.check()
                    if (Thread.currentThread().isInterrupted) throw failure
                    lastFailure = failure
                    closeConnection()
                    onSample(index, null)
                }
            }
        } finally {
            closeConnection()
        }
        return samples.minOrNull()
            ?: throw (lastFailure ?: SocketTimeoutException("Проверка маршрута превысила лимит времени"))
    }

    /** Measures the HTTPS request/response round trip after TCP and TLS are already established. */
    private fun measureProbeHttpRoundTrip(
        socket: SSLSocket,
        input: InputStream,
        method: RouteProbeMethod,
        deadline: Long,
        keepAlive: Boolean = false,
        endpoint: LatencyProbeEndpoint = LATENCY_ENDPOINTS[0],
    ): Long {
        val request = buildString {
            append(method.wireValue).append(' ').append(endpoint.path).append(" HTTP/1.1\r\n")
            append("Host: ").append(endpoint.host).append("\r\n")
            append("User-Agent: deytt-connect/1\r\n")
            append("Cache-Control: no-cache\r\n")
            if (keepAlive) {
                append("Connection: keep-alive\r\n\r\n")
            } else {
                append("Connection: close\r\n\r\n")
            }
        }.toByteArray(Charsets.US_ASCII)
        socket.soTimeout = remainingTimeout(deadline)
        val started = SystemClock.elapsedRealtime()
        socket.outputStream.apply {
            write(request)
            flush()
        }
        val response = readHttpResponse(socket, input, deadline)
        if (response.status != HTTP_NO_CONTENT && response.status != HTTP_OK) {
            throw IOException("Проверочный сервер ответил HTTP ${response.status}")
        }
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
        if (keepAlive && method == RouteProbeMethod.GET && response.status != HTTP_NO_CONTENT) {
            val contentLength = response.headers["content-length"]?.toLongOrNull()
            if (contentLength != null && contentLength in 1L..4_096L) {
                var remaining = contentLength
                val drain = ByteArray(minOf(remaining, 1024L).toInt())
                while (remaining > 0L) {
                    val read = input.read(drain, 0, minOf(drain.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    remaining -= read
                }
            } else if (contentLength == null) {
                runCatching { socket.close() }
            }
        }
        return elapsed
    }

    /** Measures steady-state bytes/s returned during a bounded authenticated download sample. */
    fun measureDownload(
        session: RouteProbeSession,
        token: String,
        sampleMillis: Int = QUICK_DOWNLOAD_MILLIS,
        maximumBytes: Long = MAX_DOWNLOAD_BYTES,
        cancellation: RouteProbeCancellation? = null,
        onProgress: (bytesPerSecond: Long) -> Unit = {},
    ): Long {
        require(token.length in 32..256 && token.none { it == '\r' || it == '\n' })
        require(sampleMillis in 400..5_000)
        val connectDeadline = SystemClock.elapsedRealtime() + DOWNLOAD_CONNECT_TIMEOUT_MILLIS
        cancellation?.check()
        val socket = openTlsTunnel(session, DOWNLOAD_HOST, connectDeadline, cancellation, largeReceiveBuffer = true)
        try {
            cancellation?.check()
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
            val body = BufferedInputStream(socket.inputStream, DOWNLOAD_BUFFER_BYTES)
            val response = readHttpResponse(socket, body, connectDeadline)
            if (response.status != HTTP_OK) throw IOException("Сервер скорости ответил HTTP ${response.status}")
            validateDownloadContentType(response.headers["content-type"])

            val sampleStarted = SystemClock.elapsedRealtime()
            val sampleDeadline = sampleStarted + sampleMillis
            val tracker = DownloadThroughputTracker(sampleStarted)
            val transferEncoding = response.headers["transfer-encoding"].orEmpty()
            val contentLength = response.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0L }
            var lastProgressAt = sampleStarted
            val reportBytes: (Long) -> Unit = { total ->
                val now = SystemClock.elapsedRealtime()
                tracker.record(total, now)
                if (now - lastProgressAt >= DOWNLOAD_PROGRESS_INTERVAL_MILLIS) {
                    onProgress(tracker.bytesPerSecond(total, now))
                    lastProgressAt = now
                }
            }
            val received = if (transferEncoding.contains("chunked", ignoreCase = true)) {
                countChunkedBody(socket, body, sampleDeadline, maximumBytes, reportBytes, cancellation)
            } else {
                countBody(
                    socket, body, sampleDeadline, minOf(contentLength ?: maximumBytes, maximumBytes), reportBytes,
                    expectedLength = contentLength, cancellation = cancellation,
                )
            }
            cancellation?.check()
            check(received > 0L) { "Сервер скорости не передал данные" }
            val finishedAt = SystemClock.elapsedRealtime()
            tracker.record(received, finishedAt)
            return tracker.bytesPerSecond(received, finishedAt).also(onProgress)
        } finally {
            runCatching { socket.close() }
            cancellation?.release(socket)
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

    private fun openTlsTunnel(
        session: RouteProbeSession,
        host: String,
        deadline: Long,
        cancellation: RouteProbeCancellation? = null,
        largeReceiveBuffer: Boolean = false,
    ): SSLSocket {
        val proxySocket = Socket()
        cancellation?.track(proxySocket)
        var tlsSocket: SSLSocket? = null
        try {
            cancellation?.check()
            proxySocket.tcpNoDelay = true
            if (largeReceiveBuffer) {
                runCatching { proxySocket.receiveBufferSize = DOWNLOAD_SOCKET_RCVBUF_BYTES }
            }
            proxySocket.connect(InetSocketAddress("127.0.0.1", session.port), remainingTimeout(deadline))
            proxySocket.soTimeout = remainingTimeout(deadline)
            if (session.socks5) {
                negotiateSocks5Connect(
                    proxySocket.getInputStream(),
                    proxySocket.getOutputStream(),
                    session.username,
                    session.password,
                    host,
                    PROBE_PORT,
                )
            } else {
                val authorization = Base64.encodeToString(
                    "${session.username}:${session.password}".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
                val request = "CONNECT $host:$PROBE_PORT HTTP/1.1\r\nHost: $host:$PROBE_PORT\r\nProxy-Authorization: Basic $authorization\r\n\r\n"
                proxySocket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val status = readHttpStatus(proxySocket, proxySocket.getInputStream(), deadline)
                if (status != HTTP_OK) throw IOException("Локальный прокси ответил HTTP $status")
            }
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(proxySocket, host, PROBE_PORT, true) as SSLSocket
            tlsSocket = tls
            cancellation?.track(tls)
            cancellation?.check()
            tls.soTimeout = remainingTimeout(deadline)
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                tls.close()
                throw SSLPeerUnverifiedException("TLS hostname verification failed")
            }
            return tls
        } catch (error: Throwable) {
            runCatching { tlsSocket?.close() }
            tlsSocket?.let { cancellation?.release(it) }
            runCatching { proxySocket.close() }
            cancellation?.release(proxySocket)
            throw error
        }
    }

    /** RFC 1928 + RFC 1929 username/password SOCKS5 CONNECT for AwgProxyBackend. */
    internal fun negotiateSocks5Connect(
        input: InputStream,
        output: OutputStream,
        username: String,
        password: String,
        host: String,
        port: Int,
    ) {
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        require(userBytes.size in 1..255 && passBytes.size in 1..255 && hostBytes.size in 1..255 && port in 1..65535)

        output.write(byteArrayOf(0x05, 0x01, 0x02))
        output.flush()
        val greeting = readExact(input, 2)
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x02.toByte()) {
            throw IOException("Локальный SOCKS5 прокси отклонил авторизацию")
        }

        val auth = ByteArray(3 + userBytes.size + passBytes.size)
        auth[0] = 0x01
        auth[1] = userBytes.size.toByte()
        System.arraycopy(userBytes, 0, auth, 2, userBytes.size)
        auth[2 + userBytes.size] = passBytes.size.toByte()
        System.arraycopy(passBytes, 0, auth, 3 + userBytes.size, passBytes.size)
        output.write(auth)
        output.flush()
        val authReply = readExact(input, 2)
        if (authReply[0] != 0x01.toByte() || authReply[1] != 0x00.toByte()) {
            throw IOException("Локальный SOCKS5 прокси отклонил учётные данные")
        }

        val connect = ByteArray(7 + hostBytes.size)
        connect[0] = 0x05
        connect[1] = 0x01
        connect[2] = 0x00
        connect[3] = 0x03
        connect[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, connect, 5, hostBytes.size)
        connect[5 + hostBytes.size] = ((port ushr 8) and 0xFF).toByte()
        connect[6 + hostBytes.size] = (port and 0xFF).toByte()
        output.write(connect)
        output.flush()

        val header = readExact(input, 4)
        if (header[0] != 0x05.toByte()) throw IOException("Некорректный ответ SOCKS5 прокси")
        val remaining = when (header[3].toInt() and 0xFF) {
            0x01 -> 4 + 2
            0x04 -> 16 + 2
            0x03 -> (readExact(input, 1)[0].toInt() and 0xFF) + 2
            else -> throw IOException("Неподдерживаемый адрес в ответе SOCKS5")
        }
        readExact(input, remaining)
        if (header[1] != 0x00.toByte()) {
            throw IOException("SOCKS5 маршрут не смог подключиться (код ${header[1].toInt() and 0xFF})")
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw IOException("SOCKS5 соединение закрыто до ответа")
            offset += read
        }
        return result
    }

    private fun countChunkedBody(
        socket: Socket,
        input: InputStream,
        deadline: Long,
        maximumBytes: Long,
        onBytesRead: (Long) -> Unit = {},
        cancellation: RouteProbeCancellation? = null,
    ): Long {
        var total = 0L
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var lastTimeoutSetAt = 0L
        while (total < maximumBytes) {
            cancellation?.check()
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
                val now = SystemClock.elapsedRealtime()
                val timeout = deadline - now
                if (timeout <= 0L) {
                    if (total > 0L) return total
                    throw SocketTimeoutException("Скоростная проверка превысила лимит времени")
                }
                if (lastTimeoutSetAt == 0L || now - lastTimeoutSetAt >= SO_TIMEOUT_UPDATE_INTERVAL_MILLIS || timeout < SO_TIMEOUT_UPDATE_INTERVAL_MILLIS) {
                    socket.soTimeout = timeout.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    lastTimeoutSetAt = now
                }
                val count = try {
                    input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                } catch (timeout: SocketTimeoutException) {
                    if (total > 0L) return total else throw timeout
                }
                if (count < 0) throw IOException("Поток скорости завершился раньше ожидаемого")
                if (count > 0) {
                    total += count
                    remaining -= count
                    onBytesRead(total)
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

    private fun countBody(
        socket: Socket,
        input: InputStream,
        deadline: Long,
        maximumBytes: Long,
        onBytesRead: (Long) -> Unit = {},
        expectedLength: Long? = null,
        cancellation: RouteProbeCancellation? = null,
    ): Long {
        var total = 0L
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        var lastTimeoutSetAt = 0L
        while (total < maximumBytes) {
            cancellation?.check()
            val now = SystemClock.elapsedRealtime()
            val remainingTime = deadline - now
            if (remainingTime <= 0L) {
                if (total > 0L) break
                throw SocketTimeoutException("Скоростная проверка превысила лимит времени")
            }
            if (lastTimeoutSetAt == 0L || now - lastTimeoutSetAt >= SO_TIMEOUT_UPDATE_INTERVAL_MILLIS || remainingTime < SO_TIMEOUT_UPDATE_INTERVAL_MILLIS) {
                socket.soTimeout = remainingTime.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                lastTimeoutSetAt = now
            }
            val count = try {
                input.read(buffer, 0, minOf(buffer.size.toLong(), maximumBytes - total).toInt())
            } catch (timeout: SocketTimeoutException) {
                if (total > 0L) break else throw timeout
            }
            if (count < 0) {
                if (isPrematureDownloadEof(total, expectedLength, maximumBytes)) {
                    throw IOException("Поток скорости завершился раньше ожидаемого")
                }
                break
            }
            if (count > 0) {
                total += count
                onBytesRead(total)
            }
        }
        return total
    }

    private fun readHttpLine(socket: Socket, input: InputStream, deadline: Long): String {
        socket.soTimeout = remainingTimeout(deadline)
        val line = StringBuilder()
        repeat(MAX_HTTP_LINE_BYTES) {
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
        sampleCount: Int = LATENCY_SAMPLE_COUNT,
        onSample: (index: Int, milliseconds: Long?) -> Unit = { _, _ -> },
    ): Long {
        val activeConnection = AtomicReference<Socket?>()
        val task = FutureTask {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            val primaryDeadline = SystemClock.elapsedRealtime() + (timeoutMillis * .65).toLong()
            try {
                measureThroughSystemVpnBlocking(LATENCY_ENDPOINTS[0], method, primaryDeadline, sampleCount, activeConnection) { index, sample ->
                    if (sample != null) onSample(index, sample)
                }
            } catch (failure: IOException) {
                if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() >= deadline) throw failure
                measureThroughSystemVpnBlocking(LATENCY_ENDPOINTS[1], method, deadline, sampleCount, activeConnection, onSample)
            }
        }
        try {
            systemVpnProbeExecutor.execute(task)
            return task.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        } catch (failure: RejectedExecutionException) {
            throw SocketTimeoutException("Слишком много одновременных проверок маршрута").also { it.initCause(failure) }
        } catch (failure: TimeoutException) {
            runCatching { activeConnection.getAndSet(null)?.close() }
            task.cancel(true)
            throw SocketTimeoutException("Проверка маршрута превысила $timeoutMillis мс").also { it.initCause(failure) }
        } catch (failure: InterruptedException) {
            runCatching { activeConnection.getAndSet(null)?.close() }
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw SocketTimeoutException("Проверка маршрута прервана").also { it.initCause(failure) }
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause) {
                is IOException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IOException("Не удалось проверить маршрут", cause)
            }
        }
    }

    private fun measureThroughSystemVpnBlocking(
        endpoint: LatencyProbeEndpoint,
        method: RouteProbeMethod,
        deadline: Long,
        sampleCount: Int,
        activeConnection: AtomicReference<Socket?>,
        onSample: (index: Int, milliseconds: Long?) -> Unit,
    ): Long {
        val samples = ArrayList<Long>(sampleCount)
        var lastFailure: Exception? = null
        var currentSocket: Socket? = null
        var currentTlsSocket: SSLSocket? = null
        var currentInput: BufferedInputStream? = null

        fun closeCurrentConnection() {
            runCatching { currentTlsSocket?.close() }
            runCatching { currentSocket?.close() }
            activeConnection.compareAndSet(currentTlsSocket, null)
            activeConnection.compareAndSet(currentSocket, null)
            currentTlsSocket = null
            currentSocket = null
            currentInput = null
        }

        try {
            for (index in 1..sampleCount) {
                if (SystemClock.elapsedRealtime() >= deadline) break
                try {
                    var tls = currentTlsSocket
                    if (tls == null || tls.isClosed || !tls.isConnected) {
                        closeCurrentConnection()
                        val socket = Socket().apply { tcpNoDelay = true }
                        currentSocket = socket
                        activeConnection.set(socket)
                        socket.connect(InetSocketAddress(endpoint.host, PROBE_PORT), remainingTimeout(deadline))
                        socket.soTimeout = remainingTimeout(deadline)
                        val newTls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(socket, endpoint.host, PROBE_PORT, true) as SSLSocket
                        currentTlsSocket = newTls
                        tls = newTls
                        activeConnection.set(newTls)
                        newTls.soTimeout = remainingTimeout(deadline)
                        newTls.startHandshake()
                        val hostnameVerified = HttpsURLConnection.getDefaultHostnameVerifier()
                            .verify(endpoint.host, newTls.session)
                        if (!hostnameVerified) throw SSLPeerUnverifiedException("TLS hostname verification failed")
                        currentInput = BufferedInputStream(newTls.inputStream, 8 * 1024)
                    }
                    val input = currentInput ?: BufferedInputStream(tls.inputStream, 8 * 1024).also { currentInput = it }
                    val isLast = index == sampleCount
                    val sample = measureProbeHttpRoundTrip(tls, input, method, deadline, keepAlive = !isLast, endpoint = endpoint)
                    samples += sample
                    onSample(index, sample)
                } catch (failure: Exception) {
                    if (Thread.currentThread().isInterrupted) throw failure
                    lastFailure = failure
                    closeCurrentConnection()
                    onSample(index, null)
                }
            }
        } finally {
            closeCurrentConnection()
        }
        return samples.filter(RouteProbeStatistics::isValidLatencyMillis).minOrNull()
            ?: throw (lastFailure ?: SocketTimeoutException("Проверочный сервер не ответил вовремя"))
    }

    /** A bounded sample through the current Android VPN. Caller verifies its route. */
    fun measureSystemDownload(
        token: String,
        stillCurrent: () -> Boolean,
        sampleMillis: Int = HOME_DOWNLOAD_MILLIS,
        transferActive: () -> Boolean = stillCurrent,
        onProgress: (bytesPerSecond: Long) -> Unit = {},
    ): Long {
        require(sampleMillis in 400..5_000)
        require(token.length in 32..256 && token.none { it == '\r' || it == '\n' })
        val activeConnection = AtomicReference<HttpsURLConnection?>()
        val taskDeadline = systemDownloadTaskTimeoutMillis(sampleMillis)
        val deadline = SystemClock.elapsedRealtime() + taskDeadline
        val task = FutureTask {
            measureSystemDownloadBlocking(token, stillCurrent, transferActive, sampleMillis, deadline, onProgress, activeConnection)
        }
        try {
            systemVpnProbeExecutor.execute(task)
            return task.get(taskDeadline, TimeUnit.MILLISECONDS)
        } catch (failure: RejectedExecutionException) {
            throw SocketTimeoutException("Слишком много одновременных проверок скорости").also { it.initCause(failure) }
        } catch (failure: TimeoutException) {
            activeConnection.getAndSet(null)?.disconnect()
            task.cancel(true)
            throw SocketTimeoutException("Замер скорости превысил $taskDeadline мс").also { it.initCause(failure) }
        } catch (failure: InterruptedException) {
            activeConnection.getAndSet(null)?.disconnect()
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw SocketTimeoutException("Замер скорости прерван").also { it.initCause(failure) }
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause) {
                is IOException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IOException("Не удалось измерить скорость", cause)
            }
        }
    }

    internal fun systemDownloadTaskTimeoutMillis(sampleMillis: Int): Long =
        DOWNLOAD_CONNECT_TIMEOUT_MILLIS + sampleMillis + SYSTEM_DOWNLOAD_CLEANUP_MILLIS

    private fun measureSystemDownloadBlocking(
        token: String,
        stillCurrent: () -> Boolean,
        transferActive: () -> Boolean,
        sampleMillis: Int,
        totalDeadline: Long,
        onProgress: (Long) -> Unit,
        activeConnection: AtomicReference<HttpsURLConnection?>,
    ): Long {
        val connection = URL("https://$DOWNLOAD_HOST$DOWNLOAD_PATH")
            .openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        activeConnection.set(connection)
        try {
            connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = DOWNLOAD_CONNECT_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("X-TG-App-Token", token)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-store")
            check(stillCurrent()) { "Route changed during measurement" }
            val responseCode = connection.responseCode
            if (responseCode != HTTP_OK) throw IOException("Download endpoint returned HTTP $responseCode")
            validateDownloadContentType(connection.contentType)
            val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
            val maximumBytes = MAX_DOWNLOAD_BYTES
            var received = 0L
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            check(stillCurrent()) { "Route changed during measurement" }
            val result = connection.inputStream.use { input ->
                val sampleStarted = SystemClock.elapsedRealtime()
                val sampleDeadline = sampleStarted + sampleMillis
                check(sampleDeadline < totalDeadline) { "Download setup exhausted the measurement budget" }
                val tracker = DownloadThroughputTracker(sampleStarted)
                var lastProgressAt = sampleStarted
                while (received < maximumBytes) {
                    check(isSystemDownloadTransferActive(transferActive) { Thread.currentThread().isInterrupted }) {
                        "Measurement interrupted"
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (remainingSystemDownloadReadTimeoutMillis(now, sampleDeadline, totalDeadline) == null) break
                    val count = try {
                        input.read(buffer, 0, minOf(buffer.size.toLong(), maximumBytes - received).toInt())
                    } catch (error: SocketTimeoutException) {
                        if (received > 0 && SystemClock.elapsedRealtime() >= sampleDeadline) break
                        throw error
                    }
                    if (count < 0) {
                        if (isPrematureDownloadEof(received, contentLength, maximumBytes)) {
                            throw IOException("Download endpoint ended before Content-Length")
                        }
                        break
                    }
                    if (count > 0) {
                        received += count
                        val progressAt = SystemClock.elapsedRealtime()
                        tracker.record(received, progressAt)
                        if (progressAt - lastProgressAt >= DOWNLOAD_PROGRESS_INTERVAL_MILLIS) {
                            onProgress(tracker.bytesPerSecond(received, progressAt))
                            lastProgressAt = progressAt
                        }
                    }
                }
                check(received > 0) { "Measurement did not complete" }
                val finishedAt = SystemClock.elapsedRealtime()
                tracker.record(received, finishedAt)
                val bytesPerSecond = tracker.bytesPerSecond(received, finishedAt)
                check(stillCurrent()) { "Route changed during measurement" }
                check(!Thread.currentThread().isInterrupted) { "Measurement interrupted" }
                bytesPerSecond
            }
            return result.also(onProgress)
        } finally {
            connection.disconnect()
            activeConnection.compareAndSet(connection, null)
        }
    }

    internal fun validateDownloadContentType(contentType: String?) {
        if (contentType?.substringBefore(';')?.trim()?.equals("application/octet-stream", ignoreCase = true) != true) {
            throw IOException("Download endpoint returned an unexpected Content-Type")
        }
    }

    internal fun isPrematureDownloadEof(received: Long, contentLength: Long?, maximumBytes: Long): Boolean =
        contentLength != null && received < contentLength && received < maximumBytes

    internal fun remainingSystemDownloadReadTimeoutMillis(
        now: Long,
        sampleDeadline: Long,
        totalDeadline: Long,
    ): Int? = (minOf(sampleDeadline, totalDeadline) - now)
        .takeIf { it > 0L }
        ?.coerceAtMost(Int.MAX_VALUE.toLong())
        ?.toInt()

    internal fun isSystemDownloadTransferActive(
        transferActive: () -> Boolean,
        isInterrupted: () -> Boolean,
    ): Boolean = transferActive() && !isInterrupted()

    fun isApplicationRoutedByTunnel(
        config: String,
        packageName: String,
        customPackages: Collection<String> = emptyList(),
        splitMode: String = UserSplitTunnelStore.MODE_BYPASS,
    ): Boolean {
        val effectiveConfig = if (customPackages.isEmpty()) config else SplitTunnelProfile.apply(
            config,
            customPackages = customPackages,
            mode = splitMode,
        )
        val inbounds = JSONObject(effectiveConfig).optJSONArray("inbounds") ?: return false
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
    const val EXTRA_SAMPLE_INDEX = "sample_index"
    const val EXTRA_TOKEN = "session_token"
    const val EXTRA_AWG_ROUTE_IDS = "awg_route_ids"
    const val EXTRA_REQUEST_STARTED_AT = "request_started_at_elapsed_ms"
    const val EXTRA_ELAPSED_MILLIS = "elapsed_millis"
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
        androidx.core.content.ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(ACTION_RESULT),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        completionReceiverRegistered = true
    }

    fun start(
        context: Context,
        config: String,
        routeTags: List<String>,
        method: RouteProbeMethod,
        token: String?,
        latencyOnly: Boolean = false,
        awgRouteIds: List<String> = emptyList(),
    ): String {
        require(routeTags.isNotEmpty() || awgRouteIds.isNotEmpty()) { "Не выбраны маршруты для проверки" }
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
            .putStringArrayListExtra(EXTRA_AWG_ROUTE_IDS, ArrayList(awgRouteIds))
            .putExtra("method", method.name)
            .putExtra(EXTRA_TOKEN, if (latencyOnly) "" else token.orEmpty())
            .putExtra(EXTRA_REQUEST_STARTED_AT, SystemClock.elapsedRealtime())
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

    fun cancel(context: Context, requestId: String? = activeRequestId) {
        if (requestId == null || activeRequestId != requestId) return
        context.startService(Intent(context, RouteProbeVpnService::class.java)
            .setAction(ConnectVpnService.ACTION_CANCEL_ROUTE_PROBE)
            .putExtra(EXTRA_REQUEST_ID, requestId))
    }
}
