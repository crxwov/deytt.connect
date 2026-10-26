package space.deytt.connect

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.glassButtonBackground
import space.deytt.connect.DeyttUi.liquidGlassButton
import space.deytt.connect.DeyttUi.ripple
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.typeface

/** App-owned confirmations share the same surfaces and readable actions as our pages. */
object AppDialog {
    class Builder(private val host: Activity) : AlertDialog.Builder(host, R.style.DeyttDialogTheme) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.setOnShowListener {
                dialog.window?.apply {
                    setBackgroundDrawable(host.rounded(DeyttUi.SHEET_SURFACE, 26f))
                    setLayout(minOf(host.resources.displayMetrics.widthPixels - host.dp(32), host.dp(390)), ViewGroup.LayoutParams.WRAP_CONTENT)
                    decorView.clipToOutline = true
                    setDimAmount(.65f)
                    DeyttUi.blurBehind(this, host.dp(24))
                }
                fun styleText(view: View) {
                    if (view is TextView) {
                        view.typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, 400)
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
                        setPadding(host.dp(18), host.dp(10), host.dp(18), host.dp(10))
                        typeface = host.typeface(DeyttUi.FontFamily.INTER_TIGHT, 620)
                        if (which == DialogInterface.BUTTON_POSITIVE) {
                            setTextColor(Color.WHITE)
                            background = host.glassButtonBackground(DeyttUi.BLUE, DeyttUi.BLUE_DEEP, 20f)
                            elevation = host.dp(2).toFloat()
                        } else {
                            setTextColor(DeyttUi.TEXT)
                            background = host.liquidGlassButton(secondary = true, radius = 20f)
                            elevation = host.dp(1).toFloat()
                        }
                        foreground = host.ripple(20f)
                    }
                }
            }
        }
    }
}
