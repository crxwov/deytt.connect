package space.deytt.connect

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A small, deliberately quiet route globe. It gives the location picker a
 * spatial anchor without pulling a map SDK or running an always-on animation.
 */
class RouteGlobeView(context: Context) : View(context) {
    private data class Point(val x: Float, val y: Float, val code: String)
    private val density = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.1f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = density * 9f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val route = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val points = listOf(
        Point(.64f, .33f, "NL"),
        Point(.58f, .35f, "DE"),
        Point(.47f, .39f, "RU"),
        Point(.61f, .23f, "FI"),
    )
    private var selectedCode = "AUTO"
    private var rotation = 0f
    private var animator: ValueAnimator? = null

    init {
        contentDescription = "Глобус маршрутов"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun focus(code: String, animate: Boolean = true) {
        val normalized = code.uppercase().substringAfterLast(':').let { candidate ->
            if (candidate in setOf("NL", "DE", "RU", "FI", "RU-DE", "AUTO")) candidate else "AUTO"
        }
        selectedCode = normalized
        contentDescription = if (normalized == "AUTO") "Глобус маршрутов, автоподбор" else "Глобус маршрутов, $normalized"
        val target = when (normalized) {
            "NL" -> 10f
            "DE" -> 18f
            "RU" -> 34f
            "FI" -> 2f
            "RU-DE" -> 26f
            else -> 0f
        }
        animator?.cancel()
        if (!animate || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())) {
            rotation = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(rotation, target).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * .5f
        val cy = height * .52f
        val radius = minOf(width, height) * .38f
        val accent = if (selectedCode == "AUTO") DeyttUi.BLUE else DeyttUi.MINT

        fill.color = Color.argb(22, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawCircle(cx, cy, radius, fill)

        line.color = Color.argb(92, 139, 157, 197)
        canvas.drawCircle(cx, cy, radius, line)
        line.color = Color.argb(44, 139, 157, 197)
        canvas.drawOval(RectF(cx - radius * .42f, cy - radius, cx + radius * .42f, cy + radius), line)
        canvas.drawOval(RectF(cx - radius * .78f, cy - radius, cx + radius * .78f, cy + radius), line)
        canvas.drawOval(RectF(cx - radius, cy - radius * .45f, cx + radius, cy + radius * .45f), line)
        canvas.drawOval(RectF(cx - radius, cy - radius * .78f, cx + radius, cy + radius * .78f), line)

        val visible = points.map { point ->
            val longitude = (point.x - .5f) * 2f
            val shift = (rotation / 34f).coerceIn(-1f, 1f) * radius * .22f
            val x = cx + longitude * radius * .86f + shift
            val y = cy + (point.y - .5f) * radius * 1.7f
            Point(x, y, point.code)
        }
        val origin = Point(cx - radius * .74f, cy + radius * .62f, "HOME")
        route.color = Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent))
        if (selectedCode == "AUTO") {
            visible.forEach { point -> drawRoute(canvas, origin, point, alpha = 52) }
        } else {
            visible.firstOrNull { it.code == selectedCode }?.let { drawRoute(canvas, origin, it) }
            if (selectedCode == "RU-DE") {
                visible.firstOrNull { it.code == "RU" }?.let { drawRoute(canvas, origin, it) }
                val ru = visible.firstOrNull { it.code == "RU" }
                val de = visible.firstOrNull { it.code == "DE" }
                if (ru != null && de != null) drawRoute(canvas, ru, de)
            }
        }
        visible.forEach { point ->
            val isSelected = point.code == selectedCode || (selectedCode == "RU-DE" && point.code == "DE")
            fill.color = if (isSelected) accent else Color.argb(180, 178, 190, 216)
            canvas.drawCircle(point.x, point.y, if (isSelected) density * 4.2f else density * 2.4f, fill)
            if (isSelected) {
                line.color = Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent))
                line.strokeWidth = density
                canvas.drawCircle(point.x, point.y, density * 8f, line)
            }
            label.color = if (isSelected) accent else Color.argb(170, 178, 190, 216)
            canvas.drawText(point.code, point.x, point.y - density * 8f, label)
        }
        fill.color = DeyttUi.TEXT
        canvas.drawCircle(origin.x, origin.y, density * 3f, fill)
        label.color = DeyttUi.TEXT
        canvas.drawText("вы", origin.x, origin.y + density * 17f, label)
    }

    private fun drawRoute(canvas: Canvas, from: Point, to: Point, alpha: Int = 105) {
        val path = Path()
        path.moveTo(from.x, from.y)
        val midX = (from.x + to.x) * .5f
        val lift = minOf(width, height) * .18f
        route.alpha = alpha
        path.quadTo(midX, minOf(from.y, to.y) - lift, to.x, to.y)
        canvas.drawPath(path, route)
    }
}
