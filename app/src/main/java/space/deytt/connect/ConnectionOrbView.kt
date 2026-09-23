package space.deytt.connect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

class ConnectionOrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = VpnPhase.IDLE
    private var glow: RadialGradient? = null

    init {
        contentDescription = "Состояние VPN"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setPhase(value: VpnPhase) {
        phase = value
        rebuildGlow()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        rebuildGlow()
    }

    private fun rebuildGlow() {
        if (width == 0 || height == 0) return
        val accent = accent()
        glow = RadialGradient(
            width / 2f,
            height / 2f,
            minOf(width, height) * .31f * 1.65f,
            intArrayOf(Color.argb(76, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    private fun accent(): Int = when (phase) {
        VpnPhase.CONNECTED -> DeyttUi.MINT
        VpnPhase.ERROR -> DeyttUi.CORAL
        else -> DeyttUi.BLUE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * .31f
        val accent = accent()
        paint.style = Paint.Style.FILL
        paint.shader = glow
        canvas.drawCircle(cx, cy, radius * 1.65f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.argb(72, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawCircle(cx, cy, radius * 1.18f, paint)
        paint.strokeWidth = resources.displayMetrics.density * 2f
        paint.color = accent
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius * .12f, paint)
    }
}
