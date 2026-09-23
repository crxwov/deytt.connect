package space.deytt.connect

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class LatencyTarget(val host: String, val port: Int)

object RouteLatency {
    private val pingTime = Regex("time[=<]([0-9.]+)\\s*ms", RegexOption.IGNORE_CASE)
    fun target(config: String, route: DeyttRoute, awgConfig: String? = null): LatencyTarget? {
        if (route.engine == TunnelEngine.AMNEZIAWG) return awgConfig?.let(::awgTarget)
        val outbounds = JSONObject(config).optJSONArray("outbounds") ?: return null
        val byTag = buildMap<String, JSONObject> {
            for (index in 0 until outbounds.length()) {
                val item = outbounds.optJSONObject(index) ?: continue
                item.optString("tag").takeIf(String::isNotBlank)?.let { put(it, item) }
            }
        }
        return resolve(byTag, route.configTag, mutableSetOf())
    }

    fun measure(target: LatencyTarget, timeoutMillis: Int = 2_500): Long? {
        val ping = runCatching {
            val process = ProcessBuilder(
                "/system/bin/ping", "-c", "1", "-W", "2", target.host,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            pingTime.find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
        }.getOrNull()
        if (ping != null) return ping

        val started = System.nanoTime()
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(target.host, target.port), timeoutMillis) }
            (System.nanoTime() - started) / 1_000_000
        }.getOrNull()
    }

    fun label(milliseconds: Long?): String = milliseconds?.let { "${it} мс" } ?: "нет ответа"

    private fun resolve(
        byTag: Map<String, JSONObject>,
        tag: String,
        visited: MutableSet<String>,
    ): LatencyTarget? {
        if (!visited.add(tag)) return null
        val item = byTag[tag] ?: return null
        val host = item.optString("server").trim()
        val port = item.optInt("server_port", 0)
        if (host.isNotBlank() && port in 1..65535) return LatencyTarget(host, port)
        val children = item.optJSONArray("outbounds") ?: return null
        for (index in 0 until children.length()) {
            resolve(byTag, children.optString(index), visited)?.let { return it }
        }
        return null
    }

    private fun awgTarget(config: String): LatencyTarget? {
        val line = config.lineSequence().firstOrNull { it.trim().startsWith("Endpoint", ignoreCase = true) }
            ?.substringAfter('=', "")?.trim().orEmpty()
        if (line.isBlank()) return null
        val host: String
        val portText: String
        if (line.startsWith("[")) {
            host = line.substringAfter('[').substringBefore(']')
            portText = line.substringAfter("]:", "")
        } else {
            host = line.substringBeforeLast(':', "")
            portText = line.substringAfterLast(':', "")
        }
        val port = portText.toIntOrNull() ?: return null
        return host.takeIf(String::isNotBlank)?.let { LatencyTarget(it, port) }
    }
}

object LatencyExecutor {
    val pool: ExecutorService = Executors.newFixedThreadPool(4)
}
