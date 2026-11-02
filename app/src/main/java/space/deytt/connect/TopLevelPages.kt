package space.deytt.connect

import android.app.Dialog
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.TextWatcher
import android.text.Editable
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.AMBER
import space.deytt.connect.DeyttUi.BLUE
import space.deytt.connect.DeyttUi.BLUE_DEEP
import space.deytt.connect.DeyttUi.SKY
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.SURFACE
import space.deytt.connect.DeyttUi.SURFACE_2
import space.deytt.connect.DeyttUi.SELECTED
import space.deytt.connect.DeyttUi.SELECTED_LINE
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.glassButtonBackground
import space.deytt.connect.DeyttUi.glassRowBackground
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.liquidGlassButton
import space.deytt.connect.DeyttUi.mapPanel
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.prefersReducedMotion
import space.deytt.connect.DeyttUi.ripple
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.routePairMark
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.AppLanguage.uiCopy

private const val UPDATE_CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L
private const val KEY_LAST_UPDATE_CHECK = "last_official_update_check_ms"
private const val KEY_LAST_OFFERED_UPDATE = "last_offered_official_update"

internal class TopLevelViewPagerAdapter(
    private val shouldDeferRouteRefresh: () -> Boolean = { false },
    private val createPage: (Int) -> View,
) : RecyclerView.Adapter<TopLevelViewPagerAdapter.PageHolder>() {
    private val pages = arrayOfNulls<View>(PAGE_COUNT)
    private val routeRefreshGate = RoutePageRefreshGate()

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val page = pages[position] ?: createPage(position).also { pages[position] = it }
        if (page.parent !== holder.container) {
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.removeAllViews()
            holder.container.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.container.removeAllViews()
        super.onViewRecycled(holder)
    }

    fun cachedPages(): List<Pair<Int, View>> = pages.mapIndexedNotNull { index, view ->
        view?.let { index to it }
    }

    fun refresh(position: Int) {
        if (position !in pages.indices) return
        if (position == ROUTE_PAGE && !routeRefreshGate.request(shouldDeferRouteRefresh())) return
        refreshNow(position)
    }

    fun flushDeferredRouteRefresh() {
        if (routeRefreshGate.takeIfReady(shouldDeferRouteRefresh())) refreshNow(ROUTE_PAGE)
    }

    private fun refreshNow(position: Int) {
        pages[position]?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pages[position] = null
        notifyItemChanged(position)
    }

    class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private companion object {
        const val PAGE_COUNT = 4
        const val ROUTE_PAGE = 1
    }
}

internal class RoutePageRefreshGate {
    private var deferred = false

    fun request(busy: Boolean): Boolean {
        if (!busy) {
            deferred = false
            return true
        }
        deferred = true
        return false
    }

    fun takeIfReady(busy: Boolean): Boolean {
        if (busy || !deferred) return false
        deferred = false
        return true
    }
}

