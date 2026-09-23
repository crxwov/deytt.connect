package space.deytt.connect

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.StateListAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

object DeyttUi {
    const val BG = 0xFF080B12.toInt()
    const val SURFACE = 0xFF10151F.toInt()
    const val SURFACE_2 = 0xFF151C29.toInt()
    const val LINE = 0xFF273249.toInt()
    const val TEXT = 0xFFF4F6FA.toInt()
    const val MUTED = 0xFF8E9AAC.toInt()
    const val BLUE = 0xFF8B96FF.toInt()
    const val MINT = 0xFF58DEB0.toInt()
    const val CORAL = 0xFFFF718B.toInt()
    const val AMBER = 0xFFFFC46B.toInt()

    fun Activity.screen(withBackdrop: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(28))
        background = if (withBackdrop) SignalBackdropDrawable() else ColorDrawable(BG)
        fitsSystemWindows = true
    }

    fun Activity.header(kicker: String, title: String, back: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (back) addView(text("Назад", 15f, MUTED, Typeface.BOLD).apply {
                setPadding(0, dp(2), 0, dp(18))
                contentDescription = "Назад"
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
            })
            if (kicker.isNotBlank()) addView(text(kicker.uppercase(), 10f, MUTED, Typeface.BOLD).apply { letterSpacing = .18f })
            addView(text(title, 31f, TEXT, Typeface.BOLD).apply { letterSpacing = -.04f; setPadding(0, dp(6), 0, dp(6)) })
        }

    fun Activity.brandHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text("./c", 20f, Color.BLACK, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            letterSpacing = .02f
            background = rounded(Color.WHITE, 11f, Color.WHITE)
            setPadding(dp(13), dp(8), dp(13), dp(8))
            contentDescription = "Логотип deytt.connect"
        })
        addView(Space(this@brandHeader), LinearLayout.LayoutParams(0, 1, 1f))
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
        minHeight = dp(56)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = rounded(if (secondary) SURFACE_2 else BLUE, 14f, if (secondary) LINE else BLUE)
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
            setPadding(dp(12), dp(12), dp(10), dp(12))
            background = GradientDrawable().apply {
                setColor(SURFACE)
                setStroke(dp(1), LINE)
                cornerRadius = dp(13).toFloat()
            }
            addView(text(leading, 12f, BLUE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = .04f
                background = rounded(SURFACE_2, 9f, LINE)
                minWidth = dp(38)
                minHeight = dp(38)
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(this@row).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, TEXT, Typeface.BOLD).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(text(subtitle, 12f, MUTED).apply {
                    setPadding(0, dp(4), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing.isNotBlank()) {
                addView(text(trailing, 16f, MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
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

    fun Activity.sectionLabel(value: String): TextView = text(value.uppercase(), 10f, MUTED, Typeface.BOLD).apply {
        letterSpacing = .16f
        setPadding(0, dp(4), 0, dp(9))
    }

    fun Activity.note(value: String, accent: Int = MUTED): TextView = text(value, 13f, accent).apply {
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = rounded(SURFACE_2, 11f, LINE)
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
