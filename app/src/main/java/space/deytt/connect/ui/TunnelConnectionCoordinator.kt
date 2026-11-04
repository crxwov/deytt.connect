package space.deytt.connect

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.core.content.edit
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.connectionGlassBackground

internal class TunnelConnectionCoordinator(
    private val host: MainActivity,
    var actionButton: TextView? = null,
    var globe: RouteGlobeView? = null,
    var primaryPages: PrimaryPages? = null,
    private val getActionButton: () -> TextView? = { null },
    private val getGlobe: () -> RouteGlobeView? = { null },
    private val getPrimaryPages: () -> PrimaryPages? = { null },
    private val refreshPageAdapter: ((Int) -> Unit)? = null,
    private val rebuildRouteRow: (() -> Unit)? = null,
    private val cancelHomeRouteDownload: (() -> Unit)? = null,
    private val cancelSelectedRouteLatency: (() -> Unit)? = null,
    private val clearHomeMetricSamples: (() -> Unit)? = null,
    private val scheduleHomeMeasurements: (() -> Unit)? = null,
    private val sampleMapTraffic: (() -> Unit)? = null,
    private val refreshNetworkLocation: ((Boolean) -> Unit)? = null,
    private val isTemporaryAwgMeasurement: () -> Boolean = { false },
    private val restoreAwgMeasurement: (() -> Unit)? = null,
    private val cancelAwgMeasurementForSelection: (() -> Boolean)? = null,
    private val clearPendingRouteProbe: (() -> Unit)? = null,
    private val selectTab: ((Int) -> Unit)? = null,
    private val invalidateRouteCatalogCache: (() -> Unit)? = null,
    private val updateQualityStrip: (() -> Unit)? = null,
    private val updateNetworkLocationViews: (() -> Unit)? = null,
    private val setSelectedDestination: ((String) -> Unit)? = null,
    private val onConnectionGenerationIncremented: (() -> Unit)? = null,
    private val onRouteSelected: ((DeyttRoute) -> Unit)? = null,
    private val migrateRemovedSelectedRouteDelegate: (() -> Boolean)? = null,
) {
    companion object {
        const val VPN_PERMISSION_REQUEST = 701
        private const val PROFILE_SETTINGS = "profile_settings"
        private const val KEY_CONNECTION_STARTED_ELAPSED = "connection_started_elapsed"
        private const val CONNECTION_PROGRESS_INTERVAL_MS = 1_000L
    }

    var homeConnectGeneration = 0L
    var homeConnectQueued = false
    var pendingRoute: SelectedRoute? = null
    var renderedPhase = VpnPhase.IDLE
    var currentAutoRouteKey: String? = null
    var currentAutoOutboundTag: String? = null

    private val connectionProgressHandler = Handler(Looper.getMainLooper())
    private val connectionProgressTick = object : Runnable {
        override fun run() {
            val phase = renderedPhase
            if (phase != VpnPhase.STARTING && phase != VpnPhase.CHECKING) return
            renderConnectionProgressDetail(phase)
            connectionProgressHandler.postDelayed(this, CONNECTION_PROGRESS_INTERVAL_MS)
        }
    }

    private fun resolveAction(): TextView? = actionButton ?: getActionButton()
    private fun resolveGlobe(): RouteGlobeView? = globe ?: getGlobe()
    private fun resolvePrimaryPages(): PrimaryPages? = primaryPages ?: getPrimaryPages()

    fun toggleTunnel() {
        if (homeConnectQueued) return
        val connectGeneration = ++homeConnectGeneration
        cancelSelectedRouteLatency?.invoke()
        val saved = VpnStateStore(host).read()
        val decision = VpnControlDecision.decide(
            saved.phase,
            ConnectVpnService.isRunning(),
            AwgTunnelController.isRunning(),
        )
        if (decision == VpnControlAction.START && RouteProbeClient.isRunning(host)) {
            val selected = SelectedRouteStore(host).read()
            clearPendingRouteProbe?.invoke()
            resolvePrimaryPages()?.cancelRouteDiagnosticsForSelection()
            runCatching { RouteProbeClient.cancel(host) }
            queueSelectedTunnel(selected, connectGeneration)
            return
        }
        if (isTemporaryAwgMeasurement()) {
            restoreAwgMeasurement?.invoke()
            return
        }
        if (decision == VpnControlAction.STOP) {
            val libboxRunning = ConnectVpnService.isRunning()
            renderStatus(VpnPhase.STOPPING, "Отключаем соединение…", null)
            if (libboxRunning) {
                host.startService(Intent(host, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            }
            AwgTunnelController.stop(host, publishStatus = false)
            awaitEnginesStopped(action = {
                VpnStateStore(host).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
                renderStatus(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE, null)
            })
            return
        }
        requestSelectedTunnel(SelectedRouteStore(host).read())
    }

    fun requestSelectedTunnel(route: SelectedRoute) {
        pendingRoute = route
        val action = resolveAction()
        if (action != null) {
            action.isEnabled = true
            action.alpha = 1f
        }
        val permission = VpnService.prepare(host)
        if (permission != null) {
            host.startActivityForResult(permission, VPN_PERMISSION_REQUEST)
        } else {
            startSelectedTunnel()
        }
    }

    fun queueSelectedTunnel(selected: SelectedRoute, generation: Long) {
        homeConnectQueued = true
        val action = resolveAction()
        if (action != null) {
            val label = host.uiCopy("Ждём окончания проверки…")
            action.text = label
            action.contentDescription = label
            action.isEnabled = false
            action.alpha = .65f
        }
        awaitEnginesStopped(
            waitForDiagnostics = true,
            isCurrent = { homeConnectQueued && generation == homeConnectGeneration },
            onFailure = {
                homeConnectQueued = false
                if (!host.isFinishing && !host.isDestroyed) {
                    val act = resolveAction()
                    if (act != null) {
                        act.isEnabled = true
                        act.alpha = 1f
                    }
                    renderStoredState()
                }
            },
            action = {
                homeConnectQueued = false
                if (VpnControlDecision.canResumeQueuedStart(
                        selected.id,
                        SelectedRouteStore(host).read().id,
                        generation,
                        homeConnectGeneration,
                        ConnectVpnService.isRunning(),
                        AwgTunnelController.isRunning(),
                    )
                ) {
                    requestSelectedTunnel(selected)
                } else {
                    renderStoredState()
                }
            }
        )
    }

    fun selectRoute(route: DeyttRoute, navigateHome: Boolean = true) {
        if (!isTemporaryAwgMeasurement() && SelectedRouteStore(host).read().id == route.id) {
            if (navigateHome) selectTab?.invoke(0)
            return
        }
        applyRouteSelection(route, navigateHome)
    }

    fun applyRouteSelection(route: DeyttRoute, navigateHome: Boolean = true) {
        val config = if (route.engine == TunnelEngine.LIBBOX) SubscriptionStore(host).readCurrent() ?: return else null
        val connectionRequested = homeConnectQueued || pendingRoute != null
        val libboxRunning = ConnectVpnService.isRunning()
        val awgRunning = AwgTunnelController.isRunning()
        val generation = ++homeConnectGeneration
        homeConnectQueued = false
        cancelHomeRouteDownload?.invoke()
        resolvePrimaryPages()?.cancelRouteDiagnosticsForSelection()
        // A manual choice owns the selection immediately; a diagnostic restore
        // must never write the old route or reconnect it after this point.
        val resumeConnection = VpnControlDecision.shouldReconnectOnSelection(
            connectionRequested, libboxRunning, awgRunning, cancelAwgMeasurementForSelection?.invoke(),
        )
        pendingRoute = null
        if (RouteProbeClient.isRunning(host)) {
            runCatching { RouteProbeClient.cancel(host) }
            clearPendingRouteProbe?.invoke()
        }
        if (ConnectVpnService.isRunning()) {
            host.startService(Intent(host, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        AwgTunnelController.stop(host)
        if (config != null) {
            SubscriptionStore(host).saveValidated(ProfileRoutes.select(config, route.configTag))
            invalidateRouteCatalogCache?.invoke()
        }
        SelectedRouteStore(host).save(route)
        onRouteSelected?.invoke(route)
        if (navigateHome) refreshPageAdapter?.invoke(1)
        rebuildRouteRow?.invoke()
        renderStoredState()
        if (navigateHome) selectTab?.invoke(0)
        if (resumeConnection) queueSelectedTunnel(SelectedRouteStore(host).read(), generation)
    }

    fun startSelectedTunnel() {
        val route = pendingRoute ?: SelectedRouteStore(host).read()
        val generation = homeConnectGeneration
        val libboxRunning = ConnectVpnService.isRunning()
        val awgRunning = AwgTunnelController.isRunning()
        if (libboxRunning) {
            host.startService(Intent(host, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        if (awgRunning) {
            AwgTunnelController.stop(host, publishStatus = false)
        }
        if (libboxRunning || awgRunning || AwgTunnelController.isStopping()) {
            awaitEnginesStopped(
                isCurrent = {
                    generation == homeConnectGeneration && route.id == SelectedRouteStore(host).read().id
                },
                action = { startSelectedTunnelAfterStop(route) }
            )
            return
        }
        startSelectedTunnelAfterStop(route)
    }

    fun startSelectedTunnelAfterStop(route: SelectedRoute) {
        if (route.engine == TunnelEngine.AMNEZIAWG) {
            val store = AwgProfileStore(host)
            val config = store.read(route.id)
            if (config == null) {
                renderStatus(
                    VpnPhase.ERROR,
                    "ошибка запуска соединения",
                    "обновите подписку: профиль ${route.subtitle.lowercase()} отсутствует"
                )
                return
            }
        }
        val intent = Intent(host, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            host.startForegroundService(intent)
        } else {
            host.startService(intent)
        }
    }

    fun awaitEnginesStopped(
        action: () -> Unit,
        attempt: Int = 0,
        waitForDiagnostics: Boolean = false,
        onFailure: (() -> Unit)? = null,
        isCurrent: () -> Boolean = { true },
    ) {
        if (!isCurrent()) return
        if (host.isFinishing || host.isDestroyed) {
            onFailure?.invoke()
            return
        }
        if (!ConnectVpnService.isRunning() &&
            !AwgTunnelController.isRunning() &&
            !AwgTunnelController.isStopping() &&
            (!waitForDiagnostics || (!RouteProbeClient.isRunning(host) && !isSystemTunnelActive()))
        ) {
            action()
            return
        }
        if (attempt >= if (waitForDiagnostics) 300 else 100) {
            onFailure?.invoke()
            renderStatus(VpnPhase.ERROR, "Не удалось завершить предыдущее соединение", "Попробуйте отключить его ещё раз")
            return
        }
        host.window.decorView.postDelayed({
            awaitEnginesStopped(action, attempt + 1, waitForDiagnostics, onFailure, isCurrent)
        }, 30L)
    }

    fun renderStoredState() {
        val snapshot = VpnStateStore(host).reconcile(
            ConnectVpnService.isRunning(),
            AwgTunnelController.isRunning(),
            systemTunnelActive = isSystemTunnelActive(),
        )
        renderStatus(snapshot.phase, snapshot.title, snapshot.detail)
    }

    fun renderStatus(phase: VpnPhase?, status: String?, error: String?) {
        val currentPhase = phase ?: VpnPhase.IDLE
        val previousPhase = renderedPhase
        val isConnecting = currentPhase == VpnPhase.STARTING || currentPhase == VpnPhase.CHECKING
        if (isConnecting) {
            ensureConnectionProgressStarted(previousPhase)
        } else {
            connectionProgressHandler.removeCallbacks(connectionProgressTick)
            host.getSharedPreferences(PROFILE_SETTINGS, Context.MODE_PRIVATE).edit {
                remove(KEY_CONNECTION_STARTED_ELAPSED)
            }
        }
        renderedPhase = currentPhase
        val globe = resolveGlobe()
        if (globe != null) {
            val selected = SelectedRouteStore(host).read()
            val isAuto = selected.id == "auto"
            val target = if (isAuto) currentAutoRouteKey else selected.id
            globe.setConnectionPhase(currentPhase.name, isAuto, target)
        }
        if (currentPhase == VpnPhase.IDLE && migrateRemovedSelectedRoute() && globe != null) {
            rebuildRouteRow?.invoke()
        }
        if (previousPhase != currentPhase) {
            cancelHomeRouteDownload?.invoke()
            clearHomeMetricSamples?.invoke()
            if (currentPhase == VpnPhase.CONNECTED) {
                onConnectionGenerationIncremented?.invoke()
                clearPendingRouteProbe?.invoke()
                scheduleHomeMeasurements?.invoke()
                sampleMapTraffic?.invoke()
            }
            if (!isTemporaryAwgMeasurement() &&
                (currentPhase == VpnPhase.CONNECTED || currentPhase == VpnPhase.IDLE || currentPhase == VpnPhase.ERROR)
            ) {
                refreshNetworkLocation?.invoke(true)
            }
            val selected = SelectedRouteStore(host).read()
            if (selected.id == "auto") updateRouteFlow(selected)
        }
        val action = resolveAction() ?: return
        if (isConnecting) {
            connectionProgressHandler.removeCallbacks(connectionProgressTick)
            connectionProgressHandler.postDelayed(connectionProgressTick, CONNECTION_PROGRESS_INTERVAL_MS)
        }
        val actionLabel = when (currentPhase) {
            VpnPhase.STARTING, VpnPhase.CHECKING -> progressDetail(currentPhase, connectionElapsedSeconds())
            VpnPhase.CONNECTED -> "Подключено"
            VpnPhase.STOPPING -> "Отключаем…"
            VpnPhase.ERROR -> "Ошибка соединения · повторить"
            VpnPhase.IDLE -> "Подключить"
        }
        action.text = host.uiCopy(actionLabel)
        val errorDetail = if (currentPhase == VpnPhase.ERROR) error ?: status else null
        action.contentDescription = when (currentPhase) {
            VpnPhase.CONNECTED -> host.uiCopy(actionLabel) + ". " + host.uiCopy("Отключить")
            else -> host.uiCopy(actionLabel) + if (!errorDetail.isNullOrBlank()) ". ${host.uiCopy(errorDetail)}" else ""
        }
        action.background = with(host) { connectionGlassBackground(currentPhase) }
        action.isEnabled = currentPhase != VpnPhase.STOPPING && !homeConnectQueued
        action.alpha = if (action.isEnabled) 1f else .66f
        updateQualityStrip?.invoke()
    }

    private fun renderConnectionProgressDetail(phase: VpnPhase) {
        val action = resolveAction() ?: return
        action.text = host.uiCopy(progressDetail(phase, connectionElapsedSeconds()))
        action.contentDescription = action.text.toString()
    }

    @Suppress("DEPRECATION")
    fun isSystemTunnelActive(): Boolean {
        val connectivity = host.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    fun selectedExitLabel(selected: SelectedRoute): String {
        val config = SubscriptionStore(host).readCurrent()
        val routes = config?.let {
            runCatching {
                RouteCatalog.from(
                    it,
                    AwgProfileStore(host).profiles(),
                    OpenFluxProfileStore(host).profiles(host),
                )
            }.getOrNull()
        }.orEmpty()
        val activeAutoRoute = ActiveRouteSelection.configuredAutoRoute(
            selected,
            currentAutoOutboundTag,
            routes,
            renderedPhase == VpnPhase.CONNECTED,
        )
        val route = activeAutoRoute ?: routes.firstOrNull { it.id == selected.id }
        if (route == null) return host.uiCopy(selected.title)
        val country = when (route.countryCode) {
            "NL" -> "Нидерланды"
            "DE", "RU-DE" -> "Германия"
            "RU" -> "Россия"
            "FI" -> "Финляндия"
            "IT" -> "Италия"
            else -> return host.uiCopy(selected.title)
        }
        return (if (route.countryCode == "RU-DE") "🇩🇪" else route.flag) + " " + host.uiCopy(country)
    }

    fun updateRouteFlow(route: DeyttRoute) {
        val selected = SelectedRouteStore(host).read().takeIf { it.id == route.id }
            ?: SelectedRoute(
                id = route.id,
                title = when (route.protocol) {
                    RouteProtocol.AUTO -> route.protocol.title
                    RouteProtocol.LTE -> "${route.flag} lte • белые списки"
                    else -> "${route.flag} ${route.country}"
                },
                subtitle = when (route.protocol) {
                    RouteProtocol.AUTO -> "лучший доступный маршрут"
                    RouteProtocol.LTE -> "обход белых списков"
                    else -> route.protocol.title
                },
                engine = route.engine,
                configTag = route.configTag,
                countryCode = route.countryCode,
                flag = route.flag,
                protocol = route.protocol,
            )
        updateRouteFlow(selected)
    }

    fun updateRouteFlow(selected: SelectedRoute) {
        val label = selectedExitLabel(selected)
        setSelectedDestination?.invoke(label)
        updateNetworkLocationViews?.invoke()
    }

    fun progressDetail(phase: VpnPhase, elapsedSeconds: Long): String {
        val hours = elapsedSeconds / 3_600L
        val minutes = (elapsedSeconds / 60L) % 60L
        val seconds = elapsedSeconds % 60L
        val elapsed = if (hours > 0L) {
            "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
        } else {
            "${(elapsedSeconds / 60L).twoDigits()}:${seconds.twoDigits()}"
        }
        val stage = if (phase == VpnPhase.CHECKING) "Проверяем маршрут и интернет" else "Настраиваем защищённый туннель"
        return "$stage · $elapsed"
    }

    fun ensureConnectionProgressStarted(previousPhase: VpnPhase) {
        val prefs = host.getSharedPreferences(PROFILE_SETTINGS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val previousWasConnecting = previousPhase == VpnPhase.STARTING || previousPhase == VpnPhase.CHECKING
        val savedStart = prefs.getLong(KEY_CONNECTION_STARTED_ELAPSED, 0L)
        if (!previousWasConnecting || savedStart <= 0L || savedStart > now) {
            prefs.edit { putLong(KEY_CONNECTION_STARTED_ELAPSED, now) }
        }
    }

    fun connectionElapsedSeconds(): Long {
        val now = SystemClock.elapsedRealtime()
        val startedAt = host.getSharedPreferences(PROFILE_SETTINGS, Context.MODE_PRIVATE)
            .getLong(KEY_CONNECTION_STARTED_ELAPSED, now)
            .takeIf { it in 1L..now }
            ?: now
        return ((now - startedAt) / 1_000L).coerceAtLeast(0L)
    }

    private fun migrateRemovedSelectedRoute(): Boolean {
        if (migrateRemovedSelectedRouteDelegate != null) return migrateRemovedSelectedRouteDelegate.invoke()
        if (ConnectVpnService.isRunning() || AwgTunnelController.isRunning() || isSystemTunnelActive()) return false
        val store = SelectedRouteStore(host)
        val selected = store.read()
        if (selected.id != "openflux:DE" && selected.engine != TunnelEngine.OPENFLUX) return false
        val config = SubscriptionStore(host).readCurrent() ?: return false
        val automatic = RouteCatalog.from(config, AwgProfileStore(host).profiles())
            .firstOrNull { it.protocol == RouteProtocol.AUTO } ?: return false
        store.save(automatic)
        return true
    }

    fun close() {
        connectionProgressHandler.removeCallbacks(connectionProgressTick)
    }

    private fun Long.twoDigits(): String = if (this < 10L) "0$this" else toString()
}