internal class PrimaryPages(
    private val host: MainActivity,
    private val onRouteDiagnosticsIdle: () -> Unit = {},
) {
    private data class ProbeRouteRow(
        val route: DeyttRoute,
        val status: TextView,
        val progress: InlineMetricProgress,
        val surface: View,
    )
    private data class CountryProbeState(
        val code: String,
        val routes: List<DeyttRoute>,
        val rows: Map<String, ProbeRouteRow>,
        val summary: TextView,
        val summaryProgress: InlineMetricProgress,
        val samples: MutableMap<String, RouteProbeSample> = mutableMapOf(),
        val pingTraces: MutableMap<String, MutableList<Long>> = mutableMapOf(),
        val errors: MutableMap<String, String> = mutableMapOf(),
        var speedCheckEnabled: Boolean = false,
        var selectionAtStart: String? = null,
        var cancelled: Boolean = false,
        var isChecking: Boolean = false,
    )

    private val diagnosticExecutor = Executors.newSingleThreadExecutor()
    private val pendingCountries = linkedMapOf<String, CountryProbeState>()
    private var awgMeasurementRunning = false
    private var activeAwgCountry: CountryProbeState? = null
    private var diagnosticForeground = true
    private var awgCompletion: ((Boolean) -> Unit)? = null
    private var updateBanner: LinearLayout? = null
    private var updateBannerText: TextView? = null
    private var updateBannerProgress: ProgressBar? = null
    private var updateDownloadInFlight = false
    private var closed = false
    private val expandedCountryCodes = mutableSetOf<String>()
    private var routeScrollY = 0
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val pairingExecutor = Executors.newSingleThreadExecutor()
    private val updateHandler = Handler(Looper.getMainLooper())
    private val nextCountryCheck = object : Runnable {
        override fun run() {
            if (closed || host.isDestroyed || !diagnosticForeground) return
            if (RouteProbeClient.isRunning(host) || routeProbeStates.isNotEmpty() || awgMeasurementRunning) {
                if (pendingCountries.isNotEmpty()) updateHandler.postDelayed(this, 100L)
                return
            }
            val next = pendingCountries.values.firstOrNull()
            if (next != null) {
                pendingCountries.remove(next.code)
                startCountryProbe(next)
            } else onRouteDiagnosticsIdle()
        }
    }
    private val profileFlows = ProfileFlows(host, ::showTelegramPairing, ::showLinkedTelegramAccount)
    private var latestRelease: ReleaseInfo? = null
    private var downloadedUpdate: File? = null
    private var updateCheckInFlight = false
    private var updateStatus: TextView? = null
    private var updateButton: TextView? = null
    private var updateStatusText = "Готово проверить официальный релиз."
    private val routeProbeStates = mutableMapOf<String, CountryProbeState>()
    private val updateCycle = object : Runnable {
        override fun run() {
            checkForUpdate(force = false)
            updateHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS)
        }
    }

    fun create(position: Int): View = when (position) {
        1 -> routePage()
        2 -> profilePage()
        else -> settingsPage()
    }

    fun createUpdateBanner(): View {
        val label = host.text("", 12f, TEXT).apply { setPadding(host.dp(18), host.dp(10), host.dp(18), host.dp(8)) }
        val progress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(DeyttUi.SKY)
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
        }
        updateBannerText = label
        updateBannerProgress = progress
        return LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            visibility = View.GONE
            addView(label)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(3)))
            setOnClickListener { if (!updateDownloadInFlight) updateAction() }
            updateBanner = this
        }
    }

    private fun showUpdateProgress(label: String, percent: Int? = null) {
        updateBanner?.visibility = View.VISIBLE
        updateBannerText?.text = label
        updateBannerProgress?.isIndeterminate = percent == null
        percent?.let { updateBannerProgress?.progress = it }
    }

    fun close() {
        closed = true
        awgCompletion?.also { awgCompletion = null }?.invoke(false)
        updateHandler.removeCallbacks(updateCycle)
        updateHandler.removeCallbacks(nextCountryCheck)
        updateExecutor.shutdownNow()
        pairingExecutor.shutdownNow()
        diagnosticExecutor.shutdownNow()
        routeProbeStates.values.forEach { it.cancelled = true }
        pendingCountries.clear()
        if (routeProbeStates.isNotEmpty()) RouteProbeClient.cancel(host)
        profileFlows.close()
    }

    fun startForegroundUpdates() {
        diagnosticForeground = true
        if (!RouteProbeClient.isRunning(host) && !awgMeasurementRunning) routeProbeStates.clear()
        updateHandler.removeCallbacks(updateCycle)
        checkForUpdate(force = false)
        updateHandler.postDelayed(updateCycle, UPDATE_CHECK_INTERVAL_MS)
        launchNextCountry()
    }

    fun hasActiveRouteDiagnostics(): Boolean = RouteProbeClient.isRunning(host) ||
        routeProbeStates.isNotEmpty() || pendingCountries.isNotEmpty() || awgMeasurementRunning || activeAwgCountry != null

    fun stopForegroundUpdates() {
        diagnosticForeground = false
        pendingCountries.clear()
        routeProbeStates.values.forEach { it.cancelled = true }
        activeAwgCountry?.let { cancelCountryProbe(it) }
        if (awgMeasurementRunning) host.cancelAwgMeasurement()
        updateHandler.removeCallbacks(updateCycle)
        updateHandler.removeCallbacks(nextCountryCheck)
    }

    fun cancelRouteDiagnosticsForSelection() {
        pendingCountries.clear()
        routeProbeStates.values.forEach { it.cancelled = true }
        activeAwgCountry?.cancelled = true
        if (RouteProbeClient.isRunning(host)) RouteProbeClient.cancel(host)
    }

    private fun scrollPage(content: LinearLayout): ScrollView = ScrollView(host).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun routePage(): View {
        val routes = host.getCachedRouteCatalog()
        val selectedId = host.getCachedSelectedRoute().id
        val countryGroups = RouteCatalog.countryGroups(routes)
        expandedCountryCodes.retainAll(countryGroups.mapTo(mutableSetOf()) { it.first })
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("", "Маршруты"))
        root.addView(spacer(8, host))
        root.addView(host.text(
            copy(
                "Откройте страну — проверим отклик её маршрутов и скорость по очереди. Дождитесь результата.",
                "Open a country to check its routes. Response checks start together; speed tests follow one by one.",
            ),
            13f,
            MUTED,
        )
            .apply { setPadding(0, 0, 0, host.dp(18)) })

        val subscriptionRow = host.row(
            copy("Обновить подписку", "Refresh subscription"),
            copy("Загрузить доступные маршруты", "Load available routes"),
            "SUBSCRIPTION_ICON",
        )
        val labels = (subscriptionRow as? ViewGroup)?.getChildAt(1) as? LinearLayout
        val subtitleView = labels?.getChildAt(1) as? TextView

        fun applyRefreshState(animateSuccess: Boolean = false) {
            if (SubscriptionRefreshState.inFlight) {
                val phaseText = SubscriptionRefreshState.phaseDescription.ifBlank {
                    host.uiCopy(copy("Обновляем подписку и маршруты…", "Refreshing subscription and routes…"))
                }
                subtitleView?.text = phaseText
                subtitleView?.setTextColor(DeyttUi.SKY)
                return
            }

            val elapsedSinceSuccess = android.os.SystemClock.elapsedRealtime() - SubscriptionRefreshState.lastSuccessTimestamp
            if (SubscriptionRefreshState.lastSuccessTimestamp > 0L && elapsedSinceSuccess < 4000L) {
                val successMsg = SubscriptionRefreshState.lastMessage.ifBlank {
                    host.uiCopy(copy("✓ Маршруты обновлены", "✓ Routes updated"))
                }
                subtitleView?.text = successMsg
                subtitleView?.setTextColor(DeyttUi.MINT)
                if (animateSuccess && !host.prefersReducedMotion()) {
                    subscriptionRow.animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .setDuration(160L)
                        .withEndAction {
                            subscriptionRow.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(220L)
                                .start()
                        }
                        .start()
                }
                subscriptionRow.postDelayed({
                    if (!SubscriptionRefreshState.inFlight &&
                        (android.os.SystemClock.elapsedRealtime() - SubscriptionRefreshState.lastSuccessTimestamp >= 3900L)
                    ) {
                        subtitleView?.text = host.uiCopy(copy("Загрузить доступные маршруты", "Load available routes"))
                        subtitleView?.setTextColor(MUTED)
                    }
                }, (4000L - elapsedSinceSuccess).coerceAtLeast(100L))
                return
            }

            if (SubscriptionRefreshState.currentPhase == SubscriptionRefreshState.Phase.FAILURE) {
                subtitleView?.text = SubscriptionRefreshState.lastMessage.ifBlank {
                    host.uiCopy(copy("Ошибка обновления", "Update failed"))
                }
                subtitleView?.setTextColor(DeyttUi.CORAL)
                return
            }

            subtitleView?.text = host.uiCopy(copy("Загрузить доступные маршруты", "Load available routes"))
            subtitleView?.setTextColor(MUTED)
        }

        subscriptionRow.setOnClickListener {
            if (SubscriptionRefreshState.inFlight) return@setOnClickListener
            val url = SubscriptionClient.getSavedSubscriptionUrl(host)
            val token = TelegramSessionStore.read(host)
            if (url == null && token == null) {
                host.openSetup()
                return@setOnClickListener
            }
            host.refreshSubscriptionManually()
        }

        val refreshListener = {
            host.runOnUiThread {
                if (!host.isFinishing && !host.isDestroyed) {
                    val isSuccess = SubscriptionRefreshState.currentPhase == SubscriptionRefreshState.Phase.SUCCESS
                    applyRefreshState(animateSuccess = isSuccess)
                }
            }
        }
        SubscriptionRefreshState.addListener(refreshListener)
        subscriptionRow.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                applyRefreshState(false)
            }
            override fun onViewDetachedFromWindow(v: View) {
                SubscriptionRefreshState.removeListener(refreshListener)
            }
        })
        applyRefreshState(false)
        root.addView(subscriptionRow)
        root.addView(spacer(14, host))

        root.addView(host.sectionLabel("быстрый выбор"))
        val quickGroup = routeGroup()
        routes.firstOrNull { it.protocol == RouteProtocol.AUTO }?.let { route ->
            val isSelected = selectedId == route.id
            val actionText = if (isSelected) copy("выбрано", "selected") else copy("выбрать", "select")
            val autoRow = host.row("Автоподбор", "Выберем доступный узел", "ROUTE_AUTO", actionText, emphasis = isSelected)
                .apply {
                    setOnClickListener { host.selectRoute(route) }
                }
            appendToGroup(quickGroup, autoRow)
        }
        routes.firstOrNull { it.protocol == RouteProtocol.RU_DE || it.protocol == RouteProtocol.LTE }?.let { route ->
            appendToGroup(quickGroup, buildLteWhitelistRow(route, selectedId == route.id))
        }
        if (quickGroup.childCount > 0) root.addView(quickGroup)

        root.addView(spacer(24, host))
        root.addView(host.sectionLabel(copy("Страны", "Countries")))
        countryGroups.forEach { (code, countryRoutes) ->
            val networkRoutes = countryRoutes.filter { it.engine == TunnelEngine.LIBBOX }
                .sortedBy { routeProtocolOrder(it.protocol) }
            val awgRoutes = countryRoutes.filter { it.engine == TunnelEngine.AMNEZIAWG }
            val first = countryRoutes.first()
            val cachedSamples = countryRoutes.mapNotNull { route -> RouteQualityStore.read(host, route.id) }
            val measuredCached = cachedSamples.filter { it.latencyMillis?.let(RouteProbeStatistics::isValidLatencyMillis) == true }
            val bestCachedId: String? = when {
                measuredCached.isEmpty() -> null
                measuredCached.size == 1 -> measuredCached.first().routeId
                else -> RouteProbeScoring.bestRouteId(cachedSamples) ?: measuredCached.minByOrNull { it.latencyMillis!! }?.routeId
            }
            val worstCachedId: String? = when {
                measuredCached.size < 2 -> null
                else -> measuredCached.filter { it.routeId != bestCachedId }.maxByOrNull { it.latencyMillis!! }?.routeId
            }
            val protocolSummary = countryRoutes.map { route ->
                when (route.protocol) {
                    RouteProtocol.AWG15 -> "awg1.5"
                    RouteProtocol.AWG31 -> "awg3.1"
                    else -> when (route.engine) {
                        TunnelEngine.LIBBOX -> route.protocol.title
                        TunnelEngine.AMNEZIAWG -> route.protocol.title
                        TunnelEngine.OPENFLUX -> route.protocol.title
                    }
                }
            }.distinct().joinToString(" · ")
            val headerDivider = View(host).apply {
                setBackgroundColor(LINE)
                visibility = if (code in expandedCountryCodes) View.VISIBLE else View.GONE
            }
            val detail = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (code in expandedCountryCodes) View.VISIBLE else View.GONE
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
            }
            val summary = host.text(
                cachedLatencySummary(cachedSamples) ?: copy("Отклик: —", "Response: —"),
                11f,
                MUTED,
            ).apply {
                maxLines = 2
                setPadding(0, host.dp(8), 0, host.dp(8))
            }
            val summaryProgress = InlineMetricProgress(summary)
            summary.visibility = View.GONE
            val rowById = linkedMapOf<String, ProbeRouteRow>()
            countryRoutes.forEachIndexed { index, route ->
                if (index > 0) detail.addView(View(host).apply { setBackgroundColor(LINE) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)).apply {
                        leftMargin = host.dp(58)
                        rightMargin = host.dp(16)
                    })
                val isAwg = route.engine == TunnelEngine.AMNEZIAWG
                val rowTitle = if (isAwg) route.profileName ?: route.protocol.title else route.protocol.title
                val rowSubtitle = if (isAwg) "${route.shortMark()} · ${route.protocol.title}" else route.protocol.detail
                val cached = RouteQualityStore.read(host, route.id)
                val isBest = route.id == bestCachedId
                val isWorst = route.id == worstCachedId
                val grade = when {
                    isBest -> RouteGrade.GOOD
                    isWorst -> RouteGrade.POOR
                    cached?.latencyMillis != null -> RouteGrade.MEDIUM
                    else -> RouteGrade.UNRATED
                }
                val metric = host.text(
                    cached?.let { sampleLabel(it, isBest, isWorst) }
                        ?: copy("Отклик: — · Скорость: —", "Response: — · Speed: —"),
                    11f,
                    gradeColor(grade),
                ).apply {
                    maxLines = 2
                    setPadding(0, host.dp(4), 0, host.dp(8))
                    if (isBest || isWorst) setTypeface(null, android.graphics.Typeface.BOLD)
                }
                val progress = InlineMetricProgress(metric)
                val leading = when {
                    isAwg && route.protocol == RouteProtocol.AWG31 -> "AWG31_MARK"
                    isAwg -> "AWG_MARK"
                    else -> protocolMark(route.protocol)
                }
                val item = host.row(rowTitle, rowSubtitle, leading, "",
                    emphasis = route.id == selectedId)
                item.background = routeRankBackground(grade, route.id == selectedId)
                val protocolDrawable = when (route.protocol) {
                    RouteProtocol.VLESS -> R.drawable.ic_xray
                    RouteProtocol.TROJAN -> R.drawable.ic_trojan
                    RouteProtocol.HYSTERIA2 -> R.drawable.ic_hysteria
                    RouteProtocol.TUIC -> R.drawable.ic_tuic
                    else -> null
                }
                protocolDrawable?.let { resource ->
                    val old = item.getChildAt(0)
                    val params = old.layoutParams
                    item.removeViewAt(0)
                    item.addView(android.widget.ImageView(host).apply {
                        setImageResource(resource)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setPadding(host.dp(5), host.dp(5), host.dp(5), host.dp(5))
                        contentDescription = route.protocol.title
                    }, 0, params)
                }
                val metricsColumn = item.getChildAt(1) as LinearLayout
                metric.setPadding(0, host.dp(4), 0, host.dp(2))
                metricsColumn.addView(metric)
                item.setOnClickListener { host.selectRoute(route) }
                val probeRow = ProbeRouteRow(route, metric, progress, item)
                if (isAwg) {
                    metric.text = cached?.let { sampleLabel(it, isBest, isWorst) }
                        ?: copy("Отклик: — · Скорость: —", "Response: — · Speed: —")
                    metric.setOnClickListener { startAwgProbe(probeRow) }
                }
                rowById[route.id] = probeRow
                detail.addView(item)
            }
            val countryState = CountryProbeState(code, networkRoutes + awgRoutes, rowById, summary, summaryProgress)
            val countryHeader = host.row(
                if (code == "AWG_UNKNOWN") "Регион не указан" else first.country,
                if (protocolSummary.isBlank()) "Маршруты не найдены" else protocolSummary,
                first.flag,
                "⌄",
                emphasis = countryRoutes.any { it.id == selectedId },
            ).apply {
                contentDescription = host.uiCopy("Раскрыть выходы ${first.country}; откроется проверка HTTP маршрутов")
                background = ColorDrawable(Color.TRANSPARENT)
                findViewWithTag<android.widget.ImageView>("country-chevron")?.rotation = if (code in expandedCountryCodes) 270f else 90f
                setOnClickListener {
                    val expanded = detail.visibility != View.VISIBLE
                    detail.visibility = if (expanded) View.VISIBLE else View.GONE
                    headerDivider.visibility = if (expanded) View.VISIBLE else View.GONE
                    if (expanded) expandedCountryCodes.add(code) else expandedCountryCodes.remove(code)
                    findViewWithTag<android.widget.ImageView>("country-chevron")?.rotation = if (expanded) 270f else 90f
                    if (expanded) startCountryProbe(countryState)
                    else cancelCountryProbe(countryState)
                }
            }
            val countryGroup = routeGroup()
            val countrySelected = countryRoutes.any { it.id == selectedId }
            countryGroup.background = host.glassRowBackground(countrySelected, 22f)
            countryGroup.clipToOutline = true
            countryGroup.addView(countryHeader)
            countryGroup.addView(headerDivider)
            countryGroup.addView(detail)
            root.addView(countryGroup)
            root.addView(spacer(8, host))
        }
        if (routes.isEmpty()) {
            root.addView(host.note("Пока нет маршрутов. Обновите подписку по ссылке, с которой открывали приложение.", AMBER))
        }
        root.addView(spacer(18, host))
        return scrollPage(root).apply {
            setOnScrollChangeListener { _, _, scrollY, _, _ -> routeScrollY = scrollY }
            post { scrollTo(0, routeScrollY) }
        }
    }

    private fun routeProtocolOrder(protocol: RouteProtocol): Int = when (protocol) {
        RouteProtocol.VLESS -> 0
        RouteProtocol.TROJAN -> 1
        RouteProtocol.HYSTERIA2 -> 2
        RouteProtocol.TUIC -> 3
        else -> 3
    }

    private fun protocolMark(protocol: RouteProtocol): String = when (protocol) {
        RouteProtocol.VLESS -> "VL"
        RouteProtocol.TROJAN -> "◇"
        RouteProtocol.HYSTERIA2 -> "H2"
        RouteProtocol.TUIC -> "TU"
        RouteProtocol.LTE -> "LTE"
        RouteProtocol.OPENFLUX -> "DE"
        else -> "·"
    }

    private fun DeyttRoute.shortMark(): String = when (countryCode) {
        "NL" -> "NL"
        "DE" -> "DE"
        "RU" -> "RU"
        "FI" -> "FI"
        else -> profileName ?: "AWG"
    }

    private fun startCountryProbe(country: CountryProbeState) {
        if (country.routes.isEmpty() || closed || !diagnosticForeground) return
        if (routeProbeStates.values.any { it === country } || activeAwgCountry === country) {
            if (country.cancelled) {
                pendingCountries[country.code] = country
                launchNextCountry()
            }
            return
        }
        country.cancelled = false
        if (RouteProbeClient.isRunning(host) || routeProbeStates.isNotEmpty() || awgMeasurementRunning) {
            pendingCountries[country.code] = country
            country.isChecking = false
            country.summary.text = copy("В очереди · дождитесь предыдущей проверки", "Queued · waiting for the current check")
            country.summaryProgress.setLoading(false)
            country.rows.values.forEach {
                it.progress.setLoading(false)
                it.status.text = copy("В очереди…", "Queued…")
            }
            launchNextCountry()
            return
        }
        country.isChecking = true
        country.selectionAtStart = SelectedRouteStore(host).read().id
        country.samples.clear()
        country.pingTraces.clear()
        country.errors.clear()
        country.summary.text = copy("Отклик: —", "Response: —")
        country.summary.contentDescription = country.summary.text
        country.summaryProgress.setLoading(true)
        if (VpnService.prepare(host) != null) {
            country.isChecking = false
            country.summary.text = copy("Разрешите проверку маршрутов в Android", "Allow route checks in Android")
            country.summaryProgress.setLoading(false)
            if (host.requestRouteProbePermission { if (!country.cancelled) startCountryProbe(country) }) return
            country.isChecking = true
            country.summary.text = copy("Отклик: —", "Response: —")
            country.summaryProgress.setLoading(true)
        }
        val config = SubscriptionStore(host).readCurrent() ?: run {
            country.isChecking = false
            country.summary.text = copy("Подписка недоступна · обновите маршруты", "Subscription unavailable · refresh routes")
            country.summaryProgress.setLoading(false)
            return
        }
        val token = TelegramSessionStore.read(host)
        country.speedCheckEnabled = !token.isNullOrBlank()
        val selectedRouteId = SelectedRouteStore(host).read().id
        country.rows.values.forEach { row ->
            val isSelected = selectedRouteId == row.route.id
            row.surface.background = if (isSelected) {
                routeRankBackground(RouteGrade.UNRATED, selected = true)
            } else {
                ColorDrawable(Color.TRANSPARENT)
            }
            if (row.route.engine == TunnelEngine.OPENFLUX) {
                row.progress.setLoading(false)
                row.status.text = copy("Проверка после подключения", "Measure after connecting")
                return@forEach
            }
            if (row.route.engine == TunnelEngine.AMNEZIAWG) {
                row.progress.setLoading(false)
                row.status.text = copy("В очереди…", "Queued…")
                row.status.setTextColor(DeyttUi.MUTED)
                row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
                return@forEach
            }
            row.status.text = sampleLabel(RouteProbeSample(row.route.id, null, null))
            row.status.setTextColor(DeyttUi.SKY)
            row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
            row.progress.setLoading(true)
            country.pingTraces[row.route.id] = mutableListOf()
        }
        val networkRoutes = country.routes.filter { it.engine == TunnelEngine.LIBBOX }
        if (networkRoutes.isEmpty()) { startCountryAwgProbes(country); return }
        runCatching {
            RouteProbeClient.start(
                host,
                config,
                networkRoutes.map(DeyttRoute::configTag),
                RouteProbePreferences.method(host),
                token,
            )
        }.onSuccess { requestId -> routeProbeStates[requestId] = country }
            .onFailure {
                country.rows.values.forEach { row ->
                    row.progress.setLoading(false)
                    row.status.text = host.uiCopy("ошибка")
                    row.status.setTextColor(DeyttUi.CORAL)
                }
                startCountryAwgProbes(country)
            }
    }

    fun onRouteProbeEvent(event: Intent) {
        val requestId = event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty()
        val stage = event.getStringExtra(RouteProbeClient.EXTRA_STAGE).orEmpty()
        val complete = event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)
        val tag = event.getStringExtra(RouteProbeClient.EXTRA_ROUTE_TAG).orEmpty()
        val error = event.getStringExtra(RouteProbeClient.EXTRA_ERROR).orEmpty()
        android.util.Log.i("DeyttRouteProbe", "onRouteProbeEvent req=$requestId stage=$stage tag=$tag complete=$complete error=$error")
        val country = routeProbeStates[requestId] ?: run {
            if (complete) launchNextCountry()
            return
        }
        if (event.getStringExtra(RouteProbeClient.EXTRA_STAGE) == "cancelled") {
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                routeProbeStates.remove(requestId)
                country.cancelled = true
                country.rows.values.filter { it.route.engine == TunnelEngine.LIBBOX }.forEach { row ->
                    row.progress.setLoading(false)
                    val sample = country.samples[row.route.id]
                    if (sample?.latencyMillis == null && sample?.downloadBytesPerSecond == null) {
                        row.status.text = copy("Проверка отменена · откройте страну снова", "Measurement cancelled · reopen country")
                    }
                }
                launchNextCountry()
            }
            return
        }
        if (country.cancelled) {
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                routeProbeStates.remove(requestId)
                launchNextCountry()
            }
            return
        }
        if (tag.isBlank() && (stage == "accepted" || stage.startsWith("startup_"))) {
            val elapsed = event.getLongExtra(RouteProbeClient.EXTRA_ELAPSED_MILLIS, -1L)
            country.summary.text = copy("Подготавливаем проверку…", "Preparing route check…") +
                elapsed.takeIf { it >= 0L }?.let { " · ${it} мс" }.orEmpty()
            country.summary.contentDescription = country.summary.text
            country.summaryProgress.setLoading(true)
        }
        if (tag.isNotBlank()) {
            val row = country.rows.values.firstOrNull { it.route.configTag == tag }
            if (row != null) {
                val previous = country.samples[row.route.id] ?: RouteProbeSample(row.route.id, null, null)
                val stage = event.getStringExtra(RouteProbeClient.EXTRA_STAGE)
                    ?: if (!event.getStringExtra(RouteProbeClient.EXTRA_ERROR).isNullOrBlank()) "failed" else ""
                val pingTrace = country.pingTraces.getOrPut(row.route.id) { mutableListOf() }
                val reportedLatency = event.getLongExtra(RouteProbeClient.EXTRA_MILLISECONDS, -1L)
                    .takeIf(RouteProbeStatistics::isValidLatencyMillis)
                if (stage == "latency_sample") reportedLatency?.let(pingTrace::add)
                val latency = if (stage == "latency_sample") {
                    RouteProbeStatistics.medianLatencyMillis(pingTrace) ?: previous.latencyMillis
                }
                    else reportedLatency ?: previous.latencyMillis
                val reportedSpeed = event.getLongExtra(RouteProbeClient.EXTRA_BYTES_PER_SECOND, -1L)
                    .takeIf { it > 0L }
                val speed = if (stage == "download_progress") previous.downloadBytesPerSecond
                    else reportedSpeed ?: previous.downloadBytesPerSecond
                val sample = RouteProbeSample(row.route.id, latency, speed)
                country.samples[row.route.id] = sample
                val english = AppLanguage.current(host) == AppLanguage.EN
                event.getStringExtra(RouteProbeClient.EXTRA_ERROR)?.takeIf(String::isNotBlank)
                    ?.let { country.errors[row.route.id] = it }
                when (stage) {
                    "latency", "latency_sample", "latency_retry", "waiting_download" -> {
                        row.status.text = sampleLabel(sample)
                        row.status.setTextColor(DeyttUi.TEXT)
                        row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
                        row.progress.setLoading(true)
                    }
                    "download", "download_progress" -> {
                        row.status.text = RouteProbePresentation.speedProgress(
                            latency?.let(::formatLatency),
                            english,
                            reportedSpeed,
                        )
                        row.status.setTextColor(DeyttUi.SKY)
                        row.progress.setLoading(true)
                    }
                    "complete", "failed" -> {
                        row.progress.setLoading(false)
                        val baseText = sampleLabel(sample)
                        if (speed == null) {
                            val reason = country.errors[row.route.id]?.let {
                                RouteProbePresentation.failureMessage(it, english)
                            } ?: if (!country.speedCheckEnabled) {
                                copy("Войдите через Telegram, чтобы проверить скорость", "Link Telegram to measure speed")
                            } else {
                                copy("Скорость не измерена · нажмите страну для повтора", "Speed unavailable · reopen country to retry")
                            }
                            row.status.text = "$baseText · $reason"
                        } else {
                            row.status.text = baseText
                        }
                        row.status.setTextColor(DeyttUi.MUTED)
                        row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
                        RouteQualityStore.write(host, sample)
                        val selectedRouteId = SelectedRouteStore(host).read().id
                        applyCountryRanking(country, selectedRouteId)
                    }
                }
                event.getStringExtra(RouteProbeClient.EXTRA_ERROR)?.let { error ->
                    if (error.isNotBlank() && stage !in setOf("complete", "failed")) {
                        row.status.contentDescription = host.uiCopy(error)
                    } else if (stage in setOf("complete", "failed")) {
                        row.status.contentDescription = row.status.text
                    }
                }
                updateCountryLatencySummary(country)
            }
        }
        if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
            routeProbeStates.remove(requestId)
            val requestError = event.getStringExtra(RouteProbeClient.EXTRA_ERROR)
            if (!requestError.isNullOrBlank()) country.rows.values.forEach { row ->
                if (country.samples[row.route.id]?.downloadBytesPerSecond == null) {
                    row.progress.setLoading(false)
                    row.status.text = sampleLabel(country.samples[row.route.id] ?: RouteProbeSample(row.route.id, null, null)) +
                        " · " + RouteProbePresentation.failureMessage(requestError, AppLanguage.current(host) == AppLanguage.EN)
                }
            }
            updateCountryLatencySummary(country)
            startCountryAwgProbes(country)
        }
    }

    private fun finishCountryProbe(country: CountryProbeState) {
        if (country.cancelled || closed || !diagnosticForeground) { launchNextCountry(); return }
        country.isChecking = false
        country.summaryProgress.setLoading(false)
        val selectedRouteId = SelectedRouteStore(host).read().id
        applyCountryRanking(country, selectedRouteId)
        updateCountryLatencySummary(country)
        launchNextCountry()
    }

    private fun startCountryAwgProbes(country: CountryProbeState) {
        val rows = country.rows.values.filter { it.route.engine == TunnelEngine.AMNEZIAWG }
        android.util.Log.i("DeyttRouteProbe", "startCountryAwgProbes country=${country.code} rows=${rows.size} cancelled=${country.cancelled} closed=$closed diagForeground=$diagnosticForeground prepare=${VpnService.prepare(host) != null}")
        if (rows.isEmpty() || country.cancelled || closed || !diagnosticForeground) { finishCountryProbe(country); return }
        if (VpnService.prepare(host) != null && host.requestRouteProbePermission {
                if (!country.cancelled && !closed) startCountryAwgProbes(country)
            }
        ) return
        awgMeasurementRunning = true
        activeAwgCountry = country
        rows.forEach {
            it.status.text = sampleLabel(RouteProbeSample(it.route.id, null, null))
            it.status.contentDescription = copy("В очереди · временный VPN только для приложения", "Queued · temporary app-only VPN")
            it.progress.setLoading(true)
        }
        host.requestAwgMeasurements(rows.map { it.route }, onComplete = {
            android.util.Log.i("DeyttRouteProbe", "awg onComplete")
            awgMeasurementRunning = false
            activeAwgCountry = null
            rows.forEach { row ->
                row.progress.setLoading(false)
                val pendingStatus = row.status.text.toString() == sampleLabel(RouteProbeSample(row.route.id, null, null))
                if (!country.cancelled && row.route.id !in country.samples && pendingStatus) {
                    row.status.text = copy("Проверка недоступна · нажмите для повтора", "Measurement unavailable · tap to retry")
                }
            }
            finishCountryProbe(country)
        }, onRouteFailure = { route ->
            android.util.Log.w("DeyttRouteProbe", "awg onRouteFailure: ${route.id}")
            country.rows[route.id]?.let { row ->
                row.progress.setLoading(false)
                country.errors[route.id] = "no response"
                row.status.text = copy("Нет ответа · нажмите для повтора", "No response · tap to retry")
                row.status.setTextColor(DeyttUi.CORAL)
                val selectedRouteId = SelectedRouteStore(host).read().id
                applyCountryRanking(country, selectedRouteId)
            }
        }, onRouteStarting = { route ->
            android.util.Log.i("DeyttRouteProbe", "awg onRouteStarting: ${route.id}")
            country.rows[route.id]?.let { row ->
                row.status.text = sampleLabel(RouteProbeSample(route.id, null, null))
                row.status.contentDescription = copy("Подключаем AmneziaWG…", "Connecting AmneziaWG…")
                row.status.setTextColor(DeyttUi.SKY)
                row.progress.setLoading(true)
            }
        }, measure = { route, finished ->
            android.util.Log.i("DeyttRouteProbe", "awg measure: ${route.id}")
            val row = country.rows.getValue(route.id)
            measureAwgRow(row, country, finished)
        })
    }

    private fun cancelCountryProbe(country: CountryProbeState) {
        pendingCountries.remove(country.code)
        country.cancelled = true
        country.isChecking = false
        country.summaryProgress.setLoading(false)
        country.rows.values.forEach {
            it.progress.setLoading(false)
            val sample = country.samples[it.route.id]
            if (sample?.latencyMillis == null && sample?.downloadBytesPerSecond == null) {
                it.status.text = copy("Проверка отменена · откройте страну снова", "Measurement cancelled · reopen country")
            }
        }
        routeProbeStates.entries.firstOrNull { it.value === country }?.key?.let { RouteProbeClient.cancel(host, it) }
        if (activeAwgCountry === country) host.cancelAwgMeasurement()
        launchNextCountry()
    }

    private fun launchNextCountry() {
        updateHandler.removeCallbacks(nextCountryCheck)
        updateHandler.post(nextCountryCheck)
    }

    private fun copy(ru: String, en: String): String = if (AppLanguage.current(host) == AppLanguage.EN) en else ru

    private fun sampleLabel(sample: RouteProbeSample, isBest: Boolean = false, isWorst: Boolean = false): String =
        copy("Отклик: ", "Response: ") + (sample.latencyMillis?.let(::formatLatency) ?: "—") +
            "  ·  " + copy("Скорость: ", "Speed: ") + (sample.downloadBytesPerSecond?.takeIf { it > 0L }?.let(::formatSpeed) ?: "—") +
            when {
                isBest -> copy(" · лучший выбор", " · best choice")
                isWorst -> copy(" · худший отклик", " · slowest response")
                else -> ""
            }

    private fun lteRenewalText(): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, 1)
        val ruMonth = when (cal.get(java.util.Calendar.MONTH)) {
            java.util.Calendar.JANUARY -> "января"
            java.util.Calendar.FEBRUARY -> "февраля"
            java.util.Calendar.MARCH -> "марта"
            java.util.Calendar.APRIL -> "апреля"
            java.util.Calendar.MAY -> "мая"
            java.util.Calendar.JUNE -> "июня"
            java.util.Calendar.JULY -> "июля"
            java.util.Calendar.AUGUST -> "августа"
            java.util.Calendar.SEPTEMBER -> "сентября"
            java.util.Calendar.OCTOBER -> "октября"
            java.util.Calendar.NOVEMBER -> "ноября"
            java.util.Calendar.DECEMBER -> "декабря"
            else -> "числа"
        }
        val enMonth = when (cal.get(java.util.Calendar.MONTH)) {
            java.util.Calendar.JANUARY -> "Jan 1"
            java.util.Calendar.FEBRUARY -> "Feb 1"
            java.util.Calendar.MARCH -> "Mar 1"
            java.util.Calendar.APRIL -> "Apr 1"
            java.util.Calendar.MAY -> "May 1"
            java.util.Calendar.JUNE -> "Jun 1"
            java.util.Calendar.JULY -> "Jul 1"
            java.util.Calendar.AUGUST -> "Aug 1"
            java.util.Calendar.SEPTEMBER -> "Sep 1"
            java.util.Calendar.OCTOBER -> "Oct 1"
            java.util.Calendar.NOVEMBER -> "Nov 1"
            java.util.Calendar.DECEMBER -> "Dec 1"
            else -> "the 1st"
        }
        return copy("0 / 10 ГБ · до 1 $ruMonth", "0 / 10 GB · until $enMonth")
    }

    private fun buildLteWhitelistRow(route: DeyttRoute, selected: Boolean): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = host.dp(64)
            setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
            background = host.glassRowBackground(selected, 22f)
            isClickable = true
            isFocusable = true
            foreground = host.ripple(22f)
            host.pressFeedback(this, .99f)
            setOnClickListener { host.selectRoute(route) }
        }
        val leadingMark = host.routePairMark(selected)
        root.addView(leadingMark, LinearLayout.LayoutParams(host.dp(40), host.dp(40)).apply { marginEnd = host.dp(10) })

        val middle = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            val title = host.text(copy("LTE · белые списки", "LTE · whitelist"), 15f, TEXT, android.graphics.Typeface.BOLD)
            addView(title)

            val barTrack = FrameLayout(host).apply {
                background = host.rounded(0x28FFFFFF, 3f, Color.TRANSPARENT)
                val fill = View(host).apply {
                    val gd = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(0xFF38BDF8.toInt(), 0xFF10B981.toInt())
                    )
                    gd.cornerRadius = host.dp(3).toFloat()
                    background = gd
                }
                addView(fill, FrameLayout.LayoutParams(host.dp(18), ViewGroup.LayoutParams.MATCH_PARENT))
            }
            addView(barTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(4)).apply {
                topMargin = host.dp(5)
                bottomMargin = host.dp(4)
                marginEnd = host.dp(8)
            })

            val sub = host.text(lteRenewalText(), 11f, MUTED)
            addView(sub)
        }
        root.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val action = host.text(
            if (selected) copy("выбрано", "selected") else copy("выбрать", "select"),
            12f,
            if (selected) DeyttUi.SKY else DeyttUi.TEXT,
            android.graphics.Typeface.BOLD
        ).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minWidth = host.dp(60)
            maxWidth = host.dp(84)
            minHeight = host.dp(36)
            setPadding(host.dp(8), host.dp(6), host.dp(8), host.dp(6))
            background = host.glassRowBackground(selected, 18f)
            isClickable = false
            isFocusable = false
        }
        root.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = host.dp(8)
        })
        return root
    }

    private fun routeRankBackground(grade: RouteGrade, selected: Boolean): Drawable {
        val cornerRadius = host.dp(14).toFloat()
        val insetH = host.dp(6)
        val insetV = host.dp(2)

        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
        }

        when {
            selected && grade == RouteGrade.GOOD -> {
                gd.setColor(0x3010B981.toInt())
                gd.setStroke(host.dp(1), 0x9534D399.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            selected && grade == RouteGrade.POOR -> {
                gd.setColor(0x28EF4444.toInt())
                gd.setStroke(host.dp(1), 0x90F87171.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            selected -> {
                gd.setColor(0x283B82F6.toInt())
                gd.setStroke(host.dp(1), 0x8060A5FA.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            grade == RouteGrade.GOOD -> {
                gd.setColor(0x2010B981.toInt())
                gd.setStroke(host.dp(1), 0x6034D399.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            grade == RouteGrade.POOR -> {
                gd.setColor(0x1CEF4444.toInt())
                gd.setStroke(host.dp(1), 0x50F87171.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            else -> return ColorDrawable(Color.TRANSPARENT)
        }
    }

    private fun cachedLatencySummary(samples: List<RouteProbeSample>): String? =
        RouteProbeStatistics.latencySummary(samples.mapNotNull { it.latencyMillis })?.let(::latencySummaryLabel)

    private fun latencySummaryLabel(summary: RouteLatencySummary): String =
        if (AppLanguage.current(host) == AppLanguage.EN) {
            "Response · best ${summary.bestMillis} · avg ${summary.averageMillis} · worst ${summary.worstMillis} ms"
        } else {
            "Отклик · лучший ${summary.bestMillis} · средний ${summary.averageMillis} · худший ${summary.worstMillis} мс"
        }

    private fun updateCountryLatencySummary(country: CountryProbeState) {
        val summary = RouteProbeStatistics.latencySummary(country.samples.values.mapNotNull { it.latencyMillis })
        val summaryLabel = summary?.let(::latencySummaryLabel)
        country.summary.text = when {
            country.isChecking && summaryLabel != null -> summaryLabel + copy(" · проверка идёт", " · check in progress")
            country.isChecking -> copy("Отклик: — · проверяем маршруты", "Response: — · checking routes")
            summaryLabel != null -> summaryLabel + copy(" · готово", " · complete")
            country.cancelled -> copy("Проверка отменена · откройте страну снова", "Check cancelled · reopen the country")
            else -> copy("Нет результата · откройте страну для повтора", "No result · reopen the country to retry")
        }
        country.summary.contentDescription = country.summary.text
        country.summaryProgress.setLoading(country.isChecking)
    }

    private fun applyCountryRanking(country: CountryProbeState, selectedRouteId: String) {
        val samples = country.samples.values.toList()
        val measured = samples.filter { it.latencyMillis?.let(RouteProbeStatistics::isValidLatencyMillis) == true }

        val bestRouteId: String? = when {
            measured.isEmpty() -> null
            measured.size == 1 -> measured.first().routeId
            else -> {
                RouteProbeScoring.bestRouteId(samples)
                    ?: measured.minByOrNull { it.latencyMillis!! }?.routeId
            }
        }

        val worstRouteId: String? = when {
            measured.size < 2 -> null
            else -> {
                val completeWorst = RouteProbeScoring.worstRouteId(samples)
                if (completeWorst != null && completeWorst != bestRouteId) {
                    completeWorst
                } else {
                    val candidates = measured.filter { it.routeId != bestRouteId }
                    candidates.maxByOrNull { it.latencyMillis!! }?.routeId
                }
            }
        }

        val english = AppLanguage.current(host) == AppLanguage.EN

        country.rows.values.forEach { row ->
            if (row.route.engine == TunnelEngine.OPENFLUX) return@forEach
            // Never disrupt rows that are actively running a measurement
            if (row.progress.isLoading()) return@forEach

            val sample = country.samples[row.route.id]
            val hasError = country.errors[row.route.id] != null

            // During an active check, untouched queued rows stay neutral
            if (country.isChecking && sample == null && !hasError) {
                val isSelected = selectedRouteId == row.route.id
                row.surface.background = if (isSelected) {
                    routeRankBackground(RouteGrade.UNRATED, selected = true)
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
                return@forEach
            }

            val isBest = row.route.id == bestRouteId
            val isWorst = row.route.id == worstRouteId
            val grade = when {
                isBest -> RouteGrade.GOOD
                isWorst -> RouteGrade.POOR
                sample?.latencyMillis != null -> RouteGrade.MEDIUM
                else -> RouteGrade.UNRATED
            }

            row.surface.background = routeRankBackground(grade, selectedRouteId == row.route.id)

            if (sample != null && sample.latencyMillis != null) {
                val baseLabel = sampleLabel(sample, isBest = isBest, isWorst = isWorst)
                val speedReason = if (sample.downloadBytesPerSecond == null) {
                    country.errors[row.route.id]?.let {
                        RouteProbePresentation.failureMessage(it, english)
                    }
                } else null
                row.status.text = if (speedReason != null) "$baseLabel · $speedReason" else baseLabel
                row.status.setTextColor(gradeColor(grade))
                row.status.setTypeface(null, if (isBest || isWorst) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            } else {
                val reason = country.errors[row.route.id]?.let {
                    RouteProbePresentation.failureMessage(it, english)
                } ?: copy("Нет ответа · нажмите для повтора", "No response · tap to retry")
                row.status.text = "${copy("Отклик: —  ·  Скорость: —", "Response: —  ·  Speed: —")} · $reason"
                row.status.setTextColor(DeyttUi.MUTED)
                row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            row.status.contentDescription = row.status.text
        }
    }

    private fun gradeColor(grade: RouteGrade): Int = when (grade) {
        RouteGrade.GOOD -> DeyttUi.MINT
        RouteGrade.MEDIUM -> DeyttUi.MUTED
        RouteGrade.POOR -> DeyttUi.CORAL
        RouteGrade.UNRATED -> DeyttUi.MUTED
    }

    private fun startAwgProbe(row: ProbeRouteRow) {
        if (awgMeasurementRunning || RouteProbeClient.isRunning(host) || closed) return
        if (VpnService.prepare(host) != null && host.requestRouteProbePermission { startAwgProbe(row) }) return
        awgMeasurementRunning = true
        row.progress.setLoading(true)
        row.status.text = sampleLabel(RouteProbeSample(row.route.id, null, null))
        row.status.contentDescription = copy("Подключаем для проверки…", "Connecting to measure…")
        host.requestAwgMeasurement(row.route, onComplete = { successful ->
            awgMeasurementRunning = false
            row.progress.setLoading(false)
            if (!successful && RouteQualityStore.read(host, row.route.id) == null &&
                row.status.text.toString() == sampleLabel(RouteProbeSample(row.route.id, null, null))
            ) row.status.text = copy("Проверка недоступна · повторите", "Measurement unavailable · retry")
            launchNextCountry()
        }) { finished -> measureAwgRow(row, null, finished) }
    }

    private fun measureAwgRow(row: ProbeRouteRow, country: CountryProbeState?, finished: (Boolean) -> Unit) {
        val token = TelegramSessionStore.read(host)
        android.util.Log.i("DeyttRouteProbe", "measureAwgRow ${row.route.id} token=${token != null} closed=$closed diagForeground=$diagnosticForeground cancelled=${country?.cancelled}")
        if (closed || !diagnosticForeground || country?.cancelled == true) { finished(false); return }
        awgCompletion = finished
        row.progress.setLoading(true)
        row.status.text = sampleLabel(RouteProbeSample(row.route.id, null, null))
        diagnosticExecutor.execute {
            var measuredLatency: Long? = null
            val result = runCatching {
                val current = {
                    !closed && diagnosticForeground && country?.cancelled != true &&
                        AwgTunnelController.isRunning() && !AwgTunnelController.isStopping() &&
                        SelectedRouteStore(host).read().id == row.route.id
                }
                check(current())
                val pingSamples = mutableListOf<Long>()
                runCatching {
                    RouteProxyProbe.measureThroughSystemVpn(
                        RouteProbePreferences.method(host),
                        timeoutMillis = 2_500,
                        sampleCount = 1,
                        onSample = { _, latency ->
                            if (latency != null && RouteProbeStatistics.isValidLatencyMillis(latency)) {
                                pingSamples += latency
                                val currentLatency = RouteProbeStatistics.medianLatencyMillis(pingSamples)
                                measuredLatency = currentLatency
                                val snapshot = pingSamples.toList()
                                host.runOnUiThread {
                                    if (current()) {
                                        val sample = RouteProbeSample(row.route.id, currentLatency, null)
                                        row.status.text = sampleLabel(sample)
                                        row.progress.setLoading(true)
                                        if (country != null) {
                                            country.samples[row.route.id] = sample
                                            country.pingTraces[row.route.id] = snapshot.toMutableList()
                                            updateCountryLatencySummary(country)
                                        }
                                    }
                                }
                            }
                        },
                    )
                }.onSuccess { measuredLatency = it }
                    .onFailure {
                        android.util.Log.w("DeyttAwgProbe", "Latency probe failed (${it.javaClass.simpleName})")
                    }
                val english = AppLanguage.current(host) == AppLanguage.EN
                val speed = if (token.isNullOrBlank()) null else runCatching {
                    RouteProxyProbe.measureSystemDownload(token, current, sampleMillis = 500, onProgress = { bytesPerSecond ->
                        val latency = measuredLatency
                        host.runOnUiThread {
                            if (current()) {
                                row.status.text = RouteProbePresentation.speedProgress(
                                    latency?.let(::formatLatency),
                                    english,
                                    bytesPerSecond,
                                )
                                row.progress.setLoading(true)
                                if (country != null) {
                                    country.samples[row.route.id] = RouteProbeSample(row.route.id, latency, bytesPerSecond)
                                    updateCountryLatencySummary(country)
                                }
                            }
                        }
                    })
                }.getOrNull()
                RouteProbeSample(row.route.id, measuredLatency, speed).also { RouteQualityStore.write(host, it) }
            }.onFailure { error ->
                android.util.Log.w(
                    "DeyttAwgProbe",
                    "Measurement failed (${error.javaClass.simpleName}: ${error.message?.take(72).orEmpty()})",
                )
            }
            host.runOnUiThread {
                row.progress.setLoading(false)
                if (!closed && diagnosticForeground && country?.cancelled != true) {
                    val sample = result.getOrNull() ?: measuredLatency?.let { RouteProbeSample(row.route.id, it, null) }
                    sample?.let {
                        country?.samples?.put(row.route.id, it)
                        RouteQualityStore.write(host, it)
                    }
                    row.status.text = sample?.let(::sampleLabel) ?: copy("Не удалось измерить · повторите проверку", "Measurement failed · try again")
                    if (sample != null && sample.downloadBytesPerSecond == null) {
                        val failure = result.exceptionOrNull()?.let {
                            RouteProbePresentation.failure(it, AppLanguage.current(host) == AppLanguage.EN)
                        } ?: copy("скорость недоступна", "speed unavailable")
                        row.status.append(" · $failure")
                    }
                    row.status.setTextColor(when {
                        sample?.downloadBytesPerSecond?.let { it > 0L } == true -> DeyttUi.MINT
                        sample != null -> AMBER
                        else -> DeyttUi.CORAL
                    })
                    if (country != null) {
                        updateCountryLatencySummary(country)
                        val selectedRouteId = SelectedRouteStore(host).read().id
                        applyCountryRanking(country, selectedRouteId)
                    }
                }
                // Only the matching measurement owns this continuation. A cancelled
                // worker must not advance a later country's diagnostic sequence.
                if (awgCompletion === finished) awgCompletion = null
                finished(result.isSuccess && measuredLatency != null)
            }
        }
    }

    private fun formatLatency(milliseconds: Long): String =
        if (AppLanguage.current(host) == AppLanguage.EN) "$milliseconds ms" else "$milliseconds мс"

    private fun formatSpeed(bytesPerSecond: Long): String {
        val megabits = bytesPerSecond * 8.0 / 1_000_000.0
        val unit = if (AppLanguage.current(host) == AppLanguage.EN) "Mbps" else "Мбит/с"
        return String.format(Locale.US, "%.1f %s", megabits, unit)
    }

    private fun routeGroup(): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private fun appendToGroup(group: LinearLayout, item: View) {
        group.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (group.childCount > 0) topMargin = host.dp(7)
        })
    }

    private fun profilePage(): View = profileFlows.page()

    private fun showSplitTunnelingSheet(onChanged: () -> Unit) {
        val dialog = Dialog(host)
        val store = UserSplitTunnelStore(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(20), host.dp(18), host.dp(20), host.dp(18))
            background = host.rounded(0xF40D1420.toInt(), 24f, 0x3060A5FA.toInt())
        }

        sheet.addView(host.mono(copy("./c · РАЗДЕЛЬНОЕ ТУННЕЛИРОВАНИЕ", "./c · SPLIT TUNNELING"), 9f, MUTED, 650))
        sheet.addView(host.text(copy("Раздельное туннелирование", "Split tunneling"), 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(6), 0, host.dp(4))
        })
        val modeDescription = host.text("", 12f, TEXT).apply {
            setLineSpacing(host.dp(2).toFloat(), 1f)
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
            background = host.rounded(0x45162438, 14f, 0x22FFFFFF)
        }
        sheet.addView(modeDescription)

        sheet.addView(host.text(copy("НАБОР ПРИЛОЖЕНИЙ", "APP PRESET"), 10f, MUTED, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(14), 0, host.dp(6))
            letterSpacing = .06f
        })
        val presetName = host.text("", 12f, TEXT, android.graphics.Typeface.BOLD).apply {
            minimumHeight = host.dp(44)
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
            background = host.rounded(0x551E324E, 14f, 0x5060A5FA.toInt())
            foreground = host.ripple(14f)
            host.pressFeedback(this, .985f)
            isClickable = true
        }
        val modeButton = host.text("", 12f, TEXT, android.graphics.Typeface.BOLD).apply {
            minimumHeight = host.dp(44)
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
            background = host.rounded(0x551E324E, 14f, 0x5060A5FA.toInt())
            foreground = host.ripple(14f)
            host.pressFeedback(this, .985f)
            isClickable = true
        }
        val addPreset = host.text("+", 16f, SKY, android.graphics.Typeface.BOLD).apply {
            setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8))
            background = host.rounded(0x551E324E, 14f, 0x5060A5FA.toInt())
            foreground = host.ripple(14f)
            host.pressFeedback(this, .985f)
            isClickable = true
            contentDescription = copy("Создать набор приложений", "Create an app preset")
        }
        val deletePreset = host.text("✕", 14f, DeyttUi.CORAL, android.graphics.Typeface.BOLD).apply {
            setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8))
            background = host.rounded(0x50381E24, 14f, 0x45F43F5E.toInt())
            foreground = host.ripple(14f)
            host.pressFeedback(this, .985f)
            isClickable = true
            contentDescription = copy("Удалить набор приложений", "Delete app preset")
        }
        val presetRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, host.dp(10), 0, host.dp(2))
            addView(presetName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(addPreset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = host.dp(6) })
            addView(deletePreset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = host.dp(6) })
        }
        sheet.addView(presetRow)
        sheet.addView(modeButton)

        lateinit var renderContent: () -> Unit
        fun updatePresetControls() {
            val active = store.activePreset()
            val vpnOnly = active.mode == UserSplitTunnelStore.MODE_VPN_ONLY
            val name = if (active.id == "default") copy(active.name, "Main") else active.name
            presetName.text = copy("Набор: ", "Set: ") + name + "  ▾"
            presetName.contentDescription = copy("Выбран набор $name. Изменить", "Selected set $name. Change")
            deletePreset.visibility = if (active.id == "default") View.GONE else View.VISIBLE
            modeButton.text = copy("Режим: ", "Mode: ") + copy(
                if (vpnOnly) "VPN только для списка" else "Обход VPN для списка",
                if (vpnOnly) "VPN only for list" else "Bypass VPN for list"
            ) + "  ▾"
            modeDescription.text = copy(
                if (vpnOnly) "Через VPN идут только выбранные сайты и приложения. Базовые правила подписки остаются активны." else "Выбранные сайты и приложения идут напрямую без VPN. Базовые правила подписки остаются активны.",
                if (vpnOnly) "Only selected sites and apps use VPN. Subscription rules stay active." else "Selected sites and apps bypass VPN. Subscription rules stay active."
            )
        }

        presetName.setOnClickListener {
            val presets = store.readPresets()
            AppDialog.Builder(host).setTitle(copy("Набор приложений", "App preset"))
                .setItems(presets.map { if (it.id == "default") copy(it.name, "Main") else it.name }.toTypedArray()) { _, index ->
                    store.selectPreset(presets[index].id)
                    updatePresetControls()
                    renderContent()
                    onChanged()
                }.show()
        }
        addPreset.setOnClickListener {
            val input = EditText(host).apply {
                hint = copy("Например, Работа", "For example, Work")
                filters = arrayOf(InputFilter.LengthFilter(32))
            }
            AppDialog.Builder(host).setTitle(copy("Создать набор приложений", "Create app preset")).setView(input)
                .setNegativeButton(host.uiCopy("Отмена"), null)
                .setPositiveButton(host.uiCopy("Создать")) { _, _ ->
                    if (store.createPreset(input.text.toString())) {
                        updatePresetControls()
                        renderContent()
                        onChanged()
                    } else AppDialog.Builder(host).setMessage(copy("Имя пустое или уже занято.", "Name is empty or already used."))
                        .setPositiveButton("OK", null).show()
                }.show()
        }
        deletePreset.setOnClickListener {
            AppDialog.Builder(host).setTitle(copy("Удалить пресет?", "Delete preset?"))
                .setNegativeButton(host.uiCopy("Отмена"), null)
                .setPositiveButton(host.uiCopy("Удалить")) { _, _ ->
                    store.deleteActivePreset()
                    updatePresetControls()
                    renderContent()
                    onChanged()
                }.show()
        }
        modeButton.setOnClickListener {
            val modes = arrayOf(copy("Обход VPN для списка", "Bypass VPN for list"), copy("VPN только для списка", "VPN only for list"))
            val selected = if (store.readMode() == UserSplitTunnelStore.MODE_VPN_ONLY) 1 else 0
            AppDialog.Builder(host).setTitle(copy("Режим", "Mode")).setSingleChoiceItems(modes, selected) { choice, index ->
                store.setMode(if (index == 1) UserSplitTunnelStore.MODE_VPN_ONLY else UserSplitTunnelStore.MODE_BYPASS)
                choice.dismiss()
                updatePresetControls()
                renderContent()
                onChanged()
            }.setNegativeButton(host.uiCopy("Отмена"), null).show()
        }
        updatePresetControls()

        val scroll = ScrollView(host).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, host.dp(12), 0, host.dp(12))
        }
        scroll.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        renderContent = {
            content.removeAllViews()
            val vpnOnly = store.readMode() == UserSplitTunnelStore.MODE_VPN_ONLY

            // 1. Свои сайты
            val sitesHeader = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(host.sectionLabel(if (vpnOnly) copy("сайты через vpn", "sites through vpn") else copy("сайты без vpn", "bypass sites")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val addBtn = host.text("+ " + copy("Добавить", "Add"), 12f, SKY, android.graphics.Typeface.BOLD).apply {
                    setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
                    background = host.glassButtonBackground(0x303B82F6.toInt(), 0x181D4ED8.toInt(), 12f)
                    foreground = host.ripple(12f)
                    host.pressFeedback(this, .985f)
                    isClickable = true
                    setOnClickListener {
                        val input = EditText(host).apply {
                            hint = "notion.so " + copy("или", "or") + " *.domain.com"
                            setTextColor(TEXT)
                            setHintTextColor(MUTED)
                            textSize = 14f
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                            background = host.rounded(SURFACE_2, 12f, LINE)
                            setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
                        }
                        val container = FrameLayout(host).apply {
                            setPadding(host.dp(20), host.dp(12), host.dp(20), host.dp(4))
                            addView(input)
                        }
                        AppDialog.Builder(host)
                            .setTitle(copy("Добавить сайт", "Add website"))
                            .setMessage(copy("Укажите домен (например, notion.so или *.example.com для поддоменов).", "Enter a domain (e.g. notion.so or *.example.com for subdomains)."))
                            .setView(container)
                            .setNegativeButton(host.uiCopy("Отмена"), null)
                            .setPositiveButton(host.uiCopy("Добавить")) { _, _ ->
                                val domain = input.text.toString()
                                val normalized = UserSplitTunnelStore.normalizeDomain(domain)
                                if (normalized != null) {
                                    store.addDomain(normalized)
                                    renderContent()
                                    onChanged()
                                } else {
                                    AppDialog.Builder(host)
                                        .setTitle(copy("Некорректный адрес", "Invalid address"))
                                        .setMessage(copy("Введите корректное имя домена, например domain.com или *.domain.com.", "Please enter a valid domain name, e.g. domain.com or *.domain.com."))
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                            .show()
                    }
                }
                addView(addBtn)
            }
            content.addView(sitesHeader)

            val customDomains = store.readDomains()
            if (customDomains.isEmpty()) {
                val empty = host.text(copy("Нет добавленных сайтов. Нажмите «+ Добавить», чтобы открывать сайт без VPN.", "No custom sites added. Tap \"+ Add\" to bypass a site."), 12f, MUTED).apply {
                    setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                    background = host.rounded(SURFACE_2, 14f, LINE)
                }
                content.addView(empty)
            } else {
                val listCard = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    background = host.rounded(SURFACE_2, 14f, LINE)
                    setPadding(host.dp(10), host.dp(4), host.dp(10), host.dp(4))
                }
                customDomains.forEachIndexed { index, domain ->
                    if (index > 0) {
                        listCard.addView(View(host).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, host.dp(1)))
                    }
                    val row = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(host.dp(4), host.dp(8), host.dp(4), host.dp(8))
                        val textCol = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(host.text(domain, 14f, TEXT, android.graphics.Typeface.BOLD))
                            addView(host.text(if (vpnOnly) copy("Через VPN", "Through VPN") else copy("Прямое соединение без VPN", "Direct connection without VPN"), 11f, MUTED))
                        }
                        addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val delBtn = host.text("✕", 14f, DeyttUi.CORAL, android.graphics.Typeface.BOLD).apply {
                            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
                            setOnClickListener {
                                store.removeDomain(domain)
                                renderContent()
                                onChanged()
                            }
                        }
                        addView(delBtn)
                    }
                    listCard.addView(row)
                }
                content.addView(listCard)
            }

            content.addView(spacer(16, host))

            // 2. Свои приложения
            val appsHeader = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(host.sectionLabel(if (vpnOnly) copy("приложения через vpn", "apps through vpn") else copy("приложения без vpn", "bypass apps")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val addBtn = host.text("+ " + copy("Добавить", "Add"), 12f, SKY, android.graphics.Typeface.BOLD).apply {
                    setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
                    background = host.glassButtonBackground(0x303B82F6.toInt(), 0x181D4ED8.toInt(), 12f)
                    foreground = host.ripple(12f)
                    host.pressFeedback(this, .985f)
                    isClickable = true
                    setOnClickListener {
                        showAppPickerSheet(vpnOnly) { selectedPkg ->
                            store.addPackage(selectedPkg)
                            renderContent()
                            onChanged()
                        }
                    }
                }
                addView(addBtn)
            }
            content.addView(appsHeader)

            val customPackages = store.readPackages()
            if (customPackages.isEmpty()) {
                val empty = host.text(copy("Нет исключённых приложений. Нажмите «+ Добавить», чтобы выбрать приложение из списка.", "No excluded apps. Tap \"+ Add\" to select an application."), 12f, MUTED).apply {
                    setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                    background = host.rounded(SURFACE_2, 14f, LINE)
                }
                content.addView(empty)
            } else {
                val listCard = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    background = host.rounded(SURFACE_2, 14f, LINE)
                    setPadding(host.dp(10), host.dp(4), host.dp(10), host.dp(4))
                }
                val pm = host.packageManager
                customPackages.forEachIndexed { index, pkg ->
                    if (index > 0) {
                        listCard.addView(View(host).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, host.dp(1)))
                    }
                    val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    val appIcon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

                    val row = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(host.dp(4), host.dp(8), host.dp(4), host.dp(8))
                        if (appIcon != null) {
                            val iv = ImageView(host).apply {
                                setImageDrawable(appIcon)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }
                            addView(iv, LinearLayout.LayoutParams(host.dp(32), host.dp(32)).apply {
                                marginEnd = host.dp(10)
                            })
                        }
                        val textCol = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(host.text(appLabel, 14f, TEXT, android.graphics.Typeface.BOLD))
                            addView(host.mono(pkg, 11f, MUTED))
                        }
                        addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val delBtn = host.text("✕", 14f, DeyttUi.CORAL, android.graphics.Typeface.BOLD).apply {
                            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
                            setOnClickListener {
                                store.removePackage(pkg)
                                renderContent()
                                onChanged()
                            }
                        }
                        addView(delBtn)
                    }
                    listCard.addView(row)
                }
                content.addView(listCard)
            }

            content.addView(spacer(16, host))

            // 3. Базовые правила из подписки
            content.addView(host.sectionLabel(copy("правила из подписки (всегда активны)", "subscription rules (always active)")))
            val subscriptionSites = SubscriptionStore(host).readCurrent()?.let { config ->
                runCatching { SplitTunnelProfile.bypassSites(config) }.getOrNull()
            } ?: emptyList()

            val subCard = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = host.rounded(SURFACE_2, 14f, LINE)
                setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                val ruleHeader = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(host.text("🔒", 12f, MUTED))
                    addView(host.text(copy("базовые правила подписки", "base subscription rules"), 12f, TEXT, android.graphics.Typeface.BOLD),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = host.dp(6) })
                    addView(host.text(copy("всегда без vpn", "always bypass VPN"), 10f, SKY, android.graphics.Typeface.BOLD).apply {
                        setPadding(host.dp(8), host.dp(5), host.dp(8), host.dp(5))
                        background = host.rounded(0x1F8494FF.toInt(), 10f, 0x3F8494FF.toInt())
                    })
                }
                addView(ruleHeader)
                val chips = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, host.dp(8), 0, host.dp(6))
                }
                if (subscriptionSites.isNotEmpty()) {
                    subscriptionSites.forEachIndexed { index, site ->
                        chips.addView(host.text(site, 11f, TEXT).apply {
                            typeface = android.graphics.Typeface.MONOSPACE
                            setPadding(host.dp(8), host.dp(5), host.dp(8), host.dp(5))
                            background = host.rounded(SURFACE, 8f, LINE)
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            if (index > 0) marginStart = host.dp(5)
                        })
                    }
                } else {
                    chips.addView(host.text(copy("Загрузка правил подписки…", "Loading subscription rules…"), 11f, MUTED))
                }
                addView(chips)
                addView(host.text(copy("Встроены в конфигурацию подписки для стабильной работы российских сервисов и госуслуг.", "Included in the subscription profile for reliable access to Russian services and public services."), 11f, MUTED))
            }
            content.addView(subCard)

            content.addView(spacer(10, host))
            content.addView(host.text(copy("Изменения вступают в силу при переподключении VPN.", "Changes take effect when VPN reconnects."), 11f, MUTED).apply {
                gravity = Gravity.CENTER
            })
        }

        renderContent()

        val doneBtn = host.button(copy("Готово", "Done")).apply {
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
            topMargin = host.dp(8)
        })

        showSheet(dialog, sheet)
    }

    private fun showAppPickerSheet(vpnOnly: Boolean, onSelected: (String) -> Unit) {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(20), host.dp(18), host.dp(20), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }

        sheet.addView(host.mono("./c · ВЫБОР ПРИЛОЖЕНИЯ", 9f, MUTED, 650))
        sheet.addView(host.text(copy("Выберите приложение", "Select application"), 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(6), 0, host.dp(4))
        })
        sheet.addView(host.text(
            copy(
                if (vpnOnly) "Трафик выбранного приложения будет идти через VPN." else "Трафик выбранного приложения будет идти напрямую в обход VPN.",
                if (vpnOnly) "Traffic for the selected application will use VPN." else "Traffic for the selected application will bypass VPN."
            ),
            12f,
            MUTED
        ))

        val searchInput = EditText(host).apply {
            hint = copy("Поиск приложения…", "Search application…")
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 14f
            background = host.rounded(SURFACE_2, 12f, LINE)
            setPadding(host.dp(14), host.dp(10), host.dp(14), host.dp(10))
        }
        sheet.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(10)
            bottomMargin = host.dp(8)
        })

        val progress = ProgressBar(host).apply {
            isIndeterminate = true
        }
        val progressContainer = FrameLayout(host).apply {
            addView(progress, FrameLayout.LayoutParams(host.dp(36), host.dp(36), Gravity.CENTER))
            setPadding(0, host.dp(24), 0, host.dp(24))
        }
        sheet.addView(progressContainer)

        val scroll = ScrollView(host).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }
        val listContainer = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(320)))

        val closeBtn = host.button(copy("Закрыть", "Close"), secondary = true).apply {
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(46)).apply {
            topMargin = host.dp(8)
        })

        val executor = Executors.newSingleThreadExecutor()
        class AppEntry(val name: String, val packageName: String, val icon: Drawable?)
        var allApps = listOf<AppEntry>()

        fun renderList(query: String = "") {
            listContainer.removeAllViews()
            val filtered = if (query.isBlank()) allApps else {
                val q = query.lowercase(Locale.ROOT)
                allApps.filter { it.name.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q) }
            }
            if (filtered.isEmpty()) {
                listContainer.addView(host.text(copy("Ничего не найдено", "No apps found"), 13f, MUTED).apply {
                    setPadding(0, host.dp(16), 0, host.dp(16))
                    gravity = Gravity.CENTER
                })
                return
            }
            filtered.forEachIndexed { index, app ->
                val row = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
                    background = host.glassRowBackground(false, 12f)
                    if (app.icon != null) {
                        val iv = ImageView(host).apply {
                            setImageDrawable(app.icon)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                        addView(iv, LinearLayout.LayoutParams(host.dp(36), host.dp(36)).apply { marginEnd = host.dp(12) })
                    }
                    val col = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(host.text(app.name, 14f, TEXT, android.graphics.Typeface.BOLD))
                        addView(host.mono(app.packageName, 11f, MUTED))
                    }
                    addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(host.text(copy("+ добавить", "+ add"), 10f, SKY, android.graphics.Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                        setPadding(host.dp(8), host.dp(7), host.dp(8), host.dp(7))
                        background = host.rounded(DeyttUi.SKY_SURFACE, 10f, LINE)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = host.dp(8)
                    })
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        onSelected(app.packageName)
                        dialog.dismiss()
                    }
                }
                listContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = host.dp(6)
                })
            }
        }

        executor.execute {
            val pm = host.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != host.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { info ->
                    AppEntry(
                        name = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                    )
                }
                .sortedBy { it.name.lowercase(Locale.ROOT) }

            host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                allApps = installed
                progressContainer.visibility = View.GONE
                scroll.visibility = View.VISIBLE
                renderList(searchInput.text.toString())
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        showSheet(dialog, sheet)
    }

    private fun showSheet(dialog: Dialog, content: View) {
        val container = FrameLayout(host).apply {
            setPadding(host.dp(14), 0, host.dp(14), host.dp(20))
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { dialog.dismiss() }
        }
        content.setOnClickListener { /* prevent dismissal on clicking inside content */ }
        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.58f)
            DeyttUi.blurBehind(this, host.dp(20))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private var settingsTelegramIcon: ImageView? = null

    fun updateTelegramAvatar(avatar: android.graphics.Bitmap?) {
        settingsTelegramIcon?.apply {
            clipToOutline = true
            if (avatar != null) {
                setImageBitmap(avatar)
                imageTintList = null
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 0, 0)
            } else {
                setImageResource(if (TelegramSessionStore.read(host) != null) R.drawable.ic_user_round else R.drawable.ic_send)
                imageTintList = ColorStateList.valueOf(SKY)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
            }
        }
    }

    private fun settingsPage(): View {
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("", "Настройки"))
        root.addView(spacer(12, host))
        root.addView(host.sectionLabel(copy("язык и аккаунт", "language and account")))
        val accountGroup = routeGroup()
        appendToGroup(accountGroup, host.row(copy("Язык приложения", "App language"), AppLanguage.label(host), "LANGUAGE_ICON", "›").apply {
            setOnClickListener {
                val codes = listOf(AppLanguage.RU, AppLanguage.EN)
                AppDialog.Builder(host).setTitle(copy("Язык приложения", "App language"))
                    .setSingleChoiceItems(arrayOf("Русский", "English"), codes.indexOf(AppLanguage.current(host))) { dialog, index ->
                        dialog.dismiss()
                        if (codes[index] != AppLanguage.current(host)) {
                            AppLanguage.set(host, codes[index])
                            host.recreate()
                        }
                    }.setNegativeButton(host.uiCopy("Отмена"), null).show()
            }
        })
        val linked = TelegramSessionStore.read(host) != null
        appendToGroup(accountGroup, host.row(
            if (linked) copy("Telegram подключён", "Telegram connected") else copy("Войти через Telegram", "Sign in with Telegram"),
            if (linked) copy("Управление аккаунтом", "Manage account") else copy("Подписка и устройства", "Subscription and devices"),
            if (linked) "ACCOUNT_ICON" else "TELEGRAM_ICON", "›", emphasis = linked,
            avatar = TelegramIdentityCache.avatar.takeIf { linked },
        ).apply {
            settingsTelegramIcon = getChildAt(0) as ImageView
            setOnClickListener { if (TelegramSessionStore.read(host) == null) showTelegramPairing() else showLinkedTelegramAccount() }
        })

        root.addView(accountGroup)
        root.addView(spacer(16, host))
        root.addView(host.sectionLabel(copy("подключение", "connection")))
        val connectionGroup = routeGroup()
        val splitStore = UserSplitTunnelStore(host)
        fun splitTunnelSummary(): String {
            val count = splitStore.readDomains().size + splitStore.readPackages().size
            val mode = if (splitStore.readMode() == UserSplitTunnelStore.MODE_VPN_ONLY) copy("VPN только для списка", "VPN only for list")
                else copy("обход VPN", "bypass VPN")
            val preset = splitStore.activePreset()
            val presetName = if (preset.id == "default") copy(preset.name, "Main") else preset.name
            return presetName + " · " + mode + " · " + count
        }
        val splitRow = host.row(
            copy("Раздельное туннелирование", "Split tunneling"),
            splitTunnelSummary(), "SPLIT_ICON", "›",
        )
        val splitSummaryView = (splitRow.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView
        splitRow.setOnClickListener {
            showSplitTunnelingSheet {
                splitSummaryView?.text = splitTunnelSummary()
                splitRow.contentDescription = copy("Раздельное туннелирование", "Split tunneling") + ". " + splitTunnelSummary()
            }
        }
        appendToGroup(connectionGroup, splitRow)
        val probeSummary = { copy("HTTP ${RouteProbePreferences.method(host).title} · быстрая проверка, загрузка 1 с", "HTTP ${RouteProbePreferences.method(host).title} · fast check, 1 s download") }
        appendToGroup(connectionGroup, host.row(copy("Проверка маршрута", "Route check"), probeSummary(), "ROUTE_CHECK_ICON", "›").apply {
            val summary = (getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView
            setOnClickListener {
                val methods = RouteProbeMethod.values()
                AppDialog.Builder(host).setTitle(copy("Метод проверки", "Check method"))
                    .setSingleChoiceItems(arrayOf(copy("HEAD · только заголовки", "HEAD · headers only"), copy("GET · с ответом", "GET · response body")), methods.indexOf(RouteProbePreferences.method(host))) { dialog, index ->
                        RouteProbePreferences.saveMethod(host, methods[index])
                        summary?.text = probeSummary()
                        contentDescription = copy("Проверка маршрута", "Route check") + ". " + probeSummary()
                        dialog.dismiss()
                    }.setNegativeButton(host.uiCopy("Отмена"), null).show()
            }
        })
        appendToGroup(connectionGroup, host.row(copy("Разрешения VPN", "VPN permissions"), copy("Системные настройки Android", "Android system settings"), "VPN_PERMISSION_ICON", "›").apply {
            setOnClickListener { runCatching { host.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } }
        })
        appendToGroup(connectionGroup, host.row(copy("Бот DEYTT", "DEYTT bot"), copy("Другие действия в Telegram", "More actions in Telegram"), "BOT_ICON", "›").apply {
            setOnClickListener { openUrl("https://t.me/deyttbot") }
        })
        root.addView(connectionGroup)

        root.addView(spacer(16, host))
        root.addView(host.sectionLabel(copy("вид и приватность", "appearance and privacy")))
        root.addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(15), host.dp(3), host.dp(15), host.dp(3))
            background = host.rounded(SURFACE, 18f, LINE)
            clipToOutline = true
            addView(preferenceSwitch(
                copy("Регион на карте", "Region on the map"),
                copy("По IP через ipinfo.io · без GPS и сохранения координат", "IP lookup via ipinfo.io · no GPS or saved coordinates"),
                host.isNetworkLocationEnabled(), host::setNetworkLocationEnabled,
            ))
            addView(View(host).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, host.dp(1)))
            addView(preferenceSwitch(
                copy("Меньше движения", "Reduce motion"), copy("Остановить анимацию фона", "Pause background animation"),
                host.isReducedMotionEnabled(), host::setReducedMotionEnabled,
            ))
        })

        root.addView(spacer(16, host))
        root.addView(host.sectionLabel(copy("обновления", "updates")))
        root.addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(16), host.dp(13), host.dp(16), host.dp(13))
            background = host.rounded(SURFACE, 18f, LINE)
            clipToOutline = true
            addView(host.text("deytt.connect · ${BuildConfig.VERSION_NAME}", 13f, TEXT, android.graphics.Typeface.BOLD))
            updateStatus = host.text(updateStatusText, 11f, MUTED).apply { setPadding(0, host.dp(5), 0, host.dp(10)) }
            addView(updateStatus)
            updateButton = host.button(copy("Проверить обновления", "Check for updates"), secondary = true).apply { setOnClickListener { updateAction() } }
            addView(updateButton, LinearLayout.LayoutParams(-1, host.dp(46)))
        })
        root.addView(spacer(18, host))
        return scrollPage(root)
    }

    private fun checkForUpdate(force: Boolean = true) {
        if (updateCheckInFlight || updateDownloadInFlight || closed) return
        val preferences = host.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        if (!force && now - lastCheck < UPDATE_CHECK_INTERVAL_MS) return
        updateCheckInFlight = true
        updateButton?.isEnabled = false
        updateButton?.alpha = .68f
        updateStatus?.apply {
            text = host.uiCopy("Проверяем официальный релиз…")
            setTextColor(DeyttUi.SKY)
        }
        updateStatusText = "Проверяем официальный релиз…"
        updateExecutor.execute {
            runCatching { UpdateChecker.latest() }
                .onSuccess { release -> host.runOnUiThread {
                    if (closed || host.isFinishing || host.isDestroyed) return@runOnUiThread
                    updateCheckInFlight = false
                    preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                    updateButton?.isEnabled = true
                    updateButton?.alpha = 1f
                    if (!ReleaseVersion.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                        latestRelease = null
                        downloadedUpdate = null
                        updateStatusText = "Установлена последняя версия."
                        updateButton?.text = host.uiCopy("Проверить снова")
                    } else {
                        latestRelease = release
                        val apkSize = release.apkSize?.let { " · ${formatUpdateSize(it)}" }.orEmpty()
                        updateStatusText = if (release.apkUrl != null) {
                            "Доступна ${release.tag}$apkSize. APK будет проверен по подписи перед установкой."
                        } else "Доступна ${release.tag}. APK пока нет; доступна только страница релиза."
                        updateButton?.text = host.uiCopy(if (release.apkUrl != null) "Скачать обновление" else "Открыть официальный релиз")
                        offerUpdateIfNew(release)
                    }
                    updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(DeyttUi.MINT) }
                } }
                .onFailure {
                    host.runOnUiThread {
                        if (closed || host.isFinishing || host.isDestroyed) return@runOnUiThread
                        updateCheckInFlight = false
                        preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                        latestRelease = null
                        updateStatusText = "Не удалось проверить официальный релиз. Повторите попытку позже."
                        updateButton?.apply { isEnabled = true; alpha = 1f; text = host.uiCopy("Проверить обновления") }
                        updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(DeyttUi.CORAL) }
                    }
                }
        }
    }

    private fun updateAction() {
        val cached = downloadedUpdate?.takeIf { it.isFile }
        if (cached != null) {
            offerInstall(cached)
            return
        }
        val release = latestRelease
        if (release == null) checkForUpdate(force = true)
        else if (release.apkUrl != null) askDownload(release)
        else openLatest()
    }

    private fun offerUpdateIfNew(release: ReleaseInfo) {
        val preferences = host.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE)
        if (preferences.getString(KEY_LAST_OFFERED_UPDATE, null) == release.tag || release.apkUrl == null) return
        preferences.edit().putString(KEY_LAST_OFFERED_UPDATE, release.tag).apply()
        AppDialog.Builder(host)
            .setTitle(host.uiCopy("Доступно обновление ${release.tag}"))
            .setMessage(host.uiCopy("Скачать официальный APK сейчас? Позже его можно будет открыть в настройках."))
            .setNegativeButton(host.uiCopy("Позже"), null)
            .setNeutralButton(host.uiCopy("Страница релиза")) { _, _ -> openLatest() }
            .setPositiveButton(host.uiCopy("Обновить сейчас")) { _, _ -> downloadUpdate(release) }
            .show()
    }

    private fun askDownload(release: ReleaseInfo) {
        if (release.apkUrl == null) {
            openLatest()
            return
        }
        val size = release.apkSize?.let { " · ${formatUpdateSize(it)}" }.orEmpty()
        AppDialog.Builder(host)
            .setTitle(host.uiCopy("Скачать версию ${release.tag}?"))
            .setMessage(host.uiCopy("Файл с GitHub будет проверен по имени пакета и подписи приложения$size."))
            .setNegativeButton(host.uiCopy("Позже"), null)
            .setNeutralButton(host.uiCopy("Страница релиза")) { _, _ -> openLatest() }
            .setPositiveButton(host.uiCopy("Скачать")) { _, _ -> downloadUpdate(release) }
            .show()
    }

    private fun downloadUpdate(release: ReleaseInfo) {
        if (updateDownloadInFlight || closed) return
        updateDownloadInFlight = true
        showUpdateProgress(copy("Скачиваем обновление…", "Downloading update…"), 0)
        updateButton?.apply { isEnabled = false; alpha = .68f; text = host.uiCopy("Скачиваем…") }
        updateStatus?.apply { text = host.uiCopy("Скачиваем и проверяем официальный APK…"); setTextColor(DeyttUi.SKY) }
        updateExecutor.execute {
            runCatching {
                UpdateChecker.downloadAndVerify(host, release) { progress ->
                    host.runOnUiThread {
                        if (!host.isFinishing && !host.isDestroyed) {
                            updateStatusText = copy("Скачиваем обновление · $progress%", "Downloading update · $progress%")
                            updateStatus?.text = updateStatusText
                            showUpdateProgress(updateStatusText, progress)
                        }
                    }
                }
            }.onSuccess { apk -> host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                updateDownloadInFlight = false
                downloadedUpdate = apk
                showUpdateProgress(copy("Обновление готово · нажмите, чтобы установить", "Update ready · tap to install"), 100)
                updateButton?.apply { isEnabled = true; alpha = 1f; text = host.uiCopy("Установить обновление") }
                updateStatusText = "APK проверен. Установка начнётся только после подтверждения Android."
                updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(DeyttUi.MINT) }
                offerInstall(apk)
            } }.onFailure { failure -> host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                updateDownloadInFlight = false
                showUpdateProgress(copy("Обновление не загружено · нажмите для повтора", "Update failed · tap to retry"), 0)
                updateButton?.apply { isEnabled = true; alpha = 1f; text = host.uiCopy("Повторить загрузку") }
                updateStatusText = when (failure.message) {
                    "Downloaded APK belongs to another app" -> "APK принадлежит другому приложению. Установка отменена."
                    "Downloaded APK signing certificate does not match" -> "Подпись APK не совпадает с установленной версией. Установка отменена."
                    else -> "Не удалось загрузить или проверить APK. Попробуйте позже."
                }
                updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(DeyttUi.CORAL) }
            } }
        }
    }

    private fun offerInstall(apk: File) {
        AppDialog.Builder(host)
            .setTitle(host.uiCopy("Обновление готово"))
            .setMessage(host.uiCopy(copy("Android попросит подтвердить установку и закроет текущую версию. После установки нажмите «Открыть» или запустите приложение позже.", "Android will ask to install and close the current version. After installation, tap Open or launch the app later.")))
            .setNegativeButton(host.uiCopy("Позже"), null)
            .setPositiveButton(host.uiCopy("Установить сейчас")) { _, _ -> launchInstaller(apk) }
            .show()
    }

    private fun launchInstaller(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !host.packageManager.canRequestPackageInstalls()) {
            AppDialog.Builder(host)
                .setTitle(host.uiCopy("Разрешите установку обновлений"))
                .setMessage(host.uiCopy("Android откроет настройки разрешения для этого приложения. Вернитесь и нажмите «Установить обновление»."))
                .setNegativeButton(host.uiCopy("Позже"), null)
                .setPositiveButton(host.uiCopy("Открыть настройки")) { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${host.packageName}"))
                    runCatching { host.startActivity(intent) }
                }
                .show()
            return
        }
        runCatching { UpdateChecker.launchInstaller(host, apk) }
            .onFailure {
                updateStatusText = "Не удалось открыть системный установщик Android."
                updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(DeyttUi.CORAL) }
            }
    }

    private fun formatUpdateSize(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

    private fun showTelegramPairing() {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        sheet.addView(host.mono("./c · АККАУНТ", 9f, MUTED, 650))
        val title = host.text("Добавить приложение", 21f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(6))
        }
        sheet.addView(title)
        val detail = host.text(
            "Укажи Telegram username. Бот подтвердит вход одноразовым кодом и подключит существующие профили.",
            13f,
            MUTED,
        ).apply { setLineSpacing(host.dp(3).toFloat(), 1f) }
        sheet.addView(detail)

        fun inputField(hintText: String, inputType: Int, maximumLength: Int): EditText =
            EditText(host).apply {
                hint = hintText
                setTextColor(TEXT)
                setHintTextColor(MUTED)
                textSize = 15f
                setPadding(host.dp(15), 0, host.dp(15), 0)
                background = host.rounded(SURFACE_2, 15f, LINE)
                this.inputType = inputType
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                filters = arrayOf(InputFilter.LengthFilter(maximumLength))
            }

        val usernameField = inputField("@username", InputType.TYPE_CLASS_TEXT, 33)
        val codeField = inputField("123456", InputType.TYPE_CLASS_NUMBER, 6).apply {
            gravity = Gravity.CENTER
            letterSpacing = .24f
        }
        sheet.addView(usernameField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(52),
        ).apply { topMargin = host.dp(18) })
        sheet.addView(codeField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(58),
        ).apply { topMargin = host.dp(14) })

        val status = host.text("", 12f, MUTED).apply {
            setPadding(host.dp(2), host.dp(11), host.dp(2), 0)
            setLineSpacing(host.dp(2).toFloat(), 1f)
        }
        sheet.addView(status)
        val openBot = host.button("Открыть ./c", secondary = true)
        sheet.addView(openBot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48),
        ).apply { topMargin = host.dp(13) })
        val primary = host.button("Продолжить")
        sheet.addView(primary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(50),
        ).apply { topMargin = host.dp(9) })
        usernameField.imeOptions = EditorInfo.IME_ACTION_GO
        usernameField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                primary.performClick()
                true
            } else false
        }

        var challenge: String? = null
        var botUrl: String? = null
        var needsBotStart = false
        var step = 0
        fun setStep(value: Int) {
            step = value
            usernameField.visibility = if (step == 0) View.VISIBLE else View.GONE
            codeField.visibility = if (step == 1) View.VISIBLE else View.GONE
            openBot.visibility = if (step == 1 && needsBotStart) View.VISIBLE else View.GONE
            primary.text = host.uiCopy(when (step) {
                0 -> "Получить код"
                1 -> "Подтвердить код"
                else -> "Готово"
            })
            title.text = host.uiCopy(when (step) {
                0 -> "Добавить приложение"
                1 -> if (needsBotStart) "Сначала открой ./c" else "Код отправлен"
                else -> "Аккаунт подключён"
            })
            detail.text = host.uiCopy(when (step) {
                0 -> "Укажи Telegram username. Бот подтвердит вход одноразовым кодом и подключит существующие профили."
                1 -> if (needsBotStart) {
                    "Этот аккаунт ещё не начинал чат с ботом. Открой ./c один раз — код придёт сюда."
                } else {
                    "Код уже отправлен в Telegram. Оставайся в приложении и введи его ниже."
                }
                else -> ""
            })
        }
        fun openBotLink() {
            val link = botUrl ?: return
            runCatching {
                val uri = Uri.parse(link)
                check(uri.scheme?.lowercase() in setOf("https", "tg")) { "Unsupported URL scheme" }
                host.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
                .onFailure { status.text = host.uiCopy("Ссылка готова. Открой бота ещё раз кнопкой ниже.") }
        }

        openBot.setOnClickListener { openBotLink() }
        primary.setOnClickListener {
            when (step) {
                0 -> {
                    val username = usernameField.text.toString().trim()
                    if (!Regex("@?[A-Za-z0-9_]{5,32}").matches(username)) {
                        status.text = host.uiCopy("Введи username Telegram длиной от 5 до 32 знаков.")
                        status.setTextColor(DeyttUi.CORAL)
                        return@setOnClickListener
                    }
                    primary.isEnabled = false
                    primary.text = host.uiCopy("Создаём ссылку…")
                    status.setTextColor(BLUE)
                    status.text = host.uiCopy("Подготавливаем одноразовое подтверждение.")
                    pairingExecutor.execute {
                        val result = runCatching { TelegramPairingClient.start(username) }
                        host.runOnUiThread {
                            if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                            primary.isEnabled = true
                            result.onSuccess {
                                challenge = it.challenge
                                botUrl = it.botUrl
                                needsBotStart = it.delivery != "sent"
                                setStep(1)
                                status.setTextColor(BLUE)
                                status.text = host.uiCopy(when (it.delivery) {
                                    "sent" -> "Код отправлен в Telegram и действует 5 минут. Переключаться в бот не нужно."
                                    "delivery_failed" -> "Не получилось доставить сообщение. Открой ./c ниже, чтобы получить код."
                                    else -> "Этого аккаунта пока нет в боте. Открой ./c ниже один раз — код придёт в чат."
                                })
                            }.onFailure {
                                val reference = SubscriptionLoadDiagnostics.record(host, "pair", it)
                                primary.text = host.uiCopy("Продолжить")
                                status.setTextColor(DeyttUi.CORAL)
                                status.text = "${host.uiCopy(pairingError(it))} ${host.uiCopy("код")}: $reference"
                            }
                        }
                    }
                }
                1 -> {
                    val currentChallenge = challenge
                    val code = codeField.text.toString().trim()
                    if (currentChallenge == null || !Regex("\\d{6}").matches(code)) {
                        status.text = host.uiCopy("Введи шесть цифр из сообщения бота.")
                        status.setTextColor(DeyttUi.CORAL)
                        return@setOnClickListener
                    }
                    primary.isEnabled = false
                    primary.text = host.uiCopy("Проверяем код…")
                    status.text = host.uiCopy("Подтверждаем Telegram-аккаунт и загружаем профили.")
                    status.setTextColor(BLUE)
                    pairingExecutor.execute {
                        val result = runCatching {
                            val session = TelegramPairingClient.verify(currentChallenge, code)
                            TelegramSessionStore.save(host, session.token)
                            var importedCount: Int? = null
                            var importFailed = false
                            runCatching {
                                val subscriptionUrl = TelegramPairingClient.subscriptionUrl(session.token)
                                if (subscriptionUrl != null) {
                                    val currentRouteId = SelectedRouteStore(host).read().id
                                    val imported = SubscriptionClient.import(host, subscriptionUrl)
                                    SubscriptionClient.saveSubscriptionUrl(host, imported.url)
                                    val routes = RouteCatalog.from(
                                        SubscriptionStore(host).readCurrent().orEmpty(),
                                        AwgProfileStore(host).profiles(),
                                        OpenFluxProfileStore(host).profiles(host),
                                    )
                                    if (routes.none { it.id == currentRouteId }) {
                                        routes.firstOrNull()?.let { SelectedRouteStore(host).save(it) }
                                    }
                                    importedCount = routes.size
                                }
                            }.onFailure { importFailed = true }
                            session to when {
                                importFailed -> "Аккаунт подключён. Не удалось обновить профили — повтори вход позже."
                                importedCount != null -> "Загружено маршрутов: $importedCount. Текущий туннель не прерывался."
                                else -> "Аккаунт подключён. Активной подписки для импорта пока нет."
                            }
                        }
                        host.runOnUiThread {
                            if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                            primary.isEnabled = true
                            result.onSuccess { (session, message) ->
                                status.setTextColor(DeyttUi.MINT)
                                status.text = host.uiCopy(message)
                                setStep(2)
                                host.updateTelegramUsername(session.username)
                                host.refreshTelegramAccount()
                                host.refreshAccountViews()
                            }.onFailure {
                                val reference = SubscriptionLoadDiagnostics.record(host, "pair", it)
                                primary.text = host.uiCopy("Подтвердить код")
                                status.setTextColor(DeyttUi.CORAL)
                                status.text = "${host.uiCopy(pairingError(it))} ${host.uiCopy("код")}: $reference"
                            }
                        }
                    }
                }
                else -> dialog.dismiss()
            }
        }
        setStep(0)
        showSheet(dialog, sheet)
    }

    private fun pairingError(error: Throwable): String = when ((error as? TelegramPairingException)?.code) {
        "invalid_username" -> "Проверь username Telegram."
        "pairing_rate_limited", "rate_limited" -> "Слишком частые попытки. Подожди немного и повтори."
        "pair_code_locked" -> "Лимит попыток исчерпан. Создай новую ссылку."
        "pair_code_invalid" -> "Код неверный или уже истёк. Проверь Telegram или создай новую ссылку."
        "account_blocked", "blocked" -> "Для этого аккаунта вход недоступен. Напиши в поддержку."
        else -> "Не удалось завершить вход. Проверь соединение и попробуй ещё раз."
    }

    private fun showLinkedTelegramAccount() {
        val token = TelegramSessionStore.read(host) ?: run {
            showTelegramPairing()
            return
        }
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        sheet.addView(host.mono("./c · TELEGRAM", 9f, MUTED, 650))
        sheet.addView(host.text(copy("Аккаунт подключён", "Account connected"), 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(7))
        })
        sheet.addView(host.text(
            copy("Профиль и сессия связаны с Telegram. Переподключение загрузит свежие профили; отключение завершит эту сессию.", "Your profile and session are linked to Telegram. Reconnect to refresh profiles, or disconnect to end this account session."),
            13f, MUTED,
        ).apply { setLineSpacing(host.dp(3).toFloat(), 1f) })
        sheet.addView(host.button(copy("Переподключить", "Reconnect"), secondary = true).apply {
            setOnClickListener {
                dialog.dismiss()
                showTelegramPairing()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
            topMargin = host.dp(14)
        })
        sheet.addView(host.button(copy("Отключить аккаунт", "Disconnect account"), secondary = true).apply {
            setOnClickListener {
                dialog.dismiss()
                confirmTelegramLogout(token)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
            topMargin = host.dp(8)
        })
        showSheet(dialog, sheet)
    }

    private fun confirmTelegramLogout(token: String) {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        sheet.addView(host.mono("./c · СЕССИЯ", 9f, MUTED, 650))
        sheet.addView(host.text("Отключить Telegram?", 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(7))
        })
        sheet.addView(host.text("Приложение перестанет получать профиль и обновления ключей.", 13f, MUTED))
        val status = host.text("", 12f, DeyttUi.CORAL).apply { setPadding(0, host.dp(10), 0, 0) }
        sheet.addView(status)
        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, host.dp(14), 0, 0)
        }
        val cancel = host.button("Отмена", secondary = true).apply { setOnClickListener { dialog.dismiss() } }
        actions.addView(cancel, LinearLayout.LayoutParams(0, host.dp(48), 1f).apply { marginEnd = host.dp(8) })
        val unlink = host.button("Отключить")
        actions.addView(unlink, LinearLayout.LayoutParams(0, host.dp(48), 1f))
        unlink.setOnClickListener {
            unlink.isEnabled = false
            unlink.text = host.uiCopy("Завершаем…")
            pairingExecutor.execute {
                val result = runCatching { TelegramPairingClient.logout(token) }
                host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    result.onSuccess {
                        TelegramSessionStore.clear(host)
                        dialog.dismiss()
                        host.updateTelegramIdentity(null, null)
                        host.refreshTelegramAccount()
                        host.refreshAccountViews()
                    }.onFailure {
                        unlink.isEnabled = true
                        unlink.text = host.uiCopy("Повторить")
                        status.text = host.uiCopy("Не удалось отозвать сессию. Проверь соединение и повтори.")
                    }
                }
            }
        }
        sheet.addView(actions)
        showSheet(dialog, sheet)
    }

    private fun openLatest() {
        latestRelease?.pageUrl?.takeIf(ReleaseUrlPolicy::isOfficialPage)?.let(::openUrl)
            ?: updateStatus?.apply { text = host.uiCopy("Ссылка на релиз не прошла проверку.") }
    }

    private fun preferenceSwitch(
        title: String,
        description: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(1), host.dp(12), host.dp(1), host.dp(12))
        val copy = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(host.text(title, 14f, TEXT, android.graphics.Typeface.BOLD))
            addView(host.text(description, 11f, MUTED).apply {
                setPadding(0, host.dp(4), host.dp(8), 0)
            })
        }
        val toggle = Switch(host).apply {
            isChecked = checked
            contentDescription = host.uiCopy(title) + ". " + host.uiCopy(description)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(DeyttUi.MINT, 0xFF8C96A5.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0xA63CDCC0.toInt(), 0x665B6675.toInt()))
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        isClickable = true
        isFocusable = false
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun openUrl(url: String) {
        runCatching {
            val uri = Uri.parse(url)
            if (uri.scheme?.lowercase() !in setOf("https", "tg")) return
            host.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun formatBytes(value: Long): String {
        val english = AppLanguage.current(host) == AppLanguage.EN
        if (value <= 0) return if (english) "0 B" else "0 Б"
        val units = if (english) arrayOf("B", "KB", "MB", "GB", "TB") else arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    private fun formatDate(seconds: Long): String {
        val locale = if (AppLanguage.current(host) == AppLanguage.EN) Locale.ENGLISH else Locale("ru")
        return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(seconds * 1000))
    }
}

private class UsageBar(context: MainActivity, private val fraction: Float) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        paint.color = SURFACE_2
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        rect.right = (width * fraction).coerceAtLeast(if (fraction > 0) h else 0f)
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(BLUE, BLUE_DEEP),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        paint.shader = null
    }
}
