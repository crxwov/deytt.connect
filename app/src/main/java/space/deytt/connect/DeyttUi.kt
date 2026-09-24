package space.deytt.connect

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

object DeyttUi {
    const val BG = 0xFF070C15.toInt()
    const val SURFACE = 0xFF0D1727.toInt()
    const val SURFACE_2 = 0xFF132038.toInt()
    const val LINE = 0xFF2A3B55.toInt()
    const val TEXT = 0xFFF5F7FC.toInt()
    const val MUTED = 0xFFA5B2C9.toInt()
    const val BLUE = 0xFF5365F6.toInt()
    const val SKY = 0xFF75D8FF.toInt()
    const val MINT = 0xFF63DFB3.toInt()
    const val CORAL = 0xFFFF7784.toInt()
    const val AMBER = 0xFFFFC46B.toInt()

    fun Activity.screen(withBackdrop: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val gutter = contentGutter()
        setPadding(gutter, dp(12), gutter, dp(22))
        background = ColorDrawable(BG)
        fitsSystemWindows = false
        clipToPadding = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(gutter, dp(12) + bars.top, gutter, dp(22))
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    fun Activity.header(kicker: String, title: String, back: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (back) {
                val backAction = LinearLayout(this@header).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumWidth = dp(84)
                    minimumHeight = dp(48)
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Назад"
                    setOnClickListener { finish() }
                    addView(text("‹", 30f, TEXT).apply {
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(dp(28), dp(48)))
                    addView(text("Назад", 13f, MUTED, Typeface.NORMAL).apply { gravity = Gravity.CENTER_VERTICAL })
                }
                addView(backAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
            }
            if (kicker.isNotBlank()) {
                addView(text(kicker.uppercase(), 10f, SKY, Typeface.BOLD).apply {
                    letterSpacing = .13f
                    setPadding(0, dp(5), 0, 0)
                })
            }
            addView(text(title, 29f, TEXT, Typeface.BOLD).apply {
                letterSpacing = -.025f
                setPadding(0, dp(5), 0, dp(5))
            })
        }

    fun Activity.brandHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text("deytt.", 21f, TEXT, Typeface.BOLD).apply {
            letterSpacing = -.035f
            contentDescription = "deytt. connect"
        })
        addView(text("connect", 10f, MUTED, Typeface.BOLD).apply {
            letterSpacing = .12f
            setPadding(dp(7), dp(5), 0, 0)
        })
    }

