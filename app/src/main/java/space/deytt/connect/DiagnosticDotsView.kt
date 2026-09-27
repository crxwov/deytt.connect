package space.deytt.connect

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** A small text-only pending indicator, shown only while a diagnostic has no value yet. */
internal class DiagnosticDotsView(context: Context) : TextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val frames = arrayOf("·", "··", "···", "··")
    private var frame = 0
    private var loading = false

    private val tick = object : Runnable {
        override fun run() {
            if (!loading || !isAttachedToWindow) return
            if (isShown) {
                text = frames[frame]
                frame = (frame + 1) % frames.size
            }
            if (loading) handler.postDelayed(this, FRAME_DELAY_MILLIS)
        }
    }

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(DeyttUi.SKY)
        gravity = Gravity.CENTER
        maxLines = 1
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    fun setLoading(value: Boolean) {
        if (loading == value && visibility == (if (value) View.VISIBLE else View.GONE)) return
        loading = value
        handler.removeCallbacks(tick)
        visibility = if (value) View.VISIBLE else View.GONE
        if (!value) {
            text = ""
            return
        }

        frame = 0
        text = if (animationsEnabled()) frames.first() else "···"
        if (animationsEnabled()) handler.postDelayed(tick, FRAME_DELAY_MILLIS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(tick)
        if (loading && animationsEnabled()) handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ValueAnimator.areAnimatorsEnabled()

    private companion object {
        const val FRAME_DELAY_MILLIS = 360L
    }
}
