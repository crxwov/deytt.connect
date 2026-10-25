package space.deytt.connect

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** Keeps each pending metric in its own text slot, including wrapped lines. */
internal class InlineMetricProgress(private val label: TextView) {
    private var loading = false
    private val startedAt = SystemClock.uptimeMillis()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { label.invalidate() }
    }

    init {
        label.setText(label.text, TextView.BufferType.SPANNABLE)
        label.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) { updateSpans() }
        })
        label.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { updateAnimation() }
            override fun onViewDetachedFromWindow(view: View) { animator.cancel() }
        })
    }

    fun setLoading(value: Boolean) {
        loading = value
        updateSpans()
    }

    private fun updateSpans() {
        val text = label.text as? Spannable ?: return
        text.getSpans(0, text.length, LoadingSpan::class.java).forEach(text::removeSpan)
        if (loading) text.indices.filter { text[it] == '—' }.forEach { index ->
            text.setSpan(LoadingSpan(), index, index + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        label.requestLayout()
        label.invalidate()
        updateAnimation()
    }

    private fun updateAnimation() {
        val hasSlots = (label.text as? Spannable)?.getSpans(0, label.length(), LoadingSpan::class.java)?.isNotEmpty() == true
        val reduced = (label.context as? MainActivity)?.isReducedMotionEnabled() == true ||
            (android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())
        if (loading && hasSlots && label.isAttachedToWindow && !reduced) {
            if (!animator.isStarted) animator.start()
        } else animator.cancel()
    }

    private inner class LoadingSpan : ReplacementSpan() {
        private fun size(paint: Paint): Float = paint.textSize * 1.2f

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            kotlin.math.ceil(size(paint) + label.resources.displayMetrics.density * 2f).toInt()

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val diameter = size(paint)
            val metrics = paint.fontMetrics
            val spinnerPaint = Paint(paint).apply {
                color = DeyttUi.TEXT
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = diameter * .065f
            }
            val phase = ((SystemClock.uptimeMillis() - startedAt) % 1_000L) / 1_000f * 16f
            canvas.save()
            canvas.translate(x + diameter / 2f, y + (metrics.ascent + metrics.descent) / 2f)
            repeat(16) { spoke ->
                val age = (phase - spoke + 16f) % 16f
                spinnerPaint.alpha = (paint.alpha * (.12f + .76f * (1f - age / 16f))).toInt()
                canvas.drawLine(0f, -diameter * .29f, 0f, -diameter * .47f, spinnerPaint)
                canvas.rotate(22.5f)
            }
            canvas.restore()
        }
    }
}
