package space.deytt.connect

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ProgressBar

/** Compact native circular indicator for route checks that are still in flight. */
internal class DiagnosticProgressView(context: Context) :
    ProgressBar(context, null, android.R.attr.progressBarStyleSmall) {
    init {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(DeyttUi.SKY)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    fun setLoading(value: Boolean) {
        visibility = if (value) View.VISIBLE else View.GONE
    }
}
