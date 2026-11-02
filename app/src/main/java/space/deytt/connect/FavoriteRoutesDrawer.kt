package space.deytt.connect

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.glassButtonBackground
import space.deytt.connect.DeyttUi.glassCardBackground
import space.deytt.connect.DeyttUi.glassRowBackground
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.ripple
import space.deytt.connect.DeyttUi.text
import java.util.concurrent.Executors

/**
 * Futuristic Favorite Routes drawer for MainActivity Home screen.
 * Features:
 * - Interactive pull-up portal glowing ring animation on swipe
 * - Real-time background blur & frosted dimming
 * - LiquidGlass drawer panel with overshoot slide and physical settle shake
 * - Chess-like alternating staggered card entrance
 * - Parallel route latency measurement engine with live grade badges
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
    private lateinit var portalView: PullPortalView
    private lateinit var sheetContainer: LinearLayout
    private lateinit var cardsLayout: LinearLayout
    private lateinit var emptyCard: View
    private lateinit var measureButton: TextView
    private lateinit var countBadge: TextView

    private val cardViews = mutableMapOf<String, View>()
    private val latencyBadges = mutableMapOf<String, TextView>()

    private val PULL_THRESHOLD_PX = host.dp(110).toFloat()

    fun attachTo(stage: ViewGroup) {
        parentStage = stage
        buildViews()
        stage.addView(overlayRoot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        FavoriteRoutesStore.addListener(::refreshFavorites)
    }

    fun detach() {
        FavoriteRoutesStore.removeListener(::refreshFavorites)
        parentStage?.removeView(overlayRoot)
        parentStage = null
    }

    fun isOpen(): Boolean = isDrawerOpen

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

        portalView = PullPortalView(host).apply {
            alpha = 0f
            visibility = View.GONE
        }
        val portalSize = host.dp(120)
        overlayRoot.addView(portalView, FrameLayout.LayoutParams(portalSize, portalSize, Gravity.CENTER))

        sheetContainer = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = LiquidGlassDrawable(
                density = host.resources.displayMetrics.density,
                cornerRadiusPx = host.dp(28).toFloat(),
                fillColors = intArrayOf(0xF80B1422.toInt(), 0xFC060C14.toInt()),
                strokeColors = intArrayOf(
                    0x70FFFFFF.toInt(),
                    0x3088C0F8.toInt(),
                    0x206AAAF0.toInt(),
                    0x30FFFFFF.toInt(),
                ),
                strokePositions = floatArrayOf(0f, 0.32f, 0.70f, 1f),
                strokeWidthPx = 1.25f,
                hasGloss = true,
                glossAlpha = 0x22,
                innerLip = true,
            )
            clipToOutline = true
            setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(16))
            isClickable = true
        }

        // Top drag handle pill
        val handlePill = View(host).apply {
            background = host.glassRowBackground(emphasis = false, radius = 3f)
        }
        sheetContainer.addView(handlePill, LinearLayout.LayoutParams(host.dp(44), host.dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = host.dp(14)
        })

        // Header row
        val headerRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = host.text("Избранные маршруты", 18f, DeyttUi.TEXT, Typeface.BOLD)
        countBadge = host.mono("", 11f, DeyttUi.SKY, 650).apply {
            setPadding(host.dp(8), host.dp(3), host.dp(8), host.dp(3))
            background = host.glassRowBackground(emphasis = true, radius = 10f)
        }
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(countBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = host.dp(10)
        })

        val closeBtn = ImageView(host).apply {
            setImageResource(R.drawable.ic_chevron)
            rotation = 90f
            imageTintList = ColorStateList.valueOf(DeyttUi.MUTED)
            background = host.glassRowBackground(emphasis = false, radius = 18f)
            setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6))
            isClickable = true
            foreground = host.ripple(18f)
            host.pressFeedback(this)
            setOnClickListener { close() }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(host.dp(34), host.dp(34)))
        sheetContainer.addView(headerRow)

        val subtext = host.text("Быстрый выбор и замер отклика ключевых узлов", 12f, DeyttUi.MUTED).apply {
            setPadding(0, host.dp(4), 0, host.dp(12))
        }
        sheetContainer.addView(subtext)

        // Measure button
        measureButton = host.text("Измерить маршруты", 13f, DeyttUi.TEXT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = host.glassButtonBackground(0x403B82F6.toInt(), 0x251D4ED8.toInt(), radius = 20f)
            foreground = host.ripple(20f)
            host.pressFeedback(this)
            isClickable = true
            isFocusable = true
            setPadding(host.dp(14), host.dp(11), host.dp(14), host.dp(11))
            setOnClickListener { measureFavorites() }
        }
        sheetContainer.addView(measureButton, LinearLayout.LayoutParams(-1, host.dp(48)).apply {
            bottomMargin = host.dp(12)
        })

        // Scrollable list of cards
        cardsLayout = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(host).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(cardsLayout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val maxListHeight = host.dp(320)
        sheetContainer.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight, 1f))

        // Empty state view
        emptyCard = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(host.dp(20), host.dp(24), host.dp(20), host.dp(24))
            background = host.glassCardBackground(emphasis = false, radius = 20f)
            val icon = ImageView(host).apply {
                setImageResource(R.drawable.ic_heart)
                imageTintList = ColorStateList.valueOf(0x80FFFFFF.toInt())
            }
            addView(icon, LinearLayout.LayoutParams(host.dp(36), host.dp(36)))
            val emptyTitle = host.text("Пока нет избранных", 15f, DeyttUi.TEXT, Typeface.BOLD).apply {
                setPadding(0, host.dp(8), 0, host.dp(4))
            }
            addView(emptyTitle)
            val emptyMsg = host.text(
                "Нажмите сердечко рядом с любым маршрутом на вкладке Маршруты, чтобы добавить его сюда.",
                12f,
                DeyttUi.MUTED,
            ).apply {
                gravity = Gravity.CENTER
            }
            addView(emptyMsg)
            val goRoutes = host.text("Перейти к маршрутам ›", 12f, DeyttUi.SKY, Typeface.BOLD).apply {
                setPadding(0, host.dp(12), 0, 0)
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
            val gutter = host.dp(10)
            val bottomNavPadding = host.dp(84) // Sits comfortably above dock
            setMargins(gutter, 0, gutter, bottomNavPadding)
        }
        overlayRoot.addView(sheetContainer, sheetParams)
    }

    private var lastPullProgress = 0f

    fun onPullStart() {
        if (isDrawerOpen) return
        lastPullProgress = 0f
        overlayRoot.visibility = View.VISIBLE
        portalView.visibility = View.VISIBLE
        portalView.alpha = 0f
        portalView.setProgress(0f)
    }

    fun onPullMove(dy: Float) {
        if (isDrawerOpen) return
        val progress = (dy / PULL_THRESHOLD_PX).coerceIn(0f, 1f)
        lastPullProgress = progress
        portalView.visibility = View.VISIBLE
        portalView.alpha = progress
        portalView.setProgress(progress)
        scrimView.alpha = progress * 0.45f
    }

    fun onPullEnd() {
        if (isDrawerOpen) return
        if (lastPullProgress >= 0.32f || portalView.alpha >= 0.32f) {
            portalView.animate()
                .scaleX(1.35f)
                .scaleY(1.35f)
                .alpha(0f)
                .setDuration(160L)
                .withEndAction {
                    portalView.visibility = View.GONE
                    portalView.scaleX = 1f
                    portalView.scaleY = 1f
                }
                .start()
            host.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            open()
        } else {
            portalView.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(180L).withEndAction {
                portalView.visibility = View.GONE
                portalView.scaleX = 1f
                portalView.scaleY = 1f
                overlayRoot.visibility = View.GONE
            }.start()
            scrimView.animate().alpha(0f).setDuration(180L).start()
        }
    }

    fun open() {
        if (isDrawerOpen) return
        isDrawerOpen = true
        overlayRoot.visibility = View.VISIBLE
        overlayRoot.isClickable = true
        scrimView.isClickable = true
        scrimView.setOnClickListener { close() }
        refreshFavorites()

        scrimView.alpha = 0f
        scrimView.animate().alpha(1f).setDuration(260L).start()

        sheetContainer.post {
            val height = sheetContainer.height.takeIf { it > 0 } ?: host.dp(400)
            sheetContainer.translationY = height.toFloat()
            sheetContainer.animate()
                .translationY(0f)
                .setDuration(340L)
                .setInterpolator(OvershootInterpolator(1.10f))
                .withEndAction {
                    // Futuristic micro-shake settle
                    triggerFuturisticSettleShake()
                }
                .start()
        }
    }

    fun close() {
        if (!isDrawerOpen) return
        isDrawerOpen = false
        overlayRoot.isClickable = false
        scrimView.isClickable = false
        scrimView.setOnClickListener(null)
        val height = sheetContainer.height.takeIf { it > 0 } ?: host.dp(400)
        sheetContainer.animate()
            .translationY(height.toFloat())
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        scrimView.animate()
            .alpha(0f)
            .setDuration(220L)
            .withEndAction {
                overlayRoot.visibility = View.GONE
            }
            .start()
    }

    private fun triggerFuturisticSettleShake() {
        sheetContainer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                val decay = kotlin.math.exp(-fraction * 5.2).toFloat()
                val shake = kotlin.math.sin(fraction * Math.PI * 6.0).toFloat() * (host.dp(3) * 1.2f) * decay
                sheetContainer.translationX = shake
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    sheetContainer.translationX = 0f
                }
            })
        }
        animator.start()
    }

    fun refreshFavorites() {
        cardsLayout.removeAllViews()
        cardViews.clear()
        latencyBadges.clear()

        val favIds = store.getFavorites()
        val allRoutes = host.getCachedRouteCatalog()
        val selectedId = host.getCachedSelectedRoute().id

        countBadge.text = "${favIds.size}"

        if (favIds.isEmpty()) {
            cardsLayout.addView(emptyCard)
            return
        }

        // Match favIds to route models
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

        favRoutes.forEachIndexed { index, route ->
            val isSelected = route.id == selectedId || (route.protocol == RouteProtocol.AUTO && selectedId == "auto")
            val card = createRouteCard(route, isSelected)
            cardViews[route.id] = card
            cardsLayout.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = host.dp(8)
            })

            // Staggered chess-like entrance
            val fromLeft = index % 2 == 0
            card.translationX = if (fromLeft) -host.dp(60).toFloat() else host.dp(60).toFloat()
            card.alpha = 0f
            card.animate()
                .translationX(0f)
                .alpha(1f)
                .setStartDelay(index * 36L)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private fun createRouteCard(route: DeyttRoute, isSelected: Boolean): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = host.dp(58)
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
            background = host.glassRowBackground(emphasis = isSelected, radius = 18f)
            isClickable = true
            isFocusable = true
            foreground = host.ripple(18f)
            host.pressFeedback(this, 0.98f)
            setOnClickListener {
                onRouteSelected(route)
                close()
            }
        }

        // Route icon / flag
        val flagView = host.text(
            when (route.protocol) {
                RouteProtocol.AUTO -> "✦"
                RouteProtocol.LTE -> "LTE"
                else -> route.flag
            },
            if (route.protocol == RouteProtocol.LTE) 12f else 18f,
            if (isSelected) DeyttUi.SKY else DeyttUi.TEXT,
            Typeface.BOLD,
        ).apply {
            gravity = Gravity.CENTER
            minWidth = host.dp(34)
        }
        root.addView(flagView, LinearLayout.LayoutParams(host.dp(38), host.dp(38)).apply { marginEnd = host.dp(8) })

        // Text column
        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = host.text(
            when (route.protocol) {
                RouteProtocol.AUTO -> "Автоподбор"
                RouteProtocol.LTE -> "LTE · белые списки"
                else -> countryCleanName(route.countryCode)
            },
            14f,
            DeyttUi.TEXT,
            Typeface.BOLD,
        )
        val subtitle = host.text(
            when (route.protocol) {
                RouteProtocol.AUTO -> "автовыбор оптимального узла"
                RouteProtocol.LTE -> "обход блокировок по белым спискам"
                else -> route.protocol.title + if (route.profileName != null) " · ${route.profileName}" else ""
            },
            11f,
            DeyttUi.MUTED,
        )
        col.addView(title)
        col.addView(subtitle)
        root.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Latency badge
        val cached = RouteQualityStore.read(host, route.id)
        val initialLatency = cached?.latencyMillis
        val badge = host.mono(
            if (initialLatency != null) "${initialLatency} мс" else "—",
            11f,
            latencyColor(initialLatency),
            650,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(host.dp(8), host.dp(4), host.dp(8), host.dp(4))
            background = host.glassRowBackground(emphasis = false, radius = 10f)
        }
        latencyBadges[route.id] = badge
        root.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = host.dp(8)
        })

        // Heart button to remove from favorites
        val heartIcon = ImageView(host).apply {
            setImageResource(R.drawable.ic_heart_filled)
            imageTintList = ColorStateList.valueOf(0xFFFF5C7A.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val heartBtn = FrameLayout(host).apply {
            background = host.glassRowBackground(emphasis = false, radius = 16f)
            setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6))
            isClickable = true
            isFocusable = true
            foreground = host.ripple(16f)
            host.pressFeedback(this)
            addView(heartIcon, FrameLayout.LayoutParams(host.dp(20), host.dp(20), Gravity.CENTER))
            setOnClickListener {
                store.setFavorite(route.id, false)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                root.animate()
                    .alpha(0f)
                    .translationX(host.dp(50).toFloat())
                    .setDuration(180L)
                    .withEndAction { refreshFavorites() }
                    .start()
            }
        }
        root.addView(heartBtn, LinearLayout.LayoutParams(host.dp(32), host.dp(32)))

        return root
    }

    private fun measureFavorites() {
        if (isMeasuring) return
        isMeasuring = true
        measureButton.text = "Измеряем маршруты…"
        measureButton.isEnabled = false
        measureButton.alpha = 0.7f

        val favIds = store.getFavorites()
        val catalog = host.getCachedRouteCatalog()
        val config = host.getCachedSubscriptionContent()
        val awgConfig = AwgProfileStore(host).profiles().firstOrNull()?.config

        measureExecutor.execute {
            var completed = 0
            val total = favIds.size

            favIds.forEach { id ->
                val route = when (id) {
                    "auto" -> catalog.firstOrNull { it.protocol == RouteProtocol.AUTO }
                    "lte" -> catalog.firstOrNull { it.protocol == RouteProtocol.LTE || it.protocol == RouteProtocol.RU_DE }
                    else -> catalog.firstOrNull { it.id == id }
                }

                var latencyResult: Long? = null
                if (route != null && config != null) {
                    val target = RouteLatency.target(config, route, awgConfig)
                    if (target != null) {
                        latencyResult = RouteLatency.measure(target, 2200)
                    } else if (route.protocol == RouteProtocol.AUTO) {
                        // Ping first available non-auto route as representative
                        val firstEdge = catalog.firstOrNull { it.engine == TunnelEngine.LIBBOX && it.protocol != RouteProtocol.AUTO }
                        if (firstEdge != null) {
                            val edgeTarget = RouteLatency.target(config, firstEdge, awgConfig)
                            if (edgeTarget != null) latencyResult = RouteLatency.measure(edgeTarget, 2200)
                        }
                    }
                }

                if (latencyResult != null && RouteProbeStatistics.isValidLatencyMillis(latencyResult)) {
                    RouteQualityStore.writeLatency(host, route?.id ?: id, latencyResult)
                }

                val finalLatency = latencyResult
                mainHandler.post {
                    latencyBadges[route?.id ?: id]?.apply {
                        text = if (finalLatency != null) "${finalLatency} мс" else "нет ответа"
                        setTextColor(latencyColor(finalLatency))
                        animate().scaleX(1.2f).scaleY(1.2f).setDuration(100L).withEndAction {
                            animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                        }.start()
                    }
                }

                completed++
            }

            mainHandler.post {
                isMeasuring = false
                measureButton.text = "Измерить маршруты"
                measureButton.isEnabled = true
                measureButton.alpha = 1f
                host.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        }
    }

    private fun countryCleanName(code: String): String = when (code.uppercase()) {
        "NL" -> "Нидерланды"
        "DE", "RU-DE" -> "Германия"
        "RU" -> "Россия"
        "FI" -> "Финляндия"
        "IT" -> "Италия"
        else -> code
    }

    private fun latencyColor(ms: Long?): Int = when {
        ms == null || !RouteProbeStatistics.isValidLatencyMillis(ms) -> DeyttUi.CORAL
        ms < 95L -> DeyttUi.MINT
        ms < 190L -> DeyttUi.SKY
        ms < 280L -> DeyttUi.AMBER
        else -> DeyttUi.CORAL
    }

    /**
     * Futuristic glowing portal circle drawn dynamically during pull-up.
     */
    private class PullPortalView(context: MainActivity) : View(context) {
        private var pullProgress = 0f
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (context.dp(3) * 1.2f)
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val ringBounds = RectF()
        private val gradientColors = intArrayOf(
            0xFF38BDF8.toInt(),
            0xFF818CF8.toInt(),
            0xFFFF5C7A.toInt(),
            0xFF34D399.toInt(),
            0xFF38BDF8.toInt(),
        )

        fun setProgress(progress: Float) {
            pullProgress = progress.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (pullProgress <= 0.02f) return
            val cx = width / 2f
            val cy = height / 2f
            val maxR = minOf(cx, cy) * 0.85f
            val curR = maxR * (0.4f + 0.6f * pullProgress)

            ringBounds.set(cx - curR, cy - curR, cx + curR, cy + curR)

            // Outer soft glow
            glowPaint.shader = RadialGradient(
                cx, cy, curR * 1.3f,
                intArrayOf(0x3038BDF8.toInt(), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, curR * 1.3f, glowPaint)

            // Rotating sweep neon ring
            val sweep = SweepGradient(cx, cy, gradientColors, null)
            val matrix = android.graphics.Matrix()
            matrix.postRotate(pullProgress * 360f, cx, cy)
            sweep.setLocalMatrix(matrix)
            circlePaint.shader = sweep
            canvas.drawArc(ringBounds, 0f, 360f, false, circlePaint)

            // Inner energy core
            glowPaint.shader = null
            glowPaint.color = 0x50FF5C7A.toInt()
            val coreR = curR * 0.35f * pullProgress
            canvas.drawCircle(cx, cy, coreR, glowPaint)
        }
    }
}
