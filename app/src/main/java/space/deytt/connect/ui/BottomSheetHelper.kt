package space.deytt.connect

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import space.deytt.connect.DeyttUi.dp

internal object BottomSheetHelper {
    fun show(host: MainActivity, dialog: Dialog, content: View) {
        val container = FrameLayout(host).apply {
            setPadding(host.dp(14), 0, host.dp(14), host.dp(20))
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
            setOnClickListener { dialog.dismiss() }
        }
        content.setOnClickListener { /* prevent dismissal on clicking inside content */ }
        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.58f)
            DeyttUi.blurBehind(this, host.dp(20))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
