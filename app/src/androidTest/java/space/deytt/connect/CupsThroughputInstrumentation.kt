package space.deytt.connect

import android.app.Instrumentation
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Isolated companion download load; never starts a VPN or reads production application data. */
class CupsThroughputInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private val threads = ThreadFactory { task -> Thread(task, "cups-throughput").apply { isDaemon = true } }
    private val coordinator = Executors.newSingleThreadExecutor(threads)
    private val workers = Executors.newFixedThreadPool(6, threads)
    private val watchdog = Executors.newScheduledThreadPool(2, threads)
    private val connections = ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private val networkLost = AtomicBoolean(false)
    private lateinit var connectivity: ConnectivityManager
    private lateinit var vpn: Network
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments?.let(::Bundle) ?: Bundle()
        super.onCreate(arguments); start()
    }

    override fun onStart() {
        val began = now()
        var result: JSONObject
        var task: java.util.concurrent.Future<JSONObject>? = null
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated companion required" }
            task = coordinator.submit(Callable { runLoad() })
            result = task.get(remaining(began + 90_000), TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            result = JSONObject().put("result", "FAIL").put("error", safeError(failure))
        } finally {
            task?.cancel(true)
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            connections.forEach { connection -> watchdog.execute { runCatching { connection.disconnect() } } }
            coordinator.shutdownNow(); workers.shutdownNow(); watchdog.shutdown()
        }
        result.put("overallElapsedMillis", now() - began)
        finish(if (result.optString("result") == "PASS") -1 else 0,
            Bundle().apply { putString("result_json", result.toString()) })
    }

    private fun runLoad(): JSONObject {
        require(arguments.getString("phase", "download") == "download")
        val streams = arguments.getString("streams")?.toInt() ?: 1
        val total = arguments.getString("totalPayloadBytes")?.toInt() ?: 4 * 1024 * 1024
        val timeout = arguments.getString("timeoutMillis")?.toLong() ?: 60_000L
        val label = arguments.getString("runLabel", "cups")!!
        val probeArgument = arguments.getString("loadedHttpsProbes", "true")!!
        require(streams == 1 || streams == 4)
        require(total in (1024 * 1024)..(8 * 1024 * 1024) && total % streams == 0)
        require(timeout in 1_000L..60_000L && label.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        require(probeArgument == "true" || probeArgument == "false")
        connectivity = targetContext.getSystemService(ConnectivityManager::class.java)
        vpn = connectivity.activeNetwork?.takeIf(::isVpn)
            ?: connectivity.allNetworks.filter(::isVpn).singleOrNull() ?: throw IOException("VPN unavailable")
        val handle = vpn.networkHandle
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { if (network == vpn) networkLost.set(true) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == vpn && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) networkLost.set(true)
            }
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), listener)
        callback = listener
        check(isVpn(vpn) && vpn in connectivity.allNetworks)
        val began = now()
        val deadline = began + timeout
        val flows = List(streams) { Flow(it + 1, total / streams) }
        val stopProbes = AtomicBoolean(false)
        val latency = CopyOnWriteArrayList<JSONObject>()
        val progress = JSONArray()
        val downloads = flows.map { flow -> workers.submit { download(flow, deadline) } }
        val probes = if (probeArgument == "true") workers.submit {
            while (!stopProbes.get() && now() < deadline && !networkLost.get()) {
                if (flows.any { it.firstByteAt.get() > 0 && it.finishedAt.get() == 0L })
                    latency += latencyProbe(minOf(deadline, now() + 5_000))
                if (!stopProbes.get() && now() < deadline) Thread.sleep(minOf(1_000, remaining(deadline)))
            }
        } else null
        var nextProgress = began + 1_000
        while (downloads.any { !it.isDone } && now() < deadline && !networkLost.get()) {
            if (now() >= nextProgress) {
                progress.put(progressPoint(flows, began))
                nextProgress += 1_000
                if (!isVpn(vpn) || vpn !in connectivity.allNetworks) networkLost.set(true)
            }
            Thread.sleep(minOf(100, remaining(deadline)))
        }
        downloads.forEachIndexed { index, future ->
            if (!future.isDone) {
                flows[index].error.compareAndSet(null, if (networkLost.get()) "VPNChanged" else "TimeoutException")
                flows[index].finishedAt.compareAndSet(0, now())
                future.cancel(true)
            }
        }
        stopProbes.set(true)
        connections.forEach { runCatching { it.disconnect() } }
        try { probes?.get(5, TimeUnit.SECONDS) } catch (_: Exception) { probes?.cancel(true) }
        val finished = now()
        flows.forEach { it.finishedAt.compareAndSet(0, finished) }
        progress.put(progressPoint(flows, began))
        val sameVpn = !networkLost.get() && isVpn(vpn) && vpn in connectivity.allNetworks
        val success = sameVpn && flows.all { it.success() }
        val transferred = flows.sumOf { it.transferred.get() }
        val completed = flows.sumOf { if (it.success()) it.requested.toLong() else 0L }
        val elapsed = (flows.maxOf { it.finishedAt.get() } - began).coerceAtLeast(1)
        latency.forEach { sample ->
            val start = sample.optLong("startedAtMillis")
            val end = sample.optLong("finishedAtMillis")
            sample.put("overlappedBodyTransfer", flows.any {
                it.firstByteAt.get() > 0 && start < it.lastByteAt.get() && end > it.firstByteAt.get()
            })
        }
        return JSONObject().put("result", if (success) "PASS" else "PARTIAL").put("runLabel", label)
            .put("phase", "download").put("streams", streams).put("timeoutMillis", timeout)
            .put("vpnNetworkHandleStart", handle).put("transportStart", "VPN")
            .put("vpnNetworkHandleFinish", if (sameVpn) vpn.networkHandle else JSONObject.NULL)
            .put("transportFinish", if (sameVpn) "VPN" else "NONE").put("sameVpnNetwork", sameVpn)
            .put("requestedPayloadBytes", total).put("transferredPayloadBytes", transferred)
            .put("completedPayloadBytes", completed).put("partialPayloadBytes", transferred - completed)
            .put("elapsedMillis", elapsed)
            .put("completedMbps", if (success) total * 8.0 / (elapsed * 1_000.0) else JSONObject.NULL)
            .put("partialObservedMbps", if (!success) transferred * 8.0 / (elapsed * 1_000.0) else JSONObject.NULL)
            .put("progress", progress).put("perStream", JSONArray(flows.map { it.json(began) }))
            .put("latencyKind", "HTTPS response headers including connection setup").put("loadedLatency", JSONArray(latency))
    }

    private fun download(flow: Flow, deadline: Long) {
        flow.startedAt.set(now())
        try {
            connection(flow.requested, deadline) { connection ->
                flow.status.set(connection.responseCode.toLong()); flow.headersAt.set(now())
                if (flow.status.get() != 200L) throw IOException("HTTP status")
                val buffer = ByteArray(32 * 1024)
                connection.inputStream.use { input ->
                    while (flow.transferred.get() < flow.requested) {
                        checkpoint(deadline)
                        val length = minOf(buffer.size.toLong(), flow.requested - flow.transferred.get()).toInt()
                        val count = input.read(buffer, 0, length)
                        if (count < 0) break
                        if (count > 0) {
                            flow.firstByteAt.compareAndSet(0, now())
                            flow.transferred.addAndGet(count.toLong()); flow.lastByteAt.set(now())
                        }
                    }
                }
                checkpoint(deadline)
                if (flow.transferred.get() != flow.requested.toLong()) throw IOException("Incomplete body")
                flow.accepted.set(true)
            }
        } catch (failure: Throwable) { flow.error.compareAndSet(null, safeError(failure)) }
        finally { flow.finishedAt.compareAndSet(0, now()) }
    }

    private fun latencyProbe(deadline: Long): JSONObject {
        val began = now()
        val status = AtomicLong()
        val headersAt = AtomicLong()
        val task = workers.submit(Callable {
            connection(0, deadline) { connection -> status.set(connection.responseCode.toLong()); headersAt.set(now()) }
            checkpoint(deadline)
            if (status.get() != 200L) throw IOException("HTTP status")
        })
        var error: String? = null
        try { task.get(remaining(deadline), TimeUnit.MILLISECONDS) }
        catch (failure: Throwable) { error = safeError(failure); task.cancel(true) }
        val end = now()
        return JSONObject().put("startedAtMillis", began).put("finishedAtMillis", end)
            .put("ttfbMillis", if (error == null) headersAt.get() - began else JSONObject.NULL)
            .put("httpStatus", if (status.get() > 0) status.get() else JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
    }

    private fun connection(bytes: Int, deadline: Long, operation: (HttpsURLConnection) -> Unit) {
        checkpoint(deadline)
        val connection = vpn.openConnection(URL("https://speed.cloudflare.com/__down?bytes=$bytes"), Proxy.NO_PROXY) as HttpsURLConnection
        connections += connection
        connection.connectTimeout = minOf(10_000, remaining(deadline)).toInt()
        connection.readTimeout = remaining(deadline).toInt()
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false; connection.useCaches = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-store")
        connection.setRequestProperty("Connection", "close")
        val timeout = watchdog.schedule({ runCatching { connection.disconnect() } }, remaining(deadline), TimeUnit.MILLISECONDS)
        try { operation(connection) }
        finally { timeout.cancel(false); runCatching { connection.disconnect() }; connections -= connection }
    }

    private fun checkpoint(deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (networkLost.get()) throw IOException("VPN changed")
        if (now() >= deadline) throw TimeoutException()
    }
    private fun isVpn(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    private fun progressPoint(flows: List<Flow>, began: Long): JSONObject = JSONObject()
        .put("elapsedMillis", now() - began).put("transferredPayloadBytes", flows.sumOf { it.transferred.get() })
        .put("completedPayloadBytes", flows.sumOf { if (it.success()) it.requested.toLong() else 0L })

    private class Flow(val id: Int, val requested: Int) {
        val startedAt = AtomicLong(); val headersAt = AtomicLong(); val firstByteAt = AtomicLong()
        val lastByteAt = AtomicLong(); val finishedAt = AtomicLong(); val transferred = AtomicLong()
        val status = AtomicLong(); val accepted = AtomicBoolean(false); val error = AtomicReference<String?>()
        fun success(): Boolean = accepted.get() && error.get() == null
        fun json(began: Long): JSONObject = JSONObject().put("stream", id).put("success", success())
            .put("requestedPayloadBytes", requested).put("transferredPayloadBytes", transferred.get())
            .put("completedPayloadBytes", if (success()) requested else 0)
            .put("startedOffsetMillis", startedAt.get() - began).put("elapsedMillis", finishedAt.get() - startedAt.get())
            .put("ttfbMillis", if (headersAt.get() > 0) headersAt.get() - startedAt.get() else JSONObject.NULL)
            .put("bodyDurationMillis", if (firstByteAt.get() > 0) lastByteAt.get() - firstByteAt.get() else JSONObject.NULL)
            .put("httpStatus", if (status.get() > 0) status.get() else JSONObject.NULL).put("error", error.get() ?: JSONObject.NULL)
    }
    private companion object {
        fun now(): Long = SystemClock.elapsedRealtime()
        fun remaining(deadline: Long): Long = (deadline - now()).coerceAtLeast(1)
        fun safeError(failure: Throwable): String =
            (if (failure is java.util.concurrent.ExecutionException) failure.cause ?: failure else failure).javaClass.simpleName
    }
}
