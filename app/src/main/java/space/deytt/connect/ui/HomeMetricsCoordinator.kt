package space.deytt.connect

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.text
import java.util.Locale

internal class HomeMetricsCoordinator(
    private val host: MainActivity,
    private val getSelectedRoute: () -> SelectedRoute = { SelectedRouteStore(host).read() },
    private val getRouteCatalog: () -> List<DeyttRoute> = {
        val config = SubscriptionStore(host).readCurrent()
        if (config != null) RouteCatalog.from(config, AwgProfileStore(host).profiles(), OpenFluxProfileStore(host).profiles(host)) else emptyList()
    },
    private val getSubscriptionContent: () -> String? = { SubscriptionStore(host).readCurrent() },
    private val getCurrentPhase: () -> VpnPhase? = { null },
    private val showHomeMetricExplanation: (speed: Boolean) -> Unit = {},
    private val isTrafficExplanationShowing: () -> Boolean = { false },
    private val isHomeSelected: () -> Boolean = { true },
    private val isPagerIdle: () -> Boolean = { true },
    private val isHomeConnectQueued: () -> Boolean = { false },
    private val isTemporaryAwgMeasurement: () -> Boolean = { false },
    private val onRequestRouteProbePermission: ((Intent) -> Unit)? = null,
    private val onTrafficActiveChanged: ((Boolean) -> Unit)? = null,
    private val getAutoRouteKey: () -> String? = { null },
    private val getAutoOutboundTag: () -> String? = { null },
    private val getRouteTitleView: (() -> TextView?)? = null,
) {
    companion object {
        const val SELECTED_ROUTE_LATENCY_INTERVAL_MS = 5_000L
        const val MAP_TRAFFIC_SAMPLE_INTERVAL_MS = 420L
        const val MAP_TRAFFIC_GRACE_MS = 2_800L
    }

    private fun dp(value: Int): Int = with(host) { dp(value) }
    private fun uiCopy(value: String): String = with(host) { uiCopy(value) }

    lateinit var latencyText: TextView
    lateinit var downloadText: TextView
    lateinit var pingValueProgress: InlineMetricProgress
    lateinit var speedValueProgress: InlineMetricProgress

    fun isQualityStripInitialized(): Boolean = ::latencyText.isInitialized && ::downloadText.isInitialized

    val homePingSamples = mutableListOf<Long>()
    var lastRouteLatency: Pair<HomeRouteMetricKey, Long>? = null
    var homePingErrorKey: HomeRouteMetricKey? = null
    var homePingBypassedKey: HomeRouteMetricKey? = null
    var homeSpeedSample: Pair<HomeRouteMetricKey, Long>? = null
    var homeSpeedError: String? = null
    var homeSpeedErrorKey: HomeRouteMetricKey? = null
    var homeSpeedBypassedKey: HomeRouteMetricKey? = null
    var homeSpeedAttemptAt = 0L
    var homeSpeedInFlight = false
    var selectedRouteLatencyInFlight = false
    var mapTrafficActive = false
        private set

    @Volatile var homeSpeedForeground = false
    @Volatile var homeSpeedOnHome = true
    @Volatile var homeSpeedGeneration = 0
    var latencyGeneration = 0
    var homeConnectionGeneration = 0L
    var homeSpeedConnectedRouteId: String? = null
    var pendingRouteProbeId: String? = null
    var pendingRouteProbeMetricKey: HomeRouteMetricKey? = null
    var pendingRouteProbeHasOutcome = false
    var pendingRouteProbeAuthorization: (() -> Unit)? = null

    val mapTrafficWindow = TrafficActivityWindow(MAP_TRAFFIC_GRACE_MS)

    private val selectedRouteLatencyHandler = Handler(Looper.getMainLooper())
    private val mapTrafficHandler = Handler(Looper.getMainLooper())
    private var selectedRouteLatencyFuture: java.util.concurrent.Future<*>? = null
    private var homeSpeedFuture: java.util.concurrent.Future<*>? = null

    val mapTrafficTick = object : Runnable {
        override fun run() {
            if (!homeSpeedForeground || host.isFinishing || host.isDestroyed) return
            sampleMapTraffic()
            mapTrafficHandler.postDelayed(this, MAP_TRAFFIC_SAMPLE_INTERVAL_MS)
        }
    }

    val selectedRouteLatencyTick = object : Runnable {
        override fun run() {
            if (host.isFinishing || host.isDestroyed) return
            if (isHomeSelected() && getCurrentPhase() == VpnPhase.CONNECTED &&
                VpnService.prepare(host) == null && !RouteProbeClient.isRunning(host)) measureSelectedRoute()
            measureHomeRouteDownload()
            selectedRouteLatencyHandler.postDelayed(this, SELECTED_ROUTE_LATENCY_INTERVAL_MS)
        }
    }

    fun startMapTrafficTick() { mapTrafficHandler.removeCallbacks(mapTrafficTick); mapTrafficHandler.post(mapTrafficTick) }
    fun stopMapTrafficTick() { mapTrafficHandler.removeCallbacks(mapTrafficTick) }
    fun startSelectedRouteLatencyTick() { selectedRouteLatencyHandler.removeCallbacks(selectedRouteLatencyTick); selectedRouteLatencyHandler.post(selectedRouteLatencyTick) }
    fun stopSelectedRouteLatencyTick() { selectedRouteLatencyHandler.removeCallbacks(selectedRouteLatencyTick) }

    fun buildQualityStrip(): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        fun metric(label: String, value: TextView, tagName: String, onClick: () -> Unit) = LinearLayout(host).apply {
            tag = tagName
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            addView(LinearLayout(host).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(host.text(label, 11f, DeyttUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            })
            value.minHeight = dp(38)
            value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            value.doAfterTextChanged { editable ->
                val content = editable.toString()
                val measured = content == "—" || content.firstOrNull()?.isDigit() == true || content.startsWith("↓")
                value.textSize = if (measured) 23f else 13f
            }
            addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            setOnClickListener { onClick() }
            host.pressFeedback(this)
        }
        latencyText = host.text("—", 23f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START
            setOnClickListener { showHomeMetricExplanation(false) }
        }
        pingValueProgress = InlineMetricProgress(latencyText)
        addView(metric(uiCopy("Задержка"), latencyText, "home-latency") { showHomeMetricExplanation(false) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(View(host).apply { setBackgroundColor(DeyttUi.LINE) }, LinearLayout.LayoutParams(dp(1), dp(56)))
        downloadText = host.text("—", 23f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        speedValueProgress = InlineMetricProgress(downloadText)
        addView(metric("Скорость", downloadText, "home-speed") { showHomeMetricExplanation(true) }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    fun updateQualityStrip() {
        if (!::downloadText.isInitialized || !::latencyText.isInitialized) return
        val selected = getSelectedRoute()
        val routes = getRouteCatalog()
        val route = routes.firstOrNull { it.id == selected.id }
        val connected = getCurrentPhase() == VpnPhase.CONNECTED
        getRouteTitleView?.invoke()?.let { titleView ->
            titleView.text = when (route?.protocol) {
                RouteProtocol.AUTO -> "auto"
                RouteProtocol.LTE -> "lte • белые списки"
                else -> route?.let { "${it.countryCode.lowercase()}./${uiCopy(it.protocol.title).replace(" ", "")}" } ?: uiCopy(selected.title)
            }
        }
        val metricKey = currentHomeRouteMetricKey(selected.id)
        val speedNeedsLogin = HomeMetricPresentation.speedNeedsLogin(connected, metricKey)
        val recentSpeed = HomeMetricPresentation.value(connected, metricKey, homeSpeedSample?.first, homeSpeedSample?.second)
        val speedError = HomeMetricPresentation.value(connected, metricKey, homeSpeedErrorKey, homeSpeedError)
        val speedBypassed = HomeMetricPresentation.isCurrent(connected, metricKey, homeSpeedBypassedKey)
        val en = AppLanguage.current(host) == AppLanguage.EN
        val latency = HomeMetricPresentation.value(connected, metricKey, lastRouteLatency?.first, lastRouteLatency?.second)?.takeIf(RouteProbeStatistics::isValidLatencyMillis)
        val pingError = HomeMetricPresentation.isCurrent(connected, metricKey, homePingErrorKey)
        val pingBypassed = HomeMetricPresentation.isCurrent(connected, metricKey, homePingBypassedKey)
        downloadText.text = HomeMetricPresentation.speedLabel(recentSpeed, speedBypassed, speedNeedsLogin, speedError != null, en)
        latencyText.text = HomeMetricPresentation.latencyLabel(latency, pingBypassed, pingError, en)
        latencyText.setTextColor(when {
            pingBypassed -> DeyttUi.AMBER
            pingError && latency == null -> DeyttUi.CORAL
            connected && (selectedRouteLatencyInFlight || pendingRouteProbeId != null) -> DeyttUi.SKY
            latency != null -> DeyttUi.MINT
            else -> DeyttUi.MUTED
        })
        val sampleSeconds = RouteProxyProbe.QUICK_DOWNLOAD_MILLIS / 1_000
        downloadText.contentDescription = when {
            speedBypassed -> uiCopy("Скорость не измеряется: приложение исключено из VPN текущими настройками обхода")
            speedNeedsLogin -> if (en) "Sign in to Telegram to measure speed. Tap for an explanation." else "Для замера скорости войдите через Telegram. Нажмите для объяснения."
            recentSpeed != null -> if (en) "Download speed: ${downloadText.text}. Tap for an explanation." else "Скорость загрузки: ${downloadText.text}. Нажмите для объяснения."
            speedError != null -> uiCopy(speedError)
            connected -> if (en) "Download speed, $sampleSeconds second sample. Tap for an explanation." else "Скорость загрузки, проба $sampleSeconds с. Нажмите для объяснения."
            else -> uiCopy("Скорость проверяется после подключения")
        }
        latencyText.contentDescription = when {
            pingBypassed -> uiCopy("HTTPS-отклик не проходит через VPN при текущих настройках обхода")
            latency != null -> if (en) "Ping: $latency milliseconds. Tap for an explanation." else "Задержка: $latency миллисекунд. Нажмите для объяснения."
            pingError -> uiCopy("Отклик: нет ответа через выбранный маршрут")
            connected -> if (en) "Measuring ping. Tap for an explanation." else "Замер задержки. Нажмите для объяснения."
            else -> uiCopy("Отклик проверяется после подключения")
        }
        if (::pingValueProgress.isInitialized) pingValueProgress.setLoading(connected && latencyText.text.toString() == "—")
        if (::speedValueProgress.isInitialized) speedValueProgress.setLoading(connected && downloadText.text.toString() == "—")
    }

    fun currentHomeRouteMetricKey(routeId: String): HomeRouteMetricKey? {
        val route = getRouteCatalog().firstOrNull { it.id == routeId } ?: return null
        val autoKey = getAutoRouteKey()
        val autoOutbound = getAutoOutboundTag()
        return HomeRouteMetricKey(
            routeId = route.id,
            sessionToken = TelegramSessionStore.read(host),
            configTag = route.configTag,
            activeAutoRouteKey = autoKey?.takeIf { route.protocol == RouteProtocol.AUTO },
            activeAutoOutboundTag = autoOutbound?.takeIf { route.protocol == RouteProtocol.AUTO },
            connectionGeneration = homeConnectionGeneration,
        )
    }

    fun isHomeApplicationRoutedByTunnel(config: String): Boolean {
        val split = UserSplitTunnelStore(host)
        return RouteProxyProbe.isApplicationRoutedByTunnel(config, host.packageName, customPackages = split.readPackages(), splitMode = split.readMode())
    }

    fun clearHomeMetricSamples() {
        cancelSelectedRouteLatency()
        homePingSamples.clear()
        lastRouteLatency = null
        homePingErrorKey = null
        homePingBypassedKey = null
        homeSpeedSample = null
        homeSpeedError = null
        homeSpeedErrorKey = null
        homeSpeedBypassedKey = null
    }

    fun cancelHomeRouteDownload() {
        if (homeSpeedInFlight) homeSpeedAttemptAt = 0L
        homeSpeedGeneration++
        homeSpeedFuture?.cancel(true)
        homeSpeedFuture = null
        homeSpeedInFlight = false
    }

    fun cancelSelectedRouteLatency() {
        latencyGeneration++
        selectedRouteLatencyFuture?.cancel(true)
        selectedRouteLatencyFuture = null
        selectedRouteLatencyInFlight = false
    }

    fun scheduleHomeMeasurements() {
        selectedRouteLatencyHandler.post {
            if (homeSpeedForeground && homeSpeedOnHome) {
                measureSelectedRoute()
                measureHomeRouteDownload()
            }
        }
    }

    fun sampleMapTraffic() {
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning() || isSystemTunnelActive()
        if (tunnelActive) LocalTrafficRecorder.sample(host)
        val connectedOnHome = homeSpeedForeground && !isTrafficExplanationShowing() && isHomeSelected() && getCurrentPhase() == VpnPhase.CONNECTED && tunnelActive
        setMapTrafficActive(connectedOnHome)
    }

    fun setMapTrafficActive(active: Boolean) {
        if (mapTrafficActive == active) return
        mapTrafficActive = active
        onTrafficActiveChanged?.invoke(active)
    }

    fun formatBytes(value: Long): String {
        val english = AppLanguage.current(host) == AppLanguage.EN
        if (value <= 0) return if (english) "0 B" else "0 Б"
        val units = if (english) arrayOf("B", "KB", "MB", "GB", "TB") else arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit++
        }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    fun formatLatency(milliseconds: Long): String =
        if (AppLanguage.current(host) == AppLanguage.EN) "$milliseconds ms" else "$milliseconds мс"

    fun buildTrafficSummary(): View {
        val metadata = SubscriptionMetadataStore(host).read()
        val uploaded = metadata.uploadBytes.coerceAtLeast(0)
        val downloaded = metadata.downloadBytes.coerceAtLeast(0)
        val used = (uploaded.toDouble() + downloaded.toDouble()).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(14))
            background = host.rounded(DeyttUi.SURFACE, 18f, DeyttUi.LINE)
        }
        panel.addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(host.mono("ТРАФИК ПОДПИСКИ", 8f, DeyttUi.MUTED, 600), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(host.text(if (metadata.totalBytes > 0) "из ${formatBytes(metadata.totalBytes)}" else "без лимита", 10f, DeyttUi.MUTED))
        })
        panel.addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(host.text(formatBytes(used), 19f, DeyttUi.TEXT, android.graphics.Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(5) })
            addView(host.text(if (used > 0) "передано" else "нет данных", 10f, DeyttUi.MUTED).apply { setPadding(0, dp(5), 0, 0) })
        })
        val usageDesc = if (metadata.totalBytes > 0) {
            "Использовано ${formatBytes(used)} из ${formatBytes(metadata.totalBytes)}. Скачано ${formatBytes(downloaded)}, отправлено ${formatBytes(uploaded)}."
        } else {
            "Передано ${formatBytes(used)} без заданного лимита. Скачано ${formatBytes(downloaded)}, отправлено ${formatBytes(uploaded)}."
        }
        panel.addView(TrafficUsageBar(host, uploaded, downloaded, metadata.totalBytes, uiCopy(usageDesc)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })
        val legend = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(host.text("↓ ${formatBytes(downloaded)}", 10f, DeyttUi.SKY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(host.text("↑ ${formatBytes(uploaded)}", 10f, DeyttUi.MINT))
        }
        panel.addView(legend)
        return panel
    }

    fun measureSelectedRoute() {
        if (!::latencyText.isInitialized) return
        if (RouteProbeClient.isRunning(host) || selectedRouteLatencyInFlight) return
        if (isHomeConnectQueued() || isTemporaryAwgMeasurement() || getCurrentPhase() in setOf(VpnPhase.STARTING, VpnPhase.CHECKING, VpnPhase.STOPPING)) return
        if (getCurrentPhase() != VpnPhase.CONNECTED) {
            latencyText.contentDescription = uiCopy("HTTPS-отклик проверяется после подключения")
            updateQualityStrip()
            latencyText.text = uiCopy("Отклик: после подключения").substringAfter(": ")
            return
        }
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (!tunnelActive) {
            latencyText.apply { contentDescription = uiCopy("HTTPS-отклик проверяется после подключения"); isEnabled = true; alpha = 1f }
            updateQualityStrip()
            latencyText.text = uiCopy("Отклик: после подключения").substringAfter(": ")
            return
        }
        val generation = ++latencyGeneration
        homePingSamples.clear()
        val selected = getSelectedRoute()
        val config = getSubscriptionContent() ?: return
        val route = getRouteCatalog().firstOrNull { it.id == selected.id } ?: return
        val metricKey = currentHomeRouteMetricKey(selected.id) ?: return
        val method = RouteProbePreferences.method(host)
        val previousLatency = lastRouteLatency?.takeIf {
            getCurrentPhase() == VpnPhase.CONNECTED && it.first == metricKey && RouteProbeStatistics.isValidLatencyMillis(it.second)
        }?.second
        homePingErrorKey = null
        homePingBypassedKey = null
        latencyText.text = previousLatency?.let(::formatLatency) ?: "—"
        updateQualityStrip()
        if (tunnelActive) {
            val appBypassed = route.engine == TunnelEngine.LIBBOX && !isHomeApplicationRoutedByTunnel(config)
            if (route.id != selected.id || appBypassed) {
                if (appBypassed) {
                    homePingSamples.clear(); lastRouteLatency = null; homePingBypassedKey = metricKey; homePingErrorKey = null; updateQualityStrip()
                }
                latencyText.apply {
                    text = uiCopy(if (appBypassed) "Отклик: в обход VPN" else "Отклик: отключите соединение").substringAfter(": ")
                    isEnabled = true; alpha = 1f; setTextColor(DeyttUi.AMBER)
                    contentDescription = if (appBypassed) uiCopy("HTTPS-отклик не проходит через VPN при текущих настройках обхода")
                    else uiCopy("Чтобы проверить другой выход, сначала отключите текущее соединение")
                }
                return
            }
            if (route.engine == TunnelEngine.AMNEZIAWG && !AwgTunnelController.isRunning()) {
                latencyText.apply {
                    text = uiCopy("Отклик: подключите AmneziaWG").substringAfter(": ")
                    isEnabled = true; alpha = 1f; setTextColor(DeyttUi.AMBER)
                    contentDescription = uiCopy("Для HTTP-проверки подключите выбранный профиль AmneziaWG")
                }
                return
            }
            selectedRouteLatencyInFlight = true
            updateQualityStrip()
            selectedRouteLatencyFuture = LatencyExecutor.pool.submit {
                val elapsed = runCatching {
                    RouteProxyProbe.measureThroughSystemVpn(method) { _, sample ->
                        if (sample != null && RouteProbeStatistics.isValidLatencyMillis(sample)) host.runOnUiThread {
                            if (!HomeMetricPresentation.ownsRequest(generation, latencyGeneration) ||
                                host.isFinishing || host.isDestroyed || getCurrentPhase() != VpnPhase.CONNECTED ||
                                currentHomeRouteMetricKey(selected.id) != metricKey ||
                                (route.engine == TunnelEngine.LIBBOX && !isHomeApplicationRoutedByTunnel(config))
                            ) return@runOnUiThread
                            homePingSamples += sample
                            if (lastRouteLatency?.first != metricKey) {
                                lastRouteLatency = metricKey to sample; updateQualityStrip()
                            }
                        }
                    }
                }.getOrNull()
                host.runOnUiThread {
                    if (!HomeMetricPresentation.ownsRequest(generation, latencyGeneration)) return@runOnUiThread
                    selectedRouteLatencyInFlight = false; selectedRouteLatencyFuture = null
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    if (currentHomeRouteMetricKey(selected.id) != metricKey) {
                        latencyText.apply { isEnabled = true; alpha = 1f }; updateQualityStrip(); return@runOnUiThread
                    }
                    if (route.engine == TunnelEngine.LIBBOX && !isHomeApplicationRoutedByTunnel(config)) {
                        homePingSamples.clear(); lastRouteLatency = null; homePingBypassedKey = metricKey; homePingErrorKey = null
                        latencyText.apply { isEnabled = true; alpha = 1f }; updateQualityStrip(); return@runOnUiThread
                    }
                    val validElapsed = elapsed?.takeIf(RouteProbeStatistics::isValidLatencyMillis)
                    if (validElapsed != null) { lastRouteLatency = metricKey to validElapsed; homePingErrorKey = null }
                    else { homePingErrorKey = metricKey }
                    if (validElapsed != null && homePingSamples.isEmpty()) homePingSamples += validElapsed
                    latencyText.apply {
                        isEnabled = true; alpha = 1f
                        setTextColor(if (validElapsed != null) DeyttUi.MINT else DeyttUi.CORAL)
                        contentDescription = validElapsed?.let { uiCopy("Медиана HTTPS-ответов через активное соединение: ${it} миллисекунд") }
                            ?: uiCopy("HTTPS-проверка через активное соединение не ответила за 8 секунд")
                    }
                    updateQualityStrip()
                }
            }
            return
        }
        if (route.engine == TunnelEngine.AMNEZIAWG) {
            latencyText.apply {
                text = uiCopy("Отклик: подключите AmneziaWG").substringAfter(": ")
                isEnabled = true; alpha = 1f; setTextColor(DeyttUi.AMBER)
                contentDescription = uiCopy("Для HTTP-проверки подключите выбранный профиль AmneziaWG")
            }
            return
        }
        if (route.engine == TunnelEngine.OPENFLUX) {
            latencyText.apply { text = uiCopy("Отклик: после подключения").substringAfter(": "); isEnabled = true; alpha = 1f }
            return
        }
        val vpnPermission = VpnService.prepare(host)
        if (vpnPermission != null) {
            latencyText.apply { text = "—"; isEnabled = true; alpha = 1f }
            onRequestRouteProbePermission?.invoke(vpnPermission)
            return
        }
        try {
            pendingRouteProbeHasOutcome = false
            pendingRouteProbeMetricKey = metricKey
            homePingSamples.clear()
            pendingRouteProbeId = RouteProbeClient.start(host, config, listOf(route.configTag), method, TelegramSessionStore.read(host), latencyOnly = true)
            if (previousLatency == null) latencyText.text = "—"
            latencyText.apply {
                isEnabled = true; alpha = 1f; setTextColor(DeyttUi.SKY)
                contentDescription = uiCopy("Проверяю выход тремя HTTPS-запросами методом ${method.wireValue}, общий тайм-аут 8 секунд")
            }
            updateQualityStrip()
        } catch (_: Throwable) {
            if (generation == latencyGeneration && !host.isFinishing && !host.isDestroyed) {
                homePingErrorKey = metricKey
                latencyText.apply {
                    text = uiCopy("Отклик: ошибка").substringAfter(": "); isEnabled = true; alpha = 1f; setTextColor(DeyttUi.CORAL)
                    contentDescription = uiCopy("Не удалось запустить проверку через прокси")
                }
                updateQualityStrip()
            }
        }
    }

    /** One bounded real throughput sample after connection, then at most once a minute on Home. */
    fun measureHomeRouteDownload() {
        if (!homeSpeedForeground || !homeSpeedOnHome || host.isFinishing || host.isDestroyed || homeSpeedInFlight) return
        if (!isPagerIdle()) return
        if (getCurrentPhase() != VpnPhase.CONNECTED || isHomeConnectQueued() || isTemporaryAwgMeasurement() || RouteProbeClient.isRunning(host)) return
        val selected = getSelectedRoute()
        if (selected.id != homeSpeedConnectedRouteId) return
        val now = SystemClock.elapsedRealtime()
        val token = TelegramSessionStore.read(host) ?: return
        val config = getSubscriptionContent() ?: return
        val route = getRouteCatalog().firstOrNull { it.id == selected.id } ?: return
        val metricKey = currentHomeRouteMetricKey(selected.id) ?: return
        val retryDelay = HomeMetricPresentation.speedRefreshDelayMillis(
            hasSample = homeSpeedSample?.first == metricKey,
            failed = homeSpeedErrorKey == metricKey && homeSpeedError != null,
        )
        if (homeSpeedAttemptAt > 0L && now - homeSpeedAttemptAt < retryDelay) return
        if (route.engine == TunnelEngine.LIBBOX && !isHomeApplicationRoutedByTunnel(config)) {
            homeSpeedSample = null; homeSpeedError = null; homeSpeedErrorKey = null; homeSpeedBypassedKey = metricKey
            updateQualityStrip(); return
        }
        if (route.engine == TunnelEngine.LIBBOX && !ConnectVpnService.isRunning()) return
        if (route.engine == TunnelEngine.AMNEZIAWG && !AwgTunnelController.isRunning()) return
        homeSpeedAttemptAt = now
        homeSpeedInFlight = true
        homeSpeedError = null
        homeSpeedErrorKey = metricKey
        homeSpeedBypassedKey = null
        val generation = homeSpeedGeneration
        val transferActive = {
            generation == homeSpeedGeneration && homeSpeedForeground && homeSpeedOnHome &&
                !isTemporaryAwgMeasurement() && !RouteProbeClient.isRunning(host) && getCurrentPhase() == VpnPhase.CONNECTED &&
                (if (route.engine == TunnelEngine.AMNEZIAWG) AwgTunnelController.isRunning() else ConnectVpnService.isRunning())
        }
        val stillCurrent = {
            transferActive() && SelectedRouteStore(host).read().id == selected.id &&
                TelegramSessionStore.read(host) == token && currentHomeRouteMetricKey(selected.id) == metricKey &&
                (route.engine != TunnelEngine.LIBBOX || isHomeApplicationRoutedByTunnel(config))
        }
        updateQualityStrip()
        homeSpeedFuture = LatencyExecutor.pool.submit {
            val result = runCatching {
                RouteProxyProbe.measureSystemDownload(token, stillCurrent, sampleMillis = RouteProxyProbe.QUICK_DOWNLOAD_MILLIS, transferActive = transferActive)
            }
            host.runOnUiThread {
                if (generation != homeSpeedGeneration) return@runOnUiThread
                homeSpeedInFlight = false; homeSpeedFuture = null
                if (!stillCurrent() || host.isFinishing || host.isDestroyed) {
                    if (!host.isFinishing && !host.isDestroyed && getCurrentPhase() == VpnPhase.CONNECTED &&
                        SelectedRouteStore(host).read().id == selected.id &&
                        currentHomeRouteMetricKey(selected.id) == metricKey && route.engine == TunnelEngine.LIBBOX &&
                        !isHomeApplicationRoutedByTunnel(config)
                    ) {
                        homeSpeedSample = null; homeSpeedError = null; homeSpeedErrorKey = null; homeSpeedBypassedKey = metricKey
                        updateQualityStrip()
                    }
                    return@runOnUiThread
                }
                result.getOrNull()?.takeIf { it > 0L }?.let { speed ->
                    homeSpeedError = null; homeSpeedErrorKey = null; homeSpeedSample = metricKey to speed
                    val latency = lastRouteLatency?.takeIf { it.first == metricKey }?.second
                    if (latency != null) RouteQualityStore.write(host, RouteProbeSample(selected.id, latency, speed))
                } ?: run {
                    homeSpeedError = result.exceptionOrNull()?.let { RouteProbePresentation.failure(it, AppLanguage.current(host) == AppLanguage.EN) }
                    homeSpeedErrorKey = metricKey.takeIf { homeSpeedError != null }
                }
                updateQualityStrip()
            }
        }
    }

    private fun isSystemTunnelActive(): Boolean {
        val connectivity = host.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    fun close() {
        cancelHomeRouteDownload()
        cancelSelectedRouteLatency()
        stopMapTrafficTick()
        stopSelectedRouteLatencyTick()
        selectedRouteLatencyHandler.removeCallbacksAndMessages(null)
        mapTrafficHandler.removeCallbacksAndMessages(null)
        setMapTrafficActive(false)
    }

    private class TrafficUsageBar(
        context: Context,
        private val uploaded: Long,
        private val downloaded: Long,
        private val quota: Long,
        description: String,
    ) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val track = android.graphics.RectF()
        private val fill = android.graphics.RectF()
        init {
            contentDescription = description
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val h = height.toFloat()
            if (h <= 0f || width <= 0) return
            track.set(0f, 0f, width.toFloat(), h)
            paint.color = DeyttUi.SURFACE_2
            canvas.drawRoundRect(track, h / 2f, h / 2f, paint)
            val total = uploaded.toDouble().coerceAtLeast(0.0) + downloaded.toDouble().coerceAtLeast(0.0)
            val fraction = if (total <= 0.0) 0.0 else if (quota > 0) (total / quota.toDouble()).coerceIn(0.0, 1.0) else 1.0
            val filledWidth = (width * fraction).toFloat()
            if (filledWidth <= 0f) return
            fill.set(0f, 0f, filledWidth, h)
            val downloadedEnd = filledWidth * (downloaded.toDouble().coerceAtLeast(0.0) / total).toFloat()
            canvas.save(); canvas.clipRect(0f, 0f, downloadedEnd, h); paint.color = DeyttUi.SKY; canvas.drawRoundRect(fill, h / 2f, h / 2f, paint); canvas.restore()
            canvas.save(); canvas.clipRect(downloadedEnd, 0f, filledWidth, h); paint.color = DeyttUi.MINT; canvas.drawRoundRect(fill, h / 2f, h / 2f, paint); canvas.restore()
        }
    }
}
