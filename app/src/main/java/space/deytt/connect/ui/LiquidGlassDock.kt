package space.deytt.connect

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.VelocityTracker
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.prefersReducedMotion
import space.deytt.connect.DeyttUi.ripple
import space.deytt.connect.DeyttUi.text
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.exp
import kotlin.random.Random

internal class LiquidGlassDock(
    private val host: Activity,
    initialPage: Int,
    private val scene: () -> View,
    private val pages: View,
    private val onSelect: (Int) -> Unit,
) : FrameLayout(host) {
    private val scale = resources.displayMetrics.density
    private val labels = listOf("Главная", "Маршруты", "Профиль", "Настройки").map { host.uiCopy(it) }
    private val labelsAndIcons = labels.mapIndexed { index, label ->
        val icon = DockIcon(host, index)
        val title = host.text(label, 10f, TAB_NEUTRAL_COLOR).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val item = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            foreground = host.ripple(20f)
            contentDescription = label
            setOnClickListener { onSelect(index) }
            addView(icon, LinearLayout.LayoutParams(host.dp(22), host.dp(22)))
            addView(title, LinearLayout.LayoutParams(-1, -2))
        }
        Triple(item, icon, title)
    }
    private val row = LinearLayout(host).apply {
        gravity = Gravity.CENTER
        setPadding(host.dp(5), 0, host.dp(5), 0)
        labelsAndIcons.forEach { (item, _, _) -> addView(item, LinearLayout.LayoutParams(0, -1, 1f)) }
    }
    private val fallbackBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE5212936.toInt() }
    private val fallbackLens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF344357.toInt() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x36FFFFFF
        strokeWidth = scale
        style = Paint.Style.STROKE
    }
    private val glass = if (Build.VERSION.SDK_INT >= 33) runCatching { DockRefraction() }.getOrNull() else null
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { invalidate() }
    private val body = RectF()
    private val padding = host.dp(5)
    private val touchSlop = ViewConfiguration.get(host).scaledTouchSlop
    private var dragStart = 0f
    private var dragOrigin = 0f
    private var dragging = false
    private var settle: ValueAnimator? = null
    private var velocity: VelocityTracker? = null
    private var pagerPosition = initialPage.toFloat()
    private var position = pagerPosition
    private var lastMovementTime = 0L
    private var fastSwipeBlend = 0f
    private var lastLensVelocity = 0f
    private var shape = 0f
    private var shapeVelocity = 0f
    private var gestureElasticity = 1f
    private var pagerGesture = false
    private var fastPagerGesture = false
    private var glideStartedAt = 0L
    private var glideFrom = 0f
    private var glideTarget = Float.NaN
    private var glideRipple = 0f
    private var glideLight = 0f
    private val glideFrame = object : Runnable {
        override fun run() {
            val progress = ((SystemClock.uptimeMillis() - glideStartedAt) / FAST_GLIDE_MS).coerceIn(0f, 1f)
            val remaining = 1f - progress
            val distance = remaining * remaining
            val pulse = sin(progress * Math.PI.toFloat()) * (1f - progress * .40f)
            glideRipple = pulse
            glideLight = pulse * (glideTarget - glideFrom).coerceIn(-1f, 1f)
            if (progress >= 1f) {
                moveTo(glideTarget)
                glideStartedAt = 0L
                if (pagerPosition == glideTarget) stopPagerGlide()
                return
            }
            moveTo(glideTarget + (glideFrom - glideTarget) * distance)
            postOnAnimation(this)
        }
    }
    private var selected = -1

    init {
        addView(row, LayoutParams(-1, -1))
        setPageOffset(initialPage, 0f)
    }

    fun setPageOffset(index: Int, offset: Float) {
        pagerPosition = (index + offset).coerceIn(0f, labels.lastIndex.toFloat())
        if (glideStartedAt == 0L && pagerPosition == glideTarget) stopPagerGlide()
        if (!dragging && settle == null && glideTarget.isNaN()) moveTo(pagerPosition)
    }

    fun beginPagerGesture() {
        stopPagerGlide()
        pagerGesture = true
        fastPagerGesture = false
        fastSwipeBlend = 0f
        beginShapeGesture()
    }

    private fun beginShapeGesture() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMovementTime > 150L) {
            shape = 0f
            shapeVelocity = 0f
        }
        gestureElasticity = .85f + Random.nextFloat() * .30f
        lastLensVelocity = 0f
        lastMovementTime = now
    }

    fun endPagerGesture() {
        pagerGesture = false
        fastPagerGesture = false
    }

    fun onPagerSelected(page: Int) {
        if (glideTarget == page.toFloat()) return
        stopPagerGlide()
        if (pagerGesture && fastPagerGesture && !dragging && settle == null && !host.prefersReducedMotion()) {
            startGlide(page)
        }
    }

    private fun startGlide(page: Int) {
        // This is the continuation of a touch gesture, like the pager's own fling.
        glideFrom = position
        glideTarget = page.toFloat()
        glideStartedAt = SystemClock.uptimeMillis()
        postOnAnimation(glideFrame)
    }

    private fun stopPagerGlide() {
        removeCallbacks(glideFrame)
        glideStartedAt = 0L
        glideTarget = Float.NaN
        glideRipple = 0f
        glideLight = 0f
    }

    private fun moveTo(value: Float) {
        if (value != position) {
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - lastMovementTime).coerceAtLeast(8L)
            val velocity = (value - position) * 1000f / elapsed
            val speed = abs(velocity)
            val blend = ((speed - 2f) / 3f).coerceIn(0f, 1f)
            if (pagerGesture && speed >= FAST_SWIPE_CELLS_PER_SECOND) fastPagerGesture = true
            // Smooth the speed estimate without delaying the lens position.
            fastSwipeBlend += (blend - fastSwipeBlend) * (elapsed / (elapsed + 10f))
            val pull = (speed * .42f + abs(velocity - lastLensVelocity) * .12f).coerceAtMost(1f) * gestureElasticity
            val dt = elapsed.coerceAtMost(60L) * .001f
            val offset = shape - pull
            val velocityTerm = shapeVelocity + SHAPE_DAMPING * offset
            val decay = exp(-SHAPE_DAMPING * dt)
            shape = pull + (offset + velocityTerm * dt) * decay
            shapeVelocity = (shapeVelocity - SHAPE_DAMPING * velocityTerm * dt) * decay
            lastLensVelocity = velocity
            lastMovementTime = now
        }
        position = value
        val active = value.roundToInt()
        val selectionChanged = active != selected
        selected = active
        labelsAndIcons.forEachIndexed { index, (item, icon, title) ->
            if (selectionChanged) item.isSelected = index == active
            val proximity = (1f - abs(index - value)).coerceIn(0f, 1f)
            val tint = proximity * proximity * (3f - 2f * proximity)
            val color = ColorUtils.blendARGB(TAB_NEUTRAL_COLOR, TAB_LENS_COLOR, tint)
            icon.setTint(color)
            if (title.currentTextColor != color) title.setTextColor(color)
        }
        invalidate()
    }

    private fun motionEnabled(): Boolean = !host.prefersReducedMotion() &&
        (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())

    private fun cellWidth(): Float = ((width - padding * 2) / labels.size.toFloat()).coerceAtLeast(1f)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                beginShapeGesture()
                dragStart = event.x
                dragOrigin = position
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - dragStart) > touchSlop) {
                velocity?.addMovement(event)
                stopPagerGlide()
                settle?.cancel()
                settle = null
                dragOrigin = position
                dragging = true
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseVelocity()
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val target = (dragOrigin + (event.x - dragStart) / cellWidth()).coerceIn(0f, labels.lastIndex.toFloat())
                moveTo(target)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val released = event.actionMasked == MotionEvent.ACTION_UP
                val momentum = releaseVelocity()
                val target = if (released) position.roundToInt() else pagerPosition.roundToInt()
                dragging = false
                if (released && abs(momentum) >= FAST_SWIPE_CELLS_PER_SECOND && !host.prefersReducedMotion()) {
                    startGlide(target)
                } else if (motionEnabled()) {
                    settleAt(target, if (released) momentum else 0f)
                }
                if (released) onSelect(target)
                if (settle == null && glideTarget.isNaN()) moveTo(pagerPosition)
            }
        }
        return true
    }

    private fun releaseVelocity(): Float {
        val tracker = velocity ?: return 0f
        tracker.computeCurrentVelocity(1000)
        val cellsPerSecond = (tracker.xVelocity / cellWidth()).coerceIn(-6f, 6f)
        tracker.recycle()
        velocity = null
        return cellsPerSecond
    }

    private fun settleAt(target: Int, velocity: Float) {
        val offset = position - target
        val damping = 17f
        val snap = ValueAnimator.ofFloat(0f, .36f)
        settle = snap
        snap.duration = 360L
        snap.interpolator = android.view.animation.LinearInterpolator()
        snap.addUpdateListener { frame ->
            val t = frame.animatedValue as Float
            val displacement = (offset + (velocity + damping * offset) * t) * exp(-damping * t)
            if (frame.animatedFraction == 1f) {
                settle = null
                moveTo(pagerPosition)
            } else {
                moveTo((target + displacement).coerceIn(-.05f, labels.lastIndex + .05f))
            }
        }
        snap.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        stopPagerGlide()
        endPagerGesture()
        settle?.cancel()
        settle = null
        dragging = false
        velocity?.recycle()
        velocity = null
        if (Build.VERSION.SDK_INT >= 33) glass?.discard()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val x = padding + cellWidth() * (position + .5f)
        val phase = (position - position.roundToInt()) * Math.PI.toFloat()
        val travel = abs(sin(phase))
        val ripple = travel * sin(phase * 4f)
        val refraction = maxOf(travel + ripple * .16f, glideRipple)
        val tension = (shape * maxOf(travel, glideRipple)).coerceIn(0f, 1f)
        val lensWidth = cellWidth() * .91f + scale * tension * 8f
        val lensHeight = scale * 48f
        val rimStabilization = if (glideStartedAt != 0L) {
            (refraction * 4f).coerceAtMost(1f)
        } else {
            fastSwipeBlend * (travel * 4f).coerceAtMost(1f)
        }
        val inset = scale * 6f
        if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated && glass != null) {
            glass.draw(canvas, this, scene(), pages.takeIf { pagerPosition > 0f }, row,
                x, lensWidth, lensHeight, refraction, rimStabilization, glideLight, tension, inset)
        } else {
            body.set(0f, inset, width.toFloat(), height - inset)
            val radius = body.height() / 2f
            canvas.drawRoundRect(body, radius, radius, fallbackBody)
            body.inset(rim.strokeWidth / 2, rim.strokeWidth / 2)
            canvas.drawRoundRect(body, radius, radius, rim)
            val y = height / 2f
            body.set(x - lensWidth / 2, y - lensHeight / 2, x + lensWidth / 2, y + lensHeight / 2)
            val corner = lensHeight * .43f
            canvas.drawRoundRect(body, corner, corner, fallbackLens)
            super.dispatchDraw(canvas)
        }
    }

    companion object {
        const val HEIGHT_DP = 68
        const val OVERLAP_DP = 56
        private const val FAST_SWIPE_CELLS_PER_SECOND = 3.5f
        private const val FAST_GLIDE_MS = 285f
        private const val SHAPE_DAMPING = 23f
        private val TAB_NEUTRAL_COLOR = 0xFFD6DCE5.toInt()
        private val TAB_LENS_COLOR = 0xFF64E9FF.toInt()
    }
}

