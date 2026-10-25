package space.deytt.connect

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Native port of the Mini App terminal splash; only its final letter differs. */
internal class StartupAnimationView(activity: Activity, private val reducedMotion: Boolean) : View(activity) {
    companion object {
        fun configureSystemSplash(activity: Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            activity.splashScreen.setOnExitAnimationListener { nativeSplash ->
                val elapsed = nativeSplash.iconAnimationStart?.let {
                    System.currentTimeMillis() - it.toEpochMilli()
                } ?: 0L
                nativeSplash.postDelayed({
                    nativeSplash.animate().alpha(0f).setDuration(340L)
                        .setInterpolator(PathInterpolator(.16f, 1f, .3f, 1f))
                        .withEndAction { nativeSplash.remove() }.start()
                }, (900L - elapsed).coerceAtLeast(0L))
            }
        }
    }

    private val density = resources.displayMetrics.density
    private val fontSize = (if (resources.configuration.screenWidthDp <= 380) 48f else 58f) * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = fontSize
        typeface = if (Build.VERSION.SDK_INT >= 26) {
            Typeface.Builder(activity.assets, "fonts/unbounded-variable.ttf").setFontVariationSettings("'wght' 700").build()
        } else Typeface.create("sans-serif-black", Typeface.BOLD)
    }
    private val strikeCurve = PathInterpolator(.2f, 0f, 0f, 1f)
    private val alignCurve = PathInterpolator(.16f, 1f, .3f, 1f)
    private var startedAt = 0L
    private val characters = charArrayOf('.', '/', 'C')
    private val revealTimes = longArrayOf(140L, 320L, 500L)
    private val strikeY = arrayOf(floatArrayOf(-5f, 4f, 3f), floatArrayOf(-7f, -2f, -3f), floatArrayOf(-5f, 3f, 2f))
    private val strikeRotation = arrayOf(floatArrayOf(0f, -2.5f, -2f), floatArrayOf(0f, 2.5f, 2f), floatArrayOf(0f, -2f, -1.5f))

    init {
        tag = "startup-splash"
        setBackgroundColor(Color.BLACK)
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun start() {
        if (startedAt != 0L) return
        startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (startedAt == 0L) return
        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (elapsed >= 1_240L) {
            post { (parent as? ViewGroup)?.removeView(this) }
            return
        }
        val aligned = if (reducedMotion) 1f else alignCurve.getInterpolation(((elapsed - 680L) / 420f).coerceIn(0f, 1f))
        val tracking = fontSize * (-.02f - .045f * aligned)
        val visible = revealTimes.count { elapsed >= it }
        val cursorWidth = 3.5f * density
        val cursorMargin = 4f * density
        val advances = characters.map { paint.measureText(it.toString()) + tracking }
        val terminalWidth = advances.take(visible).sum() + cursorMargin + cursorWidth
        val baseline = height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        var x = (width - terminalWidth) / 2f
        repeat(visible) { index ->
            val rawStrike = ((elapsed - revealTimes[index]) / 100f).coerceIn(0f, 1f)
            val segment = if (rawStrike < .5f) 0 else 1
            val fraction = strikeCurve.getInterpolation((rawStrike * 2f - segment).coerceIn(0f, 1f))
            fun interpolate(values: FloatArray): Float = values[segment] + (values[segment + 1] - values[segment]) * fraction
            val offsetY = if (reducedMotion) 0f else interpolate(strikeY[index]) * density * (1f - aligned)
            val rotation = if (reducedMotion) 0f else interpolate(strikeRotation[index]) * (1f - aligned)
            val scale = if (reducedMotion) 1f else interpolate(floatArrayOf(1.22f, .96f, 1f))
            val pivotX = (advances[index] - tracking) / 2f
            val pivotY = baseline + fontSize * .1f
            canvas.save()
            canvas.translate(x + pivotX, pivotY + offsetY)
            canvas.rotate(rotation)
            canvas.scale(scale, scale)
            canvas.drawText(characters[index].toString(), -pivotX, baseline - pivotY, paint)
            canvas.restore()
            x += advances[index]
        }
        val cursorAlpha = 1f - ((elapsed - 680L) / 160f).coerceIn(0f, 1f)
        paint.alpha = (255 * cursorAlpha).toInt()
        val cursorBottom = baseline + fontSize * .04f
        canvas.drawRoundRect(x + cursorMargin, cursorBottom - fontSize * .76f, x + cursorMargin + cursorWidth,
            cursorBottom, 1.5f * density, 1.5f * density, paint)
        paint.alpha = 255
        if (elapsed >= 900L) {
            val exit = alignCurve.getInterpolation(((elapsed - 900L) / 340f).coerceIn(0f, 1f))
            alpha = 1f - exit
            if (!reducedMotion) { scaleX = 1f + exit * .02f; scaleY = scaleX }
        }
        postInvalidateOnAnimation()
    }
}
