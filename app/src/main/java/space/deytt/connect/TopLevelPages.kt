package space.deytt.connect

import android.app.Dialog
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.AMBER
import space.deytt.connect.DeyttUi.BLUE
import space.deytt.connect.DeyttUi.BLUE_DEEP
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.SURFACE
import space.deytt.connect.DeyttUi.SURFACE_2
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.mapPanel
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.rounded
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
    private val createPage: (Int) -> View,
) : RecyclerView.Adapter<TopLevelViewPagerAdapter.PageHolder>() {
    private val pages = arrayOfNulls<View>(PAGE_COUNT)

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
        pages[position]?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pages[position] = null
        notifyItemChanged(position)
    }

    class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private companion object {
        const val PAGE_COUNT = 4
    }
}

internal class PrimaryPages(private val host: MainActivity) {
    private data class ProbeRouteRow(val route: DeyttRoute, val status: TextView, val progress: ProgressBar)
    private data class CountryProbeState(
        val code: String,
        val routes: List<DeyttRoute>,
        val rows: Map<String, ProbeRouteRow>,
        val samples: MutableMap<String, RouteProbeSample> = mutableMapOf(),
        var selectionAtStart: String? = null,
        var cancelled: Boolean = false,
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
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val pairingExecutor = Executors.newSingleThreadExecutor()
    private val updateHandler = Handler(Looper.getMainLooper())
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
        updateHandler.removeCallbacks(updateCycle)
        checkForUpdate(force = false)
        updateHandler.postDelayed(updateCycle, UPDATE_CHECK_INTERVAL_MS)
    }

    fun stopForegroundUpdates() {
        diagnosticForeground = false
        pendingCountries.clear()
        routeProbeStates.values.forEach { it.cancelled = true }
        activeAwgCountry?.let { cancelCountryProbe(it) }
        if (awgMeasurementRunning) host.cancelAwgMeasurement()
        updateHandler.removeCallbacks(updateCycle)
    }

