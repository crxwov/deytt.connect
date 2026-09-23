package space.deytt.connect

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable

class SignalBackdropDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 113, 128, 255)
        strokeWidth = 1f
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        paint.shader = RadialGradient(
            bounds.width() * .52f,
            bounds.height() * .23f,
            bounds.width() * .72f,
            intArrayOf(Color.argb(36, 113, 128, 255), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(DeyttUi.BG)
        canvas.drawRect(bounds, paint)
        val step = (bounds.width() / 8f).coerceAtLeast(44f)
        var x = -step
        while (x < bounds.width() + step) {
            canvas.drawLine(x, 0f, x + bounds.height() * .24f, bounds.height().toFloat(), line)
            x += step
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