    fun Activity.text(value: String, size: Float, color: Int = TEXT, style: Int = Typeface.NORMAL): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", style)
            includeFontPadding = false
        }

    fun Activity.button(label: String, secondary: Boolean = false): TextView =
        text(label, 15f, if (secondary) TEXT else Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            minHeight = dp(54)
            setPadding(dp(18), dp(13), dp(18), dp(13))
            background = rounded(if (secondary) SURFACE_2 else BLUE, 13f, if (secondary) LINE else BLUE)
            isClickable = true
            isFocusable = true
            contentDescription = label
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ValueAnimator.areAnimatorsEnabled()) {
                val target = this
                stateListAnimator = StateListAnimator().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), scaleAnimator(target, .98f, 100))
                    addState(intArrayOf(), scaleAnimator(target, 1f, 130))
                }
            }
        }

    fun Activity.actionLabel(label: String = "проверить"): TextView =
        text(label, 11f, SKY, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            minHeight = dp(42)
            minWidth = dp(66)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(SURFACE_2, 10f, LINE)
            isClickable = true
            isFocusable = true
        }

    fun Activity.row(
        title: String,
        subtitle: String,
        leading: String,
        trailing: String = "›",
        interactive: Boolean = true,
        emphasis: Boolean = false,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(70)
        setPadding(if (emphasis) dp(12) else dp(2), dp(9), if (emphasis) dp(10) else dp(2), dp(9))
        background = if (emphasis) rounded(SURFACE, 14f, LINE) else ColorDrawable(Color.TRANSPARENT)
        if (leading.isNotBlank()) {
            addView(text(leading, 11f, SKY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = .025f
                background = rounded(SURFACE_2, 11f, LINE)
                minWidth = dp(38)
                minHeight = dp(38)
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        }
        addView(LinearLayout(this@row).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, TEXT, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(text(subtitle, 12f, MUTED).apply {
                setPadding(0, dp(4), 0, 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(1).toFloat(), 1f)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing.isNotBlank()) {
            addView(text(trailing, 12f, if (emphasis) SKY else MUTED, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        isClickable = interactive
        isFocusable = interactive
        if (interactive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ValueAnimator.areAnimatorsEnabled()) {
            val target = this
            stateListAnimator = StateListAnimator().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ObjectAnimator.ofFloat(target, "alpha", 1f, .76f).setDuration(90))
                addState(intArrayOf(), ObjectAnimator.ofFloat(target, "alpha", .76f, 1f).setDuration(130))
            }
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
        letterSpacing = .12f
        setPadding(0, dp(3), 0, dp(7))
    }

    fun Activity.note(value: String, accent: Int = MUTED): TextView = text(value, 13f, accent).apply {
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setLineSpacing(dp(3).toFloat(), 1f)
        background = rounded(SURFACE_2, 12f, LINE)
    }

    fun Activity.rounded(fill: Int, radius: Float, stroke: Int = fill): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Activity.contentGutter(): Int =
        dp((resources.displayMetrics.widthPixels / resources.displayMetrics.density * .055f)
            .roundToInt()
            .coerceIn(18, 24))

    fun Activity.present(content: LinearLayout) {
        val navigation = bottomNavigation()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(BG)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (navigation == null) {
            ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
                val bottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
                ).bottom
                view.setPadding(0, 0, 0, bottom)
                insets
            }
            ViewCompat.requestApplyInsets(scroll)
            setContentView(scroll)
            return
        }
        val navFrame = FrameLayout(this)
        val shell = FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(navFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74), Gravity.BOTTOM))
            navFrame.addView(navigation, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(7)
            })
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                val navHeight = dp(74) + bars.bottom
                navFrame.layoutParams = (navFrame.layoutParams as FrameLayout.LayoutParams).apply { height = navHeight }
                (navigation.layoutParams as FrameLayout.LayoutParams).apply {
                    bottomMargin = bars.bottom + dp(7)
                    navigation.layoutParams = this
                }
                scroll.setPadding(0, 0, 0, navHeight)
                insets
            }
            ViewCompat.requestApplyInsets(this)
        }
        setContentView(shell)
    }

    private fun Activity.bottomNavigation(): LinearLayout? {
        val current = when (this) {
            is MainActivity -> 0
            is RoutesActivity -> 1
            is ProfileActivity -> 2
            is SettingsActivity -> 3
            else -> return null
        }
        val destinations = listOf(
            Triple("Главная", MainActivity::class.java, NavGlyph.HOME),
            Triple("Маршруты", RoutesActivity::class.java, NavGlyph.MAP),
            Triple("Профиль", ProfileActivity::class.java, NavGlyph.PROFILE),
            Triple("Настройки", SettingsActivity::class.java, NavGlyph.SETTINGS),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = rounded(SURFACE_2, 19f, LINE)
            elevation = dp(8).toFloat()
            contentDescription = "Основная навигация"
            destinations.forEachIndexed { index, (label, destination, glyph) ->
                val selected = index == current
                val item = LinearLayout(this@bottomNavigation).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(56)
                    isClickable = !selected
                    isFocusable = true
                    contentDescription = if (selected) "$label, выбран" else label
                    if (!selected) setOnClickListener {
                        startActivity(Intent(this@bottomNavigation, destination).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }
                    addView(NavGlyphView(this@bottomNavigation, glyph, if (selected) SKY else MUTED),
                        LinearLayout.LayoutParams(dp(19), dp(19)))
                    addView(text(label, 10f, if (selected) TEXT else MUTED, if (selected) Typeface.BOLD else Typeface.NORMAL).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(4), 0, 0)
                    })
                    addView(View(this@bottomNavigation).apply {
                        background = rounded(if (selected) BLUE else Color.TRANSPARENT, 2f, Color.TRANSPARENT)
                    }, LinearLayout.LayoutParams(dp(16), dp(2)).apply { topMargin = dp(3) })
                }
                addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        }
    }

    private enum class NavGlyph { HOME, MAP, PROFILE, SETTINGS }

    private class NavGlyphView(context: android.content.Context, private val glyph: NavGlyph, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        private val path = Path()
        private val density = resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = density
            val cx = width / 2f
            val cy = height / 2f
            when (glyph) {
                NavGlyph.HOME -> {
                    path.reset()
                    path.moveTo(cx - 7 * s, cy - .5f * s)
                    path.lineTo(cx, cy - 7 * s)
                    path.lineTo(cx + 7 * s, cy - .5f * s)
                    path.lineTo(cx + 5.5f * s, cy - .5f * s)
                    path.lineTo(cx + 5.5f * s, cy + 6 * s)
                    path.lineTo(cx + 1.5f * s, cy + 6 * s)
                    path.lineTo(cx + 1.5f * s, cy + 2 * s)
                    path.lineTo(cx - 1.5f * s, cy + 2 * s)
                    path.lineTo(cx - 1.5f * s, cy + 6 * s)
                    path.lineTo(cx - 5.5f * s, cy + 6 * s)
                    path.lineTo(cx - 5.5f * s, cy - .5f * s)
                    canvas.drawPath(path, paint)
                }
                NavGlyph.MAP -> {
                    path.reset()
                    path.moveTo(cx, cy + 7 * s)
                    path.cubicTo(cx - 2 * s, cy + 3 * s, cx - 6.5f * s, cy - 1 * s, cx - 6.5f * s, cy - 4 * s)
                    path.cubicTo(cx - 6.5f * s, cy - 11 * s, cx + 6.5f * s, cy - 11 * s, cx + 6.5f * s, cy - 4 * s)
                    path.cubicTo(cx + 6.5f * s, cy - 1 * s, cx + 2 * s, cy + 3 * s, cx, cy + 7 * s)
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawCircle(cx, cy - 4 * s, 1.8f * s, paint)
                }
                NavGlyph.PROFILE -> {
                    canvas.drawCircle(cx, cy - 3.5f * s, 3.1f * s, paint)
                    path.reset()
                    path.moveTo(cx - 7 * s, cy + 7 * s)
                    path.cubicTo(cx - 6 * s, cy + .5f * s, cx + 6 * s, cy + .5f * s, cx + 7 * s, cy + 7 * s)
                    canvas.drawPath(path, paint)
                }
                NavGlyph.SETTINGS -> {
                    canvas.drawCircle(cx, cy, 4 * s, paint)
                    for (index in 0 until 8) {
                        val angle = Math.toRadians(index * 45.0)
                        val inner = 5.5f * s
                        val outer = 7.5f * s
                        canvas.drawLine(
                            cx + kotlin.math.cos(angle).toFloat() * inner,
                            cy + kotlin.math.sin(angle).toFloat() * inner,
                            cx + kotlin.math.cos(angle).toFloat() * outer,
                            cy + kotlin.math.sin(angle).toFloat() * outer,
                            paint,
                        )
                    }
                }
            }
        }
    }
}
