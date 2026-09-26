package space.deytt.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborEntryIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.URL
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

open class ConnectVpnService : VpnService(), CommandServerHandler, PlatformInterface {
    companion object {
        const val ACTION_START = "space.deytt.connect.action.START"
        const val ACTION_STOP = "space.deytt.connect.action.STOP"
        const val ACTION_ROUTE_PROBE = "space.deytt.connect.action.ROUTE_PROBE"
        const val ACTION_CANCEL_ROUTE_PROBE = "space.deytt.connect.action.CANCEL_ROUTE_PROBE"
        const val ACTION_RESTORE_NOTIFICATION = "space.deytt.connect.action.RESTORE_NOTIFICATION"
        const val ACTION_STATUS = "space.deytt.connect.action.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "deytt-connect"
        // A hostname-backed HTTPS probe avoids treating a TLS handshake to a
        // bare CDN IP as a route failure. DNS is checked separately below.
        private const val TRANSPORT_CANARY = "https://www.cloudflare.com/cdn-cgi/trace"
        private const val DNS_CANARY = "https://www.gstatic.com/generate_204"
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
    private var commandServer: CommandServer? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var started = false
    private var routeProbeOnly = false
    private var notificationText = "Запуск deytt./connect"
    private val operation = AtomicLong(0)
    private val networkBridge by lazy { AndroidNetworkBridge(this) }

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
        if (routeProbeOnly) closeRouteProbeCore() else stopTunnel()
        executor.shutdownNow()
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
        runtimeRunning = false
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
            val session = RouteProxyProbe.newSession()
            stage = "libbox setup"
            setupLibbox()
            stage = "command server start"
            val server = CommandServer(this, this)
            commandServer = server
            server.start()
            routeTags.forEach { tag ->
                if (operation.get() != operationId || !routeProbeOnly) return@forEach
                var routeStage = "route config"
                var latency: Long? = null
                try {
                    val probeConfig = session.configuration(config, tag)
                    routeStage = "route reload"
                    server.startOrReloadService(
                        runtimeConfig(probeConfig),
                        OverrideOptions().apply { autoRedirect = false },
                    )
                    if (operation.get() != operationId || !routeProbeOnly) return@forEach
                    routeStage = "HTTP proxy probe"
                    sendRouteProbeResult(requestId, tag, null, null, stage = "latency")
                    latency = RouteProxyProbe.measureProxy(session, method)
                    if (token.isNotBlank()) {
                        routeStage = "download sample"
                        sendRouteProbeResult(requestId, tag, null, null, stage = "download")
                        val speed = RouteProxyProbe.measureDownload(session, token)
                        sendRouteProbeResult(
                            requestId,
                            tag,
                            latency,
                            null,
                            stage = "complete",
                            bytesPerSecond = speed,
                        )
                    } else {
                        sendRouteProbeResult(requestId, tag, latency, null, stage = "complete")
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Route proxy probe failed at $routeStage (${probeFailureKind(error)})")
                    sendRouteProbeResult(
                        requestId,
                        tag,
                        latency,
                        routeProbeError(error),
                        stage = "failed",
                    )
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Route proxy probe failed at $stage (${probeFailureKind(error)})")
            routeTags.forEach { tag ->
                sendRouteProbeResult(requestId, tag, null, routeProbeError(error))
            }
        } finally {
            sendRouteProbeResult(requestId, "", null, null, complete = true)
            RouteProbeClient.clear(this, requestId)
            closeRouteProbeCore()
            started = false
            runtimeRunning = false
            routeProbeOnly = false
            routeProbeRunning = false
            stopForegroundCompat()
            stopSelf(startId)
        }
    }

    private fun cancelRouteProbe(startId: Int) {
        operation.incrementAndGet()
        routeProbeRunning = false
        closeRouteProbeCore()
        started = false
        routeProbeOnly = false
        stopForegroundCompat()
        stopSelf(startId)
    }

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
    ) {
        val intent = Intent(RouteProbeClient.ACTION_RESULT)
            .setPackage(packageName)
            .putExtra(RouteProbeClient.EXTRA_REQUEST_ID, requestId)
            .putExtra(RouteProbeClient.EXTRA_ROUTE_TAG, routeTag)
            .putExtra(RouteProbeClient.EXTRA_COMPLETE, complete)
        if (milliseconds != null) intent.putExtra(RouteProbeClient.EXTRA_MILLISECONDS, milliseconds)
        if (!error.isNullOrBlank()) intent.putExtra(RouteProbeClient.EXTRA_ERROR, error)
        if (!stage.isNullOrBlank()) intent.putExtra(RouteProbeClient.EXTRA_STAGE, stage)
        if (bytesPerSecond != null) intent.putExtra(RouteProbeClient.EXTRA_BYTES_PER_SECOND, bytesPerSecond)
        sendBroadcast(intent)
    }

    private fun routeProbeError(error: Throwable): String = when {
        error.message.orEmpty().contains("timeout", ignoreCase = true) ||
            error.message.orEmpty().contains("времен", ignoreCase = true) -> "тайм-аут · 10 с"
        error.message.orEmpty().startsWith("Локальный прокси ответил HTTP") ->
            error.message.orEmpty().substringAfter("ответил ")
        errorChainContains(error, "TLS hostname verification failed") -> "ошибка проверки TLS-сертификата"
        error.message.orEmpty().startsWith("Проверочный сервер ответил HTTP") ->
            error.message.orEmpty().substringAfter("ответил ")
        else -> "маршрут не ответил"
    }

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
        try {
            val config = SubscriptionStore(this).readCurrent()
            if (config == null) {
                fail("Нет сохранённой подписки")
                return
            }
            val runtimeConfig = runtimeConfig(config)
            ProfileValidator.validate(runtimeConfig)
            setupLibbox()
            val server = CommandServer(this, this)
            commandServer = server
            server.start()
            server.startOrReloadService(runtimeConfig, OverrideOptions().apply { autoRedirect = false })
            ensureCurrent(operationId)
            publishStatus(VpnPhase.CHECKING, "Проверяем туннель…", "Проверяю доступ к интернету через выбранный маршрут")
            updateNotification("Проверяем туннель…")
            verifyTunnel()
            ensureCurrent(operationId)
            updateNotification("Подключено")
            publishStatus(VpnPhase.CONNECTED, "Подключено")
            Log.i(TAG, "VPN service started")
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start VPN", error)
            if (operation.get() == operationId) {
                fail(errorMessage(error, "Не удалось запустить соединение"))
            }
        }
    }

