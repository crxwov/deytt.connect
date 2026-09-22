package space.deytt.connect

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private enum class ConnectionVisualState {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

class MainActivity : Activity() {
    companion object {
        private const val VPN_PERMISSION_REQUEST = 1001
        private const val URL_KEY = "subscription_url"

        private const val CANVAS = 0xFF070C16.toInt()
        private const val PANEL = 0xFF0E182A.toInt()
        private const val PANEL_RAISED = 0xFF111D32.toInt()
        private const val INK = 0xFFF3F6FF.toInt()
        private const val COPY = 0xFFAFBBD0.toInt()
        private const val MUTED = 0xFF78869F.toInt()
        private const val LINE = 0xFF2B3954.toInt()
        private const val BLUE = 0xFF7180FF.toInt()
        private const val MINT = 0xFF68E3B8.toInt()
        private const val ERROR = 0xFFE86F87.toInt()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusDot: View
    private lateinit var connectButton: TextView
    private lateinit var importButton: TextView
    private lateinit var disconnectButton: TextView

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ConnectVpnService.ACTION_STATUS) return
            val message = intent.getStringExtra(ConnectVpnService.EXTRA_STATUS)
                ?: return
            renderStatus(message, intent.getStringExtra(ConnectVpnService.EXTRA_ERROR))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = CANVAS
        window.navigationBarColor = CANVAS
        window.decorView.systemUiVisibility = 0
        buildView()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ConnectVpnService.ACTION_STATUS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnStatusReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(vpnStatusReceiver)
        super.onStop()
    }

    private fun buildView() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(CANVAS)
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(42), dp(22), dp(34))
        }

        root.addView(scroll, FrameLayout.LayoutParams(match, match))
        scroll.addView(content, ViewGroup.LayoutParams(match, wrap))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.systemWindowInsetTop
            val bottom = insets.systemWindowInsetBottom
            content.setPadding(
                dp(22),
                (top + dp(18)).coerceAtLeast(dp(42)),
                dp(22),
                bottom + dp(34),
            )
            insets
        }

        val brand = TextView(this).apply {
            text = "deytt./connect"
            textSize = 29f
            letterSpacing = -0.04f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        content.addView(brand)

        val subtitle = TextView(this).apply {
            text = "VPN для deytt."
            textSize = 14f
            setTextColor(COPY)
        }
        content.addView(subtitle, marginParams(wrap, top = 6))

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            background = rounded(PANEL, dp(20), LINE, dp(1))
            setPadding(dp(17), dp(16), dp(17), dp(16))
        }
        statusDot = View(this).apply {
            background = rounded(MUTED, dp(99), null, 0)
        }
        statusPanel.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply {
            topMargin = dp(6)
            rightMargin = dp(12)
        })
        val statusCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusTitle = TextView(this).apply {
            textSize = 18f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        statusDetail = TextView(this).apply {
            textSize = 12f
            setTextColor(COPY)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.18f)
        }
        statusCopy.addView(statusTitle)
        statusCopy.addView(statusDetail, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(5)
        })
        statusPanel.addView(statusCopy, LinearLayout.LayoutParams(0, wrap, 1f))
        content.addView(statusPanel, marginParams(match, top = 26))

        val urlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL_RAISED, dp(18), LINE, dp(1))
            setPadding(dp(17), dp(12), dp(17), dp(12))
        }
        urlInput = EditText(this).apply {
            hint = "Ссылка на подписку"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            isHorizontalFadingEdgeEnabled = true
            setHorizontallyScrolling(true)
            setTextColor(INK)
            setHintTextColor(MUTED)
            textSize = 14f
            letterSpacing = 0.01f
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            setText(getPreferences(0).getString(URL_KEY, "").orEmpty())
        }
        urlPanel.addView(urlInput, LinearLayout.LayoutParams(match, dp(42)))
        content.addView(urlPanel, marginParams(match, top = 18))

        importButton = actionButton("ИМПОРТИРОВАТЬ ПОДПИСКУ", BLUE, Color.WHITE, BLUE)
        content.addView(importButton, marginParams(match, top = 10))
        importButton.setOnClickListener { importSubscription() }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        connectButton = actionButton("ПОДКЛЮЧИТЬ", BLUE, Color.WHITE, BLUE)
        disconnectButton = actionButton("ОТКЛЮЧИТЬ", Color.TRANSPARENT, COPY, LINE)
        actions.addView(connectButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(disconnectButton, LinearLayout.LayoutParams(dp(122), dp(56)).apply {
            leftMargin = dp(10)
        })
        content.addView(actions, marginParams(match, top = 20))
        connectButton.setOnClickListener { requestOrStartVpn() }
        disconnectButton.setOnClickListener {
            stopService(Intent(this@MainActivity, ConnectVpnService::class.java))
            renderStatus("VPN отключён", "Соединение остановлено")
        }

        val note = TextView(this).apply {
            text = "Профиль сохраняется автоматически"
            textSize = 11f
            setTextColor(MUTED)
        }
        content.addView(note, marginParams(wrap, top = 16))

        setContentView(root)
        root.requestApplyInsets()
        renderStatus("Готов к настройке", "Импортируйте подписку, затем запустите защищённый туннель.")
    }

    private fun importSubscription() {
        val rawUrl = urlInput.text.toString().trim()
        if (rawUrl.isBlank()) {
            renderStatus("Нужна ссылка", "Вставьте HTTPS-ссылку на подписку.", ConnectionVisualState.ERROR)
            return
        }
        importButton.isEnabled = false
        renderStatus("Проверяем профиль", "Загружаю и проверяю конфигурацию…", ConnectionVisualState.CONNECTING)
        getPreferences(0).edit().putString(URL_KEY, rawUrl).apply()
        executor.execute {
            try {
                val imported = SubscriptionClient.import(this, rawUrl)
                runOnUiThread {
                    importButton.isEnabled = true
                    renderStatus(
                        "Профиль готов",
                        "${imported.summary.outboundCount} выходов  ·  " +
                            "${imported.summary.protocols.ifEmpty { setOf("sing-box") }.joinToString()}",
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    importButton.isEnabled = true
                    renderStatus(
                        "Импорт не выполнен",
                        error.message ?: "Не удалось импортировать подписку.",
                        ConnectionVisualState.ERROR,
                    )
                }
            }
        }
    }

    private fun requestOrStartVpn() {
        if (SubscriptionStore(this).readCurrent() == null) {
            renderStatus("Нужна подписка", "Сначала импортируйте профиль.", ConnectionVisualState.ERROR)
            return
        }
        renderStatus("Ожидаем разрешение", "Android запросит системное разрешение VPN…", ConnectionVisualState.CONNECTING)
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST)
        } else {
            startVpnService()
        }
    }

    @Deprecated("Android activity result API is sufficient for the MVP")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_PERMISSION_REQUEST) return
        if (resultCode == RESULT_OK) {
            startVpnService()
        } else {
            renderStatus("Разрешение отклонено", "Без системного разрешения VPN туннель не запускается.", ConnectionVisualState.ERROR)
        }
    }

    private fun startVpnService() {
        try {
            val intent = Intent(this, ConnectVpnService::class.java)
                .setAction(ConnectVpnService.ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            renderStatus("Подключаемся", "Запускаю защищённый туннель…", ConnectionVisualState.CONNECTING)
        } catch (error: Throwable) {
            renderStatus(
                "Запуск не выполнен",
                error.message?.takeIf { it.isNotBlank() } ?: "Не удалось запустить VPN.",
                ConnectionVisualState.ERROR,
            )
        }
    }

    private fun renderStatus(
        message: String,
        detail: String? = null,
        forcedState: ConnectionVisualState? = null,
    ) {
        if (!::statusTitle.isInitialized) return
        val normalized = message.lowercase()
        val state = forcedState ?: when {
            normalized.contains("ошиб") || normalized.contains("не выполн") || normalized.contains("отклон") -> ConnectionVisualState.ERROR
            normalized.contains("подключён") || normalized.contains("подключено") -> ConnectionVisualState.CONNECTED
            normalized.contains("запуска") || normalized.contains("подключаем") || normalized.contains("ожидаем") || normalized.contains("проверяем") -> ConnectionVisualState.CONNECTING
            else -> ConnectionVisualState.IDLE
        }
        statusTitle.text = message
        statusDetail.text = detail.orEmpty()
        statusDot.setBackgroundColor(when (state) {
            ConnectionVisualState.CONNECTED -> MINT
            ConnectionVisualState.CONNECTING -> BLUE
            ConnectionVisualState.ERROR -> ERROR
            ConnectionVisualState.IDLE -> MUTED
        })
    }

    private fun actionButton(textValue: String, fill: Int, textColor: Int, stroke: Int): TextView = TextView(this).apply {
        text = textValue
        textSize = 12f
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        minHeight = dp(56)
        isClickable = true
        isFocusable = true
        background = rounded(fill, dp(15), stroke.takeIf { it != Color.TRANSPARENT }, if (stroke == Color.TRANSPARENT) 0 else dp(1))
        foreground = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(42, 255, 255, 255)),
            null,
            null,
        )
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(110L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
            }
            false
        }
    }

    private fun rounded(fill: Int, radius: Int, strokeColor: Int?, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun marginParams(width: Int, height: Int = wrap, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private val match: Int get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap: Int get() = ViewGroup.LayoutParams.WRAP_CONTENT
}
