package space.deytt.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborEntryIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.URL
import java.net.Inet4Address
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import org.amnezia.awg.config.Config
import space.deytt.awg.AwgProxyBackend
import space.deytt.openflux.OpenFluxProxyBackend
import java.io.BufferedReader
import java.io.StringReader
import java.util.UUID

private class OpenFluxRouteException(message: String) : IllegalStateException(message)
private class TunnelVerificationException(message: String, cause: Throwable?) : IllegalStateException(message, cause)

open class ConnectVpnService : VpnService(), CommandServerHandler, PlatformInterface {
    companion object {
        const val ACTION_START = "space.deytt.connect.action.START"
        const val ACTION_STOP = "space.deytt.connect.action.STOP"
        const val ACTION_ROUTE_PROBE = "space.deytt.connect.action.ROUTE_PROBE"
        const val ACTION_CANCEL_ROUTE_PROBE = "space.deytt.connect.action.CANCEL_ROUTE_PROBE"
        const val ACTION_RESTORE_NOTIFICATION = "space.deytt.connect.action.RESTORE_NOTIFICATION"
        const val ACTION_STATUS = "space.deytt.connect.action.STATUS"
        const val ACTION_ACTIVE_ROUTE = "space.deytt.connect.action.ACTIVE_ROUTE"
        const val EXTRA_ACTIVE_ROUTE_KEY = "active_route_key"
        const val EXTRA_ACTIVE_EXIT_COUNTRY = "active_exit_country"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "deytt-connect"
        private const val MAX_CARRIER_DNS_SERVERS = 4
        private val FALLBACK_CARRIER_DNS = arrayOf("1.1.1.1", "8.8.8.8")
        private const val CANARY_TIMEOUT_MILLIS = 6_000
        private const val CANARY_BUDGET_MILLIS = 36_000L
        const val STATE_PREFS = "vpn_state"
        const val STATE_STATUS = "status"
        const val STATE_ERROR = "error"
        const val STATE_PHASE = "phase"

        @Volatile
        private var libboxSetup = false

        @Volatile
        private var runtimeRunning = false

        @Volatile
        private var routeProbeRunning = false

        fun isRunning(): Boolean = runtimeRunning
        fun isRouteProbeRunning(): Boolean = routeProbeRunning
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val openFluxExecutor = Executors.newSingleThreadExecutor()
    private val openFluxLeaseExecutor = Executors.newSingleThreadExecutor()
    private val canaryTimeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    private var openFluxMonitor: Future<*>? = null
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var awgProxy: AwgProxyBackend? = null
    private var openFluxProxy: OpenFluxProxyBackend? = null
    private var openFluxExpiry: Long? = null
    @Volatile private var activeCanary: HttpsURLConnection? = null
    @Volatile private var previousVpnNetworks: Set<android.net.Network> = emptySet()
    private var activeRuntimeConfig: String? = null
    private var started = false
    private var routeProbeOnly = false
    private var notificationText = "Запуск deytt./connect"
    private var lastActiveAutoRoute: ActiveRouteSelection? = null
    private val operation = AtomicLong(0)

    private fun formatConnectedNotificationText(autoRoute: ActiveRouteSelection? = lastActiveAutoRoute): String {
        val selected = SelectedRouteStore(this).read()
        return if (selected.id == "auto" || selected.protocol == RouteProtocol.AUTO) {
            val route = autoRoute ?: lastActiveAutoRoute
            if (route != null) {
                val flag = RouteCatalog.flag(route.exitCountry)
                val country = route.routeKey.lowercase()
                "$flag ./auto $country"
            } else {
                "✦ ./auto"
            }
        } else {
            val flag = selected.flag.ifBlank { RouteCatalog.flag(selected.countryCode) }
            val country = selected.countryCode.lowercase()
            val protocol = selected.protocol.bypassNotificationLabel
            if (country.isNotBlank()) {
                "$flag ./$country $protocol"
            } else {
                "$flag ./$protocol"
            }
        }
    }
    @Volatile private var probeWorkers: ExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val networkBridge by lazy {
        AndroidNetworkBridge(this) { network ->
            applyUnderlyingNetworks(network)
        }
    }

    private fun applyUnderlyingNetworks(network: Network?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to apply underlying network", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deytt:vpn_connection")?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) it.acquire()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to release wake lock", e)
        } finally {
            wakeLock = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE_NOTIFICATION) {
            if (started && (runtimeRunning || routeProbeOnly)) startForegroundCompat(notificationText)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ROUTE_PROBE) {
            if (!started && !runtimeRunning) {
                try {
                    startForegroundCompat("Подготавливаем проверку маршрутов")
                } catch (error: Throwable) {
                    Log.e(TAG, "Unable to enter foreground for route probe", error)
                    sendRouteProbeResult(
                        intent.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty(),
                        "",
                        null,
                        "Не удалось запустить системный сервис проверки маршрутов",
                        complete = true,
                    )
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            beginRouteProbe(intent, startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_ROUTE_PROBE) {
            if (routeProbeOnly) cancelRouteProbe(startId)
            else if (!started) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            if (routeProbeOnly) {
                cancelRouteProbe(startId)
                return START_NOT_STICKY
            }
            operation.incrementAndGet()
            publishStatus(VpnPhase.STOPPING, "Отключаем соединение…")
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            runtimeRunning = true
            acquireWakeLock()
            val operationId = operation.incrementAndGet()
            try {
                startForegroundCompat("Запуск deytt./connect")
                publishStatus(VpnPhase.STARTING, "Запускаем соединение…")
                executor.execute { startTunnel(operationId) }
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to enter foreground", error)
                publishFailure(error)
                started = false
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        operation.incrementAndGet()
        if (this is RouteProbeVpnService || routeProbeOnly) closeRouteProbeCore() else stopTunnel()
        probeWorkers?.shutdownNow()
        executor.shutdownNow()
        openFluxExecutor.shutdownNow()
        openFluxLeaseExecutor.shutdownNow()
        canaryTimeoutExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun beginRouteProbe(intent: Intent, startId: Int) {
        val requestId = intent.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty()
        val config = intent.getStringExtra("config")
        val token = intent.getStringExtra(RouteProbeClient.EXTRA_TOKEN).orEmpty()
        val routeTags = intent.getStringArrayListExtra("route_tags").orEmpty()
        val method = runCatching {
            RouteProbeMethod.valueOf(intent.getStringExtra("method") ?: RouteProbeMethod.HEAD.name)
        }.getOrDefault(RouteProbeMethod.HEAD)
        if (started || runtimeRunning) {
            sendRouteProbeResult(requestId, "", null, "Сначала отключите активный VPN", complete = true)
            return
        }
        if (isUpstreamVpnActive()) {
            sendRouteProbeResult(requestId, "", null, "Отключите VPN другого приложения для точного замера", complete = true)
            stopSelf(startId)
            return
        }
        if (requestId.isBlank() || config.isNullOrBlank() || routeTags.isEmpty()) {
            sendRouteProbeResult(requestId, "", null, "Не удалось подготовить проверку", complete = true)
            stopSelf(startId)
            return
        }

        started = true
        routeProbeOnly = true
        routeProbeRunning = true
        val operationId = operation.incrementAndGet()
        try {
            startForegroundCompat("Проверяем маршрут через прокси…")
            executor.execute { runRouteProbe(requestId, config, routeTags, method, token, operationId, startId) }
        } catch (error: Throwable) {
            sendRouteProbeResult(requestId, "", null, "Не удалось запустить проверку", complete = true)
            closeRouteProbeCore()
            started = false
            routeProbeOnly = false
            routeProbeRunning = false
            stopForegroundCompat()
            stopSelf(startId)
        }
    }

    private fun runRouteProbe(
        requestId: String,
        config: String,
        routeTags: List<String>,
        method: RouteProbeMethod,
        token: String,
        operationId: Long,
        startId: Int,
    ) {
        var stage = "profile validation"
        try {
            ProfileValidator.validate(config)
            stage = "loopback proxy setup"
            val sessions = linkedMapOf<String, RouteProbeSession>()
            routeTags.distinct().forEach { tag ->
                var session = RouteProxyProbe.newSession()
                while (sessions.values.any { it.port == session.port }) session = RouteProxyProbe.newSession()
                sessions[tag] = session
            }
            if (operation.get() != operationId || !routeProbeOnly) return
            stage = "libbox setup"
            setupLibbox()
            stage = "command server start"
            val server = CommandServer(this, this)
            commandServer = server
            server.start()
            if (operation.get() != operationId || !routeProbeOnly) return
            stage = "batch routes setup"
            server.startOrReloadService(
                runtimeConfig(RouteProxyProbe.batchConfiguration(config, sessions)),
                OverrideOptions().apply { autoRedirect = false },
            )
            val latencies = linkedMapOf<String, Long>()
            val workers = Executors.newFixedThreadPool(minOf(3, sessions.size))
            probeWorkers = workers
            val completed = ExecutorCompletionService<Pair<String, Long?>>(workers)
            sessions.forEach { (tag, session) ->
                completed.submit(java.util.concurrent.Callable {
                    if (operation.get() != operationId || !routeProbeOnly) return@Callable tag to null
                    try {
                        sendRouteProbeResult(requestId, tag, null, null, stage = "latency")
                        val latency = RouteProxyProbe.measureProxy(session, method) { sampleIndex, sample ->
                            if (operation.get() == operationId && routeProbeOnly) {
                                if (sample != null) {
                                    sendRouteProbeResult(
                                        requestId,
                                        tag,
                                        sample,
                                        null,
                                        stage = "latency_sample",
                                        sampleIndex = sampleIndex,
                                    )
                                } else {
                                    sendRouteProbeResult(
                                        requestId,
                                        tag,
                                        null,
                                        null,
                                        stage = "latency_retry",
                                        sampleIndex = sampleIndex,
                                    )
                                }
                            }
                        }
                        if (operation.get() == operationId && routeProbeOnly) {
                            sendRouteProbeResult(requestId, tag, latency, null,
                                stage = if (token.isBlank()) "complete" else "waiting_download")
                        }
                        tag to latency
                    } catch (error: Throwable) {
                        if (operation.get() == operationId && routeProbeOnly) {
                            Log.w(TAG, "Route ${safeRouteLogLabel(tag)} latency probe failed (${probeFailureKind(error)})")
                            sendRouteProbeResult(requestId, tag, null, routeProbeError(error), stage = "failed")
                        }
                        tag to null
                    }
                })
            }
            repeat(sessions.size) {
                if (operation.get() != operationId || !routeProbeOnly) return@repeat
                val result = completed.poll((RouteProxyProbe.TIMEOUT_MILLIS + 1_000).toLong(), TimeUnit.MILLISECONDS)
                    ?: throw java.net.SocketTimeoutException("Diagnostic batch timed out")
                val (tag, latency) = result.get()
                if (latency != null) latencies[tag] = latency
            }
            workers.shutdownNow()
            probeWorkers = null
            // Latencies run in parallel, but downloads stay serial: simultaneous
            // bandwidth samples would measure contention rather than each route.
            if (token.isNotBlank()) latencies.forEach { (tag, latency) ->
                if (operation.get() != operationId || !routeProbeOnly) return@forEach
                try {
                    sendRouteProbeResult(requestId, tag, latency, null, stage = "download")
                    val speed = RouteProxyProbe.measureDownload(sessions.getValue(tag), token) { bytesPerSecond ->
                        if (operation.get() == operationId && routeProbeOnly) {
                            sendRouteProbeResult(
                                requestId,
                                tag,
                                latency,
                                null,
                                stage = "download_progress",
                                bytesPerSecond = bytesPerSecond,
                            )
                        }
                    }
                    if (operation.get() == operationId && routeProbeOnly) {
                        sendRouteProbeResult(requestId, tag, latency, null, stage = "complete", bytesPerSecond = speed)
                    }
                } catch (error: Throwable) {
                    if (operation.get() == operationId && routeProbeOnly) {
                        Log.w(TAG, "Route ${safeRouteLogLabel(tag)} download probe failed (${probeFailureKind(error)})")
                        sendRouteProbeResult(requestId, tag, latency, routeProbeError(error, speedProbe = true), stage = "failed")
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Route proxy probe failed at $stage (${probeFailureKind(error)}): ${error.message}", error)
            val detail = error.message?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
            val message = if (stage == "batch routes setup" || stage == "command server start" || stage == "libbox setup")
                "Сбой запуска ядра$detail" else routeProbeError(error)
            routeTags.forEach { tag ->
                sendRouteProbeResult(requestId, tag, null, message)
            }
        } finally {
            probeWorkers?.shutdownNow()
            probeWorkers = null
            closeRouteProbeCore()
            started = false
            routeProbeOnly = false
            routeProbeRunning = false
            stopForegroundCompat()
            stopSelf(startId)
            sendRouteProbeResult(requestId, "", null, null, complete = true)
        }
    }

    private fun cancelRouteProbe(startId: Int) {
        operation.incrementAndGet()
        routeProbeRunning = false
        probeWorkers?.shutdownNow()
        closeRouteProbeCore()
        started = false
        routeProbeOnly = false
        stopForegroundCompat()
        stopSelf(startId)
    }

    @Synchronized
    private fun closeRouteProbeCore() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunnel?.close() }
        tunnel = null
    }

    private fun sendRouteProbeResult(
        requestId: String,
        routeTag: String,
        milliseconds: Long?,
        error: String?,
        complete: Boolean = false,
        stage: String? = null,
        bytesPerSecond: Long? = null,
        sampleIndex: Int? = null,
    ) {
        if (complete) RouteProbeClient.clear(this, requestId)
        val intent = Intent(RouteProbeClient.ACTION_RESULT)
            .setPackage(packageName)
            .putExtra(RouteProbeClient.EXTRA_REQUEST_ID, requestId)
            .putExtra(RouteProbeClient.EXTRA_ROUTE_TAG, routeTag)
            .putExtra(RouteProbeClient.EXTRA_COMPLETE, complete)
        if (milliseconds != null) intent.putExtra(RouteProbeClient.EXTRA_MILLISECONDS, milliseconds)
        if (!error.isNullOrBlank()) intent.putExtra(RouteProbeClient.EXTRA_ERROR, error)
        if (!stage.isNullOrBlank()) intent.putExtra(RouteProbeClient.EXTRA_STAGE, stage)
        if (bytesPerSecond != null) intent.putExtra(RouteProbeClient.EXTRA_BYTES_PER_SECOND, bytesPerSecond)
        if (sampleIndex != null) intent.putExtra(RouteProbeClient.EXTRA_SAMPLE_INDEX, sampleIndex)
        sendBroadcast(intent)
    }

    private fun routeProbeError(error: Throwable, speedProbe: Boolean = false): String = when {
        speedProbe && errorChainMessage(error).contains("http 401", ignoreCase = true) ->
            "Сервер скорости отклонил вход · проверьте Telegram"
        speedProbe && errorChainMessage(error).contains("http 403", ignoreCase = true) ->
            "Сервер скорости запретил замер"
        speedProbe && Regex("http 5\\d{2}", RegexOption.IGNORE_CASE).containsMatchIn(errorChainMessage(error)) ->
            "Сервер скорости временно недоступен"
        error.message.orEmpty().contains("timeout", ignoreCase = true) ||
            error.message.orEmpty().contains("времен", ignoreCase = true) -> "тайм-аут · 8 с"
        error.message.orEmpty().startsWith("Локальный прокси ответил HTTP") ->
            error.message.orEmpty().substringAfter("ответил ")
        speedProbe && error.message.orEmpty().startsWith("Сервер скорости ответил HTTP") ||
            speedProbe &&
            error.message.orEmpty().startsWith("Download endpoint returned HTTP") ->
            "Сервер скорости: " + Regex("HTTP\\s+\\d{3}", RegexOption.IGNORE_CASE)
                .find(error.message.orEmpty())?.value.orEmpty()
        errorChainContains(error, "TLS hostname verification failed") -> "ошибка проверки TLS-сертификата"
        error.message.orEmpty().startsWith("Проверочный сервер ответил HTTP") ->
            error.message.orEmpty().substringAfter("ответил ")
        else -> "маршрут не ответил"
    }

    private fun errorChainMessage(error: Throwable): String =
        generateSequence(error) { it.cause }.take(8).mapNotNull { it.message }.joinToString(" ")

    private fun safeRouteLogLabel(tag: String): String = tag.substringAfterLast(':')
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(24)

    private fun causeClassChain(error: Throwable): String {
        val classes = mutableListOf<String>()
        var current: Throwable? = error
        repeat(8) {
            val failure = current ?: return@repeat
            classes += failure.javaClass.simpleName
            current = failure.cause
        }
        return classes.distinct().joinToString("→")
    }

    private fun probeFailureKind(error: Throwable): String {
        val messages = buildString {
            var current: Throwable? = error
            repeat(8) {
                val failure = current
                if (failure != null) {
                    failure.message?.let { append(it.lowercase()).append(' ') }
                    current = failure.cause
                }
            }
        }
        return when {
            "connection refused" in messages || "econnrefused" in messages -> "connection refused"
            "proxy authentication" in messages || "http 407" in messages -> "proxy authentication rejected"
            "unexpected end of stream" in messages -> "proxy closed the stream"
            "unable to resolve host" in messages || "unknownhostexception" in messages -> "DNS lookup failed"
            "timeout" in messages || "timed out" in messages -> "timed out"
            "connection reset" in messages || "broken pipe" in messages -> "connection reset"
            "ssl" in messages || "handshake" in messages -> "TLS handshake failed"
            Regex("http\\s+\\d{3}").find(messages)?.value != null ->
                Regex("http\\s+\\d{3}").find(messages)!!.value.uppercase()
            else -> causeClassChain(error)
        }
    }

    private fun isUpstreamVpnActive(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val activeVpn = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ownerUid = connectivity.getNetworkCapabilities(activeVpn)?.ownerUid ?: return true
            if (ownerUid == applicationInfo.uid) return false
        }
        return true
    }

    private fun startTunnel(operationId: Long) {
        var pendingOpenFlux: OpenFluxProxyBackend? = null
        try {
            val config = SubscriptionStore(this).readCurrent()
            if (config == null) {
                fail("Нет сохранённой подписки")
                return
            }
            val selected = SelectedRouteStore(this).read()
            Log.i(TAG, "VPN start engine=${selected.engine}")
            val parsedAwg = if (selected.engine == TunnelEngine.AMNEZIAWG) {
                val raw = AwgProfileStore(this).read(selected.id)
                    ?: error("Обновите подписку: профиль AmneziaWG отсутствует")
                Config.parse(BufferedReader(StringReader(raw)))
            } else null
            // Endpoint hostname resolution must not hold the lifecycle lock:
            // Android calls stop/revoke on its main thread.
            val awgUserspace = parsedAwg?.toAwgUserspaceString()
            val openFluxOwner = if (selected.engine == TunnelEngine.OPENFLUX) OpenFluxProfileStore.owner(this)
                ?: throw OpenFluxRouteException("Подключите аккаунт и обновите подписку") else null
            val openFluxProfile = if (selected.engine == TunnelEngine.OPENFLUX) {
                val cached = OpenFluxProfileStore(this).profiles(openFluxOwner).firstOrNull { it.id == selected.id }
                val profile = if (cached == null || OpenFluxLeaseSchedule.needsRefreshBeforeStart(
                        cached.expiresAt, System.currentTimeMillis() / 1000)) {
                    SubscriptionClient.refreshOpenFlux(this, requireAvailable = true).firstOrNull { it.id == selected.id }
                } else cached
                profile
                    ?: throw OpenFluxRouteException("Обновите подписку: профиль обхода отсутствует или истёк")
                if (OpenFluxLeaseSchedule.needsRefreshBeforeStart(profile.expiresAt, System.currentTimeMillis() / 1000)) {
                    throw OpenFluxRouteException("Обновите подписку: профиль обхода скоро истечёт")
                }
                profile
            } else null
            val openFluxUsername = UUID.randomUUID().toString()
            val openFluxPassword = UUID.randomUUID().toString()
            if (openFluxProfile != null) {
                ensureCurrent(operationId)
                if (OpenFluxProfileStore.owner(this) != openFluxOwner) throw OpenFluxRouteException("Аккаунт изменён")
                pendingOpenFlux = OpenFluxProxyBackend.start(openFluxProfile.nativeJson(),
                    openFluxUsername, openFluxPassword, object : OpenFluxProxyBackend.SocketProtector {
                        override fun protect(fd: Int): Boolean = this@ConnectVpnService.protect(fd)
                        override fun dnsServers(): Array<String> = physicalDnsServers()
                    })
            }
            synchronized(this) {
                ensureCurrent(operationId)
                previousVpnNetworks = ownedVpnNetworks().toSet()
                val routed = if (pendingOpenFlux != null) {
                    check(openFluxOwner != null && OpenFluxProfileStore.owner(this) == openFluxOwner) { "Аккаунт изменён" }
                    val proxy = pendingOpenFlux!!
                    openFluxProxy = proxy
                    pendingOpenFlux = null
                    openFluxExpiry = openFluxProfile!!.expiresAt
                    check(proxy.isConnected()) { "Маршрут обхода ещё не подтвердил защищённое соединение" }
                    SplitTunnelProfile.forOpenFlux(config, proxy.port(), openFluxUsername, openFluxPassword)
                } else if (parsedAwg != null) {
                    val local = parsedAwg.`interface`
                    val username = UUID.randomUUID().toString()
                    val password = UUID.randomUUID().toString()
                    val proxy = AwgProxyBackend.start(
                        awgUserspace!!,
                        local.addresses.map { it.address.hostAddress!! }.toTypedArray(),
                        local.dnsServers.map { it.hostAddress!! }.toTypedArray(),
                        local.mtu.orElse(1280), username, password, this::protect,
                    )
                    awgProxy = proxy
                    SplitTunnelProfile.forAwg(config, proxy.port, username, password)
                } else {
                    val selectedConfig = if (selected.configTag.isNotBlank()) {
                        ProfileRoutes.select(config, selected.configTag)
                    } else config
                    SplitTunnelProfile.apply(selectedConfig)
                }
                val runtimeConfig = runtimeConfig(routed)
                ProfileValidator.validate(runtimeConfig)
                setupLibbox()
                val server = CommandServer(this, this)
                commandServer = server
                server.start()
                server.startOrReloadService(runtimeConfig, OverrideOptions().apply { autoRedirect = false })
                activeRuntimeConfig = runtimeConfig
                observeAutoRoute()
            }
            ensureCurrent(operationId)
            publishStatus(VpnPhase.CHECKING, "Проверяем туннель…", "Проверяю доступ к интернету через выбранный маршрут")
            updateNotification("Проверяем туннель…")
            verifyTunnel(operationId, openFluxOwner)
            synchronized(this) {
                ensureCurrent(operationId)
                openFluxProxy?.let {
                    check(it.isConnected() && it.bytesSent() > 0 && it.bytesReceived() > 0) { "Маршрут обхода не передаёт трафик" }
                    Log.i(TAG, "OpenFlux carrier verified sent=${it.bytesSent()} received=${it.bytesReceived()}")
                }
                updateNotification(formatConnectedNotificationText())
                publishStatus(VpnPhase.CONNECTED, "Подключено")
                if (openFluxProfile != null) {
                    openFluxMonitor = openFluxExecutor.submit {
                        try {
                            monitorOpenFlux(operationId, openFluxProfile, openFluxOwner!!)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (error: Throwable) {
                            synchronized(this) {
                                if (operation.get() == operationId && runtimeRunning)
                                    fail(errorMessage(error, "Маршрут обхода отключился"))
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "VPN service started")
        } catch (error: Throwable) {
            runCatching { pendingOpenFlux?.close() }
            Log.e(TAG, "Unable to start VPN", error)
            if (operation.get() == operationId) {
                fail(errorMessage(error, "Не удалось запустить соединение"))
            }
        }
    }

    private fun monitorOpenFlux(operationId: Long, profile: OpenFluxProfile, owner: String) {
        // A separate interruptible worker keeps lease I/O off the start/stop queue.
        var renewAt = android.os.SystemClock.elapsedRealtime() + OpenFluxLeaseSchedule.renewalDelayMillis(
            profile.expiresAt, System.currentTimeMillis() / 1000)
        var reportAt = android.os.SystemClock.elapsedRealtime() + 30_000L
        val recovery = OpenFluxReconnectGrace()
        var recovering = false
        var renewal: Future<List<OpenFluxProfile>?>? = null
        try {
        while (operation.get() == operationId && runtimeRunning) {
            Thread.sleep(2_000)
            synchronized(this) {
                if (operation.get() != operationId || !runtimeRunning) return
                if (OpenFluxProfileStore.owner(this) != owner) {
                    fail("Аккаунт изменён. Подключитесь заново")
                    return
                }
                if ((openFluxExpiry ?: 0) <= System.currentTimeMillis() / 1000) {
                    fail("Профиль обхода истёк. Обновите подписку")
                    return
                }
            }
            if (android.os.SystemClock.elapsedRealtime() >= renewAt && renewal == null) {
                // Lease HTTP must not suspend expiry, owner or recovery checks.
                renewal = openFluxLeaseExecutor.submit<List<OpenFluxProfile>?> {
                    runCatching { SubscriptionClient.refreshOpenFlux(this) }.getOrNull()
                }
            }
            if (renewal?.isDone == true) {
                val renewed = runCatching { renewal?.get() }.getOrNull()
                renewal = null
                var extended = false
                synchronized(this) {
                    if (operation.get() != operationId || !runtimeRunning) return
                    val sameChannel = renewed?.firstOrNull { it.id == profile.id && profile.sameChannel(it) }
                    if (renewed != null && sameChannel == null) {
                        fail("Профиль обхода отозван. Обновите подписку")
                        return
                    }
                    if (sameChannel != null) {
                        extended = sameChannel.expiresAt > (openFluxExpiry ?: 0)
                        openFluxExpiry = sameChannel.expiresAt
                    }
                }
                // Retry a transient outage before the current lease expires.
                renewAt = android.os.SystemClock.elapsedRealtime() + OpenFluxLeaseSchedule.renewalDelayMillis(
                    openFluxExpiry ?: 0, System.currentTimeMillis() / 1000, retry = !extended)
            }
            val recoveryState = synchronized(this) {
                if (operation.get() != operationId || !runtimeRunning) return
                if (OpenFluxProfileStore.owner(this) != owner) {
                    fail("Аккаунт изменён. Подключитесь заново")
                    return
                }
                if ((openFluxExpiry ?: 0) <= System.currentTimeMillis() / 1000) {
                    fail("Профиль обхода истёк. Обновите подписку")
                    return
                }
                val state = recovery.observe(openFluxProxy?.isConnected() == true,
                    android.os.SystemClock.elapsedRealtime())
                if (state == OpenFluxReconnectGrace.State.EXPIRED) {
                    fail("Маршрут обхода не восстановился. Повторите подключение")
                    return
                }
                if (state == OpenFluxReconnectGrace.State.WAIT && !recovering) {
                    recovering = true
                    publishStatus(VpnPhase.CHECKING, "Восстанавливаем обход…")
                    updateNotification("Восстанавливаем обход…")
                }
                if (android.os.SystemClock.elapsedRealtime() >= reportAt) {
                    openFluxProxy?.let { Log.i(TAG, "OpenFlux carrier sent=${it.bytesSent()} received=${it.bytesReceived()}") }
                    reportAt = android.os.SystemClock.elapsedRealtime() + 30_000L
                }
                state
            }
            if (recoveryState == OpenFluxReconnectGrace.State.VERIFY) {
                try {
                    verifyTunnel(operationId, owner, recovery.deadlineMillis)
                    synchronized(this) {
                        ensureCurrent(operationId)
                        if (OpenFluxProfileStore.owner(this) != owner)
                            throw OpenFluxRouteException("Аккаунт изменён. Подключитесь заново")
                        if ((openFluxExpiry ?: 0) <= System.currentTimeMillis() / 1000)
                            throw OpenFluxRouteException("Профиль обхода истёк. Обновите подписку")
                        if (openFluxProxy?.isConnected() == true && recovery.observe(true,
                                android.os.SystemClock.elapsedRealtime()) == OpenFluxReconnectGrace.State.VERIFY) {
                            recovery.confirmed()
                            recovering = false
                            publishStatus(VpnPhase.CONNECTED, "Подключено")
                            updateNotification(formatConnectedNotificationText())
                            Log.i(TAG, "OpenFlux carrier recovered; HTTPS verified")
                        }
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: OpenFluxRouteException) {
                    throw error
                } catch (_: Exception) {
                    // Socket recovery alone is insufficient; retain checking until
                    // fresh VPN-bound HTTPS succeeds or the original grace expires.
                    ensureCurrent(operationId)
                }
            }
        }
        } finally {
            renewal?.cancel(true)
        }
    }

    private fun physicalDnsServers(): Array<String> {
        val manager = getSystemService(ConnectivityManager::class.java)
        val physical = manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)?.let { capabilities ->
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
        val preferred = physical.sortedBy { if (it == manager.activeNetwork) 0 else 1 }
        return preferred.firstNotNullOfOrNull { network ->
            manager.getLinkProperties(network)?.dnsServers?.filterIsInstance<Inet4Address>()
                ?.mapNotNull { it.hostAddress }?.distinct()?.take(MAX_CARRIER_DNS_SERVERS)
                ?.takeIf { it.isNotEmpty() }
        }?.toTypedArray() ?: FALLBACK_CARRIER_DNS.copyOf()
    }

    private fun ensureCurrent(operationId: Long) {
        check(operation.get() == operationId && runtimeRunning) { "Подключение отменено" }
    }

    private fun observeAutoRoute() {
        val selected = SelectedRouteStore(this).read()
        publishActiveAutoRoute(null)
        if (selected.id != "auto" || selected.configTag.isBlank()) return

        val options = io.nekohasekai.libbox.CommandClientOptions().apply {
            addCommand(Libbox.CommandGroup)
        }
        val clientHandler = object : CommandClientHandler {
            override fun connected() = Unit
            override fun disconnected(message: String) = Unit
            override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
            override fun setDefaultLogLevel(level: Int) = Unit
            override fun updateClashMode(newMode: String) = Unit
            override fun clearLogs() = Unit
            override fun writeConnectionEvents(events: ConnectionEvents) = Unit
            override fun writeLogs(messageList: LogIterator) = Unit
            override fun writeOutbounds(message: OutboundGroupItemIterator) = Unit
            override fun writeStatus(message: StatusMessage) = Unit

            override fun writeGroups(message: OutboundGroupIterator) {
                while (message.hasNext()) {
                    val group = message.next()
                    if (group.tag != selected.configTag) continue
                    val route = ActiveRouteSelection.fromOutboundTag(group.selected) ?: return
                    if (SelectedRouteStore(this@ConnectVpnService).read().id == "auto") {
                        publishActiveAutoRoute(route)
                    }
                    return
                }
            }
        }
        val client = Libbox.newCommandClient(clientHandler, options)
        commandClient = client
        runCatching { client.connect() }
            .onFailure {
                Log.w(TAG, "Unable to observe selected auto route (${it.javaClass.simpleName})")
                if (commandClient === client) commandClient = null
                runCatching { client.disconnect() }
            }
    }

    private fun publishActiveAutoRoute(route: ActiveRouteSelection?) {
        lastActiveAutoRoute = route
        val preferences = getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
        if (route == null) {
            preferences.remove(EXTRA_ACTIVE_ROUTE_KEY).remove(EXTRA_ACTIVE_EXIT_COUNTRY)
        } else {
            preferences.putString(EXTRA_ACTIVE_ROUTE_KEY, route.routeKey)
                .putString(EXTRA_ACTIVE_EXIT_COUNTRY, route.exitCountry)
        }
        preferences.apply()
        sendBroadcast(
            Intent(ACTION_ACTIVE_ROUTE).setPackage(packageName)
                .putExtra(EXTRA_ACTIVE_ROUTE_KEY, route?.routeKey.orEmpty())
                .putExtra(EXTRA_ACTIVE_EXIT_COUNTRY, route?.exitCountry.orEmpty()),
        )
        if (started && runtimeRunning && VpnStateStore(this).read().phase == VpnPhase.CONNECTED) {
            updateNotification(formatConnectedNotificationText(route))
        }
    }

    private fun closeCommandClient() {
        val client = commandClient ?: return
        commandClient = null
        runCatching { client.disconnect() }
    }

    private fun setupLibbox() {
        if (libboxSetup) return
        synchronized(ConnectVpnService::class.java) {
            if (libboxSetup) return
            val probeProcess = this is RouteProbeVpnService
            val baseDirectory = if (probeProcess) File(filesDir, "route-probe") else filesDir
            val temporaryDirectory = if (probeProcess) File(cacheDir, "route-probe") else cacheDir
            check(baseDirectory.isDirectory || baseDirectory.mkdirs())
            check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs())
            val setup = SetupOptions().apply {
                basePath = baseDirectory.absolutePath
                workingPath = baseDirectory.absolutePath
                tempPath = temporaryDirectory.absolutePath
                fixAndroidStack = Build.VERSION.SDK_INT in 24..25
                debug = BuildConfig.DEBUG
                appVersion = BuildConfig.VERSION_NAME
                appMarketingVersion = BuildConfig.VERSION_NAME
                logMaxLines = 300
            }
            Libbox.setup(setup)
            libboxSetup = true
            Log.i(TAG, "libbox setup complete: basePath=${baseDirectory.absolutePath}")
        }
    }

    private fun runtimeConfig(config: String): String {
        val directory = File(noBackupFilesDir, if (this is RouteProbeVpnService) "route-probe/sing-box" else "sing-box")
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать локальное хранилище соединения" }
        check(directory.canWrite()) { "Нет доступа к локальному хранилищу соединения" }
        return RuntimeProfile.withPrivateCacheFile(config, File(directory, "cache.db").absolutePath)
    }

    /**
     * `startOrReloadService` only confirms that libbox accepted the config.
     * Keep Android's full-route TUN only after one request has passed through it.
     */
    private fun verifyTunnel(operationId: Long, openFluxOwner: String? = null, recoveryDeadline: Long? = null) {
        var lastError: Throwable? = null
        val now = android.os.SystemClock.elapsedRealtime()
        val leaseDeadline = openFluxExpiry?.let { now + maxOf(0L, it - System.currentTimeMillis() / 1000) * 1_000 }
        val deadline = minOf(now + CANARY_BUDGET_MILLIS, recoveryDeadline ?: Long.MAX_VALUE,
            leaseDeadline ?: Long.MAX_VALUE)
        val checkpoint = {
            synchronized(this) {
                ensureCurrent(operationId)
                if (openFluxOwner != null) {
                    if (OpenFluxProfileStore.owner(this) != openFluxOwner)
                        throw OpenFluxRouteException("Аккаунт изменён. Подключитесь заново")
                    if ((openFluxExpiry ?: 0) <= System.currentTimeMillis() / 1000)
                        throw OpenFluxRouteException("Профиль обхода истёк. Обновите подписку")
                }
            }
            if (android.os.SystemClock.elapsedRealtime() >= deadline)
                throw java.net.SocketTimeoutException("VPN verification timed out")
        }
        repeat(2) { attempt ->
            try {
                var vpnNetwork = TunnelCanary.currentNetwork(ownedVpnNetworks(), previousVpnNetworks)
                while (vpnNetwork == null) {
                    checkpoint()
                    Thread.sleep(100)
                    vpnNetwork = TunnelCanary.currentNetwork(ownedVpnNetworks(), previousVpnNetworks)
                }
                val currentNetwork = vpnNetwork
                TunnelCanary.verifyAny(checkpoint = checkpoint) { endpoint ->
                    val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw java.net.SocketTimeoutException("VPN verification timed out")
                    verifyTunnelOnce(currentNetwork, endpoint.url, endpoint.expectedStatus, operationId, deadline,
                        minOf(CANARY_TIMEOUT_MILLIS.toLong(), remaining).toInt())
                }
                return
            } catch (error: Throwable) {
                ensureCurrent(operationId)
                if (error is InterruptedException) throw error
                if (error is OpenFluxRouteException) throw error
                lastError = error
                if (attempt == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(1_500)
            }
        }
        val reason = lastError?.let(::safeNetworkFailure) ?: "нет ответа"
        throw TunnelVerificationException(
            "Выбранный маршрут не прошёл HTTPS-проверку: $reason. Смените обход или повторите попытку.", lastError)
    }

    private fun ownedVpnNetworks(): List<android.net.Network> {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || capabilities.ownerUid == applicationInfo.uid)
        }
    }

    private fun verifyTunnelOnce(vpnNetwork: android.net.Network, url: String, expectedStatus: Int?,
        operationId: Long, deadline: Long, timeoutMillis: Int) {
        // Bind the canary to this VPN generation. The process HTTP pool may still
        // contain sockets from the previous AWG/TUN network after a handoff.
        val connection = (vpnNetwork.openConnection(URL(url)) as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Connection", "close")
            setRequestProperty("Cache-Control", "no-cache")
        }
        val endpointDeadline = minOf(deadline, android.os.SystemClock.elapsedRealtime() + timeoutMillis)
        val watchdog = canaryTimeoutExecutor.schedule({ runCatching { connection.disconnect() } },
            maxOf(1, endpointDeadline - android.os.SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
        try {
            synchronized(this) {
                ensureCurrent(operationId)
                activeCanary = connection
            }
            val status = connection.responseCode
            if (android.os.SystemClock.elapsedRealtime() >= endpointDeadline)
                throw java.net.SocketTimeoutException("VPN verification timed out")
            if (!TunnelCanary.acceptsHttpResponse(status, expectedStatus)) throw TunnelCanary.HttpFailure(status)
        } catch (error: Throwable) {
            Log.w(TAG, "VPN HTTPS verification host=${URL(url).host}: ${safeNetworkFailure(error)}")
            throw error
        } finally {
            watchdog.cancel(false)
            synchronized(this) {
                if (activeCanary === connection) activeCanary = null
            }
            connection.disconnect()
        }
    }

    @Synchronized
    private fun fail(message: String) {
        runCatching { activeCanary?.disconnect() }
        activeCanary = null
        closeCommandClient()
        publishActiveAutoRoute(null)
        publishStatus(VpnPhase.ERROR, "Ошибка запуска соединения", message)
        runCatching { updateNotification(message) }
            .onFailure { Log.w(TAG, "Failed to update error notification", it) }
        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "Failed to close core service after startup error", it) }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { awgProxy?.close() }
        awgProxy = null
        openFluxMonitor?.cancel(true)
        openFluxMonitor = null
        runCatching { openFluxProxy?.close() }
        openFluxProxy = null
        openFluxExpiry = null
        activeRuntimeConfig = null
        runCatching { tunnel?.close() }
        tunnel = null
        started = false
        runtimeRunning = false
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    @Synchronized
    private fun stopTunnel() {
        runCatching { activeCanary?.disconnect() }
        activeCanary = null
        if (!started && commandServer == null && tunnel == null && awgProxy == null && openFluxProxy == null) {
            runtimeRunning = false
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (VpnStateStore(this).read().phase != VpnPhase.ERROR)
                VpnStateStore(this).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
            return
        }
        closeCommandClient()
        publishActiveAutoRoute(null)
        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "Failed to close core service", it) }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { awgProxy?.close() }
        awgProxy = null
        openFluxMonitor?.cancel(true)
        openFluxMonitor = null
        runCatching { openFluxProxy?.close() }
        openFluxProxy = null
        openFluxExpiry = null
        activeRuntimeConfig = null
        runCatching { tunnel?.close() }
        tunnel = null
        started = false
        runtimeRunning = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (VpnStateStore(this).read().phase != VpnPhase.ERROR) {
            publishStatus(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
        }
    }

    private fun publishFailure(error: Throwable) {
        publishStatus(VpnPhase.ERROR, "Ошибка запуска соединения", errorMessage(error, "Не удалось запустить соединение"))
    }

    private fun publishStatus(phase: VpnPhase, status: String, error: String? = null) {
        VpnStateStore(this).write(phase, status, error)
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            .putExtra(STATE_PHASE, phase.name)
        if (!error.isNullOrBlank()) intent.putExtra(EXTRA_ERROR, error)
        sendBroadcast(intent)
    }

    private fun errorMessage(error: Throwable, fallback: String): String =
        when {
            error is OpenFluxRouteException || error is OpenFluxUnavailableException -> error.message ?: fallback
            error is TunnelVerificationException -> error.message ?: fallback
            error.message.orEmpty().contains("проверочный сайт ответил HTTP") -> error.message.orEmpty()
            error.message.orEmpty().contains("DNS через соединение не отвечает") -> error.message.orEmpty()
            error.message.orEmpty().contains("Проверка соединения не прошла") -> error.message.orEmpty()
            errorChainContains(error, "timeout") || errorChainContains(error, "timed out") ->
                "Проверка соединения не завершилась вовремя. Проверьте выбранный маршрут и повторите попытку."
            errorChainContains(error, "unexpected end of stream") ||
                errorChainContains(error, "connection reset") ||
                errorChainContains(error, "broken pipe") ->
                "Соединение оборвалось во время проверки. Повторите попытку."
            else -> fallback
        }

    private fun safeNetworkFailure(error: Throwable): String = when {
        error is TunnelCanary.HttpFailure -> "проверочный сайт ответил HTTP ${error.status}"
        error is java.net.UnknownHostException -> "DNS через VPN не отвечает"
        error is javax.net.ssl.SSLException -> "не удалось установить защищённое HTTPS-соединение"
        errorChainContains(error, "timeout") || errorChainContains(error, "timed out") ->
            "проверка не завершилась вовремя"
        errorChainContains(error, "unexpected end of stream") ||
            errorChainContains(error, "connection reset") ||
            errorChainContains(error, "broken pipe") ->
            "соединение оборвалось во время проверки"
        else -> "не удалось выполнить HTTPS-проверку"
    }

    private fun errorChainContains(error: Throwable, needle: String): Boolean {
        var current: Throwable? = error
        repeat(8) {
            if (current?.message.orEmpty().contains(needle, ignoreCase = true)) return true
            current = current?.cause
        }
        return false
    }

    private fun startForegroundCompat(text: String) {
        notificationText = text
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "deytt./connect", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = if (routeProbeOnly) ACTION_CANCEL_ROUTE_PROBE else ACTION_STOP
        val stopTitle = if (routeProbeOnly) "Отменить проверку" else "Отключить"
        val disconnect = PendingIntent.getService(
            this,
            NOTIFICATION_ID + 1,
            Intent(this, if (routeProbeOnly) RouteProbeVpnService::class.java else ConnectVpnService::class.java).setAction(stopAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restore = PendingIntent.getService(
            this,
            NOTIFICATION_ID + 2,
            Intent(this, ConnectVpnService::class.java).setAction(ACTION_RESTORE_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = notificationBuilder
            .setContentTitle("deytt./connect")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setDeleteIntent(restore)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_vpn),
                    stopTitle,
                    disconnect,
                ).build(),
            )
            .build()
        // Keep the foreground indicator visible until the explicit disconnect
        // action or service stop. setOngoing protects the normal path; the
        // NO_CLEAR flag covers OEM notification shade implementations that
        // otherwise expose a swipe affordance for VPN services.
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        check(notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            "Foreground notification must be ongoing"
        }
        check(notification.flags and Notification.FLAG_AUTO_CANCEL == 0) {
            "Foreground notification must not auto-cancel"
        }
        check(!ForegroundNotificationContract.isUserClearable(notification.flags)) {
            "Foreground notification must not be user-clearable"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        operation.incrementAndGet()
        stopTunnel()
        super.onRevoke()
    }

    private fun updateNotification(text: String) {
        if (!started) return
        startForegroundCompat(text)
    }

    // CommandServerHandler
    override fun connectSSHAgent(): Int = -1
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    @Synchronized
    override fun serviceReload() {
        val config = activeRuntimeConfig ?: return
        commandServer?.startOrReloadService(config, OverrideOptions().apply { autoRedirect = false })
    }
    override fun serviceStop() {
        if (routeProbeOnly) {
            runCatching { tunnel?.close() }
            tunnel = null
            return
        }
        stopTunnel()
    }
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String) {
        Log.d(TAG, message)
    }

    // PlatformInterface
    override fun autoDetectInterfaceControl(fd: Int) {
        if (this is RouteProbeVpnService) {
            networkBridge.bindSocketToUnderlying(fd)
        } else if (!protect(fd)) {
            error("Не удалось исключить core-соединение из VPN")
        }
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun checkPlatformShell() = Unit
    override fun clearDNSCache() = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) =
        networkBridge.closeDefaultInterfaceMonitor(listener)
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun createBridge(options: BridgeOptions): BridgeSession = error("Android bridge не поддержан в MVP")
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner = error("Поиск владельца соединения ещё не включён")

    override fun getInterfaces(): NetworkInterfaceIterator = networkBridge.interfaces()

    override fun includeAllNetworks(): Boolean = false
    override fun localDNSTransport(): LocalDNSTransport = networkBridge.localDnsTransport()
    override fun lookupSFTPServer(): String = error("SFTP не поддержан")
    override fun lookupUser(username: String): PlatformUser = error("Системные пользователи не поддержаны")
    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = error("Shell не поддержан")

    override fun openTun(options: TunOptions): Int {
        check(!routeProbeOnly) { "Проверка маршрута не должна создавать Android TUN" }
        check(prepare(this) == null) { "Нет разрешения Android VPN" }
        val builder = Builder().setSession("deytt./connect").setMtu(options.getMTU())

        prefixIterator(options.getInet4Address()).forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
        }
        prefixIterator(options.getInet6Address()).forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
        }

        if (options.getAutoRoute()) {
            val ipv4Routes = prefixIterator(options.getInet4RouteAddress())
            if (ipv4Routes.isEmpty()) {
                builder.addRoute("0.0.0.0", 0)
            } else {
                ipv4Routes.forEach { (address, prefix) -> builder.addRoute(address, prefix) }
            }
            val ipv6Routes = prefixIterator(options.getInet6RouteAddress())
            if (ipv6Routes.isEmpty()) {
                builder.addRoute("::", 0)
            } else {
                ipv6Routes.forEach { (address, prefix) -> builder.addRoute(address, prefix) }
            }

            if (options.getDNSMode().getValue() != Libbox.DNSModeDisabled) {
                stringIterator(options.getDNSServerAddress()).forEach(builder::addDnsServer)
            }
            stringIterator(options.getIncludePackage()).forEach { packageName ->
                builder.addAllowedApplication(packageName)
            }
            stringIterator(options.getExcludePackage()).forEach { packageName ->
                builder.addDisallowedApplication(packageName)
            }
        }

        tunnel?.close()
        tunnel = builder.establish() ?: error("Android не выдал TUN-интерфейс")
        applyUnderlyingNetworks(networkBridge.currentNetwork)
        return tunnel!!.fd
    }

    override fun readSystemSSHHostKey(): String = error("SSH host key не поддержан")
    override fun readWIFIState(): WIFIState? = null
    override fun registerMyInterface(name: String) = Unit
    override fun sendNotification(notification: LibboxNotification) = Unit
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) =
        networkBridge.startDefaultInterfaceMonitor(listener)
    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun tailscaleHostname(): String = ""
    override fun underNetworkExtension(): Boolean = false
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false
    override fun useProcFS(): Boolean = true

    private fun prefixIterator(iterator: RoutePrefixIterator): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        while (iterator.hasNext()) {
            val prefix = iterator.next()
            result += prefix.address() to prefix.prefix()
        }
        return result
    }

    private fun stringIterator(iterator: StringIterator): List<String> {
        val result = mutableListOf<String>()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }
}
