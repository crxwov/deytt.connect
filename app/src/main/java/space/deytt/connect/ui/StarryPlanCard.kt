package space.deytt.connect

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Plan details on the app's native starfield. */
class StarryPlanCard(context: Context) : FrameLayout(context) {

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density

    // --- Typefaces ---
    private val unboundedBold: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                Typeface.Builder(context.assets, "fonts/unbounded-variable.ttf")
                    .setWeight(700)
                    .setFontVariationSettings("'wght' 700")
                    .build()
            }.getOrElse { Typeface.create("sans-serif-black", Typeface.BOLD) }
        } else {
            Typeface.create("sans-serif-black", Typeface.BOLD)
        }
    }

    private val interTightRegular: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                Typeface.Builder(context.assets, "fonts/inter-tight-variable.ttf")
                    .setWeight(400)
                    .setFontVariationSettings("'wght' 400")
                    .build()
            }.getOrElse { Typeface.create("sans-serif", Typeface.NORMAL) }
        } else {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    private val interTightBold: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                Typeface.Builder(context.assets, "fonts/inter-tight-variable.ttf")
                    .setWeight(700)
                    .setFontVariationSettings("'wght' 700")
                    .build()
            }.getOrElse { Typeface.create("sans-serif", Typeface.BOLD) }
        } else {
            Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }

    private val cornerRadiusPx = dp(24).toFloat()
    private val borderRect = RectF()
    private val labelTariffView: TextView
    private val tariffNameView: TextView
    private val infinityGlyphView: InfinityGlyphView
    private val heroTextView: TextView
    private val labelDevicesView: TextView
    private val deviceLimitView: TextView
    private val labelTrafficView: TextView
    private val trafficView: TextView

    init {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        background = DeyttUi.starfieldBackground(context, allowMotion = false)

        val paddingH = dp(20)
        val paddingV = dp(20)
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(paddingH, paddingV, paddingH, paddingV)
        }

        // Top row: "текущий тариф" + "deytt./ultra" on left, "✓ активна" badge on right
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val planLabels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val isEnInitial = AppLanguage.current(context) == AppLanguage.EN
        val labelTariff = TextView(context).apply {
            text = if (isEnInitial) "current plan" else "текущий тариф"
            textSize = 11f
            setTextColor(0xFF8EA2BA.toInt())
            typeface = interTightRegular
            letterSpacing = 0.02f
            setPadding(0, 0, 0, dp(3))
        }
        labelTariffView = labelTariff
        planLabels.addView(labelTariff)

        tariffNameView = TextView(context).apply {
            text = "deytt./connect"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = unboundedBold
            includeFontPadding = false
        }
        planLabels.addView(tariffNameView)
        topRow.addView(planLabels)
        contentLayout.addView(topRow)

        // Center Hero: Large thick Vector Infinity glyph + "бессрочно" or expiry date
        val heroLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(18))
        }

        infinityGlyphView = InfinityGlyphView(context).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(24)).apply {
                marginEnd = dp(14)
            }
        }
        heroLayout.addView(infinityGlyphView)

        heroTextView = TextView(context).apply {
            text = "…"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = unboundedBold
            includeFontPadding = false
        }
        heroLayout.addView(heroTextView)
        contentLayout.addView(heroLayout)

        // Hairline subtle divider
        val divider = View(context).apply {
            setBackgroundColor(0x1FFFFFFF)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(16)
            }
        }
        contentLayout.addView(divider)

        // Bottom 2-column info grid: devices & traffic
        val bottomGrid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val deviceColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val lbl = TextView(context).apply {
                text = if (isEnInitial) "devices included" else "устройств в тарифе"
                textSize = 11f
                setTextColor(0xFF8EA2BA.toInt())
                typeface = interTightRegular
            }
            labelDevicesView = lbl
            addView(lbl)
            deviceLimitView = TextView(context).apply {
                text = "10"
                textSize = 22f
                setTextColor(Color.WHITE)
                typeface = unboundedBold
                includeFontPadding = false
                setPadding(0, dp(4), 0, 0)
            }
            addView(deviceLimitView)
        }
        bottomGrid.addView(deviceColumn)

        val trafficColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f)
            val lbl = TextView(context).apply {
                text = if (isEnInitial) "traffic" else "трафик"
                textSize = 11f
                setTextColor(0xFF8EA2BA.toInt())
                typeface = interTightRegular
            }
            labelTrafficView = lbl
            addView(lbl)
            trafficView = TextView(context).apply {
                text = "…"
                textSize = 22f
                setTextColor(Color.WHITE)
                typeface = unboundedBold
                includeFontPadding = false
                setPadding(0, dp(4), 0, 0)
            }
            addView(trafficView)
        }
        bottomGrid.addView(trafficColumn)
        contentLayout.addView(bottomGrid)

        addView(contentLayout)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val halfStroke = borderPaint.strokeWidth / 2f
        borderRect.set(halfStroke, halfStroke, w.toFloat() - halfStroke, h.toFloat() - halfStroke)
        if (w > 0 && h > 0) {
            borderPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0x458CCEFA, 0x14446A88, 0x3083B6E0), null, Shader.TileMode.CLAMP,
            )
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        canvas.drawRoundRect(borderRect, cornerRadiusPx, cornerRadiusPx, borderPaint)
    }

    fun updateData(
        tariffName: String?,
        expiryText: String?,
        isUnlimitedTime: Boolean,
        isActive: Boolean,
        deviceLimit: Int,
        trafficTotalBytes: Long,
        trafficQuotaLimitBytes: Long
    ) {
        val isEn = AppLanguage.current(context) == AppLanguage.EN
        labelTariffView.text = if (isEn) "current plan" else "текущий тариф"
        labelDevicesView.text = if (isEn) "devices included" else "устройств в тарифе"
        labelTrafficView.text = if (isEn) "traffic" else "трафик"

        val rawName = tariffName?.takeIf { it.isNotBlank() } ?: "deytt./connect"
        val translatedName = if (isEn) AppLanguage.text(context, rawName) else rawName
        tariffNameView.text = translatedName

        if (isUnlimitedTime) {
            infinityGlyphView.visibility = View.VISIBLE
            heroTextView.text = if (isEn) "lifetime" else "бессрочно"
        } else {
            infinityGlyphView.visibility = View.GONE
            val fallback = if (isEn) "active" else "активна"
            heroTextView.text = expiryText?.takeIf { it.isNotBlank() } ?: fallback
        }

        deviceLimitView.text = if (deviceLimit > 0) deviceLimit.toString() else "∞"
        trafficView.text = formatBytes(trafficTotalBytes, isEn)
    }

    private fun formatBytes(bytes: Long, isEn: Boolean = false): String {
        val b = bytes.coerceAtLeast(0L)
        val gb = b.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            if (isEn) String.format(Locale.US, "%.1f gb", gb)
            else String.format(Locale.GERMANY, "%.1f gb", gb).replace('.', ',')
        } else {
            val mb = b.toDouble() / (1024.0 * 1024.0)
            if (isEn) String.format(Locale.US, "%.0f mb", mb)
            else String.format(Locale.GERMANY, "%.0f mb", mb).replace('.', ',')
        }
    }

    /**
     * Custom view that renders the thick, smooth vector infinity lemniscate
     * exactly matching the reference design.
     */
    private class InfinityGlyphView(context: Context) : View(context) {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            path.reset()
            val stroke = (h * 0.22f).coerceAtLeast(4f)
            paint.strokeWidth = stroke

            val pad = stroke / 2f
            val width = w - pad * 2f
            val height = h - pad * 2f
            val cx = pad + width / 2f
            val cy = pad + height / 2f

            // Smooth cubic lemniscate path
            path.moveTo(cx, cy)
            // Right lobe top
            path.cubicTo(
                cx + width * 0.18f, cy - height * 0.52f,
                cx + width * 0.50f, cy - height * 0.52f,
                cx + width * 0.50f, cy
            )
            // Right lobe bottom
            path.cubicTo(
                cx + width * 0.50f, cy + height * 0.52f,
                cx + width * 0.18f, cy + height * 0.52f,
                cx, cy
            )
            // Left lobe top
            path.cubicTo(
                cx - width * 0.18f, cy - height * 0.52f,
                cx - width * 0.50f, cy - height * 0.52f,
                cx - width * 0.50f, cy
            )
            // Left lobe bottom
            path.cubicTo(
                cx - width * 0.50f, cy + height * 0.52f,
                cx - width * 0.18f, cy + height * 0.52f,
                cx, cy
            )
            path.close()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(path, paint)
        }
    }
}
