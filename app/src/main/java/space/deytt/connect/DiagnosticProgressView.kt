package space.deytt.connect

import android.content.Context
import android.content.res.ColorStateList
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.View
import android.widget.ImageView

/** Compact rotating refresh mark for route checks that are still in flight. */
internal class DiagnosticProgressView(context: Context) : ImageView(context) {
    private val spin = RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, .5f, Animation.RELATIVE_TO_SELF, .5f).apply {
        duration = context.resources.getInteger(R.integer.sync_rotation_duration_ms).toLong()
        repeatCount = Animation.INFINITE
        interpolator = LinearInterpolator()
    }

    init {
        setImageResource(R.drawable.ic_sync)
        imageTintList = ColorStateList.valueOf(DeyttUi.SKY)
        scaleType = ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    fun setLoading(value: Boolean) {
        if (value && visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            startAnimation(spin)
        } else if (!value && visibility != View.GONE) {
            clearAnimation()
            visibility = View.GONE
        }
    }
}
