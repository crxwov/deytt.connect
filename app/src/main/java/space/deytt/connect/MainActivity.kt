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
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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
        private const val BLUE_DEEP = 0xFF96A2FF.toInt()
        private const val MINT = 0xFF68E3B8.toInt()
        private const val ERROR = 0xFFE86F87.toInt()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusDot: View
    private lateinit var stage: NetworkBackdropView
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
            setPadding(dp(22), dp(18), dp(22), dp(34))
        }

        root.addView(scroll, FrameLayout.LayoutParams(match, match))
        scroll.addView(content, ViewGroup.LayoutParams(match, wrap))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.systemWindowInsetTop
            val bottom = insets.systemWindowInsetBottom
            content.setPadding(dp(22), top + dp(18), dp(22), bottom + dp(34))
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "deytt./connect"
            textSize = 31f
            letterSpacing = -0.04f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val buildTag = TextView(this).apply {
            text = "ANDROID / 01"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(BLUE_DEEP)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(Color.TRANSPARENT, dp(9), BLUE, 1)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        header.addView(brand, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(buildTag, LinearLayout.LayoutParams(wrap, wrap))
        content.addView(header)

        val eyebrow = technicalLabel("PRIVATE NETWORK  /  SYSTEM VPN")
        content.addView(eyebrow, marginParams(wrap, top = 10))

        val intro = TextView(this).apply {
            text = "Твой трафик.\nТвоя сеть."
            textSize = 31f
            letterSpacing = -0.035f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        content.addView(intro, marginParams(wrap, top = 16))

        val subintro = TextView(this).apply {
            text = "DEYTTT Connect собирает защищённый маршрут в один системный туннель — тихо, быстро и без лишнего интерфейса."
            textSize = 14f
            setTextColor(COPY)
            setLineSpacing(0f, 1.28f)
        }
        content.addView(subintro, marginParams(wrap, top = 12))

        val stageShell = FrameLayout(this).apply {
            background = rounded(PANEL, dp(24), LINE, dp(1))
            clipChildren = true
            clipToPadding = true
        }
        stage = NetworkBackdropView(this)
        stageShell.addView(stage, FrameLayout.LayoutParams(match, dp(220)))

        val stageTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(16), dp(17), 0)
        }
        val stageLabel = technicalLabel("NETWORK STATUS")
        stageTop.addView(stageLabel, LinearLayout.LayoutParams(0, wrap, 1f))
        val coreTag = TextView(this).apply {
            text = "SING-BOX CORE"
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(MUTED)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        stageTop.addView(coreTag, LinearLayout.LayoutParams(wrap, wrap))
        stageShell.addView(stageTop, FrameLayout.LayoutParams(match, wrap).apply {
            gravity = Gravity.TOP
        })

        val stageStatus = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        statusTitle = TextView(this).apply {
            textSize = 18f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        statusDetail = TextView(this).apply {
            textSize = 12f
            setTextColor(COPY)
            gravity = Gravity.CENTER
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.18f)
        }
        stageStatus.addView(statusTitle, LinearLayout.LayoutParams(match, wrap))
        stageStatus.addView(statusDetail, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(5)
        })
        stageShell.addView(stageStatus, FrameLayout.LayoutParams(match, wrap).apply {
            gravity = Gravity.BOTTOM
        })
        content.addView(stageShell, marginParams(match, top = 24))

        val subscriptionLabel = technicalLabel("SUBSCRIPTION  /  SECURE CONFIG")
        content.addView(subscriptionLabel, marginParams(wrap, top = 28))

        val urlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL_RAISED, dp(18), LINE, dp(1))
            setPadding(dp(17), dp(15), dp(17), dp(13))
        }
        urlInput = EditText(this).apply {
            hint = "https://…/sub/token/…"
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
        urlPanel.addView(urlInput, LinearLayout.LayoutParams(match, dp(30)))
        val urlHint = TextView(this).apply {
            text = "HTTPS only  ·  конфигурация заменяется атомарно"
            textSize = 10f
            setTextColor(MUTED)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        urlPanel.addView(urlHint, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(8)
        })
        content.addView(urlPanel, marginParams(match, top = 10))

        importButton = actionButton("ИМПОРТИРОВАТЬ ПОДПИСКУ", BLUE, Color.WHITE, BLUE)
        content.addView(importButton, marginParams(match, top = 12))
        importButton.setOnClickListener { importSubscription() }

        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            background = rounded(PANEL, dp(18), LINE, dp(1))
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        statusDot = View(this).apply {
            background = rounded(MUTED, dp(99), null, 0)
        }
        statusPanel.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply {
            topMargin = dp(5)
            rightMargin = dp(12)
        })
        val statusCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val statusKicker = technicalLabel("LIVE DIAGNOSTIC")
        statusCopy.addView(statusKicker)
        statusCopy.addView(TextView(this).apply {
            text = "Состояние туннеля"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(3), 0, 0)
        })
        statusPanel.addView(statusCopy, LinearLayout.LayoutParams(0, wrap, 1f))
        content.addView(statusPanel, marginParams(match, top = 12))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        connectButton = actionButton("ПОДКЛЮЧИТЬ", BLUE, Color.WHITE, BLUE)
        disconnectButton = actionButton("ОТКЛЮЧИТЬ", Color.TRANSPARENT, COPY, LINE)
        actions.addView(connectButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(disconnectButton, LinearLayout.LayoutParams(dp(118), dp(56)).apply {
            leftMargin = dp(10)
        })
        content.addView(actions, marginParams(match, top = 12))
        connectButton.setOnClickListener { requestOrStartVpn() }
        disconnectButton.setOnClickListener {
            stopService(Intent(this@MainActivity, ConnectVpnService::class.java))
            renderStatus("VPN отключён", "Соединение остановлено")
        }

        val note = TextView(this).apply {
            text = "LAST KNOWN GOOD\nПрофиль сохраняется атомарно. Предыдущая рабочая версия остаётся локально для отката."
            textSize = 10f
            setTextColor(MUTED)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setLineSpacing(0f, 1.35f)
        }
        content.addView(note, marginParams(wrap, top = 25))

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
        stage.setState(state)
    }

    private fun technicalLabel(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 9f
        letterSpacing = 0.12f
        setTextColor(BLUE_DEEP)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
