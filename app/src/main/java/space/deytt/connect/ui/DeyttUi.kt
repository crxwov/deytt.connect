package space.deytt.connect

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.VelocityTracker
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.webkit.WebView
import space.deytt.connect.AppLanguage.uiCopy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
import kotlin.math.sin

object DeyttUi {
    const val BG = 0xFF000000.toInt()
    const val MAP_SURFACE = 0xFF080808.toInt()
    const val SURFACE = 0xFF101010.toInt()
    const val SHEET_SURFACE = 0xFF171717.toInt()
    const val SURFACE_2 = 0xFF171717.toInt()
    const val SELECTED = 0xFF202020.toInt()
    const val LINE = 0xFF2A2A2A.toInt()
    const val MAP_LINE = LINE
    const val ROW_FILL = 0xAC191919.toInt()
    const val CARD_FILL = 0xBB171717.toInt()
    const val CARD_SELECTED_FILL = 0xD0202020.toInt()
    const val SELECTED_LINE = 0xFF6D6D6D.toInt()
    const val TEXT = 0xFFF5F5F5.toInt()
    const val MUTED = 0xFFA0A0A0.toInt()
    const val ACTIVE = 0xFFE5E7E9.toInt()
    const val SKY = 0xFF8DBDC5.toInt()
    const val MINT = 0xFF91D4BC.toInt()
    const val CORAL = 0xFFFF9AA5.toInt()
    const val AMBER = 0xFFDEBD83.toInt()

    const val CONTROL_RADIUS = 16f
    const val PANEL_RADIUS = 20f
    const val CONTROL_HEIGHT = 48
    const val ICON_SIZE = 20

    internal enum class FontFamily(val asset: String, val fallback: String) {
        INTER_TIGHT("fonts/inter-tight-variable.ttf", "sans-serif"),
        UNBOUNDED("fonts/unbounded-variable.ttf", "sans-serif-black"),
        JETBRAINS_MONO("fonts/jetbrains-mono-variable.ttf", "sans-serif-monospace"),
    }

    private val typefaceCache = mutableMapOf<String, Typeface>()
    private val pressInterpolator = PathInterpolator(.23f, 1f, .32f, 1f)

    internal fun Activity.typeface(family: FontFamily, weight: Int): Typeface =
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

    fun Activity.prefersReducedMotion(): Boolean =
        (this as? MainActivity)?.isReducedMotionEnabled()
            ?: getSharedPreferences("profile_settings", Context.MODE_PRIVATE).getBoolean("reduced_motion", false)

