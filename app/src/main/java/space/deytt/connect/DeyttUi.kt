package space.deytt.connect

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.VelocityTracker
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

object DeyttUi {
    const val BG = 0xFF080B12.toInt()
    const val SURFACE = 0xFF111823.toInt()
    const val SURFACE_2 = 0xFF192331.toInt()
    const val LINE = 0xFF283548.toInt()
    const val TEXT = 0xFFF2F5FC.toInt()
    const val MUTED = 0xFF9AA8BC.toInt()
    const val BLUE = 0xFF8494FF.toInt()
    const val BLUE_DEEP = 0xFF5D6CF0.toInt()
    const val SKY = 0xFF6BDDF2.toInt()
    const val MINT = 0xFF63E0B4.toInt()
    const val CORAL = 0xFFFF8295.toInt()
    const val AMBER = 0xFFFFC76E.toInt()
    const val MAP_SURFACE = 0xFF0C1420.toInt()
    const val MAP_LINE = 0xFF27384A.toInt()
    const val SELECTED = 0xFF1C2940.toInt()
    const val SELECTED_LINE = 0xFF435A87.toInt()
    const val BLUE_SURFACE = 0xFF242C4A.toInt()
    const val SKY_SURFACE = 0xFF1B303D.toInt()
    const val MINT_SURFACE = 0xFF18342E.toInt()

    private enum class FontFamily(val asset: String, val fallback: String) {
        INTER_TIGHT("fonts/inter-tight-variable.ttf", "sans-serif"),
        UNBOUNDED("fonts/unbounded-variable.ttf", "sans-serif-black"),
        JETBRAINS_MONO("fonts/jetbrains-mono-variable.ttf", "sans-serif-monospace"),
    }

    private val typefaceCache = mutableMapOf<String, Typeface>()

    fun Activity.screen(withBackdrop: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val gutter = contentGutter()
        setPadding(gutter, 0, gutter, dp(22))
        background = if (withBackdrop) NetworkAtmosphereDrawable(this@screen) else ColorDrawable(BG)
        fitsSystemWindows = false
        clipToPadding = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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
            addView(text(title, 24f, TEXT, Typeface.BOLD).apply {
                letterSpacing = -.035f
                typeface = typeface(FontFamily.INTER_TIGHT, 760)
                setPadding(0, dp(5), 0, dp(7))
            })
        }

