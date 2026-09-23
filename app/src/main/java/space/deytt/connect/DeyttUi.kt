package space.deytt.connect

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

object DeyttUi {
    const val BG = 0xFF070C16.toInt()
    const val SURFACE = 0xFF101A2C.toInt()
    const val LINE = 0xFF273653.toInt()
    const val TEXT = 0xFFF5F7FF.toInt()
    const val MUTED = 0xFF8997B3.toInt()
    const val BLUE = 0xFF7180FF.toInt()
    const val MINT = 0xFF4ED7A6.toInt()
    const val CORAL = 0xFFFF6B86.toInt()

    fun Activity.screen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(22), dp(24), dp(28))
        setBackgroundColor(BG)
        fitsSystemWindows = true
    }

    fun Activity.header(kicker: String, title: String, back: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (back) addView(text("‹  назад", 15f, MUTED, Typeface.BOLD).apply {
                setPadding(0, dp(4), 0, dp(20))
                contentDescription = "Назад"
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
            })
            addView(text(kicker.uppercase(), 12f, MUTED, Typeface.BOLD).apply { letterSpacing = .18f })
            addView(text(title, 32f, TEXT, Typeface.BOLD).apply { setPadding(0, dp(8), 0, dp(8)) })
        }

    fun Activity.text(value: String, size: Float, color: Int = TEXT, style: Int = Typeface.NORMAL): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans", style)
            includeFontPadding = false
        }

    fun Activity.button(label: String, secondary: Boolean = false): TextView = text(label, 17f, if (secondary) TEXT else Color.WHITE, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(if (secondary) SURFACE else BLUE, 18f, if (secondary) LINE else BLUE)
        isClickable = true
        isFocusable = true
        val target = this
        stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ObjectAnimator.ofFloat(target, "alpha", 1f, .78f).setDuration(90))
            addState(intArrayOf(), ObjectAnimator.ofFloat(target, "alpha", .78f, 1f).setDuration(130))
        }
    }

    fun Activity.row(
        title: String,
        subtitle: String,
        leading: String,
        trailing: String = "›",
        interactive: Boolean = true,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(18))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), LINE)
                cornerRadius = dp(2).toFloat()
            }
            addView(text(leading, 25f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(this@row).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 17f, TEXT, Typeface.BOLD).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(text(subtitle, 13f, MUTED).apply {
                    setPadding(0, dp(5), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(trailing, 22f, MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
            isClickable = interactive
            isFocusable = interactive
        }

    fun spacer(height: Int, activity: Activity): Space = Space(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, activity.dp(height))
    }

    fun Activity.rounded(fill: Int, radius: Float, stroke: Int = fill): GradientDrawable =
        GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(1), stroke) }

    fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun Activity.present(content: LinearLayout) {
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }
}