    private fun ensureCurrent(operationId: Long) {
        check(operation.get() == operationId && runtimeRunning) { "Подключение отменено" }
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
    private fun verifyTunnel() {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                verifyTunnelOnce(TRANSPORT_CANARY, null, "Туннель не передаёт HTTPS-трафик")
                verifyTunnelOnce(DNS_CANARY, 204, "DNS через соединение не отвечает")
                return
            } catch (error: Throwable) {
                lastError = error
                if (attempt < 2) Thread.sleep(1_500)
            }
        }
        val detail = lastError?.message
            ?.takeIf { it.contains("проверочный сайт ответил HTTP") || it.contains("не отвечает") || it.contains("не завершилась") }
            ?: "Проверка соединения не прошла"
        throw IllegalStateException(detail, lastError)
    }

    private fun verifyTunnelOnce(url: String, expectedStatus: Int?, failurePrefix: String) {
        val connection = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            check(TunnelCanary.acceptsHttpResponse(connection.responseCode, expectedStatus)) {
                "$failurePrefix: проверочный сайт ответил HTTP ${connection.responseCode}"
            }
        } catch (error: Throwable) {
            throw IllegalStateException("$failurePrefix: ${safeNetworkFailure(error)}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun fail(message: String) {
        publishStatus(VpnPhase.ERROR, "Ошибка запуска соединения", message)
        runCatching { updateNotification(message) }
            .onFailure { Log.w(TAG, "Failed to update error notification", it) }
        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "Failed to close core service after startup error", it) }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunnel?.close() }
        tunnel = null
        started = false
        runtimeRunning = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopTunnel() {
        if (!started && commandServer == null && tunnel == null) {
            runtimeRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            VpnStateStore(this).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
            return
        }
        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "Failed to close core service", it) }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunnel?.close() }
        tunnel = null
        started = false
        runtimeRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (SelectedRouteStore(this).read().engine == TunnelEngine.LIBBOX) {
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
            Intent(this, ConnectVpnService::class.java).setAction(stopAction),
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
    override fun serviceReload() {
        val config = SubscriptionStore(this).readCurrent() ?: return
        commandServer?.startOrReloadService(runtimeConfig(config), OverrideOptions().apply { autoRedirect = false })
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
