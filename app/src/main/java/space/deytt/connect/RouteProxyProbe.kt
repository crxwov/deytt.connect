package space.deytt.connect

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
    const val PROBE_URL = "https://cp.cloudflare.com/generate_204"
    private const val PROBE_INBOUND = "deytt-route-probe"
    private const val PROBE_HOST = "cp.cloudflare.com"
    private const val PROBE_PORT = 443
    private const val PROBE_PATH = "/generate_204"
    private const val HTTP_OK = 200
    private const val HTTP_NO_CONTENT = 204
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

    fun measureProxy(
        session: RouteProbeSession,
        method: RouteProbeMethod,
        timeoutMillis: Int = TIMEOUT_MILLIS,
    ): Long {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var lastMeasurement: Long? = null
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
                lastMeasurement = SystemClock.elapsedRealtime() - started
            } finally {
                runCatching { tlsSocket?.close() }
                runCatching { proxySocket.close() }
            }
        }
        return lastMeasurement ?: throw IOException("Проверка маршрута не получила ответа")
    }

    internal fun parseHttpStatusCode(statusLine: String): Int {
        val match = HTTP_STATUS_LINE.matchEntire(statusLine)
            ?: throw IOException("Некорректная строка HTTP-статуса")
        return match.groupValues[1].toInt()
    }

    private fun readHttpStatus(socket: Socket, input: InputStream, deadline: Long): Int {
        val status = parseHttpStatusCode(readHttpLine(socket, input, deadline))
        var headerBytes = 0
        repeat(MAX_HTTP_HEADERS) {
            val line = readHttpLine(socket, input, deadline)
            headerBytes += line.length + 2
            if (headerBytes > MAX_HTTP_HEADER_BYTES) throw IOException("Слишком длинные HTTP-заголовки")
            if (line.isEmpty()) return status
        }
        throw IOException("Слишком много HTTP-заголовков")
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

    fun start(
        context: Context,
        config: String,
        routeTags: List<String>,
        method: RouteProbeMethod,
    ): String {
        require(routeTags.isNotEmpty()) { "Не выбраны маршруты для проверки" }
        check(VpnService.prepare(context) == null) { "Нужно системное разрешение VPN для диагностики маршрутов" }
        val requestId = java.util.UUID.randomUUID().toString()
        val intent = Intent(context, ConnectVpnService::class.java)
            .setAction(ConnectVpnService.ACTION_ROUTE_PROBE)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra("config", config)
            .putStringArrayListExtra("route_tags", ArrayList(routeTags))
            .putExtra("method", method.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        return requestId
    }
}
