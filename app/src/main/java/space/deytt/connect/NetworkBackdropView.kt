package space.deytt.connect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.min
import kotlin.math.sin

enum class ConnectionVisualState {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/** A quiet network map that turns the connection state into a visible signal. */
class NetworkBackdropView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state = ConnectionVisualState.IDLE

    private val nodes = listOf(
        PointF(0.14f, 0.34f),
        PointF(0.86f, 0.29f),
        PointF(0.2f, 0.78f),
        PointF(0.8f, 0.74f),
        PointF(0.5f, 0.11f),
    )

    fun setState(nextState: ConnectionVisualState) {
        state = nextState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            width * 0.5f,
            height * 0.47f,
            min(width, height) * 0.58f,
            intArrayOf(Color.argb(54, 93, 109, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null

        val center = PointF(width * 0.5f, height * 0.49f)
        val nodePoints = nodes.map { PointF(width * it.x, height * it.y) }
        val color = when (state) {
            ConnectionVisualState.CONNECTED -> Color.rgb(104, 227, 184)
            ConnectionVisualState.CONNECTING -> Color.rgb(113, 128, 255)
            ConnectionVisualState.ERROR -> Color.rgb(232, 111, 135)
            ConnectionVisualState.IDLE -> Color.rgb(129, 145, 176)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(80, 109, 124, 255)
        nodePoints.forEach { node ->
            canvas.drawLine(center.x, center.y, node.x, node.y, paint)
        }

        val phase = (SystemClock.uptimeMillis() % 1800L) / 1800f
        val pulse = (sin(phase * Math.PI * 2.0) + 1.0) * 0.5
        val ringRadius = min(width, height) * 0.2f
        paint.color = Color.argb((34 + pulse * 28).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        paint.strokeWidth = 1.5f
        canvas.drawCircle(center.x, center.y, ringRadius + (pulse * 7f).toFloat(), paint)
        paint.color = Color.argb(92, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(center.x, center.y, ringRadius, paint)

        paint.style = Paint.Style.FILL
        nodePoints.forEachIndexed { index, node ->
            val radius = if (index == 4) 3.5f else 2.5f
            paint.color = Color.argb(if (index == 4) 230 else 150, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(node.x, node.y, radius, paint)
        }

        paint.shader = RadialGradient(
            center.x,
            center.y,
            ringRadius * 0.75f,
            intArrayOf(
                Color.argb(245, Color.red(color), Color.green(color), Color.blue(color)),
                Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(center.x, center.y, ringRadius * 0.56f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawCircle(center.x, center.y, 4f, paint)

        if (state != ConnectionVisualState.IDLE) {
            postInvalidateDelayed(50L)
        }
    }
}
