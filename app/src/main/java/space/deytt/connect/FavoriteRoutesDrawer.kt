package space.deytt.connect

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.glassRowBackground
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.ripple
import space.deytt.connect.DeyttUi.text
import java.util.concurrent.Executors

/**
 * Compact Favorite Routes drawer for MainActivity Home screen.
 * Pulling up physically shifts the Home page upwards and reveals favorites from below.
 * Tapping any route immediately connects and dismisses.
 * Full speed and ping measurement matching the routes page.
 */
@SuppressLint("ViewConstructor")
internal class FavoriteRoutesDrawer(
    private val host: MainActivity,
    private val onRouteSelected: (DeyttRoute) -> Unit,
) {
    private val store = FavoriteRoutesStore(host)
    private var parentStage: ViewGroup? = null
    private var isDrawerOpen = false
    private var isMeasuring = false
    private val measureExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var overlayRoot: FrameLayout
    private lateinit var scrimView: View
    private lateinit var sheetContainer: LinearLayout
    private lateinit var cardsLayout: LinearLayout
    private lateinit var emptyCard: View
    private lateinit var measureButton: TextView
    private lateinit var countBadge: TextView

    private val statusViews = mutableMapOf<String, TextView>()
    private val currentFavRoutes = mutableListOf<DeyttRoute>()
    private var currentProbeRequestId: String? = null
    private var shiftAnimator: ValueAnimator? = null

    private val maxHomeShiftPx: Float get() = host.dp(140).toFloat()

    fun attachTo(stage: ViewGroup) {
        parentStage = stage
        buildViews()
        stage.addView(overlayRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        FavoriteRoutesStore.addListener(::refreshFavorites)
    }

    fun detach() {
        FavoriteRoutesStore.removeListener(::refreshFavorites)
        shiftAnimator?.cancel()
        parentStage?.removeView(overlayRoot)
        parentStage = null
    }

    fun isOpen(): Boolean = isDrawerOpen

    private fun sheetHeight(): Int {
        if (!::sheetContainer.isInitialized) return host.dp(260)
        val h = sheetContainer.height
        return if (h > 0) h else host.dp(260)
    }

    private fun buildViews() {
        overlayRoot = FrameLayout(host).apply {
            visibility = View.GONE
            isClickable = false
            clipChildren = false
            clipToPadding = false
        }

        scrimView = View(host).apply {
            setBackgroundColor(0xC0060A10.toInt())
            alpha = 0f
            isClickable = false
        }
        overlayRoot.addView(scrimView, FrameLayout.LayoutParams(-1, -1))

        sheetContainer = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = LiquidGlassDrawable(
                density = host.resources.displayMetrics.density,
                cornerRadiusPx = host.dp(24).toFloat(),
                fillColors = intArrayOf(0xF80B1422.toInt(), 0xFC060C14.toInt()),
                strokeColors = intArrayOf(
                    0x60FFFFFF.toInt(),
                    0x2888C0F8.toInt(),
                    0x1A6AAAF0.toInt(),
                    0x28FFFFFF.toInt(),
                ),
                strokePositions = floatArrayOf(0f, 0.32f, 0.70f, 1f),
                strokeWidthPx = 1.15f,
                hasGloss = true,
                glossAlpha = 0x18,
                innerLip = true,
            )
            clipToOutline = true
            setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(14))
            isClickable = true
        }

        var startY = 0f
        var isDraggingDown = false
        val touchSlop = ViewConfiguration.get(host).scaledTouchSlop
        sheetContainer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDraggingDown = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startY
                    if (!isDraggingDown && dy > touchSlop) {
                        isDraggingDown = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (isDraggingDown) {
                        sheetContainer.translationY = dy.coerceAtLeast(0f)
                        val h = sheetHeight().toFloat()
                        val dismissProgress = (dy / h).coerceIn(0f, 1f)
                        scrimView.alpha = (1f - dismissProgress * 0.85f).coerceIn(0f, 1f)
                        host.setHomeContentShift((1f - dismissProgress) * maxHomeShiftPx)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingDown) {
                        val dy = event.rawY - startY
                        if (dy > host.dp(50)) {
                            animateClose(fromCurrent = true)
                        } else {
                            animateOpen(fromCurrent = true)
                        }
                        isDraggingDown = false
                        true
                    } else false
                }
                else -> false
            }
        }

        // Top drag handle pill
        val handlePill = View(host).apply {
            background = host.glassRowBackground(emphasis = false, radius = 3f)
        }
        sheetContainer.addView(handlePill, LinearLayout.LayoutParams(host.dp(40), host.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = host.dp(12)
        })

        // Header row
        val headerRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heartIcon = ImageView(host).apply {
            setImageResource(R.drawable.ic_heart_filled)
            imageTintList = ColorStateList.valueOf(0xFFFF5C7A.toInt())
        }
        headerRow.addView(heartIcon, LinearLayout.LayoutParams(host.dp(15), host.dp(15)).apply { marginEnd = host.dp(7) })
        val titleText = host.text("избранные маршруты", 16f, DeyttUi.TEXT, Typeface.BOLD)
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        countBadge = host.mono("", 10.5f, DeyttUi.SKY, 650).apply {
            setPadding(host.dp(7), host.dp(2), host.dp(7), host.dp(2))
            background = host.glassRowBackground(emphasis = true, radius = 9f)
        }
        headerRow.addView(countBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = host.dp(8)
        })

        measureButton = host.text("измерить", 11f, DeyttUi.SKY, Typeface.BOLD).apply {
            background = host.glassRowBackground(emphasis = false, radius = 12f)
            setPadding(host.dp(9), host.dp(4), host.dp(9), host.dp(4))
            isClickable = true
            isFocusable = true
            foreground = host.ripple(12f)
            host.pressFeedback(this)
            setOnClickListener { measureFavorites() }
        }
        headerRow.addView(measureButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = host.dp(8)
        })

        val closeBtn = ImageView(host).apply {
            setImageResource(R.drawable.ic_chevron)
            rotation = 90f
            imageTintList = ColorStateList.valueOf(DeyttUi.MUTED)
            background = host.glassRowBackground(emphasis = false, radius = 15f)
            setPadding(host.dp(5), host.dp(5), host.dp(5), host.dp(5))
            isClickable = true
            foreground = host.ripple(15f)
            host.pressFeedback(this)
            setOnClickListener { close() }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(host.dp(28), host.dp(28)))
        sheetContainer.addView(headerRow)

        // Cards layout inside bounded ScrollView (max 46% screen height, wrap_content otherwise)
        cardsLayout = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = object : ScrollView(host) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (host.resources.displayMetrics.heightPixels * 0.46f).toInt()
                val mode = MeasureSpec.getMode(heightMeasureSpec)
                val size = MeasureSpec.getSize(heightMeasureSpec)
                val constrainedSpec = MeasureSpec.makeMeasureSpec(
                    if (mode == MeasureSpec.UNSPECIFIED) maxH else minOf(size, maxH),
                    MeasureSpec.AT_MOST,
                )
                super.onMeasure(widthMeasureSpec, constrainedSpec)
            }
        }.apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(cardsLayout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sheetContainer.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(10)
        })

        // Empty state view
        emptyCard = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(host.dp(16), host.dp(18), host.dp(16), host.dp(18))
            background = host.glassRowBackground(emphasis = false, radius = 18f)
            val icon = ImageView(host).apply {
                setImageResource(R.drawable.ic_heart)
                imageTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
            }
            addView(icon, LinearLayout.LayoutParams(host.dp(30), host.dp(30)))
            val emptyTitle = host.text("пока нет избранных", 14f, DeyttUi.TEXT, Typeface.BOLD).apply {
                setPadding(0, host.dp(6), 0, host.dp(3))
            }
            addView(emptyTitle)
            val emptyMsg = host.text(
                "зажмите любой маршрут на вкладке маршруты, чтобы добавить его сюда.",
                11.5f,
                DeyttUi.MUTED,
            ).apply {
                gravity = Gravity.CENTER
            }
            addView(emptyMsg)
            val goRoutes = host.text("перейти к маршрутам ›", 12f, DeyttUi.SKY, Typeface.BOLD).apply {
                setPadding(0, host.dp(10), 0, 0)
                isClickable = true
                setOnClickListener {
                    close()
                    host.selectTab(1)
                }
            }
            addView(goRoutes)
        }

        val sheetParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            val gutter = host.dp(12)
            val bottomNavPadding = host.dp(78)
            setMargins(gutter, 0, gutter, bottomNavPadding)
        }
        overlayRoot.addView(sheetContainer, sheetParams)
    }

    // Touch dragging from Home screen
    fun onDragStart() {
        if (isDrawerOpen) return
        refreshFavorites()
        overlayRoot.visibility = View.VISIBLE
        overlayRoot.isClickable = false
        val h = sheetHeight().toFloat()
        sheetContainer.translationY = h + host.dp(30)
        scrimView.alpha = 0f
    }

    fun onDragMove(dy: Float) {
        if (isDrawerOpen) return
        val h = sheetHeight().toFloat()
        val cappedDy = dy.coerceAtLeast(0f)
        val progress = (cappedDy / h).coerceIn(0f, 1f)
        sheetContainer.translationY = h * (1f - progress)
        scrimView.alpha = progress * 0.85f
        host.setHomeContentShift(progress * maxHomeShiftPx)
    }

    fun onDragEnd(dy: Float) {
        if (isDrawerOpen) return
        if (dy > host.dp(45)) {
            animateOpen(fromCurrent = true)
        } else {
            animateClose(fromCurrent = true)
        }
    }

    fun onDragCancel() {
        if (!isDrawerOpen) {
            animateClose(fromCurrent = true)
        }
    }

    private fun animateOpen(fromCurrent: Boolean) {
        isDrawerOpen = true
        overlayRoot.visibility = View.VISIBLE
        overlayRoot.isClickable = true
        scrimView.isClickable = true
        scrimView.setOnClickListener { close() }

        sheetContainer.animate().cancel()
        scrimView.animate().cancel()
        shiftAnimator?.cancel()

        sheetContainer.animate()
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        scrimView.animate()
            .alpha(1f)
            .setDuration(220L)
            .start()

        val startShift = host.getHomeContentShift()
        shiftAnimator = ValueAnimator.ofFloat(startShift, maxHomeShiftPx).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { host.setHomeContentShift(it.animatedValue as Float) }
            start()
        }
    }

    private fun animateClose(fromCurrent: Boolean) {
        isDrawerOpen = false
        overlayRoot.isClickable = false
        scrimView.isClickable = false
        scrimView.setOnClickListener(null)

        sheetContainer.animate().cancel()
        scrimView.animate().cancel()
        shiftAnimator?.cancel()

        val h = sheetHeight().toFloat()
        sheetContainer.animate()
            .translationY(h + host.dp(60))
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        scrimView.animate()
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                if (!isDrawerOpen) overlayRoot.visibility = View.GONE
            }
            .start()

        val startShift = host.getHomeContentShift()
        shiftAnimator = ValueAnimator.ofFloat(startShift, 0f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { host.setHomeContentShift(it.animatedValue as Float) }
            start()
        }
    }

    fun open() {
        if (isDrawerOpen) return
        refreshFavorites()
        overlayRoot.visibility = View.VISIBLE
        sheetContainer.post {
            val h = sheetHeight().toFloat()
            sheetContainer.translationY = h + host.dp(60)
            scrimView.alpha = 0f
            animateOpen(fromCurrent = false)
        }
    }

    fun close() {
        if (!isDrawerOpen) return
        animateClose(fromCurrent = false)
    }

    fun refreshFavorites() {
        cardsLayout.removeAllViews()
        statusViews.clear()

        val favIds = store.getFavorites()
        val allRoutes = host.getCachedRouteCatalog()
        val selectedId = host.getCachedSelectedRoute().id

        countBadge.text = "${favIds.size}"

        if (favIds.isEmpty()) {
            cardsLayout.addView(emptyCard)
            return
        }

        val favRoutes = mutableListOf<DeyttRoute>()
        favIds.forEach { id ->
            when (id) {
                "auto" -> allRoutes.firstOrNull { it.protocol == RouteProtocol.AUTO }?.let { favRoutes.add(it) }
                "lte" -> allRoutes.firstOrNull { it.protocol == RouteProtocol.LTE || it.protocol == RouteProtocol.RU_DE }?.let { favRoutes.add(it) }
                else -> allRoutes.firstOrNull { it.id == id }?.let { favRoutes.add(it) }
            }
        }

        if (favRoutes.isEmpty()) {
            cardsLayout.addView(emptyCard)
            return
        }

        favRoutes.forEach { route ->
            val isSelected = route.id == selectedId || (route.protocol == RouteProtocol.AUTO && selectedId == "auto")
            val card = createRouteCard(route, isSelected)
            cardsLayout.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = host.dp(7)
            })
        }
    }

    private fun createRouteCard(route: DeyttRoute, isSelected: Boolean): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = host.dp(56)
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
            background = host.glassRowBackground(emphasis = isSelected, radius = 16f)
            isClickable = true
            isFocusable = true
            foreground = host.ripple(16f)
            host.pressFeedback(this, 0.98f)
            setOnClickListener {
                onRouteSelected(route)
                close()
            }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                store.setFavorite(route.id, false)
                refreshFavorites()
                true
            }
        }

        val flagView = host.text(
            when (route.protocol) {
                RouteProtocol.AUTO -> "✦"
                RouteProtocol.LTE -> "LTE"
                else -> route.flag
            },
            if (route.protocol == RouteProtocol.LTE) 11f else 17f,
            if (isSelected) DeyttUi.SKY else DeyttUi.TEXT,
            Typeface.BOLD,
        ).apply {
            gravity = Gravity.CENTER
            minWidth = host.dp(30)
        }
        root.addView(flagView, LinearLayout.LayoutParams(host.dp(34), host.dp(34)).apply { marginEnd = host.dp(8) })

        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = host.text(
            when (route.protocol) {
                RouteProtocol.AUTO -> "автоподбор"
                RouteProtocol.LTE -> "lte · белые списки"
                else -> countryCleanName(route.countryCode).lowercase()
            },
            13.5f,
            DeyttUi.TEXT,
            Typeface.BOLD,
        )
        val protoSubtitle = host.text(
            when (route.protocol) {
                RouteProtocol.AUTO -> "выберем доступный узел"
                RouteProtocol.LTE -> "обход блокировок по спискам"
                else -> "${route.protocol.title.lowercase()}${if (route.profileName != null) " · ${route.profileName.lowercase()}" else ""}"
            },
            11f,
            DeyttUi.MUTED,
        )

        val cached = RouteQualityStore.read(host, route.id)
        val status = host.mono(
            formatSampleLabel(cached?.latencyMillis, cached?.downloadBytesPerSecond),
            10.5f,
            if (cached?.downloadBytesPerSecond != null && cached.downloadBytesPerSecond > 0L) DeyttUi.MINT
            else if (cached?.latencyMillis != null) DeyttUi.SKY
            else DeyttUi.MUTED,
            500,
        ).apply {
            setPadding(0, host.dp(2), 0, 0)
        }
        statusViews[route.id] = status

        col.addView(title)
        col.addView(protoSubtitle)
        col.addView(status)
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val connectIndicator = host.text(
            if (isSelected) "выбрано" else "подключить",
            11f,
            if (isSelected) DeyttUi.SKY else DeyttUi.TEXT,
            Typeface.BOLD,
        ).apply {
            setPadding(host.dp(8), host.dp(4), host.dp(8), host.dp(4))
            background = host.glassRowBackground(isSelected, 12f)
        }
        root.addView(connectIndicator)

        return root
    }

    private fun formatSampleLabel(latency: Long?, speedBytes: Long?): String {
        val latText = if (latency != null && RouteProbeStatistics.isValidLatencyMillis(latency)) "${latency} мс" else "—"
        val speedText = if (speedBytes != null && speedBytes > 0L) {
            val bits = speedBytes * 8.0
            if (bits >= 1_000_000.0) {
                String.format(java.util.Locale.US, "%.1f мбит/с", bits / 1_000_000.0)
            } else {
                String.format(java.util.Locale.US, "%.0f кбит/с", bits / 1_000.0)
            }
        } else "—"
        return "отклик: $latText  ·  скорость: $speedText"
    }

    private fun measureFavorites() {
        if (isMeasuring) return
        isMeasuring = true
        measureButton.text = "замер…"
        measureButton.isEnabled = false
        measureButton.alpha = 0.7f

        val favIds = store.getFavorites()
        val catalog = host.getCachedRouteCatalog()
        val config = host.getCachedSubscriptionContent()
        val token = TelegramSessionStore.read(host)

        currentFavRoutes.clear()
        favIds.forEach { id ->
            when (id) {
                "auto" -> catalog.firstOrNull { it.protocol == RouteProtocol.AUTO }?.let { currentFavRoutes.add(it) }
                "lte" -> catalog.firstOrNull { it.protocol == RouteProtocol.LTE || it.protocol == RouteProtocol.RU_DE }?.let { currentFavRoutes.add(it) }
                else -> catalog.firstOrNull { it.id == id }?.let { currentFavRoutes.add(it) }
            }
        }

        if (currentFavRoutes.isEmpty() || config == null) {
            finishMeasuring()
            return
        }

        currentFavRoutes.forEach { route ->
            statusViews[route.id]?.apply {
                text = "в очереди…"
                setTextColor(DeyttUi.SKY)
            }
        }

        val libboxRoutes = currentFavRoutes.filter { it.engine == TunnelEngine.LIBBOX }
        val awgRoutes = currentFavRoutes.filter { it.engine == TunnelEngine.AMNEZIAWG }

        if (libboxRoutes.isNotEmpty()) {
            runCatching {
                if (RouteProbeClient.isRunning(host)) {
                    RouteProbeClient.cancel(host)
                }
                RouteProbeClient.start(
                    host,
                    config,
                    libboxRoutes.map(DeyttRoute::configTag),
                    RouteProbePreferences.method(host),
                    token,
                    latencyOnly = false,
                )
            }.onSuccess { reqId ->
                currentProbeRequestId = reqId
            }.onFailure { err ->
                android.util.Log.w("FavoriteRoutesDrawer", "RouteProbeClient.start failed: ${err.message}")
                measureLibboxDirect(libboxRoutes) {
                    if (awgRoutes.isNotEmpty()) {
                        startAwgProbes(awgRoutes)
                    } else {
                        finishMeasuring()
                    }
                }
            }
        } else if (awgRoutes.isNotEmpty()) {
            startAwgProbes(awgRoutes)
        } else {
            finishMeasuring()
        }
    }

    fun onRouteProbeEvent(event: Intent) {
        val requestId = event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank() || requestId != currentProbeRequestId) return
        val stage = event.getStringExtra(RouteProbeClient.EXTRA_STAGE).orEmpty()
        val tag = event.getStringExtra(RouteProbeClient.EXTRA_ROUTE_TAG).orEmpty()
        val route = currentFavRoutes.firstOrNull { it.configTag == tag } ?: return
        val latency = event.getLongExtra(RouteProbeClient.EXTRA_MILLISECONDS, -1L).takeIf { it >= 0L }
        val speed = event.getLongExtra(RouteProbeClient.EXTRA_BYTES_PER_SECOND, -1L).takeIf { it >= 0L }

        when (stage) {
            "ping" -> {
                statusViews[route.id]?.apply {
                    text = if (latency != null) "отклик: ${latency} мс  ·  замер скорости…" else "проверка отклика…"
                    setTextColor(DeyttUi.SKY)
                }
            }
            "speed" -> {
                statusViews[route.id]?.apply {
                    text = formatSampleLabel(latency, speed)
                    setTextColor(DeyttUi.SKY)
                }
            }
            "complete", "failed" -> {
                val sample = RouteProbeSample(route.id, latency, speed)
                RouteQualityStore.write(host, sample)
                statusViews[route.id]?.apply {
                    text = formatSampleLabel(latency, speed)
                    setTextColor(if (speed != null && speed > 0L) DeyttUi.MINT else if (latency != null) DeyttUi.SKY else DeyttUi.CORAL)
                }
            }
        }

        if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
            currentProbeRequestId = null
            val awgRoutes = currentFavRoutes.filter { it.engine == TunnelEngine.AMNEZIAWG }
            if (awgRoutes.isNotEmpty()) {
                startAwgProbes(awgRoutes)
            } else {
                finishMeasuring()
            }
        }
    }

    private fun startAwgProbes(awgRoutes: List<DeyttRoute>) {
        host.requestAwgMeasurements(awgRoutes, onComplete = {
            finishMeasuring()
        }, onRouteFailure = { route ->
            statusViews[route.id]?.apply {
                text = "отклик: —  ·  скорость: —"
                setTextColor(DeyttUi.CORAL)
            }
        }, onRouteStarting = { route ->
            statusViews[route.id]?.apply {
                text = "подключение amneziawg…"
                setTextColor(DeyttUi.SKY)
            }
        }, measure = { route, finished ->
            measureAwgRoute(route, finished)
        })
    }

    private fun measureAwgRoute(route: DeyttRoute, finished: (Boolean) -> Unit) {
        val token = TelegramSessionStore.read(host)
        measureExecutor.execute {
            var measuredLatency: Long? = null
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
                            mainHandler.post {
                                statusViews[route.id]?.apply {
                                    text = "отклик: ${currentLatency} мс  ·  замер скорости…"
                                    setTextColor(DeyttUi.SKY)
                                }
                            }
                        }
                    }
                )
            }.onSuccess { measuredLatency = it }

            val speed = if (token.isNullOrBlank()) null else runCatching {
                val current = { AwgTunnelController.isRunning() }
                RouteProxyProbe.measureSystemDownload(token, current, sampleMillis = 500, onProgress = { bytesPerSecond ->
                    val lat = measuredLatency
                    mainHandler.post {
                        statusViews[route.id]?.apply {
                            text = formatSampleLabel(lat, bytesPerSecond)
                            setTextColor(DeyttUi.SKY)
                        }
                    }
                })
            }.getOrNull()

            val sample = RouteProbeSample(route.id, measuredLatency, speed)
            RouteQualityStore.write(host, sample)

            mainHandler.post {
                statusViews[route.id]?.apply {
                    text = formatSampleLabel(measuredLatency, speed)
                    setTextColor(if (speed != null && speed > 0L) DeyttUi.MINT else DeyttUi.MUTED)
                }
                finished(measuredLatency != null)
            }
        }
    }

    private fun measureLibboxDirect(routes: List<DeyttRoute>, onComplete: () -> Unit) {
        val config = host.getCachedSubscriptionContent()
        val awgConfig = AwgProfileStore(host).profiles().firstOrNull()?.config

        measureExecutor.execute {
            routes.forEach { route ->
                mainHandler.post {
                    statusViews[route.id]?.apply {
                        text = "замер отклика…"
                        setTextColor(DeyttUi.SKY)
                    }
                }

                var latencyResult: Long? = null
                if (config != null) {
                    val target = RouteLatency.target(config, route, awgConfig)
                    if (target != null) {
                        latencyResult = RouteLatency.measure(target, 2200)
                    } else if (route.protocol == RouteProtocol.AUTO) {
                        val firstEdge = host.getCachedRouteCatalog().firstOrNull { it.engine == TunnelEngine.LIBBOX && it.protocol != RouteProtocol.AUTO }
                        if (firstEdge != null) {
                            val edgeTarget = RouteLatency.target(config, firstEdge, awgConfig)
                            if (edgeTarget != null) latencyResult = RouteLatency.measure(edgeTarget, 2200)
                        }
                    }
                }

                val speedResult: Long? = null
                val sample = RouteProbeSample(route.id, latencyResult, speedResult)
                if (latencyResult != null) {
                    RouteQualityStore.write(host, sample)
                }

                mainHandler.post {
                    statusViews[route.id]?.apply {
                        text = formatSampleLabel(latencyResult, speedResult)
                        setTextColor(if (latencyResult != null) DeyttUi.SKY else DeyttUi.CORAL)
                    }
                }
            }
            mainHandler.post { onComplete() }
        }
    }

    private fun finishMeasuring() {
        mainHandler.post {
            isMeasuring = false
            measureButton.text = "измерить"
            measureButton.isEnabled = true
            measureButton.alpha = 1f
            host.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun countryCleanName(code: String): String = when (code.uppercase()) {
        "NL" -> "нидерланды"
        "DE", "RU-DE" -> "германия"
        "RU" -> "россия"
        "FI" -> "финляндия"
        "IT" -> "италия"
        else -> code.lowercase()
    }
}
