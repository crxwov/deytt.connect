package space.deytt.connect

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
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
    const val SURFACE = 0xFF0E182A.toInt()
    const val SURFACE_2 = 0xFF111D32.toInt()
    const val LINE = 0xFF2B3954.toInt()
    const val TEXT = 0xFFF3F6FF.toInt()
    const val MUTED = 0xFF8795AD.toInt()
    const val BLUE = 0xFF7180FF.toInt()
    const val MINT = 0xFF4ED7A6.toInt()
    const val CORAL = 0xFFFF6B86.toInt()

    fun Activity.screen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(30))
        background = SignalBackdropDrawable()
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
            if (kicker.isNotBlank()) addView(text(kicker.uppercase(), 11f, MUTED, Typeface.BOLD).apply { letterSpacing = .16f })
            addView(text(title, 34f, TEXT, Typeface.BOLD).apply { letterSpacing = -.035f; setPadding(0, dp(7), 0, dp(8)) })
        }

    fun Activity.brandHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text("deytt.", 29f, TEXT, Typeface.BOLD).apply { letterSpacing = -.045f })
        addView(Space(this@brandHeader), LinearLayout.LayoutParams(0, 1, 1f))
        addView(text("●  сеть готова", 11f, MINT, Typeface.BOLD).apply { letterSpacing = .06f })
    }

    fun Activity.text(value: String, size: Float, color: Int = TEXT, style: Int = Typeface.NORMAL): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", style)
            includeFontPadding = false
        }

    fun Activity.button(label: String, secondary: Boolean = false): TextView = text(label, 16f, if (secondary) TEXT else Color.WHITE, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(if (secondary) SURFACE_2 else BLUE, 18f, if (secondary) LINE else BLUE)
        isClickable = true
        isFocusable = true
        val target = this
        stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), scaleAnimator(target, .975f, 110))
            addState(intArrayOf(), scaleAnimator(target, 1f, 140))
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
            setPadding(dp(4), dp(17), dp(4), dp(17))
            background = GradientDrawable().apply {
                setColor(SURFACE)
                setStroke(dp(1), LINE)
                cornerRadius = dp(18).toFloat()
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
            if (trailing.isNotBlank()) {
                addView(text(trailing, 22f, MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            isClickable = interactive
            isFocusable = interactive
            val target = this
            if (interactive) stateListAnimator = StateListAnimator().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ObjectAnimator.ofFloat(target, "alpha", 1f, .76f).setDuration(90))
                addState(intArrayOf(), ObjectAnimator.ofFloat(target, "alpha", .76f, 1f).setDuration(130))
            }
        }

    private fun scaleAnimator(target: View, scale: Float, duration: Long): AnimatorSet = AnimatorSet().apply {
        playTogether(
            ObjectAnimator.ofFloat(target, "scaleX", scale),
            ObjectAnimator.ofFloat(target, "scaleY", scale),
        )
        this.duration = duration
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
