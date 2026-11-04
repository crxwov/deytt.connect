package space.deytt.connect

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.AMBER
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.glassRowBackground
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.prefersReducedMotion
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.ripple
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.routePairMark
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

internal class RoutePageView(
    private val host: MainActivity,
    private val copy: (String, String) -> String,
    private val presenter: RouteProbePresenter,
) {
    fun build(
        expandedCountryCodes: MutableSet<String>,
        onScrollChanged: (Int) -> Unit,
        initialScrollY: Int,
    ): View {
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
        ).apply { setPadding(0, 0, 0, host.dp(18)) })

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
                    copy("Обновляем подписку и маршруты…", "Refreshing subscription and routes…")
                }
                subtitleView?.text = host.uiCopy(phaseText)
                subtitleView?.setTextColor(DeyttUi.SKY)
                return
            }

            val elapsedSinceSuccess = android.os.SystemClock.elapsedRealtime() - SubscriptionRefreshState.lastSuccessTimestamp
            if (SubscriptionRefreshState.lastSuccessTimestamp > 0L && elapsedSinceSuccess < 4000L) {
                val successMsg = SubscriptionRefreshState.lastMessage.ifBlank {
                    copy("✓ Маршруты обновлены", "✓ Routes updated")
                }
                subtitleView?.text = host.uiCopy(successMsg)
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
                .sortedBy { presenter.routeProtocolOrder(it.protocol) }
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
                presenter.styling.cachedLatencySummary(cachedSamples) ?: copy("Отклик: —", "Response: —"),
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
                val rowSubtitle = if (isAwg) "${presenter.shortMark(route)} · ${route.protocol.title}" else route.protocol.detail
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
                    cached?.let { presenter.styling.sampleLabel(it, isBest, isWorst) }
                        ?: copy("Отклик: — · Скорость: —", "Response: — · Speed: —"),
                    11f,
                    presenter.styling.gradeColor(grade),
                ).apply {
                    maxLines = 2
                    setPadding(0, host.dp(4), 0, host.dp(8))
                    if (isBest || isWorst) setTypeface(null, android.graphics.Typeface.BOLD)
                }
                val progress = InlineMetricProgress(metric)
                val leading = when {
                    isAwg && route.protocol == RouteProtocol.AWG31 -> "AWG31_MARK"
                    isAwg -> "AWG_MARK"
                    else -> presenter.protocolMark(route.protocol)
                }
                val item = host.row(rowTitle, rowSubtitle, leading, "",
                    emphasis = route.id == selectedId)
                item.background = presenter.styling.routeRankBackground(grade, route.id == selectedId)
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
                    metric.text = cached?.let { presenter.styling.sampleLabel(it, isBest, isWorst) }
                        ?: copy("Отклик: — · Скорость: —", "Response: — · Speed: —")
                    metric.setOnClickListener { presenter.startAwgProbe(probeRow) }
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
                    if (expanded) presenter.startCountryProbe(countryState)
                    else presenter.cancelCountryProbe(countryState)
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
        return ScrollView(host).apply {
            isFillViewport = true
            addView(root)
            setOnScrollChangeListener { _, _, scrollY, _, _ -> onScrollChanged(scrollY) }
            post { scrollTo(0, initialScrollY) }
        }
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
}
