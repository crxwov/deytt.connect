package space.deytt.connect

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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

internal enum class ConnectionVisualState {
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
    private lateinit var signalDial: SignalDialView
    private lateinit var connectButton: TextView
    private lateinit var importButton: TextView
    private lateinit var routeButton: TextView
    private var rawSubscriptionUrl: String? = null
    private var maskedSubscriptionUrl = false
    private var profileReady = false
    private var vpnConnected = false
    private var busy = false

    private val vpnStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ConnectVpnService.ACTION_STATUS) return
            val message = intent.getStringExtra(ConnectVpnService.EXTRA_STATUS)
                ?: return
            renderStatus(
                message,
                intent.getStringExtra(ConnectVpnService.EXTRA_ERROR)?.let(::friendlyErrorMessage),
            )
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

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "deytt."
            textSize = 27f
            letterSpacing = -0.045f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val edition = TextView(this).apply {
            text = "CONNECT / ANDROID"
            textSize = 10f
            letterSpacing = 0.16f
            setTextColor(MUTED)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.END
        }
        header.addView(brand, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(edition)
        content.addView(header, marginParams(match))

        signalDial = SignalDialView(this)
        content.addView(signalDial, marginParams(match, dp(222), top = 14))

        statusTitle = TextView(this).apply {
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(INK)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        content.addView(statusTitle, marginParams(match, top = -8))
        statusDetail = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COPY)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            setPadding(dp(10), 0, dp(10), 0)
        }
        content.addView(statusDetail, marginParams(match, top = 7))

        routeButton = actionButton("МАРШРУТ  ·  ИМПОРТИРУЙТЕ ПОДПИСКУ", PANEL, COPY, LINE).apply {
            contentDescription = "Выбор VPN-маршрута"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        content.addView(routeButton, marginParams(match, dp(58), top = 24))
        routeButton.setOnClickListener { selectRoute() }

        connectButton = actionButton("ПОДКЛЮЧИТЬ", BLUE, Color.WHITE, BLUE)
        content.addView(connectButton, marginParams(match, dp(58), top = 10))
        connectButton.setOnClickListener {
            if (busy || vpnConnected) {
                startService(
                    Intent(this@MainActivity, ConnectVpnService::class.java)
                        .setAction(ConnectVpnService.ACTION_STOP),
                )
                renderStatus(
                    "VPN отключается…",
                    "Завершаю соединение и освобождаю сетевые ресурсы.",
                    ConnectionVisualState.CONNECTING,
                )
            } else {
                requestOrStartVpn()
            }
        }

        val subscriptionLabel = TextView(this).apply {
            text = "ПОДПИСКА"
            textSize = 10f
            letterSpacing = 0.15f
            setTextColor(MUTED)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        content.addView(subscriptionLabel, marginParams(wrap, top = 30))

        val urlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(PANEL_RAISED, dp(15), LINE, dp(1))
            setPadding(dp(16), 0, dp(8), 0)
        }
        urlInput = EditText(this).apply {
            hint = "HTTPS-ссылка на подписку"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            isHorizontalFadingEdgeEnabled = true
            setHorizontallyScrolling(true)
            contentDescription = "Ссылка на подписку"
            setTextColor(INK)
            setHintTextColor(MUTED)
            textSize = 16f
            letterSpacing = 0.01f
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            rawSubscriptionUrl = getPreferences(0).getString(URL_KEY, "")
                ?.takeIf { it.isNotBlank() }
            if (rawSubscriptionUrl != null) {
                maskedSubscriptionUrl = true
                setText(maskSensitiveUrl(rawSubscriptionUrl.orEmpty()))
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) revealSubscriptionUrl()
                else if (!maskedSubscriptionUrl && !rawSubscriptionUrl.isNullOrBlank() &&
                    urlInput.text.toString().trim() == rawSubscriptionUrl.orEmpty()
                ) {
                    maskSubscriptionUrl(rawSubscriptionUrl.orEmpty())
                }
            }
        }
        urlPanel.addView(urlInput, LinearLayout.LayoutParams(0, dp(54), 1f))
        importButton = actionButton("ИМПОРТ", Color.TRANSPARENT, BLUE, Color.TRANSPARENT).apply {
            textSize = 11f
            minHeight = dp(44)
        }
        urlPanel.addView(importButton, LinearLayout.LayoutParams(dp(94), dp(44)))
        content.addView(urlPanel, marginParams(match, dp(62), top = 10))
        importButton.setOnClickListener { importSubscription() }

        val note = TextView(this).apply {
            text = "Профиль хранится только на устройстве · токен скрыт"
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        content.addView(note, marginParams(match, top = 13))

        setContentView(root)
        root.requestApplyInsets()
        restoreScreenState()
        updateControls()
    }

    private fun importSubscription() {
        val rawUrl = currentSubscriptionUrl()
        if (rawUrl.isBlank()) {
            renderStatus("Нужна ссылка", "Вставьте HTTPS-ссылку на подписку.", ConnectionVisualState.ERROR)
            return
        }
        busy = true
        updateControls()
        importButton.isEnabled = false
        renderStatus("Проверяем профиль", "Загружаю и проверяю конфигурацию…", ConnectionVisualState.CONNECTING)
        executor.execute {
            try {
                val imported = SubscriptionClient.import(this, rawUrl)
                runOnUiThread {
                    getPreferences(0).edit().putString(URL_KEY, imported.url).apply()
                    rawSubscriptionUrl = imported.url
                    maskSubscriptionUrl(imported.url)
                    profileReady = true
                    busy = false
                    updateRouteButton()
                    updateControls()
                    importButton.isEnabled = true
                    renderStatus(
                        "Профиль готов",
                        "${imported.summary.outboundCount} выходов  ·  " +
                            "${ProfileRoutes.options(SubscriptionStore(this).readCurrent().orEmpty()).size} маршрутов",
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    busy = false
                    profileReady = hasValidStoredProfile()
                    updateControls()
                    importButton.isEnabled = true
                    renderStatus(
                        "Импорт не выполнен",
                        friendlyError(error, "Не удалось импортировать подписку."),
                        ConnectionVisualState.ERROR,
                    )
                }
            }
        }
    }

    private fun requestOrStartVpn() {
        val config = SubscriptionStore(this).readCurrent()
        if (config == null) {
            profileReady = false
            updateControls()
            renderStatus("Нужна подписка", "Сначала импортируйте профиль.", ConnectionVisualState.ERROR)
            return
        }
        try {
            ProfileValidator.validate(config)
            profileReady = true
        } catch (error: Exception) {
            profileReady = false
            updateControls()
            renderStatus(
                "Профиль требует обновления",
                friendlyError(error, "Импортируйте подписку заново."),
                ConnectionVisualState.ERROR,
            )
            return
        }
        busy = true
        updateControls()
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
            busy = false
            updateControls()
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
            busy = false
            updateControls()
            renderStatus(
                "Запуск не выполнен",
                friendlyError(error, "Не удалось запустить VPN."),
                ConnectionVisualState.ERROR,
            )
        }
    }

    private fun selectRoute() {
        val config = SubscriptionStore(this).readCurrent()
        if (config == null) {
            renderStatus("Нужна подписка", "Сначала импортируйте профиль.", ConnectionVisualState.ERROR)
            return
        }
        val routes = runCatching { ProfileRoutes.options(config) }.getOrElse { error ->
            renderStatus("Маршруты недоступны", friendlyError(error, "Импортируйте подписку заново."), ConnectionVisualState.ERROR)
            return
        }
        if (routes.isEmpty()) {
            renderStatus("Маршруты не найдены", "Импортируйте подписку заново.", ConnectionVisualState.ERROR)
            return
        }
        val selectedTag = ProfileRoutes.selected(config)
        val selectedIndex = routes.indexOfFirst { it.tag == selectedTag }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Выберите маршрут")
            .setSingleChoiceItems(routes.map(RouteOption::label).toTypedArray(), selectedIndex) { dialog, which ->
                val route = routes[which]
                try {
                    val updated = ProfileRoutes.select(config, route.tag)
                    ProfileValidator.validate(updated)
                    SubscriptionStore(this).saveValidated(updated)
                    updateRouteButton()
                    renderStatus("Маршрут выбран", "${route.label}. Теперь можно подключиться.")
                    dialog.dismiss()
                } catch (error: Exception) {
                    renderStatus("Маршрут не сохранён", friendlyError(error, "Импортируйте подписку заново."), ConnectionVisualState.ERROR)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        statusDetail.text = detail.orEmpty().trim().take(360)
        statusTitle.setTextColor(if (state == ConnectionVisualState.ERROR) ERROR else INK)
        statusDetail.setTextColor(if (state == ConnectionVisualState.ERROR) 0xFFD6A6B1.toInt() else COPY)
        signalDial.setState(state)
        when (state) {
            ConnectionVisualState.CONNECTED -> {
                vpnConnected = true
                busy = false
            }
            ConnectionVisualState.ERROR -> {
                vpnConnected = false
                busy = false
            }
            ConnectionVisualState.IDLE -> {
                if (message.contains("отключ", ignoreCase = true)) vpnConnected = false
                busy = false
            }
            ConnectionVisualState.CONNECTING -> Unit
        }
        updateControls()
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
        val radius = dp(15)
        background = rounded(fill, radius, stroke.takeIf { it != Color.TRANSPARENT }, if (stroke == Color.TRANSPARENT) 0 else dp(1))
        foreground = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(42, 255, 255, 255)),
            null,
            rounded(Color.WHITE, radius, null, 0),
        )
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(110L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
            }
            false
        }
    }

    private fun restoreScreenState() {
        profileReady = hasValidStoredProfile()
        updateRouteButton()
        val state = getSharedPreferences(ConnectVpnService.STATE_PREFS, MODE_PRIVATE)
        when (state.getString(ConnectVpnService.STATE_STATUS, null)) {
            "VPN подключён" -> renderStatus("VPN подключён", "Туннель активен", ConnectionVisualState.CONNECTED)
            "Ошибка запуска VPN" -> renderStatus(
                "Ошибка запуска VPN",
                state.getString(ConnectVpnService.STATE_ERROR, null)
                    ?.let { friendlyErrorMessage(it) },
                ConnectionVisualState.ERROR,
            )
            else -> {
                val config = SubscriptionStore(this).readCurrent()
                if (config == null) {
                    renderStatus("Готов к настройке", "Импортируйте подписку, затем запустите защищённый туннель.")
                } else {
                    val summary = runCatching { ProfileValidator.validate(config) }.getOrNull()
                    if (summary != null) {
                        renderStatus("Профиль готов", "${summary.outboundCount} выходов · готов к подключению")
                    } else {
                        renderStatus(
                            "Профиль требует обновления",
                            "Импортируйте подписку заново для обновления конфигурации.",
                            ConnectionVisualState.ERROR,
                        )
                    }
                }
            }
        }
    }

    private fun hasValidStoredProfile(): Boolean =
        SubscriptionStore(this).readCurrent()?.let {
            runCatching { ProfileValidator.validate(it) }.isSuccess
        } == true

    private fun updateRouteButton() {
        if (!::routeButton.isInitialized) return
        val selected = SubscriptionStore(this).readCurrent()
            ?.let { config -> runCatching { ProfileRoutes.selected(config) }.getOrNull() }
        val label = selected?.let { tag ->
            runCatching { ProfileRoutes.options(SubscriptionStore(this).readCurrent().orEmpty()) }
                .getOrNull()
                ?.firstOrNull { it.tag == tag }
                ?.label
        } ?: "Импортируйте подписку"
        routeButton.text = "МАРШРУТ  ·  ${label.uppercase()}   ›"
    }

    private fun currentSubscriptionUrl(): String =
        if (maskedSubscriptionUrl) rawSubscriptionUrl.orEmpty()
        else urlInput.text.toString().trim()

    private fun revealSubscriptionUrl() {
        if (!maskedSubscriptionUrl) return
        urlInput.setText(rawSubscriptionUrl.orEmpty())
        urlInput.setSelection(urlInput.length())
        maskedSubscriptionUrl = false
    }

    private fun maskSubscriptionUrl(url: String) {
        rawSubscriptionUrl = url
        maskedSubscriptionUrl = true
        if (::urlInput.isInitialized) {
            urlInput.clearFocus()
            urlInput.setText(maskSensitiveUrl(url))
            urlInput.setSelection(0)
        }
    }

    private fun maskSensitiveUrl(url: String): String = url
        .replace(Regex("(/token/)[^/?#]+"), "$1••••••••")
        .replace(Regex("([?&](?:token|key|password)=)[^&#]+", RegexOption.IGNORE_CASE), "$1••••••••")

    private fun friendlyError(error: Throwable, fallback: String): String =
        friendlyErrorMessage(error.message?.takeIf { it.isNotBlank() } ?: fallback)

    private fun friendlyErrorMessage(message: String): String {
        val normalized = message.replace(Regex("\\s+"), " ").trim()
        return when {
            normalized.contains("legacy", ignoreCase = true) ||
                normalized.contains("inet4_address", ignoreCase = true) ->
                "Конфигурация устарела. Нажмите «Импортировать подписку» заново."
            normalized.contains("unknown field", ignoreCase = true) ->
                "Сервер прислал несовместимую конфигурацию. Повторите импорт подписки."
            normalized.contains("initialize cache-file", ignoreCase = true) ||
                normalized.contains("cache-file", ignoreCase = true) ->
                "Предыдущий запуск не завершился. Повторите подключение."
            normalized.contains("unable to resolve host", ignoreCase = true) ||
                normalized.contains("no address associated", ignoreCase = true) ||
                normalized.contains("DNS через VPN не отвечает", ignoreCase = true) ->
                "DNS через VPN не ответил. Проверьте сеть или выберите другой маршрут."
            normalized.contains("Туннель не передаёт HTTPS-трафик", ignoreCase = true) ->
                "Выбранный маршрут не передаёт трафик. Выберите другой маршрут и повторите подключение."
            normalized.contains("Нет доступной физической сети", ignoreCase = true) ->
                "Телефон не подключён к интернету. Включите Wi-Fi или мобильную сеть и повторите подключение."
            else -> normalized.take(360)
        }
    }

    private fun updateControls() {
        if (!::connectButton.isInitialized) return
        importButton.isEnabled = !busy
        routeButton.isEnabled = profileReady && !busy && !vpnConnected
        connectButton.isEnabled = profileReady || busy || vpnConnected
        connectButton.text = when {
            busy -> "ОСТАНОВИТЬ"
            vpnConnected -> "ОТКЛЮЧИТЬ"
            else -> "ПОДКЛЮЧИТЬ"
        }
        connectButton.background = rounded(
            if (busy || vpnConnected) PANEL_RAISED else BLUE,
            dp(15),
            if (busy || vpnConnected) LINE else BLUE,
            dp(1),
        )
        connectButton.setTextColor(if (busy || vpnConnected) INK else Color.WHITE)
        importButton.text = if (profileReady) "ОБНОВИТЬ" else "ИМПОРТ"
        importButton.alpha = if (importButton.isEnabled) 1f else 0.55f
        routeButton.alpha = if (routeButton.isEnabled) 1f else 0.45f
        connectButton.alpha = if (connectButton.isEnabled) 1f else 0.45f
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
