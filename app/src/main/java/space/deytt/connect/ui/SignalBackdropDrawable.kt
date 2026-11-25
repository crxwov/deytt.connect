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
        color = Color.argb(18, 113, 128, 255)
        strokeWidth = 1f
    }
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stars = listOf(
        .08f to .12f, .18f to .28f, .31f to .08f, .43f to .19f, .57f to .11f,
        .71f to .27f, .86f to .14f, .94f to .34f, .12f to .47f, .27f to .58f,
        .48f to .44f, .64f to .56f, .78f to .45f, .91f to .65f, .06f to .79f,
        .22f to .88f, .39f to .74f, .58f to .86f, .74f to .77f, .89f to .92f,
    )

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
        val step = (bounds.width() / 3.2f).coerceAtLeast(140f)
        var x = -step
        while (x < bounds.width() + step) {
            canvas.drawLine(x, 0f, x + bounds.height() * .24f, bounds.height().toFloat(), line)
            x += step
        }
        stars.forEachIndexed { index, (x, y) ->
            star.alpha = if (index % 5 == 0) 92 else 42
            canvas.drawCircle(bounds.width() * x, bounds.height() * y, if (index % 5 == 0) 1.35f else .8f, star)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
