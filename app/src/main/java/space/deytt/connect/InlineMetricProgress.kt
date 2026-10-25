package space.deytt.connect

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView

/** Keeps each pending metric in its own text slot, including wrapped lines. */
internal class InlineMetricProgress(private val label: TextView) {
    private var loading = false
    private val startedAt = SystemClock.uptimeMillis()
    // Essential progress uses opacity only and remains visible when decorative motion is disabled.
    private val frame = object : Runnable {
        override fun run() {
            label.invalidate()
            label.postDelayed(this, 1_000L / 16)
        }
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
            override fun onViewDetachedFromWindow(view: View) { label.removeCallbacks(frame) }
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
        label.removeCallbacks(frame)
        if (loading && hasSlots && label.isAttachedToWindow) label.postOnAnimation(frame)
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