    private fun scrollPage(content: LinearLayout): ScrollView = ScrollView(host).apply {
        isFillViewport = true
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun routePage(): View {
        val config = SubscriptionStore(host).readCurrent()
        val profiles = AwgProfileStore(host).profiles()
        val routes = config?.let { RouteCatalog.from(it, profiles) }.orEmpty()
        val selectedId = SelectedRouteStore(host).read().id
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("выходы · выбор · диагностика", "Маршруты"))
        root.addView(spacer(8, host))
        root.addView(host.text("Раскройте страну, чтобы проверить выходы. Выбор не запускает подключение.", 13f, MUTED)
            .apply { setPadding(0, 0, 0, host.dp(18)) })

        root.addView(host.sectionLabel("быстрый выбор"))
        val quickGroup = routeGroup()
        routes.firstOrNull { it.protocol == RouteProtocol.AUTO }?.let { route ->
            appendToGroup(quickGroup, host.row("Автоподбор", "Выберем доступный узел", "✦", "выбрать", selectedId == route.id)
                .apply { setOnClickListener { host.selectRoute(route) } })
        }
        routes.firstOrNull { it.protocol == RouteProtocol.RU_DE }?.let { route ->
            appendToGroup(quickGroup, host.row(
                copy("LTE + белые списки", "LTE + whitelist"),
                copy("Россия → Германия · двойной маршрут", "Russia → Germany · double route"),
                "ROUTE_RU_DE",
                "выбрать",
                selectedId == route.id,
            ).apply { setOnClickListener { host.selectRoute(route) } })
        }
        if (quickGroup.childCount > 0) root.addView(quickGroup)

        root.addView(spacer(24, host))
        root.addView(host.sectionLabel(copy("Страны", "Countries")))
        val orderedCodes = listOf("NL", "DE", "RU", "FI", "AWG_UNKNOWN")
        val countries = routes.filter { it.countryCode in orderedCodes }
            .groupBy { it.countryCode }
        orderedCodes.filter { it in countries }.forEach { code ->
            val countryRoutes = countries[code].orEmpty().distinctBy { it.id }
            val networkRoutes = countryRoutes.filter { it.engine == TunnelEngine.LIBBOX }
                .sortedBy { routeProtocolOrder(it.protocol) }
            val awgRoutes = countryRoutes.filter { it.engine == TunnelEngine.AMNEZIAWG }
            val first = countryRoutes.first()
            val protocolSummary = countryRoutes.map { route ->
                when (route.engine) {
                    TunnelEngine.LIBBOX -> route.protocol.title
                    TunnelEngine.AMNEZIAWG -> "AmneziaWG ${if (route.protocol == RouteProtocol.AWG31) "3.1" else "1.5"}"
                }
            }.distinct().joinToString(" · ")
            val detail = routeGroup().apply { visibility = View.GONE }
            val rowById = linkedMapOf<String, ProbeRouteRow>()
            countryRoutes.forEachIndexed { index, route ->
                if (index > 0) detail.addView(View(host).apply { setBackgroundColor(LINE) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)).apply {
                        leftMargin = host.dp(54)
                        rightMargin = host.dp(16)
                    })
                val isAwg = route.engine == TunnelEngine.AMNEZIAWG
                val rowTitle = if (isAwg) route.profileName ?: route.protocol.title else route.protocol.title
                val rowSubtitle = if (isAwg) "${route.shortMark()} · ${route.protocol.title}" else route.protocol.detail
                val cached = RouteQualityStore.read(host, route.id)
                val metric = host.text(cached?.let(::sampleLabel) ?: copy("Ещё не проверен", "Not measured yet"), 11f, MUTED).apply {
                    setPadding(host.dp(54), 0, host.dp(16), host.dp(12))
                }
                val spinner = ProgressBar(host, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(DeyttUi.SKY)
                    visibility = View.GONE
                }
                val item = host.row(rowTitle, rowSubtitle, if (isAwg) "AWG_MARK" else protocolMark(route.protocol), "",
                    emphasis = route.id == selectedId)
                val protocolDrawable = when (route.protocol) {
                    RouteProtocol.VLESS -> R.drawable.ic_xray
                    RouteProtocol.HYSTERIA2 -> R.drawable.ic_hysteria
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
                item.addView(LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(spinner, LinearLayout.LayoutParams(host.dp(18), host.dp(18)))

                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, host.dp(44)))
                item.setOnClickListener { host.selectRoute(route) }
                val probeRow = ProbeRouteRow(route, metric, spinner)
                if (isAwg) {
                    item.setOnClickListener {
                        AppDialog.Builder(host)
                            .setTitle(host.uiCopy(rowTitle))
                            .setItems(arrayOf(copy("Выбрать маршрут", "Select route"), copy("Подключить и проверить", "Connect and measure"))) { _, action ->
                                if (action == 0) host.selectRoute(route) else startAwgProbe(probeRow)
                            }.show()
                    }
                    metric.text = cached?.let(::sampleLabel) ?: copy("Автопроверка после HTTP-маршрутов", "Automatic check after HTTP routes")
                    metric.setOnClickListener { startAwgProbe(probeRow) }
                }
                rowById[route.id] = probeRow
                detail.addView(item)
                detail.addView(metric)
            }
            val countryState = CountryProbeState(code, networkRoutes + awgRoutes, rowById)
            val countryHeader = host.row(
                if (code == "AWG_UNKNOWN") "Регион не указан" else first.country,
                if (protocolSummary.isBlank()) "Маршруты не найдены" else protocolSummary,
                first.flag,
                "⌄",
                emphasis = countryRoutes.any { it.id == selectedId },
            ).apply {
                contentDescription = host.uiCopy("Раскрыть выходы ${first.country}; откроется проверка HTTP маршрутов")
                setOnClickListener {
                    val expanded = detail.visibility != View.VISIBLE
                    detail.visibility = if (expanded) View.VISIBLE else View.GONE
                    findViewWithTag<TextView>("country-chevron")?.text = if (expanded) "⌃" else "⌄"
                    if (expanded) startCountryProbe(countryState)
                    else cancelCountryProbe(countryState)
                }
                (getChildAt(childCount - 1) as? TextView)?.tag = "country-chevron"
            }
            root.addView(countryHeader)
            root.addView(detail)
            root.addView(spacer(6, host))
        }
        if (routes.isEmpty()) {
            root.addView(host.note("Пока нет маршрутов. Обновите подписку по ссылке, с которой открывали приложение.", AMBER))
        }
        root.addView(spacer(18, host))
        return scrollPage(root)
    }

    private fun routeProtocolOrder(protocol: RouteProtocol): Int = when (protocol) {
        RouteProtocol.VLESS -> 0
        RouteProtocol.TROJAN -> 1
        RouteProtocol.HYSTERIA2 -> 2
        else -> 3
    }

    private fun protocolMark(protocol: RouteProtocol): String = when (protocol) {
        RouteProtocol.VLESS -> "VL"
        RouteProtocol.TROJAN -> "◇"
        RouteProtocol.HYSTERIA2 -> "H2"
        RouteProtocol.RU_DE -> "RU→DE"
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
            if (country.cancelled) pendingCountries[country.code] = country
            return
        }
        country.cancelled = false
        if (RouteProbeClient.isRunning(host) || awgMeasurementRunning) {
            pendingCountries[country.code] = country
            country.rows.values.forEach { it.status.text = copy("В очереди…", "Queued…") }
            return
        }
        country.selectionAtStart = SelectedRouteStore(host).read().id
        country.samples.clear()
        if (VpnService.prepare(host) != null && host.requestRouteProbePermission { startCountryProbe(country) }) return
        val config = SubscriptionStore(host).readCurrent() ?: return
        country.rows.values.forEach { row ->
            row.status.text = host.uiCopy("в очереди")
            row.status.setTextColor(DeyttUi.SKY)
            row.progress.visibility = View.VISIBLE
        }
        val token = TelegramSessionStore.read(host)
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
                    row.progress.visibility = View.GONE
                    row.status.text = host.uiCopy("ошибка")
                    row.status.setTextColor(DeyttUi.CORAL)
                }
                startCountryAwgProbes(country)
            }
    }

    fun onRouteProbeEvent(event: Intent) {
        val requestId = event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty()
        val country = routeProbeStates[requestId] ?: run {
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) launchNextCountry()
            return
        }
        if (country.cancelled) {
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                routeProbeStates.remove(requestId)
                launchNextCountry()
            }
            return
        }
        val tag = event.getStringExtra(RouteProbeClient.EXTRA_ROUTE_TAG).orEmpty()
        if (tag.isNotBlank()) {
            val row = country.rows.values.firstOrNull { it.route.configTag == tag }
            if (row != null) {
                val previous = country.samples[row.route.id] ?: RouteProbeSample(row.route.id, null, null)
                val latency = event.getLongExtra(RouteProbeClient.EXTRA_MILLISECONDS, -1L)
                    .takeIf { it >= 0L } ?: previous.latencyMillis
                val speed = event.getLongExtra(RouteProbeClient.EXTRA_BYTES_PER_SECOND, -1L)
                    .takeIf { it >= 0L } ?: previous.downloadBytesPerSecond
                country.samples[row.route.id] = RouteProbeSample(row.route.id, latency, speed)
                when (event.getStringExtra(RouteProbeClient.EXTRA_STAGE)
                    ?: if (!event.getStringExtra(RouteProbeClient.EXTRA_ERROR).isNullOrBlank()) "failed" else "") {
                    "latency" -> row.status.text = host.uiCopy("измеряем задержку…")
                    "download" -> row.status.text = host.uiCopy("измеряем скорость…")
                    "waiting_download" -> row.status.text = copy("задержка: ", "ping: ") +
                        (latency?.let(::formatLatency) ?: "—") + copy(" · скорость в очереди", " · speed queued")
                    "complete", "failed" -> {
                        row.progress.visibility = View.GONE
                        row.status.text = sampleLabel(country.samples.getValue(row.route.id))
                        if (speed == null) row.status.append("\n" + copy("Скорость не измерена · нажмите страну для повтора", "Speed unavailable · reopen country to retry"))
                        country.samples[row.route.id]?.let { RouteQualityStore.write(host, it) }
                        row.status.setTextColor(if (latency != null) DeyttUi.TEXT else DeyttUi.CORAL)
                    }
                }
                event.getStringExtra(RouteProbeClient.EXTRA_ERROR)?.let { error ->
                    if (error.isNotBlank()) row.status.contentDescription = host.uiCopy(error)
                }
            }
        }
        if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
            routeProbeStates.remove(requestId)
            val requestError = event.getStringExtra(RouteProbeClient.EXTRA_ERROR)
            if (!requestError.isNullOrBlank()) country.rows.values.forEach { row ->
                row.status.text = host.uiCopy(requestError)
            }
            startCountryAwgProbes(country)
        }
    }

    private fun finishCountryProbe(country: CountryProbeState) {
        if (country.cancelled || closed || !diagnosticForeground) { launchNextCountry(); return }
        val grades = RouteProbeScoring.grade(country.samples.values.toList())
        country.rows.values.forEach { row ->
            row.progress.visibility = View.GONE
            row.status.setTextColor(when (grades[row.route.id] ?: RouteGrade.UNRATED) {
                RouteGrade.GOOD -> DeyttUi.MINT
                RouteGrade.MEDIUM -> AMBER
                RouteGrade.POOR -> DeyttUi.CORAL
                RouteGrade.UNRATED -> DeyttUi.MUTED
            })
            row.status.contentDescription = row.status.text
        }
        val best = RouteProbeScoring.bestRouteId(country.samples.values.toList())?.let { id -> country.routes.firstOrNull { it.id == id } }
        best?.let { country.rows[it.id]?.status?.append(copy(" · лучший", " · best")) }
        launchNextCountry()
    }

    private fun startCountryAwgProbes(country: CountryProbeState) {
        val rows = country.rows.values.filter { it.route.engine == TunnelEngine.AMNEZIAWG }
        if (rows.isEmpty() || country.cancelled || closed || !diagnosticForeground) { finishCountryProbe(country); return }
        if (TelegramSessionStore.read(host) == null) {
            rows.forEach { it.progress.visibility = View.GONE; it.status.text = copy("Подключите Telegram для проверки", "Link Telegram to measure") }
            finishCountryProbe(country)
            return
        }
        awgMeasurementRunning = true
        activeAwgCountry = country
        rows.forEach { it.status.text = copy("В очереди · временный VPN только для приложения", "Queued · temporary app-only VPN") }
        host.requestAwgMeasurements(rows.map { it.route }, onComplete = {
            awgMeasurementRunning = false
            activeAwgCountry = null
            rows.forEach { row ->
                row.progress.visibility = View.GONE
                if (!country.cancelled && row.route.id !in country.samples) row.status.text = copy("Проверка недоступна · нажмите для повтора", "Measurement unavailable · tap to retry")
            }
            finishCountryProbe(country)
        }, onRouteFailure = { route ->
            country.rows[route.id]?.let { row ->
                row.progress.visibility = View.GONE
                row.status.text = copy("Нет ответа · нажмите для повтора", "No response · tap to retry")
            }
        }, onRouteStarting = { route ->
            country.rows[route.id]?.status?.text = copy("Подключаем AmneziaWG…", "Connecting AmneziaWG…")
        }, measure = { route, finished ->
            val row = country.rows.getValue(route.id)
            measureAwgRow(row, country, finished)
        })
    }

    private fun cancelCountryProbe(country: CountryProbeState) {
        pendingCountries.remove(country.code)
        country.cancelled = true
        country.rows.values.forEach {
            it.progress.visibility = View.GONE
            if (it.route.id !in country.samples) it.status.text = copy("Проверка отменена · откройте страну снова", "Measurement cancelled · reopen country")
        }
        if (routeProbeStates.values.any { it === country }) RouteProbeClient.cancel(host)
        if (activeAwgCountry === country) host.cancelAwgMeasurement()
    }

    private fun launchNextCountry() {
        updateHandler.postDelayed({
            if (!closed && diagnosticForeground && !host.isDestroyed && !RouteProbeClient.isRunning(host) && !awgMeasurementRunning) {
                pendingCountries.values.firstOrNull()?.let { next ->
                    pendingCountries.remove(next.code)
                    startCountryProbe(next)
                }
            }
        }, 200)
    }

    private fun copy(ru: String, en: String): String = if (AppLanguage.current(host) == AppLanguage.EN) en else ru

    private fun sampleLabel(sample: RouteProbeSample): String =
        copy("задержка: ", "ping: ") + (sample.latencyMillis?.let(::formatLatency) ?: "—") +
            "  ·  " + copy("скорость: ", "speed: ") + (sample.downloadBytesPerSecond?.let(::formatSpeed) ?: "—")

    private fun startAwgProbe(row: ProbeRouteRow) {
        if (awgMeasurementRunning || RouteProbeClient.isRunning(host) || closed) return
        if (TelegramSessionStore.read(host) == null) {
            row.status.text = copy("Подключите Telegram для измерения скорости", "Link Telegram to measure speed")
            return
        }
        awgMeasurementRunning = true
        row.progress.visibility = View.VISIBLE
        row.status.text = copy("Подключаем для проверки…", "Connecting to measure…")
        host.requestAwgMeasurement(row.route, onComplete = { successful ->
            awgMeasurementRunning = false
            row.progress.visibility = View.GONE
            if (!successful && RouteQualityStore.read(host, row.route.id) == null) row.status.text = copy("Проверка недоступна · повторите", "Measurement unavailable · retry")
            launchNextCountry()
        }) { finished -> measureAwgRow(row, null, finished) }
    }

    private fun measureAwgRow(row: ProbeRouteRow, country: CountryProbeState?, finished: (Boolean) -> Unit) {
        val token = TelegramSessionStore.read(host)
        if (closed || !diagnosticForeground || country?.cancelled == true || token == null) { finished(false); return }
        awgCompletion = finished
        row.progress.visibility = View.VISIBLE
        row.status.text = copy("Измеряем задержку…", "Measuring ping…")
        diagnosticExecutor.execute {
            var measuredLatency: Long? = null
            val result = runCatching {
                val current = { !closed && diagnosticForeground && country?.cancelled != true && AwgTunnelController.isRunning() &&
                    VpnStateStore(host).read().phase == VpnPhase.CONNECTED && SelectedRouteStore(host).read().id == row.route.id }
                check(current())
                val latency = RouteProxyProbe.measureThroughSystemVpn(RouteProbePreferences.method(host))
                measuredLatency = latency
                host.runOnUiThread { if (current()) row.status.text = copy("Задержка: ", "Ping: ") + formatLatency(latency) + copy(" · скорость 1 с…", " · speed 1 s…") }
                val speed = RouteProxyProbe.measureSystemDownload(token, current)
                RouteProbeSample(row.route.id, latency, speed).also { RouteQualityStore.write(host, it) }
            }
            host.runOnUiThread {
                row.progress.visibility = View.GONE
                if (!closed && diagnosticForeground && country?.cancelled != true) {
                    val sample = result.getOrNull() ?: measuredLatency?.let { RouteProbeSample(row.route.id, it, null) }
                    sample?.let { country?.samples?.put(row.route.id, it); RouteQualityStore.write(host, it) }
                    row.status.text = sample?.let(::sampleLabel) ?: copy("Не удалось измерить · повторите проверку", "Measurement failed · try again")
                    if (sample != null && sample.downloadBytesPerSecond == null) row.status.append(copy(" · скорость недоступна", " · speed unavailable"))
                    row.status.setTextColor(if (sample != null) DeyttUi.MINT else DeyttUi.CORAL)
                }
                // Only the matching measurement owns this continuation. A cancelled
                // worker must not advance a later country's diagnostic sequence.
                if (awgCompletion === finished) awgCompletion = null
                finished(result.isSuccess)
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
        background = host.rounded(SURFACE, 20f, LINE)
        clipToOutline = true
    }

    private fun appendToGroup(group: LinearLayout, item: View) {
        if (group.childCount > 0) {
            group.addView(View(host).apply { setBackgroundColor(LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)).apply {
                    leftMargin = host.dp(54)
                    rightMargin = host.dp(16)
                })
        }
        group.addView(item)
    }

    private fun profilePage(): View = profileFlows.page()

    private fun showSheet(dialog: Dialog, content: View) {
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.58f)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.setPadding(host.dp(14), 0, host.dp(14), host.dp(18))
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun settingsPage(): View {
        val root = host.screen(withBackdrop = true)
        root.addView(host.header(copy("под вас", "make it yours"), "Настройки"))
        root.addView(spacer(12, host))
        root.addView(host.sectionLabel(copy("язык и аккаунт", "language and account")))
        root.addView(host.row(copy("Язык приложения", "App language"), AppLanguage.label(host), "Aa", "›").apply {
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
        root.addView(host.row(
            if (linked) copy("Telegram подключён", "Telegram connected") else copy("Войти через Telegram", "Sign in with Telegram"),
            if (linked) copy("Управление аккаунтом", "Manage account") else copy("Подписка и устройства", "Subscription and devices"),
            if (linked) "✓" else "↗", "›", emphasis = linked,
        ).apply { setOnClickListener { if (TelegramSessionStore.read(host) == null) showTelegramPairing() else showLinkedTelegramAccount() } })

        root.addView(spacer(16, host))
        root.addView(host.sectionLabel(copy("подключение", "connection")))
        val probeSummary = { copy("HTTP ${RouteProbePreferences.method(host).title} · быстрая проверка, загрузка 1 с", "HTTP ${RouteProbePreferences.method(host).title} · fast check, 1 s download") }
        root.addView(host.row(copy("Проверка маршрута", "Route check"), probeSummary(), "↻", "›").apply {
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
        root.addView(host.row(copy("Разрешения VPN", "VPN permissions"), copy("Системные настройки Android", "Android system settings"), "↗", "›").apply {
            setOnClickListener { runCatching { host.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } }
        })
        root.addView(host.row(copy("Бот DEYTT", "DEYTT bot"), copy("Другие действия в Telegram", "More actions in Telegram"), "↗", "›").apply {
            setOnClickListener { openUrl("https://t.me/deyttbot") }
        })

        root.addView(spacer(16, host))
        root.addView(host.sectionLabel(copy("вид и приватность", "appearance and privacy")))
        root.addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(15), host.dp(3), host.dp(15), host.dp(3))
            background = host.rounded(SURFACE, 18f, LINE)
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
            background = host.rounded(SURFACE, 24f, LINE)
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
            primary.text = when (step) {
                0 -> "Получить код"
                1 -> "Подтвердить код"
                else -> "Готово"
            }
            title.text = when (step) {
                0 -> "Добавить приложение"
                1 -> if (needsBotStart) "Сначала открой ./c" else "Код отправлен"
                else -> "Аккаунт подключён"
            }
            detail.text = when (step) {
                0 -> "Укажи Telegram username. Бот подтвердит вход одноразовым кодом и подключит существующие профили."
                1 -> if (needsBotStart) {
                    "Этот аккаунт ещё не начинал чат с ботом. Открой ./c один раз — код придёт сюда."
                } else {
                    "Код уже отправлен в Telegram. Оставайся в приложении и введи его ниже."
                }
                else -> ""
            }
        }
        fun openBotLink() {
            val link = botUrl ?: return
            runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                .onFailure { status.text = "Ссылка готова. Открой бота ещё раз кнопкой ниже." }
        }

        openBot.setOnClickListener { openBotLink() }
        primary.setOnClickListener {
            when (step) {
                0 -> {
                    val username = usernameField.text.toString().trim()
                    if (!Regex("@?[A-Za-z0-9_]{5,32}").matches(username)) {
                        status.text = "Введи username Telegram длиной от 5 до 32 знаков."
                        status.setTextColor(DeyttUi.CORAL)
                        return@setOnClickListener
                    }
                    primary.isEnabled = false
                    primary.text = "Создаём ссылку…"
                    status.setTextColor(BLUE)
                    status.text = "Подготавливаем одноразовое подтверждение."
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
                                status.text = when (it.delivery) {
                                    "sent" -> "Код отправлен в Telegram и действует 5 минут. Переключаться в бот не нужно."
                                    "delivery_failed" -> "Не получилось доставить сообщение. Открой ./c ниже, чтобы получить код."
                                    else -> "Этого аккаунта пока нет в боте. Открой ./c ниже один раз — код придёт в чат."
                                }
                            }.onFailure {
                                primary.text = "Продолжить"
                                status.setTextColor(DeyttUi.CORAL)
                                status.text = pairingError(it)
                            }
                        }
                    }
                }
                1 -> {
                    val currentChallenge = challenge
                    val code = codeField.text.toString().trim()
                    if (currentChallenge == null || !Regex("\\d{6}").matches(code)) {
                        status.text = "Введи шесть цифр из сообщения бота."
                        status.setTextColor(DeyttUi.CORAL)
                        return@setOnClickListener
                    }
                    primary.isEnabled = false
                    primary.text = "Проверяем код…"
                    status.text = "Подтверждаем Telegram-аккаунт и загружаем профили."
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
                                    host.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE)
                                        .edit().putString("subscription_url", imported.url).apply()
                                    val routes = RouteCatalog.from(
                                        SubscriptionStore(host).readCurrent().orEmpty(),
                                        AwgProfileStore(host).profiles(),
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
                                status.text = message
                                setStep(2)
                                host.updateTelegramUsername(session.username)
                                host.refreshTelegramAccount()
                                host.refreshAccountViews()
                            }.onFailure {
                                primary.text = "Подтвердить код"
                                status.setTextColor(DeyttUi.CORAL)
                                status.text = pairingError(it)
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
            background = host.rounded(SURFACE, 24f, LINE)
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
            background = host.rounded(SURFACE, 24f, LINE)
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
            unlink.text = "Завершаем…"
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
                        unlink.text = "Повторить"
                        status.text = "Не удалось отозвать сессию. Проверь соединение и повтори."
                    }
                }
            }
        }
        sheet.addView(actions)
        showSheet(dialog, sheet)
    }

    private fun openLatest() {
        latestRelease?.pageUrl?.takeIf(ReleaseUrlPolicy::isOfficialPage)?.let(::openUrl)
            ?: updateStatus?.apply { text = "Ссылка на релиз не прошла проверку." }
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
        runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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
