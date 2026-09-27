package space.deytt.connect

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

internal enum class RouteProbeVisualState {
    IDLE,
    PING,
    SPEED,
    PING_RESULT,
    SPEED_RESULT,
    FAILURE,
}

/** Small live trace: ping samples and speed samples are plotted from real measurements. */
internal class RouteProbeVisualizerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val bounds = RectF()
    private val chartPath = Path()
    private val fillPath = Path()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33283848
        strokeWidth = dp(1f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedGradient: LinearGradient? = null
    private var cachedGradientAccent = 0
    private var cachedGradientTop = Float.NaN
    private var cachedGradientBottom = Float.NaN
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DeyttUi.MUTED
        textSize = dp(6.5f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val activityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var state = RouteProbeVisualState.IDLE
    private var pingSamples: List<Long> = emptyList()
    private var speedSamples: List<Long> = emptyList()
    private var animationPhase = 0f
    private var english = AppLanguage.current(context) == AppLanguage.EN

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationPhase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        minimumWidth = dp(72f).toInt()
        minimumHeight = dp(42f).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = if (AppLanguage.current(context) == AppLanguage.EN) {
            "Route measurement has not run yet"
        } else {
            "Измерение маршрута ещё не запускалось"
        }
    }

    fun render(
        nextState: RouteProbeVisualState,
        ping: List<Long> = pingSamples,
        speed: List<Long> = speedSamples,
        description: String? = null,
    ) {
        state = nextState
        pingSamples = ping.filter { it >= 0L }.takeLast(MAX_POINTS)
        speedSamples = speed.filter { it >= 0L }.takeLast(MAX_POINTS)
        english = AppLanguage.current(context) == AppLanguage.EN
        contentDescription = description ?: when (nextState) {
            RouteProbeVisualState.IDLE -> if (english) "Route measurement is idle" else "Замер маршрута не запущен"
            RouteProbeVisualState.PING -> if (english) "Measuring HTTPS latency" else "Измеряю задержку HTTPS"
            RouteProbeVisualState.SPEED -> if (english) "Measuring download speed" else "Измеряю скорость загрузки"
            RouteProbeVisualState.PING_RESULT -> if (english) "Latency sample chart" else "График замеров задержки"
            RouteProbeVisualState.SPEED_RESULT -> if (english) "Download speed sample chart" else "График скорости загрузки"
            RouteProbeVisualState.FAILURE -> if (english) "Route measurement failed" else "Не удалось измерить маршрут"
        }
        syncPulse()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPulse()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = dp(9f)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        backgroundPaint.color = if (state == RouteProbeVisualState.FAILURE) 0xFF29202B.toInt() else DeyttUi.SURFACE_2
        canvas.drawRoundRect(bounds, radius, radius, backgroundPaint)

        val left = dp(8f)
        val right = (width - dp(8f)).coerceAtLeast(left + 1f)
        val top = dp(13f)
        val bottom = (height - dp(7f)).coerceAtLeast(top + 1f)
        canvas.drawLine(left, top + (bottom - top) * .5f, right, top + (bottom - top) * .5f, gridPaint)
        canvas.drawLine(left, bottom, right, bottom, gridPaint)

        val isSpeed = state == RouteProbeVisualState.SPEED || state == RouteProbeVisualState.SPEED_RESULT
        val samples = if (isSpeed) speedSamples else pingSamples
        val accent = when (state) {
            RouteProbeVisualState.FAILURE -> DeyttUi.CORAL
            RouteProbeVisualState.SPEED, RouteProbeVisualState.SPEED_RESULT -> DeyttUi.MINT
            RouteProbeVisualState.PING, RouteProbeVisualState.PING_RESULT -> DeyttUi.SKY
            RouteProbeVisualState.IDLE -> DeyttUi.LINE
        }
        labelPaint.color = when (state) {
            RouteProbeVisualState.SPEED, RouteProbeVisualState.SPEED_RESULT -> DeyttUi.MINT
            RouteProbeVisualState.PING, RouteProbeVisualState.PING_RESULT -> DeyttUi.SKY
            RouteProbeVisualState.FAILURE -> DeyttUi.CORAL
            RouteProbeVisualState.IDLE -> DeyttUi.MUTED
        }
        val label = when (state) {
            RouteProbeVisualState.SPEED, RouteProbeVisualState.SPEED_RESULT -> if (english) "↓ SPEED" else "↓ СКОРОСТЬ"
            RouteProbeVisualState.PING, RouteProbeVisualState.PING_RESULT -> if (english) "PING" else "ПИНГ"
            RouteProbeVisualState.FAILURE -> if (english) "NO DATA" else "НЕТ ДАННЫХ"
            RouteProbeVisualState.IDLE -> if (english) "ROUTE" else "МАРШРУТ"
        }
        canvas.drawText(label, left, dp(9f), labelPaint)

        if (samples.isEmpty()) {
            if (state == RouteProbeVisualState.PING || state == RouteProbeVisualState.SPEED) {
                drawWave(canvas, left, right, bottom, accent)
            } else {
                activityPaint.color = accent
                canvas.drawRoundRect(left, bottom - dp(2f), right, bottom, dp(1f), dp(1f), activityPaint)
            }
            return
        }

        chartPath.reset()
        fillPath.reset()
        val denominator = (samples.size - 1).coerceAtLeast(1)
        val maxSpeed = speedSamples.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        samples.forEachIndexed { index, value ->
            val x = if (samples.size == 1) (left + right) / 2f else left + (right - left) * index / denominator
            val score = if (isSpeed) {
                (value.toFloat() / maxSpeed).coerceIn(.1f, 1f)
            } else {
                (1f - value.toFloat().coerceAtMost(MAX_PING_MILLIS) / MAX_PING_MILLIS).coerceIn(.08f, 1f)
            }
            val y = bottom - dp(2f) - score * (bottom - top - dp(3f))
            if (index == 0) {
                chartPath.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
            } else {
                chartPath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        val lastX = if (samples.size == 1) (left + right) / 2f else right
        fillPath.lineTo(lastX, bottom)
        fillPath.close()
        fillPaint.shader = gradient(accent, top, bottom)
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null
        linePaint.color = accent
        canvas.drawPath(chartPath, linePaint)
        pointPaint.color = accent
        samples.forEachIndexed { index, _ ->
            val x = if (samples.size == 1) (left + right) / 2f else left + (right - left) * index / denominator
            val y = chartPathPointY(index, samples, isSpeed, top, bottom, maxSpeed)
            canvas.drawCircle(x, y, dp(if (index == samples.lastIndex && state in ACTIVE_STATES) 3f else 2f), pointPaint)
        }
        if (state in ACTIVE_STATES) {
            val glow = dp(4f + 2f * animationPhase)
            pointPaint.color = withAlpha(accent, (100 * (1f - animationPhase * .65f)).toInt())
            canvas.drawCircle(lastX, chartPathPointY(samples.lastIndex, samples, isSpeed, top, bottom, maxSpeed), glow, pointPaint)
        }
    }

    private fun drawWave(canvas: Canvas, left: Float, right: Float, bottom: Float, accent: Int) {
        val count = 5
        val cell = (right - left) / count
        repeat(count) { index ->
            val distance = abs((animationPhase * count - index + count) % count - (count / 2f))
            val heightFactor = (1f - (distance / (count / 2f)).coerceAtMost(1f))
            val barHeight = dp(3f + 10f * heightFactor)
            val x = left + cell * index + cell * .34f
            val w = cell * .32f
            activityPaint.color = withAlpha(accent, 80 + (150 * heightFactor).toInt())
            canvas.drawRoundRect(x, bottom - barHeight, x + w, bottom, w / 2f, w / 2f, activityPaint)
        }
    }

    private fun chartPathPointY(
        index: Int,
        samples: List<Long>,
        isSpeed: Boolean,
        top: Float,
        bottom: Float,
        maxSpeed: Long,
    ): Float {
        val value = samples[index]
        val score = if (isSpeed) (value.toFloat() / maxSpeed).coerceIn(.1f, 1f)
        else (1f - value.toFloat().coerceAtMost(MAX_PING_MILLIS) / MAX_PING_MILLIS).coerceIn(.08f, 1f)
        return bottom - dp(2f) - score * (bottom - top - dp(3f))
    }

    private fun syncPulse() {
        val shouldRun = isAttachedToWindow && state in ACTIVE_STATES && animationsAllowed()
        if (shouldRun && !pulse.isStarted) pulse.start()
        else if (!shouldRun && pulse.isStarted) pulse.cancel()
    }

    private fun gradient(accent: Int, top: Float, bottom: Float): LinearGradient {
        if (cachedGradient == null || cachedGradientAccent != accent ||
            cachedGradientTop != top || cachedGradientBottom != bottom
        ) {
            cachedGradientAccent = accent
            cachedGradientTop = top
            cachedGradientBottom = bottom
            cachedGradient = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(withAlpha(accent, 88), withAlpha(accent, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        return checkNotNull(cachedGradient)
    }

    private fun animationsAllowed(): Boolean {
        val reducedMotion = context.getSharedPreferences("profile_settings", Context.MODE_PRIVATE)
            .getBoolean("reduced_motion", false)
        val systemScale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return !reducedMotion && systemScale > 0f
    }

    private fun dp(value: Float): Float = value * density

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun withAlpha(color: Int, alpha: Float): Int = withAlpha(color, alpha.toInt())

    companion object {
        private const val MAX_PING_MILLIS = 2_000f
        private const val MAX_POINTS = 8
        private val ACTIVE_STATES = setOf(RouteProbeVisualState.PING, RouteProbeVisualState.SPEED)
    }
}
