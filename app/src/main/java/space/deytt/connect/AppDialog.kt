package space.deytt.connect

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import space.deytt.connect.DeyttUi.dp

/** App-owned confirmations share the same surfaces and readable actions as our pages. */
object AppDialog {
    class Builder(private val host: Activity) : AlertDialog.Builder(host, R.style.DeyttDialogTheme) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.setOnShowListener {
                dialog.window?.apply {
                    setBackgroundDrawable(GradientDrawable().apply {
                        setColor(DeyttUi.SURFACE)
                        cornerRadius = host.dp(26).toFloat()
                        setStroke(host.dp(1), DeyttUi.LINE)
                    })
                    setLayout(minOf(host.resources.displayMetrics.widthPixels - host.dp(32), host.dp(390)), ViewGroup.LayoutParams.WRAP_CONTENT)
                    decorView.clipToOutline = true
                    setDimAmount(.65f)
                }
                fun styleText(view: View) {
                    if (view is TextView) {
                        view.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                        view.setTextColor(DeyttUi.TEXT)
                        view.setLineSpacing(host.dp(2).toFloat(), 1f)
                    }
                    if (view is ViewGroup) for (index in 0 until view.childCount) styleText(view.getChildAt(index))
                }
                dialog.window?.decorView?.let(::styleText)
                dialog.findViewById<TextView>(android.R.id.message)?.apply {
                    textSize = 15f
                    setTextColor(DeyttUi.MUTED)
                }
                listOf(DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_POSITIVE).forEach { which ->
                    dialog.getButton(which)?.apply {
                        isAllCaps = false
                        textSize = 14f
                        minHeight = host.dp(48)
                        setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8))
                        setTextColor(if (which == DialogInterface.BUTTON_POSITIVE) DeyttUi.SKY else DeyttUi.MUTED)
                        background = GradientDrawable().apply {
                            setColor(if (which == DialogInterface.BUTTON_POSITIVE) DeyttUi.SKY_SURFACE else Color.TRANSPARENT)
                            cornerRadius = host.dp(16).toFloat()
                        }
                    }
                }
            }
        }
    }
}
