package space.deytt.connect

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView

/** Keeps each pending metric in its own text slot, including wrapped lines. */
internal class InlineMetricProgress(private val label: TextView) {
    private var loading = false
    private var paused = false
    private var hasSlots = false
    private var framePosted = false
    private var visibilityObserver: ViewTreeObserver? = null
    private val startedAt = SystemClock.uptimeMillis()
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fontMetrics = Paint.FontMetrics()
    private val visibleRect = Rect()
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener { updateAnimation() }
    private val focusChanged = ViewTreeObserver.OnWindowFocusChangeListener { updateAnimation() }
    private val scrollChanged = ViewTreeObserver.OnScrollChangedListener { updateAnimation() }
    // Essential progress uses opacity only and remains visible when decorative motion is disabled.
    private val frame = object : Runnable {
        override fun run() {
            framePosted = false
            if (!canAnimate()) return
            label.invalidate()
            updateAnimation()
        }
    }

    init {
        // Editor can cache ReplacementSpan pixels after setText; keep each loading frame live.
        label.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        label.setText(label.text, TextView.BufferType.SPANNABLE)
        label.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) { updateSpans() }
        })
        label.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { observeVisibility(); updateAnimation() }
            override fun onViewDetachedFromWindow(view: View) {
                visibilityObserver?.takeIf { it.isAlive }?.apply {
                    removeOnGlobalLayoutListener(layoutChanged)
                    removeOnWindowFocusChangeListener(focusChanged)
                    removeOnScrollChangedListener(scrollChanged)
                }
                visibilityObserver = null
                label.removeCallbacks(frame)
                framePosted = false
            }
        })
        if (label.isAttachedToWindow) observeVisibility()
    }

    fun setLoading(value: Boolean) {
        if (loading == value) { updateAnimation(); return }
        loading = value
        updateSpans()
    }

    fun setPaused(value: Boolean) {
        paused = value
        updateAnimation()
    }

    private fun observeVisibility() {
        if (visibilityObserver != null) return
        visibilityObserver = label.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(layoutChanged)
            it.addOnWindowFocusChangeListener(focusChanged)
            it.addOnScrollChangedListener(scrollChanged)
        }
    }

    private fun updateSpans() {
        val text = label.text as? Spannable ?: return
        val previous = text.getSpans(0, text.length, LoadingSpan::class.java)
        previous.forEach(text::removeSpan)
        hasSlots = false
        if (loading) for (index in text.indices) if (text[index] == '—') {
            text.setSpan(LoadingSpan(), index, index + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            hasSlots = true
        }
        if (previous.isNotEmpty() || hasSlots) label.requestLayout()
        label.invalidate()
        updateAnimation()
    }

    private fun updateAnimation() {
        if (canAnimate()) {
            if (!framePosted) { framePosted = true; label.postOnAnimation(frame) }
        } else {
            label.removeCallbacks(frame)
            framePosted = false
        }
    }

    private fun canAnimate(): Boolean = loading && hasSlots && !paused &&
        label.isAttachedToWindow && label.isShown && label.windowVisibility == View.VISIBLE && label.getGlobalVisibleRect(visibleRect)

    private inner class LoadingSpan : ReplacementSpan() {
        private fun size(paint: Paint): Float = paint.textSize * 1.2f

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            kotlin.math.ceil(size(paint) + label.resources.displayMetrics.density * 2f).toInt()

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val diameter = size(paint)
            paint.getFontMetrics(fontMetrics)
            spinnerPaint.color = DeyttUi.TEXT
            spinnerPaint.strokeWidth = diameter * .065f
            val phase = ((SystemClock.uptimeMillis() - startedAt) % 1_000L) / 1_000f * 16f
            canvas.save()
            canvas.translate(x + diameter / 2f, y + (fontMetrics.ascent + fontMetrics.descent) / 2f)
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