@RequiresApi(33)
private class DockRefraction {
    private val node = RenderNode("dockBackdrop")
    private val shader = RuntimeShader(SOURCE)
    private val location = IntArray(2)
    private val sourceLocation = IntArray(2)
    private val sourceBounds = Rect()
    private val currentLens = RectF()
    private val previousLens = RectF()
    private var previousRefraction = Float.NaN
    private var previousStabilization = Float.NaN
    private var previousLight = Float.NaN
    private var previousTension = Float.NaN

    fun draw(
        canvas: Canvas,
        dock: View,
        scene: View,
        pageSource: View?,
        controls: View,
        x: Float,
        lensWidth: Float,
        lensHeight: Float,
        refraction: Float,
        stabilization: Float,
        travelLight: Float,
        tension: Float,
        inset: Float,
    ) {
        val width = dock.width
        val height = dock.height
        if (width == 0 || height == 0) return
        dock.getLocationInWindow(location)
        val recording = node.beginRecording(width, height)
        try {
            recording.drawColor(DeyttUi.BG)
            scene.getLocationInWindow(sourceLocation)
            val saved = recording.save()
            recording.translate((sourceLocation[0] - location[0]).toFloat(), (sourceLocation[1] - location[1]).toFloat())
            scene.background?.draw(recording)
            recording.restoreToCount(saved)
            if (pageSource != null && pageSource.isShown) {
                pageSource.getLocationInWindow(sourceLocation)
                val left = sourceLocation[0] - location[0]
                val top = sourceLocation[1] - location[1]
                sourceBounds.set(left, top, left + pageSource.width, top + pageSource.height)
                if (sourceBounds.intersects(0, 0, width, height)) {
                    val save = recording.save()
                    recording.clipRect(sourceBounds)
                    recording.translate(left.toFloat(), top.toFloat())
                    pageSource.draw(recording)
                    recording.restoreToCount(save)
                }
            }
            recording.drawColor(0xA2121822.toInt())
            // The floating lens passes over the dock's own glyphs and captions, too.
            controls.draw(recording)
        } finally {
            node.endRecording()
        }
        currentLens.set(x, height / 2f, lensWidth / 2f, lensHeight / 2f)
        if (node.width != width || node.height != height || previousLens != currentLens ||
            refraction != previousRefraction || stabilization != previousStabilization ||
            travelLight != previousLight || tension != previousTension
        ) {
            node.setPosition(0, 0, width, height)
            shader.setFloatUniform("dockSize", width.toFloat(), height.toFloat())
            shader.setFloatUniform("inset", inset)
            shader.setFloatUniform("refraction", refraction)
            shader.setFloatUniform("motion", stabilization, travelLight, tension)
            shader.setFloatUniform("lens", currentLens.left, currentLens.top, currentLens.right, currentLens.bottom)
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "backdrop"))
            previousLens.set(currentLens)
            previousRefraction = refraction
            previousStabilization = stabilization
            previousLight = travelLight
            previousTension = tension
        }
        canvas.drawRenderNode(node)
    }

    fun discard() {
        node.discardDisplayList()
    }

    companion object {
        private const val SOURCE = """
            uniform shader backdrop;
            uniform float2 dockSize;
            uniform float4 lens;
            uniform float inset;
            uniform float refraction;
            uniform float3 motion;

            float2 capsuleOffset(float2 p, float2 halfSize) {
                float radius = min(halfSize.x, halfSize.y);
                return p - clamp(p, -(halfSize - radius), halfSize - radius);
            }

            half4 main(float2 p) {
                float2 dockHalf = dockSize * 0.5;
                float2 bodyHalf = dockHalf - float2(0, inset);
                float2 edge = capsuleOffset(p - dockHalf, bodyHalf);
                float edgeLength = length(edge);
                float dockDistance = edgeLength - bodyHalf.y;
                // Distance is in device pixels, so keep coverage gradual at subpixel positions.
                float bodyAlpha = 1.0 - smoothstep(-1.05, 1.05, dockDistance);

                float2 local = p - lens.xy;
                float tension = motion.z;
                float side = min(abs(local.x) / lens.z, 1.0);
                float radius = min(lens.z, lens.w) * (0.79 + 0.13 * tension);
                // Shape the shoulders symmetrically without lifting or tilting the droplet.
                float2 contour = float2(local.x, local.y * (1.0 + 0.11 * tension * side * side));
                float2 shoulder = contour - clamp(contour, -lens.zw + radius, lens.zw - radius);
                float shoulderLength = length(shoulder);
                float distance = shoulderLength - radius;
                float lensAlpha = 1.0 - smoothstep(-1.15, 1.15, distance);
                if (bodyAlpha + lensAlpha <= 0.0) return half4(0);

                float2 normal = edge / max(edgeLength, 0.01);
                float bevel = smoothstep(-inset, 0.0, dockDistance);
                float2 minPixel = float2(0.5);
                float2 maxPixel = dockSize - 0.5;
                float2 samplePoint = p - normal * inset * 0.9 * bevel;
                float bodyRim = smoothstep(-2.0, 0.0, dockDistance);
                half3 light = half3(0.72, 0.83, 0.96) * ((0.10 * max(-normal.y, 0.0) + 0.024) * bodyRim);
                float2 dispersion = float2(0);
                if (lensAlpha > 0.0) {
                    float2 lensNormal = shoulder / max(shoulderLength, 0.01);
                    float rim = smoothstep(-inset * 1.25, 0.0, distance);
                    float fold = rim * rim;
                    float stabilization = motion.x;
                    float slowBend = (0.65 + refraction * 1.8) * fold;
                    float fastBend = 0.55 * rim * (1.0 - rim);
                    float bend = mix(slowBend, fastBend, stabilization);
                    float focus = mix(0.012 + refraction * 0.018, 0.18 * refraction * (1.0 - rim), stabilization);
                    float2 bent = p - local * focus - lensNormal * inset * bend;
                    samplePoint = mix(samplePoint, bent, lensAlpha);
                    float chroma = refraction * (1.0 - stabilization);
                    if (fold > 0.025 && chroma > 0.06) {
                        float fade = smoothstep(0.025, 0.08, fold) * smoothstep(0.06, 0.18, chroma);
                        dispersion = lensNormal * inset * chroma * 0.10 * fold * fade * lensAlpha;
                    }
                    float top = max(dot(lensNormal, float2(-0.42 + motion.y * 0.85, -0.91)), 0.0);
                    float bounce = max(dot(lensNormal, float2(0.34, 0.94)), 0.0);
                    float glint = (0.14 * top * top + 0.095 * bounce * bounce) * rim * rim * rim;
                    float gleam = glint * (1.0 + stabilization * refraction * 0.8) + 0.032 * fold;
                    light = mix(light, half3(0.78, 0.87, 0.98) * gleam, lensAlpha);
                }

                half3 color = backdrop.eval(clamp(samplePoint, minPixel, maxPixel)).rgb;
                if (dispersion.x != 0.0 || dispersion.y != 0.0) {
                    color.r = backdrop.eval(clamp(samplePoint + dispersion, minPixel, maxPixel)).r;
                    color.b = backdrop.eval(clamp(samplePoint - dispersion, minPixel, maxPixel)).b;
                }
                half coverage = half(mix(bodyAlpha, 1.0, lensAlpha));
                return half4((color + light) * coverage, coverage);
            }
        """
    }
}