    fun Activity.screen(withBackdrop: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val gutter = contentGutter()
        setPadding(gutter, 0, gutter, dp(22))
        background = if (withBackdrop) ColorDrawable(Color.TRANSPARENT) else ColorDrawable(BG)
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
                    contentDescription = uiCopy("Назад")
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
                addView(text(uiCopy(kicker), 11f, MUTED, Typeface.BOLD).apply {
                    letterSpacing = .13f
                    setPadding(0, dp(5), 0, 0)
                })
            }
            addView(text(uiCopy(title), 26f, TEXT).apply {
                letterSpacing = -.035f
                typeface = typeface(FontFamily.UNBOUNDED, 700)
                setPadding(0, dp(5), 0, dp(7))
            })
        }

    fun Activity.brandHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        foreground = ripple(24f)
        addView(displayText("./connect", 18f).apply {
            tag = "deytt-wordmark"
            gravity = Gravity.CENTER_VERTICAL
            text = android.text.SpannableString("./connect").apply {
                setSpan(android.text.style.ForegroundColorSpan(SKY), 0, 2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            letterSpacing = -.035f
            contentDescription = uiCopy("./connect")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(Space(this@brandHeader), LinearLayout.LayoutParams(0, 1, 1f))
        val profileControl = FrameLayout(this@brandHeader).apply {
            tag = "telegram-profile-control"
            background = ColorDrawable(Color.TRANSPARENT)
            foreground = ripple(24f)
            contentDescription = uiCopy("Профиль")
        }
        val avatar = FrameLayout(this@brandHeader).apply {
            tag = "telegram-avatar-container"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CARD_FILL)
                setStroke(dp(1), LINE)
            }
            clipToOutline = true
            contentDescription = uiCopy("Аватар Telegram")
        }
        avatar.addView(text("•", 18f, MUTED, Typeface.BOLD).apply {
            tag = "telegram-avatar-fallback"
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        avatar.addView(ImageView(this@brandHeader).apply {
            tag = "telegram-avatar-image"
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            clipToOutline = true
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        profileControl.addView(avatar, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        addView(profileControl, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(text("", 14f, TEXT).apply {
            tag = "telegram-account-name"
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, 0))
        pressFeedback(this, .995f)
    }

    fun starfieldBackground(
        context: Context,
        referenceHeightPx: Int? = null,
        allowMotion: Boolean = true,
    ): Drawable = NetworkAtmosphereDrawable(context, referenceHeightPx?.takeIf { it > 0 }, allowMotion)

    fun Activity.text(value: String, size: Float, color: Int = TEXT, style: Int = Typeface.NORMAL): TextView =
        TextView(this).apply {
            text = uiCopy(value)
            textSize = size
            setTextColor(color)
            typeface = typeface(FontFamily.INTER_TIGHT, if (style == Typeface.BOLD) 560 else 400)
            includeFontPadding = false
        }

    fun Activity.displayText(value: String, size: Float = 22f, color: Int = TEXT): TextView =
        text(value, size, color, Typeface.BOLD).apply {
            typeface = typeface(FontFamily.UNBOUNDED, 650)
            letterSpacing = -.025f
        }

    fun Activity.mono(value: String, size: Float, color: Int = MUTED, weight: Int = 560): TextView =
        text(value, size, color).apply { typeface = typeface(FontFamily.JETBRAINS_MONO, weight) }

    fun Activity.mapPanel(map: RouteGlobeView): FrameLayout = FrameLayout(this).apply {
        background = rounded(MAP_SURFACE, 24f, MAP_LINE)
        clipToOutline = true
        addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun Activity.button(label: String, secondary: Boolean = false): TextView =
        text(label, 15f, if (secondary) TEXT else SURFACE, Typeface.BOLD).apply {
            typeface = typeface(FontFamily.INTER_TIGHT, 620)
            gravity = Gravity.CENTER
            minHeight = dp(CONTROL_HEIGHT)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = buttonSurface(secondary)
            isClickable = true
            isFocusable = true
            contentDescription = uiCopy(label)
            foreground = ripple(CONTROL_RADIUS, onLight = !secondary)
            pressFeedback(this, .985f)
        }

    fun Activity.buttonSurface(secondary: Boolean, radius: Float = CONTROL_RADIUS): Drawable =
        if (secondary) rounded(SURFACE_2, radius, LINE) else rounded(ACTIVE, radius)

    fun Activity.fieldSurface(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(SURFACE, CONTROL_RADIUS, ACTIVE))
        addState(intArrayOf(), rounded(SURFACE, CONTROL_RADIUS, SELECTED_LINE))
    }

    fun Activity.connectionSurface(phase: VpnPhase): Drawable = when (phase) {
        VpnPhase.IDLE -> buttonSurface(false)
        VpnPhase.CONNECTED -> rounded(SURFACE_2, CONTROL_RADIUS, MINT)
        VpnPhase.ERROR -> rounded(SURFACE_2, CONTROL_RADIUS, CORAL)
        else -> rounded(SELECTED, CONTROL_RADIUS, LINE)
    }

    fun Activity.actionLabel(label: String = "пинг"): TextView =
        mono(label, 8.5f, SKY, 650).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(54)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = rowSurface(false, 12f)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = uiCopy("Проверить задержку")
            foreground = ripple(12f)
            pressFeedback(this, .99f)
        }

    fun Activity.row(
        title: String,
        subtitle: String,
        leading: String,
        trailing: String = "›",
        interactive: Boolean = true,
        emphasis: Boolean = false,
        avatar: Bitmap? = null,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = if (interactive) rowSurface(emphasis, CONTROL_RADIUS) else ColorDrawable(Color.TRANSPARENT)
        if (leading.isNotBlank()) {
            val icon = if (avatar != null) {
                ImageView(this@row).apply {
                    setImageBitmap(avatar)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rowIconBacking(emphasis)
                    clipToOutline = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            } else when (leading) {
                "AWG_MARK" -> awgMark(emphasis)
                "AWG31_MARK" -> awgMark(emphasis, version31 = true)
                "ROUTE_RU_DE" -> routePairMark(emphasis)
                "SUBSCRIPTION_ICON" -> actionIcon(R.drawable.ic_cloud_download, emphasis)
                "ROUTE_AUTO" -> actionIcon(R.drawable.ic_route, emphasis)
                "PAYMENTS_ICON" -> actionIcon(R.drawable.ic_credit_card, emphasis)
                "LANGUAGE_ICON" -> actionIcon(R.drawable.ic_languages, emphasis)
                "TELEGRAM_ICON" -> actionIcon(R.drawable.ic_send, emphasis)
                "SPLIT_ICON" -> actionIcon(R.drawable.ic_split, emphasis)
                "ROUTE_CHECK_ICON", "DOC_ICON" -> actionIcon(R.drawable.ic_activity, emphasis)
                "VPN_PERMISSION_ICON", "SHIELD_ICON" -> actionIcon(R.drawable.ic_shield_check, emphasis)
                "BOT_ICON" -> actionIcon(R.drawable.ic_bot, emphasis)
                "ACCOUNT_ICON" -> actionIcon(R.drawable.ic_user_round, emphasis)
                "DEVICE_DESKTOP" -> actionIcon(R.drawable.ic_monitor, emphasis)
                "DEVICE_PHONE" -> actionIcon(R.drawable.ic_smartphone, emphasis)
                "↻" -> actionIcon(R.drawable.ic_sync, emphasis)
                else -> {
                val isCountryFlag = leading.any { it.code in 0xD800..0xDBFF }
                text(leading, if (isCountryFlag) 20f else 11f, if (isCountryFlag) TEXT else TEXT,
                    if (isCountryFlag) Typeface.NORMAL else Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    letterSpacing = .025f
                    background = if (isCountryFlag) ColorDrawable(Color.TRANSPARENT)
                        else rowIconBacking(emphasis)
                    minWidth = dp(38)
                    minHeight = dp(38)
                    maxLines = 1
                }
                }
            }
            addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
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
            if (trailing == "›" || trailing == "⌄" || trailing == "⌃") {
                val chevron = ImageView(this@row).apply {
                    setImageResource(R.drawable.ic_chevron)
                    imageTintList = ColorStateList.valueOf(if (emphasis) TEXT else MUTED)
                    rotation = when (trailing) { "⌄" -> 90f; "⌃" -> 270f; else -> 0f }
                    tag = if (trailing == "›") null else "country-chevron"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(8) })
            } else {
                addView(text(trailing, 12f, TEXT, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    minWidth = dp(60)
                    maxWidth = dp(84)
                    minHeight = dp(36)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = buttonSurface(secondary = true, radius = 14f)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                })
            }
        }
        isClickable = interactive
        isFocusable = interactive
        if (interactive) foreground = ripple(CONTROL_RADIUS)
        if (interactive) pressFeedback(this, .99f)
    }

    private fun Activity.actionIcon(resource: Int, emphasis: Boolean) = ImageView(this).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(TEXT)
        background = rowIconBacking(emphasis)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun Activity.rowIconBacking(emphasis: Boolean, radius: Float = 16f): Drawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius.roundToInt()).toFloat()
            setColor(if (emphasis) LINE else SURFACE_2)
        }

    internal fun Activity.rowSurface(emphasis: Boolean, radius: Float): Drawable =
        rounded(if (emphasis) CARD_SELECTED_FILL else ROW_FILL, radius, if (emphasis) SELECTED_LINE else ROW_FILL)

    fun Activity.cardSurface(emphasis: Boolean = false, radius: Float = PANEL_RADIUS): Drawable =
        rounded(if (emphasis) CARD_SELECTED_FILL else CARD_FILL, radius, LINE)

    private fun Activity.awgMark(emphasis: Boolean, version31: Boolean = false): FrameLayout {
        val context = this
        return FrameLayout(context).apply {
            background = rowIconBacking(emphasis, 12f)
            contentDescription = uiCopy(if (version31) "AmneziaWG 3.1" else "Значок AmneziaWG 1.5")
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_amneziawg)
                imageTintList = ColorStateList.valueOf(DeyttUi.TEXT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = uiCopy(if (version31) "Знак AmneziaWG 3.1" else "Оригинальный знак AmneziaWG")
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
    }

    internal fun Activity.routePairMark(emphasis: Boolean): FrameLayout {
        val context = this
        return FrameLayout(context).apply {
            background = rounded(if (emphasis) SELECTED else SURFACE_2, 16f,
                if (emphasis) SELECTED_LINE else LINE)
            contentDescription = uiCopy("Маршрут через Россию и Германию")
            addView(text("🇷🇺", 14f, TEXT).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(dp(20), dp(24), Gravity.CENTER_VERTICAL or Gravity.START))
            addView(text("🇩🇪", 14f, TEXT).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(dp(20), dp(24), Gravity.CENTER_VERTICAL or Gravity.END))
        }
    }

    private class AmneziaMarkView(context: Context) : View(context) {
        private val scale = resources.displayMetrics.density
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DeyttUi.SKY
            strokeWidth = 2.1f * scale
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DeyttUi.MINT
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = minOf(width, height) / 24f
            val centerX = width / 2f
            val centerY = height / 2f
            val mark = Path().apply {
                moveTo(centerX - 7f * unit, centerY + 7f * unit)
                lineTo(centerX, centerY - 8f * unit)
                lineTo(centerX + 7f * unit, centerY + 7f * unit)
                moveTo(centerX - 4f * unit, centerY + 1.5f * unit)
                lineTo(centerX + 4f * unit, centerY + 1.5f * unit)
            }
            canvas.drawPath(mark, outline)
            canvas.drawCircle(centerX, centerY + 7f * unit, 1.25f * unit, accent)
        }
    }

    fun Activity.pressFeedback(view: View, pressedScale: Float = .99f) {
        var inside = false
        fun animateScale(target: View, scale: Float, duration: Long) {
            target.animate().cancel()
            val motionEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
            if (motionEnabled && !prefersReducedMotion()) {
                target.animate().scaleX(scale).scaleY(scale)
                    .setDuration(duration).setInterpolator(pressInterpolator).start()
            } else {
                target.scaleX = 1f
                target.scaleY = 1f
            }
        }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inside = true
                    animateScale(target, pressedScale, 95L)
                }
                MotionEvent.ACTION_MOVE -> {
                    val over = event.x >= 0f && event.x < target.width && event.y >= 0f && event.y < target.height
                    if (inside != over) {
                        inside = over
                        animateScale(target, if (over) pressedScale else 1f, if (over) 120L else 140L)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    inside = false
                    animateScale(target, 1f, 140L)
                }
            }
            false
        }
    }

    fun spacer(height: Int, activity: Activity): Space = Space(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, activity.dp(height))
    }

    fun Activity.sectionLabel(value: String): TextView = mono(uiCopy(value), 11f, MUTED, 560).apply {
        setPadding(0, dp(3), 0, dp(7))
    }

    fun Activity.note(value: String, accent: Int = MUTED): TextView = text(value, 13f, accent).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setLineSpacing(dp(3).toFloat(), 1f)
        background = rounded(SURFACE_2, 16f, LINE)
        elevation = dp(1).toFloat()
    }

    fun Activity.rounded(fill: Int, radius: Float, stroke: Int = fill): Drawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
            if (stroke != fill) setStroke(dp(1).coerceAtLeast(1), stroke)
        }

    internal fun Activity.ripple(radius: Float, onLight: Boolean = false): RippleDrawable =
        RippleDrawable(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                if (onLight) intArrayOf(0x51000000, 0x25000000) else intArrayOf(0x52FFFFFF, 0x24FFFFFF),
            ),
            null,
            rounded(Color.WHITE, radius),
        )

    fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Activity.contentGutter(): Int =
        dp((resources.displayMetrics.widthPixels / resources.displayMetrics.density * .055f)
            .roundToInt()
            .coerceIn(18, 24))

    fun setStarfieldMotion(root: View, active: Boolean, reducedMotion: Boolean = false, profileVisible: Boolean = false) {
        val canAnimate = active && !reducedMotion
        if (root is StarryPlanCard) {
            root.setGalaxyMotion(canAnimate && profileVisible)
            return
        }
        (root.background as? NetworkAtmosphereDrawable)?.setMotionEnabled(canAnimate)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                setStarfieldMotion(root.getChildAt(index), active, reducedMotion, profileVisible)
            }
        }
    }

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
            contentDescription = uiCopy("Основная навигация")
            destinations.forEachIndexed { index, (label, destination, glyph) ->
                val selected = index == current
                val item = LinearLayout(this@bottomNavigation).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(56)
                    isClickable = !selected
                    isFocusable = true
                    contentDescription = if (selected) "${uiCopy(label)}, ${uiCopy("выбран")}" else uiCopy(label)
                    if (!selected) {
                        setOnClickListener { navigateTopLevel(index) }
                        foreground = ripple(17f)
                    }
                    val icon = FrameLayout(this@bottomNavigation).apply {
                        background = rounded(
                            if (selected) SELECTED else Color.TRANSPARENT,
                            14f,
                            if (selected) SELECTED_LINE else Color.TRANSPARENT,
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
                        background = rounded(if (selected) ACTIVE else Color.TRANSPARENT, 2f, Color.TRANSPARENT)
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

private class NetworkAtmosphereDrawable(
    context: Context,
    private val referenceHeightPx: Int? = null,
    private val allowMotion: Boolean = true,
) : Drawable() {
    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
        val alpha: Int,
        val phase: Float,
        val period: Float,
        val tint: Int,
    )

    private val density = context.resources.displayMetrics.density
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = Array(128) { index ->
        val random = java.util.Random(0x44D7L + index * 7919L)
        Star(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = (.3f + random.nextFloat().let { it * it } * 1.3f) * density,
            alpha = 32 + random.nextInt(129),
            phase = random.nextFloat() * (Math.PI * 2).toFloat(),
            period = 5.5f + random.nextFloat() * 9f,
            tint = if (index % 4 == 0) DeyttUi.MUTED else DeyttUi.TEXT,
        )
    }
    private var drawableAlpha = 255
    private var motionEnabled = false
    private var colorFilter: ColorFilter? = null
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (motionEnabled) {
                invalidateSelf()
                scheduleSelf(this, SystemClock.uptimeMillis() + 32L)
            }
        }
    }

    fun setMotionEnabled(enabled: Boolean) {
        val systemAllowsMotion = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
        val shouldRun = allowMotion && enabled && systemAllowsMotion
        if (motionEnabled == shouldRun) return
        motionEnabled = shouldRun
        if (shouldRun) scheduleSelf(tick, SystemClock.uptimeMillis() + 32L) else unscheduleSelf(tick)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        // The shared backdrop can have unclipped parents; never paint over sibling controls.
        val saveCount = canvas.save()
        canvas.clipRect(area)
        canvas.drawColor(DeyttUi.BG)
        val seconds = if (motionEnabled) SystemClock.uptimeMillis() / 1000f else 0f
        val atmosphereHeight = (referenceHeightPx ?: area.height()).toFloat()
        stars.forEach { star ->
            val twinkle = if (motionEnabled) .57f + .43f * ((sin(seconds * (6.2831855f / star.period) + star.phase) + 1f) * .5f) else .74f
            val alpha = (star.alpha * twinkle * drawableAlpha / 255f).toInt().coerceIn(0, 255)
            val x = area.left + area.width() * star.x
            val y = area.top + atmosphereHeight * star.y
            starPaint.color = star.tint
            starPaint.alpha = alpha
            starPaint.colorFilter = colorFilter
            canvas.drawCircle(x, y, star.radius, starPaint)
        }
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { this.colorFilter = colorFilter; invalidateSelf() }
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

        fun setColor(color: Int) {
            if (paint.color != color) {
                paint.color = color
                invalidate()
            }
        }

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
