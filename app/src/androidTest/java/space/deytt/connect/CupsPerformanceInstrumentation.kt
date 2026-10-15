package space.deytt.connect

import android.app.Instrumentation
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Self-targeted companion QA: consumes an existing VPN without reading production app data. */
class CupsPerformanceInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private val threadFactory = ThreadFactory { task -> Thread(task, "cups-benchmark").apply { isDaemon = true } }
    private val coordinator = Executors.newSingleThreadExecutor(threadFactory)
    private val workers = Executors.newCachedThreadPool(threadFactory)
    private val watchdog = Executors.newScheduledThreadPool(2, threadFactory)
    private val connections = ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private lateinit var connectivity: ConnectivityManager
    private lateinit var vpn: Network
    private var overallDeadline = 0L

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments?.let(::Bundle) ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val started = now()
        overallDeadline = started + OVERALL_TIMEOUT_MILLIS
        var result: JSONObject
        var experiment: java.util.concurrent.Future<JSONObject>? = null
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated companion required" }
            experiment = coordinator.submit(Callable { runBenchmark() })
            result = experiment.get(remaining(overallDeadline), TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            result = JSONObject().put("result", "FAIL").put("error", safeError(failure))
        } finally {
            experiment?.cancel(true)
            connections.forEach { runCatching { it.disconnect() } }
            coordinator.shutdownNow()
            workers.shutdownNow()
            watchdog.shutdownNow()
        }
        result.put("overallElapsedMillis", now() - started)
        finish(if (result.optString("result") == "PASS") -1 else 0,
            Bundle().apply { putString("result_json", result.toString()) })
    }

    private fun runBenchmark(): JSONObject {
        val phase = arguments.getString("phase", "all")!!
        require(phase in setOf("all", "idle", "download", "upload"))
        val label = arguments.getString("runLabel", "cups")!!
        require(label.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        val downloadBytes = arguments.getString("downloadBytes")?.toInt() ?: 1024 * 1024
        require(downloadBytes in 1..MAX_DOWNLOAD_BYTES)
        val uploadBytes = arguments.getString("uploadBytes")?.toInt() ?: 128 * 1024
        require(uploadBytes == 128 * 1024 || uploadBytes == 256 * 1024)
        connectivity = targetContext.getSystemService(ConnectivityManager::class.java)
        val candidates = connectivity.allNetworks.filter(::isVpn)
        vpn = connectivity.activeNetwork?.takeIf(::isVpn) ?: candidates.singleOrNull()
            ?: throw IOException("VPN unavailable or ambiguous")
        checkpoint(overallDeadline)
        val networkHandle = vpn.networkHandle
        val result = JSONObject().put("runLabel", label).put("phase", phase)
            .put("vpnNetworkHandleStart", networkHandle).put("transportStart", "VPN")
            .put("latencyKind", "HTTPS response headers including connection setup")
        val idle = JSONArray()
        val downloads = JSONArray()
        val uploads = JSONArray()
        val attempts = mutableListOf<Attempt>()
        if (phase == "all" || phase == "idle") repeat(3) {
            latency("idle", minOf(overallDeadline, now() + 8_000)).also {
                attempts += it
                idle.put(it.json())
            }
        }
        if (phase == "all" || phase == "download") repeat(2) { run ->
            val deadline = minOf(overallDeadline, now() + ATTEMPT_TIMEOUT_MILLIS)
            val startedTransfer = CountDownLatch(1)
            val stopProbes = AtomicBoolean(false)
            val loaded = CopyOnWriteArrayList<Attempt>()
            val probes = workers.submit {
                if (startedTransfer.await(remaining(deadline), TimeUnit.MILLISECONDS)) {
                    while (!stopProbes.get() && now() < deadline && loaded.size < 20) {
                        loaded += latency("loaded", minOf(deadline, now() + 5_000))
                        if (!stopProbes.get() && now() < deadline)
                            Thread.sleep(minOf(400, remaining(deadline)))
                    }
                }
            }
            val download = measure("download", downloadBytes, deadline) { attempt, active ->
                withConnection("$DOWN?bytes=$downloadBytes", "GET", deadline, active) { connection ->
                    acceptHeaders(attempt, connection)
                    attempt.transferStartedAt.set(now())
                    startedTransfer.countDown()
                    val buffer = ByteArray(32 * 1024)
                    connection.inputStream.use { input ->
                        while (attempt.transferred.get() < downloadBytes) {
                            checkpoint(deadline)
                            val count = input.read(buffer, 0,
                                minOf(buffer.size.toLong(), downloadBytes - attempt.transferred.get()).toInt())
                            if (count < 0) break
                            if (count > 0) attempt.transferred.addAndGet(count.toLong())
                        }
                    }
                    checkpoint(deadline)
                    if (attempt.transferred.get() != downloadBytes.toLong()) throw IOException("Incomplete body")
                    attempt.accepted.set(true)
                }
            }
            stopProbes.set(true)
            startedTransfer.countDown()
            try { probes.get(minOf(5_000, remaining(overallDeadline)), TimeUnit.MILLISECONDS) }
            catch (_: Exception) { probes.cancel(true) }
            attempts += download
            val loadedJson = JSONArray()
            loaded.forEach { sample ->
                val overlap = sample.startedAt < download.finishedAt.get() &&
                    sample.finishedAt.get() > download.transferStartedAt.get() && download.transferStartedAt.get() > 0
                loadedJson.put(sample.json().put("overlappedTransfer", overlap))
            }
            downloads.put(download.json().put("run", run + 1).put("loadedLatency", loadedJson))
        }
        if (phase == "all" || phase == "upload") {
            val payload = ByteArray(uploadBytes).also { SecureRandom().nextBytes(it) }
            val deadline = minOf(overallDeadline, now() + ATTEMPT_TIMEOUT_MILLIS)
            val upload = measure("upload", uploadBytes, deadline) { attempt, active ->
                withConnection(UP, "POST", deadline, active) { connection ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(uploadBytes)
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                    attempt.transferStartedAt.set(now())
                    connection.outputStream.use { output ->
                        while (attempt.transferred.get() < uploadBytes) {
                            checkpoint(deadline)
                            val offset = attempt.transferred.get().toInt()
                            val count = minOf(16 * 1024, uploadBytes - offset)
                            output.write(payload, offset, count)
                            attempt.transferred.addAndGet(count.toLong())
                        }
                    }
                    acceptHeaders(attempt, connection)
                    checkpoint(deadline)
                    attempt.accepted.set(true)
                }
            }
            attempts += upload
            uploads.put(upload.json())
        }
        val sameVpn = isVpn(vpn) && vpn in connectivity.allNetworks
        result.put("vpnNetworkHandleFinish", if (sameVpn) vpn.networkHandle else JSONObject.NULL)
            .put("transportFinish", if (sameVpn) "VPN" else "NONE")
            .put("sameVpnNetwork", sameVpn && vpn.networkHandle == networkHandle)
            .put("idleLatency", idle).put("downloads", downloads).put("uploads", uploads)
            .put("result", if (sameVpn && attempts.all { it.success() }) "PASS" else "PARTIAL")
        return result
    }

    private fun latency(kind: String, deadline: Long): Attempt = measure(kind, 0, deadline) { attempt, active ->
        withConnection("$DOWN?bytes=0", "GET", deadline, active) { connection ->
            acceptHeaders(attempt, connection)
            checkpoint(deadline)
            attempt.accepted.set(true)
        }
    }

    private fun measure(kind: String, requestedBytes: Int, deadline: Long,
        operation: (Attempt, AtomicReference<HttpsURLConnection?>) -> Unit): Attempt {
        val attempt = Attempt(kind, requestedBytes)
        val active = AtomicReference<HttpsURLConnection?>()
        val task = workers.submit {
            try { checkpoint(deadline); operation(attempt, active) }
            catch (failure: Throwable) { attempt.error.compareAndSet(null, safeError(failure)) }
            finally { attempt.finishedAt.compareAndSet(0, now()) }
        }
        try { task.get(remaining(deadline), TimeUnit.MILLISECONDS) }
        catch (failure: Throwable) {
            attempt.error.compareAndSet(null, safeError(failure))
            runCatching { active.get()?.disconnect() }
            task.cancel(true)
        }
        attempt.finishedAt.compareAndSet(0, now())
        return attempt
    }

    private fun withConnection(address: String, method: String, deadline: Long,
        active: AtomicReference<HttpsURLConnection?>, operation: (HttpsURLConnection) -> Unit) {
        checkpoint(deadline)
        // Never use process-default HTTP connections: they may silently use Wi-Fi.
        val connection = vpn.openConnection(URL(address), Proxy.NO_PROXY) as HttpsURLConnection
        active.set(connection)
        connections += connection
        val timeout = minOf(10_000, remaining(deadline)).toInt()
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-store")
        connection.setRequestProperty("Connection", "close")
        val timeoutTask = watchdog.schedule({ runCatching { connection.disconnect() } },
            remaining(deadline), TimeUnit.MILLISECONDS)
        try { operation(connection) }
        finally {
            timeoutTask.cancel(false)
            runCatching { connection.disconnect() }
            connections -= connection
            active.compareAndSet(connection, null)
        }
    }

    private fun acceptHeaders(attempt: Attempt, connection: HttpsURLConnection) {
        attempt.httpStatus.set(connection.responseCode.toLong())
        attempt.headersAt.set(now())
        if (attempt.httpStatus.get() != 200L) throw IOException("Unexpected HTTP status")
    }

    private fun isVpn(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun checkpoint(deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (now() >= minOf(deadline, overallDeadline)) throw TimeoutException()
        if (!isVpn(vpn) || vpn !in connectivity.allNetworks) throw IOException("VPN changed")
    }

    private class Attempt(val kind: String, val requestedBytes: Int) {
        val startedAt = now()
        val finishedAt = AtomicLong()
        val headersAt = AtomicLong()
        val transferStartedAt = AtomicLong()
        val transferred = AtomicLong()
        val httpStatus = AtomicLong()
        val accepted = AtomicBoolean(false)
        val error = AtomicReference<String?>()

        fun success(): Boolean = accepted.get() && error.get() == null

        fun json(): JSONObject {
            val elapsed = (finishedAt.get() - startedAt).coerceAtLeast(1)
            val completed = if (success()) requestedBytes.toLong() else 0L
            return JSONObject().put("kind", kind).put("success", success())
                .put("requestedPayloadBytes", requestedBytes).put("transferredPayloadBytes", transferred.get())
                .put("completedPayloadBytes", completed)
                .put("partialPayloadBytes", if (success()) 0L else transferred.get())
                .put("elapsedMillis", elapsed)
                .put("ttfbMillis", if (headersAt.get() > 0) headersAt.get() - startedAt else JSONObject.NULL)
                .put("payloadMbps", if (success() && completed > 0) completed * 8.0 / (elapsed * 1_000.0) else JSONObject.NULL)
                .put("httpStatus", if (httpStatus.get() > 0) httpStatus.get() else JSONObject.NULL)
                .put("error", error.get() ?: JSONObject.NULL)
        }
    }

    private companion object {
        const val OVERALL_TIMEOUT_MILLIS = 120_000L
        const val ATTEMPT_TIMEOUT_MILLIS = 20_000L
        const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
        const val DOWN = "https://speed.cloudflare.com/__down"
        const val UP = "https://speed.cloudflare.com/__up"
        fun now(): Long = SystemClock.elapsedRealtime()
        fun remaining(deadline: Long): Long = (deadline - now()).coerceAtLeast(1)
        fun safeError(failure: Throwable): String =
            (if (failure is java.util.concurrent.ExecutionException) failure.cause ?: failure else failure).javaClass.simpleName
    }
}
