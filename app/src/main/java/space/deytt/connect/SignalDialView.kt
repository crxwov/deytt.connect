package space.deytt.connect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** A quiet, stateful focal point; it communicates tunnel state without decoration. */
internal class SignalDialView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val bounds = RectF()
    private var visualState = ConnectionVisualState.IDLE

    init {
        contentDescription = "VPN не подключён"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setState(state: ConnectionVisualState) {
        if (visualState == state) return
        visualState = state
        contentDescription = when (state) {
            ConnectionVisualState.IDLE -> "VPN не подключён"
            ConnectionVisualState.CONNECTING -> "VPN подключается"
            ConnectionVisualState.CONNECTED -> "VPN подключён"
            ConnectionVisualState.ERROR -> "Ошибка подключения VPN"
        }
        animate().cancel()
        alpha = 0.72f
        scaleX = 0.97f
        scaleY = 0.97f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = size * 0.34f
        val accent = when (visualState) {
            ConnectionVisualState.IDLE -> 0xFF70809A.toInt()
            ConnectionVisualState.CONNECTING -> 0xFF7C8CFF.toInt()
            ConnectionVisualState.CONNECTED -> 0xFF65E6B7.toInt()
            ConnectionVisualState.ERROR -> 0xFFFF718D.toInt()
        }

        paint.strokeWidth = size * 0.012f
        paint.color = 0xFF1C2940.toInt()
        bounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(bounds, -214f, 248f, false, paint)

        paint.strokeWidth = size * 0.018f
        paint.color = accent
        val sweep = when (visualState) {
            ConnectionVisualState.IDLE -> 42f
            ConnectionVisualState.CONNECTING -> 126f
            ConnectionVisualState.CONNECTED -> 248f
            ConnectionVisualState.ERROR -> 76f
        }
        canvas.drawArc(bounds, -214f, sweep, false, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(22, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawCircle(centerX, centerY, radius * 0.7f, paint)
        paint.color = accent
        canvas.drawCircle(centerX, centerY, size * 0.032f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.012f
        paint.color = accent
        canvas.drawLine(centerX, centerY - radius * 0.45f, centerX, centerY - radius * 0.14f, paint)
        bounds.set(
            centerX - radius * 0.34f,
            centerY - radius * 0.34f,
            centerX + radius * 0.34f,
            centerY + radius * 0.34f,
        )
        canvas.drawArc(bounds, -42f, 264f, false, paint)
    }
}