    fun Activity.brandHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text("deytt.", 21f, TEXT, Typeface.BOLD).apply {
            letterSpacing = -.035f
            typeface = typeface(FontFamily.UNBOUNDED, 700)
            contentDescription = "deytt."
        })
        addView(Space(this@brandHeader), LinearLayout.LayoutParams(0, 1, 1f))
        addView(mono("PRIVATE  ·  ON DEVICE", 8f, MUTED, 600))
    }

    fun Activity.text(value: String, size: Float, color: Int = TEXT, style: Int = Typeface.NORMAL): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = typeface(FontFamily.INTER_TIGHT, if (style == Typeface.BOLD) 720 else 470)
            includeFontPadding = false
        }

    fun Activity.mono(value: String, size: Float, color: Int = MUTED, weight: Int = 560): TextView =
        text(value, size, color).apply { typeface = typeface(FontFamily.JETBRAINS_MONO, weight) }

    private fun Activity.typeface(family: FontFamily, weight: Int): Typeface =
        typefaceCache.getOrPut("${family.name}-$weight") {
            val fallbackStyle = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    Typeface.Builder(assets, family.asset)
                        .setWeight(weight)
                        .setFontVariationSettings("'wght' $weight")
                        .build()
                }.getOrElse { Typeface.create(family.fallback, fallbackStyle) }
            } else Typeface.create(family.fallback, fallbackStyle)
        }

    fun Activity.mapPanel(map: RouteGlobeView): FrameLayout = FrameLayout(this).apply {
        background = rounded(MAP_SURFACE, 24f, MAP_LINE)
        clipToOutline = true
        addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(mono("СЕТЬ  /  04 УЗЛА", 8f, 0xFFB3C5E2.toInt(), 600).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xD9111823.toInt(), 8f, 0xFF35475F.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(27), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(12)
            topMargin = dp(12)
        })
        addView(mono("EUROPE  ·  PRIVATE", 7.5f, 0xFF90A2BC.toInt(), 560).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xB90A101A.toInt(), 8f, 0xFF2A394C.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(27), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(12)
            bottomMargin = dp(10)
        })
    }

    fun Activity.button(label: String, secondary: Boolean = false): TextView =
        text(label, 15f, if (secondary) TEXT else Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            minHeight = dp(54)
            setPadding(dp(18), dp(13), dp(18), dp(13))
            background = if (secondary) rounded(SURFACE_2, 17f, LINE) else GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(BLUE, BLUE_DEEP),
            ).apply { cornerRadius = dp(17).toFloat() }
            elevation = if (secondary) 0f else dp(5).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = label
            foreground = ripple(17f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ValueAnimator.areAnimatorsEnabled()) {
                val target = this
                stateListAnimator = StateListAnimator().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), scaleAnimator(target, .98f, 100))
                    addState(intArrayOf(), scaleAnimator(target, 1f, 130))
                }
            }
        }

    fun Activity.actionLabel(label: String = "пинг"): TextView =
        mono(label, 8.5f, SKY, 650).apply {
            gravity = Gravity.CENTER
            minHeight = dp(38)
            minWidth = dp(54)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = rounded(0xFF18243A.toInt(), 11f, 0xFF344B71.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "Проверить задержку"
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
        minimumHeight = dp(64)
        setPadding(if (emphasis) dp(12) else dp(2), dp(9), if (emphasis) dp(10) else dp(2), dp(9))
        background = if (emphasis) rounded(SELECTED, 15f, SELECTED_LINE) else ColorDrawable(Color.TRANSPARENT)
        if (leading.isNotBlank()) {
            addView(text(leading, 11f, SKY, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                letterSpacing = .025f
                background = rounded(if (emphasis) 0xFF253654.toInt() else SURFACE_2, 12f, if (emphasis) 0xFF3F5985.toInt() else LINE)
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
        if (interactive) foreground = ripple(if (emphasis) 15f else 12f)
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

    fun Activity.sectionLabel(value: String): TextView = mono(value.uppercase(), 9f, MUTED, 560).apply {
        setPadding(0, dp(3), 0, dp(7))
    }

    fun Activity.note(value: String, accent: Int = MUTED): TextView = text(value, 13f, accent).apply {
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setLineSpacing(dp(3).toFloat(), 1f)
        background = rounded(SURFACE_2, 14f, LINE)
    }

    fun Activity.rounded(fill: Int, radius: Float, stroke: Int = fill): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun Activity.ripple(radius: Float): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(0x3B8EA2FF),
        null,
        rounded(Color.WHITE, radius, Color.WHITE),
    )

    fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Activity.contentGutter(): Int =
        dp((resources.displayMetrics.widthPixels / resources.displayMetrics.density * .055f)
            .roundToInt()
            .coerceIn(18, 24))

    fun Activity.present(content: LinearLayout, anchoredAction: View? = null) {
        val navigation = bottomNavigation()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (navigation == null) {
            if (anchoredAction != null) {
                val actionHeight = dp(78)
                val actionFrame = FrameLayout(this).apply {
                    setBackgroundColor(SURFACE)
                    addView(anchoredAction, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.CENTER).apply {
                        leftMargin = contentGutter()
                        rightMargin = contentGutter()
                    })
                }
                val shell = FrameLayout(this).apply {
                    setBackgroundColor(BG)
                    addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    addView(actionFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actionHeight, Gravity.BOTTOM))
                    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                        val systemBars = insets.getInsets(
                            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
                        )
                        val bottomBars = insets.getInsets(
                            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
                        ).bottom
                        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        val bottom = maxOf(bottomBars, ime)
                        (actionFrame.layoutParams as FrameLayout.LayoutParams).apply {
                            bottomMargin = bottom
                            actionFrame.layoutParams = this
                        }
                        scroll.setPadding(0, dp(12) + systemBars.top, 0, bottom + actionHeight + dp(16))
                        insets
                    }
                    ViewCompat.requestApplyInsets(this)
                }
                setContentView(shell)
                animateContentIn(content)
                return
            }
            ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
                val top = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
                ).top
                val bottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
                ).bottom
                view.setPadding(0, dp(12) + top, 0, bottom + dp(16))
                insets
            }
            ViewCompat.requestApplyInsets(scroll)
            setContentView(scroll)
            animateContentIn(content)
            return
        }
        val navFrame = FrameLayout(this)
        val actionHeight = if (anchoredAction == null) 0 else dp(78)
        val actionFrame = anchoredAction?.let { action ->
            FrameLayout(this).apply {
                setBackgroundColor(SURFACE)
                addView(action, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.CENTER).apply {
                    leftMargin = contentGutter()
                    rightMargin = contentGutter()
                })
            }
        }
        val shell = PageSwipeFrame(this, scroll, topLevelIndex()) { delta ->
            navigateTopLevel(topLevelIndex() + delta)
        }.apply {
            setBackgroundColor(BG)
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            navFrame.setBackgroundColor(SURFACE)
            navFrame.elevation = dp(12).toFloat()
            navFrame.tag = PAGE_SWIPE_BLOCK_TAG
            addView(navFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66), Gravity.BOTTOM))
            navFrame.addView(navigation, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66), Gravity.BOTTOM))
            actionFrame?.let {
                it.tag = PAGE_SWIPE_BLOCK_TAG
                addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actionHeight, Gravity.BOTTOM))
            }
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                val statusBar = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
                ).top
                val navHeight = dp(66) + bars.bottom
                navFrame.layoutParams = (navFrame.layoutParams as FrameLayout.LayoutParams).apply { height = navHeight }
                (navigation.layoutParams as FrameLayout.LayoutParams).apply {
                    bottomMargin = bars.bottom
                    navigation.layoutParams = this
                }
                actionFrame?.let { frame ->
                    (frame.layoutParams as FrameLayout.LayoutParams).apply {
                        bottomMargin = navHeight
                        frame.layoutParams = this
                    }
                }
                scroll.setPadding(0, dp(12) + statusBar, 0, navHeight + actionHeight + dp(16))
                insets
            }
            ViewCompat.requestApplyInsets(this)
        }
        setContentView(shell)
        animateContentIn(content)
    }

    private fun Activity.animateContentIn(content: View) {
        if (this is MainActivity || this is RoutesActivity || this is ProfileActivity || this is SettingsActivity) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) return
        content.alpha = 0f
        content.translationY = dp(8).toFloat()
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .setInterpolator(PathInterpolator(.22f, 1f, .36f, 1f))
            .start()
    }

    private fun Activity.bottomNavigation(): LinearLayout? {
        val current = topLevelIndex()
        if (current < 0) return null
        val destinations = listOf(
            Triple("Главная", MainActivity::class.java, NavGlyph.HOME),
            Triple("Маршруты", RoutesActivity::class.java, NavGlyph.MAP),
            Triple("Профиль", ProfileActivity::class.java, NavGlyph.PROFILE),
            Triple("Настройки", SettingsActivity::class.java, NavGlyph.SETTINGS),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
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
                    if (!selected) {
                        setOnClickListener { navigateTopLevel(index) }
                        foreground = ripple(17f)
                    }
                    val icon = FrameLayout(this@bottomNavigation).apply {
                        background = rounded(
                            if (selected) 0xFF222F49.toInt() else Color.TRANSPARENT,
                            14f,
                            if (selected) 0xFF344867.toInt() else Color.TRANSPARENT,
                        )
                        addView(NavGlyphView(this@bottomNavigation, glyph, if (selected) SKY else MUTED),
                            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    }
                    addView(icon, LinearLayout.LayoutParams(dp(38), dp(29)))
                    val motionEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
                    if (selected && motionEnabled) {
                        icon.alpha = .72f
                        icon.scaleX = .88f
                        icon.scaleY = .88f
                        icon.post {
                            icon.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setStartDelay(45L)
                                .setDuration(240L)
                                .setInterpolator(PathInterpolator(.22f, 1f, .36f, 1f))
                                .start()
                        }
                    }
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

private fun Activity.topLevelIndex(): Int = when (this) {
    is MainActivity -> 0
    is RoutesActivity -> 1
    is ProfileActivity -> 2
    is SettingsActivity -> 3
    else -> -1
}

private fun Activity.navigateTopLevel(index: Int) {
    val current = topLevelIndex()
    val destination = when (index) {
        0 -> MainActivity::class.java
        1 -> RoutesActivity::class.java
        2 -> ProfileActivity::class.java
        3 -> SettingsActivity::class.java
        else -> return
    }
    if (index == current) return
    startActivity(Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
        overridePendingTransition(0, 0)
    } else if (index > current) {
        overridePendingTransition(R.anim.page_enter_from_right, R.anim.page_exit_to_left)
    } else {
        overridePendingTransition(R.anim.page_enter_from_left, R.anim.page_exit_to_right)
    }
}

private const val PAGE_SWIPE_BLOCK_TAG = "deytt-page-swipe-block"

private class PageSwipeFrame(
    context: Context,
    private val swipeContent: View,
    private val currentPage: Int,
    private val onNavigate: (Int) -> Unit,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeWidth = 28f * resources.displayMetrics.density
    private val density = resources.displayMetrics.density
    private var velocityTracker: VelocityTracker? = null
    private var settleAnimator: ValueAnimator? = null
    private var startX = 0f
    private var startY = 0f
    private var dragOriginX = 0f
    private var candidate = false
    private var intercepted = false
    private var skipNextTouchMove = false

    init {
        isClickable = true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && !intercepted) resetSwipeOffset()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && !intercepted) resetSwipeOffset()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleAnimator?.cancel()
                settleAnimator = null
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                startX = event.x
                startY = event.y
                dragOriginX = swipeContent.translationX
                candidate = event.x > edgeWidth && event.x < width - edgeWidth && !blocksSwipeAt(event.x, event.y)
                intercepted = false
                skipNextTouchMove = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (candidate) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f) {
                        intercepted = true
                        skipNextTouchMove = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (kotlin.math.abs(dy) > touchSlop) candidate = false
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> candidate = false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishTracking()
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepted) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (skipNextTouchMove) {
                    skipNextTouchMove = false
                } else {
                    velocityTracker?.addMovement(event)
                }
                val dx = event.x - startX
                val step = if (dx < 0f) 1 else -1
                val targetExists = currentPage + step in 0..3
                val resistance = if (targetExists) 1f else 0.18f
                val limit = width * 0.88f
                swipeContent.translationX = dragOriginX + (dx * resistance).coerceIn(-limit, limit)
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val dx = event.x - startX
                val direction = if (dx < 0f) 1 else -1
                val targetExists = currentPage + direction in 0..3
                val distance = kotlin.math.abs(dx)
                val velocityX = velocityTracker?.xVelocity ?: 0f
                val threshold = maxOf(24f * density, width * 0.06f)
                val flingDistance = maxOf(18f * density, touchSlop * 1.5f)
                val fling = distance >= flingDistance &&
                    kotlin.math.sign(velocityX) == kotlin.math.sign(dx) &&
                    kotlin.math.abs(velocityX) >= 650f * density
                if (targetExists && (distance >= threshold || fling)) {
                    finishTracking()
                    onNavigate(direction)
                } else {
                    finishTracking()
                    settleContentBack()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                finishTracking()
                settleContentBack()
                return true
            }
            else -> return true
        }
    }

    private fun settleContentBack() {
        val start = swipeContent.translationX
        if (start == 0f) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            swipeContent.translationX = 0f
            return
        }
        settleAnimator = ValueAnimator.ofFloat(start, 0f).apply {
            duration = 150L
            interpolator = PathInterpolator(.22f, 1f, .36f, 1f)
            addUpdateListener { swipeContent.translationX = it.animatedValue as Float }
            start()
        }
    }

    private fun resetSwipeOffset() {
        settleAnimator?.cancel()
        settleAnimator = null
        swipeContent.translationX = 0f
    }

    private fun finishTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
        candidate = false
        intercepted = false
        skipNextTouchMove = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun blocksSwipeAt(x: Float, y: Float): Boolean = blocksSwipeIn(this, x, y, inspectSelf = false)

    private fun blocksSwipeIn(view: View, x: Float, y: Float, inspectSelf: Boolean = true): Boolean {
        if (x < 0f || y < 0f || x >= view.width || y >= view.height || view.visibility != View.VISIBLE) return false
        if (inspectSelf && (view.tag == PAGE_SWIPE_BLOCK_TAG || view is WebView)) return true
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                val scrollOffset = if (view is ScrollView) view.scrollY else 0
                if (blocksSwipeIn(child, x - child.left, y - child.top + scrollOffset)) return true
            }
        }
        return false
    }
}

private class NetworkAtmosphereDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        canvas.drawColor(DeyttUi.BG)
        val area = bounds
        if (area.isEmpty) return
        val glowRadius = area.width() * .92f
        paint.shader = android.graphics.RadialGradient(
            area.left + area.width() * .82f,
            area.top + area.height() * .12f,
            glowRadius,
            intArrayOf(0x252B43A5, 0x10214270, 0x00080B12),
            floatArrayOf(0f, .48f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        paint.alpha = drawableAlpha
        canvas.drawRect(area, paint)
        paint.shader = android.graphics.RadialGradient(
            area.left + area.width() * .04f,
            area.top + area.height() * .68f,
            area.width() * .76f,
            intArrayOf(0x142B8A82, 0x00080B12),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(area, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
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
