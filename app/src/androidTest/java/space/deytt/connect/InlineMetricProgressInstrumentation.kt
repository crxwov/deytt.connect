package space.deytt.connect

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Exercises real Android span/layout behavior without account or VPN data. */
class InlineMetricProgressInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated QA package required" }
            var checkFailure: Throwable? = null
            runOnMainSync {
                try {
                    val label = TextView(targetContext).apply {
                        textSize = 11f
                        text = "Отклик: — · Скорость: —"
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val progress = InlineMetricProgress(label)
                    fun slots(): Int = (label.text as Spanned).getSpans(0, label.length(), ReplacementSpan::class.java).size
                    progress.setLoading(true)
                    check(slots() == 2) { "Both pending values need their own inline indicator" }
                    val width = (110 * targetContext.resources.displayMetrics.density).toInt()
                    label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    label.layout(0, 0, width, label.measuredHeight)
                    check(label.lineCount > 1) { "Narrow metric row should wrap without moving indicators out of text" }
                    val image = Bitmap.createBitmap(width, label.height, Bitmap.Config.ARGB_8888)
                    label.draw(Canvas(image))
                    image.recycle()
                    label.text = "Отклик: 45 мс · Скорость: —"
                    check(slots() == 1) { "A measured response must immediately release its loading slot" }
                    label.append(" · скорость в очереди")
                    check(slots() == 1) { "Appending a status must retain exactly one speed indicator" }
                    progress.setLoading(false)
                    check(slots() == 0) { "Completion must remove all animation spans" }
                    check(label.text.toString() == "Отклик: 45 мс · Скорость: — · скорость в очереди")
                    progress.setLoading(true)
                    check(slots() == 1)
                    label.text = "Отклик: 45 мс · Скорость: 12 Мбит/с"
                    check(slots() == 0) { "Live values must not keep a detached spinner" }
                    progress.setLoading(false)
                } catch (error: Throwable) { checkFailure = error }
            }
            checkFailure?.let { throw it }
            result.putString("result", "PASS: two/one/no inline slots, live updates, wrapping, drawing, completion")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