private class DockIcon(context: Context, private val index: Int) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.55f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path().apply {
        when (index) {
            0 -> {
                moveTo(-7.5f, -.3f)
                lineTo(-1.15f, -6.25f)
                quadTo(0f, -7.3f, 1.15f, -6.25f)
                lineTo(7.5f, -.3f)
                moveTo(-5.65f, -1.8f)
                lineTo(-5.65f, 5.2f)
                quadTo(-5.65f, 6.15f, -4.65f, 6.15f)
                lineTo(-2.05f, 6.15f)
                lineTo(-2.05f, 2.2f)
                quadTo(-2.05f, 1.4f, -1.2f, 1.4f)
                lineTo(1.2f, 1.4f)
                quadTo(2.05f, 1.4f, 2.05f, 2.2f)
                lineTo(2.05f, 6.15f)
                lineTo(4.65f, 6.15f)
                quadTo(5.65f, 6.15f, 5.65f, 5.2f)
                lineTo(5.65f, -1.8f)
            }
            1 -> {
                addCircle(-4.8f, -4.5f, 1.75f, Path.Direction.CW)
                addCircle(4.8f, 4.5f, 1.75f, Path.Direction.CW)
                moveTo(-3.05f, -4.5f)
                lineTo(1.55f, -4.5f)
                cubicTo(4.8f, -4.5f, 4.8f, 0f, 1.55f, 0f)
                lineTo(-1.55f, 0f)
                cubicTo(-4.8f, 0f, -4.8f, 4.5f, -1.55f, 4.5f)
                lineTo(3.05f, 4.5f)
            }
            2 -> {
                addCircle(0f, -3.65f, 2.9f, Path.Direction.CW)
                moveTo(-6.3f, 6.1f)
                cubicTo(-5.7f, .6f, 5.7f, .6f, 6.3f, 6.1f)
            }
            else -> {
                moveTo(-6.75f, -4f)
                lineTo(-3.55f, -4f)
                addCircle(-1.7f, -4f, 1.85f, Path.Direction.CW)
                moveTo(.15f, -4f)
                lineTo(6.75f, -4f)
                moveTo(-6.75f, 4f)
                lineTo(-.15f, 4f)
                addCircle(1.7f, 4f, 1.85f, Path.Direction.CW)
                moveTo(3.55f, 4f)
                lineTo(6.75f, 4f)
            }
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setTint(color: Int) {
        if (ink.color == color) return
        ink.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val scale = resources.displayMetrics.density
        val saved = canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(path, ink)
        canvas.restoreToCount(saved)
    }
}
