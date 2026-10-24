package space.deytt.connect

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.AppLanguage.uiCopy

class SetupActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var state: TextView
    private lateinit var accountAction: TextView
    private var loading = false
    private var pendingImportUrl: String? = null
    private var lastFailureMessage: String? = null
    private var deviceSlotConflict = false
    private var transferRequestSent = false
    private var pairingDialog: Dialog? = null
    @Volatile private var loadingStage = "account"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val updating = SubscriptionStore(this).readCurrent() != null
        val sessionToken = TelegramSessionStore.read(this)
        val incomingUrl = intent.getStringExtra(EXTRA_SUBSCRIPTION_URL)
        pendingImportUrl = SubscriptionImportLink.validateSubscriptionUrl(
            savedInstanceState?.getString("pending_import_url") ?: incomingUrl,
        )
        deviceSlotConflict = savedInstanceState?.getBoolean("device_slot_conflict") == true
        transferRequestSent = savedInstanceState?.getBoolean("transfer_request_sent") == true
        val root = screen()
        root.tag = "setup-content"
        val pageTitle = when {
            pendingImportUrl != null -> "Импортировать подписку"
            updating -> "Обновить подписку"
            else -> "Подключить Telegram"
        }
        val pageHeader = header("аккаунт · подписка · устройства", pageTitle, updating).apply { tag = "setup-header" }
        val topBack = if (updating && pageHeader.childCount > 0) {
            pageHeader.getChildAt(0).also {
                pageHeader.removeViewAt(0)
                it.tag = "setup-back"
            }
        } else null
        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            background = rounded(DeyttUi.SURFACE_2, 22f, DeyttUi.LINE)
            tag = "setup-intro-card"
        }
        intro.addView(mono("./connect · TELEGRAM", 9f, DeyttUi.SKY, 650))
        val introText = uiCopy(if (pendingImportUrl != null) {
                if (updating) "Ссылка готова к импорту. Подписка будет заменена, текущее соединение отключится."
                else "Ссылка готова к импорту. Нажмите кнопку ниже, чтобы загрузить подписку и маршруты."
            } else if (updating) "Войдите в Telegram, чтобы обновить подписку и маршруты."
            else "Войдите в Telegram, чтобы загрузить подписку и маршруты.")
        intro.addView(text(introText, 14f, DeyttUi.MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            tag = "setup-intro-copy"
            setPadding(0, dp(9), 0, 0)
        })
        accountAction = button(uiCopy(when {
            deviceSlotConflict && transferRequestSent -> "Повторить проверку"
            deviceSlotConflict -> "Обратиться в поддержку"
            pendingImportUrl != null -> "Импортировать подписку"
            sessionToken == null -> "Подключить аккаунт Telegram"
            else -> "Обновить подписку"
        })).apply {
            tag = "setup-primary"
            setOnClickListener {
                when {
                    deviceSlotConflict && !transferRequestSent -> requestDeviceTransfer()
                    pendingImportUrl != null -> pendingImportUrl?.let(::importProfile)
                    else -> loadAccountSubscription()
                }
            }
        }
        intro.addView(accountAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(15)
        })
        state = note("", DeyttUi.MUTED).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            tag = "setup-state"
        }
        intro.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(9)
        })
        val flexibleSpaceParams = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        if (updating) {
            // Keep only back navigation at the top; center the title with its related refresh card below.
            root.addView(checkNotNull(topBack))
        }
        root.addView(View(this), flexibleSpaceParams())
        root.addView(pageHeader)
        root.addView(spacer(10, this))
        root.addView(intro)
        root.addView(View(this), flexibleSpaceParams())
        present(root)
        savedInstanceState?.getString("failure_message")?.let {
            lastFailureMessage = it
            state.text = it
            state.setTextColor(DeyttUi.CORAL)
            state.visibility = View.VISIBLE
        }
        if (savedInstanceState?.getBoolean("resume_download") == true) {
            pendingImportUrl?.let(::importProfile) ?: loadAccountSubscription()
        }
    }

    private fun loadAccountSubscription() {
        if (loading || isFinishing || isDestroyed) return
        val token = TelegramSessionStore.read(this)
        if (token == null) {
            showTelegramPairing()
            return
        }
        loading = true
        lastFailureMessage = null
        loadingStage = "account"
        state.visibility = View.VISIBLE
        state.setTextColor(DeyttUi.BLUE)
        state.text = uiCopy("Проверяем подписку в Telegram…")
        state.announceForAccessibility(state.text)
        accountAction.isEnabled = false
        accountAction.alpha = .65f
        executor.execute {
            val subscription = runCatching { TelegramPairingClient.subscriptionUrl(token) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                if (TelegramSessionStore.read(this) != token) {
                    loadAccountSubscription()
                    return@runOnUiThread
                }
                subscription.onSuccess { url ->
                    if (url.isNullOrBlank()) {
                        state.setTextColor(DeyttUi.AMBER)
                        state.text = uiCopy("Активная подписка не найдена. Оформите её в Telegram, затем повторите загрузку.")
                        accountAction.text = uiCopy("Повторить загрузку")
                        accountAction.isEnabled = true
                        accountAction.alpha = 1f
                    } else {
                        importProfile(url)
                    }
                }.onFailure { error ->
                    showLoadFailure(error, token)
                }
            }
        }
    }

    private fun importProfile(raw: String) {
        if (loading || isFinishing || isDestroyed) return
        loading = true
        lastFailureMessage = null
        loadingStage = "download"
        accountAction.isEnabled = false
        accountAction.alpha = .65f
        state.visibility = View.VISIBLE
        state.setTextColor(DeyttUi.BLUE)
        state.text = uiCopy("Получаем маршруты…")
        state.announceForAccessibility(state.text)
        val expectedSession = TelegramSessionStore.read(this)
        executor.execute {
            runCatching { SubscriptionClient.import(this, raw) { stage ->
                loadingStage = stage
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && loading) {
                        state.text = when (stage) {
                            "validate" -> uiCopy("Проверяем подписку…")
                            "awg" -> uiCopy("Загружаем дополнительные маршруты…")
                            "save" -> uiCopy("Сохраняем подписку…")
                            else -> uiCopy("Получаем маршруты…")
                        }
                    }
                }
            } }
                .onSuccess { imported -> runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loading = false
                    deviceSlotConflict = false
                    transferRequestSent = false
                    if (TelegramSessionStore.read(this) != expectedSession) {
                        loadAccountSubscription()
                        return@runOnUiThread
                    }
                    val routes = runCatching {
                        val config = SubscriptionStore(this).readCurrent() ?: throw SubscriptionStorageException()
                        RouteCatalog.from(config, AwgProfileStore(this@SetupActivity).profiles(), OpenFluxProfileStore(this@SetupActivity).profiles(this@SetupActivity))
                    }.getOrElse {
                        loadingStage = "save"
                        showLoadFailure(SubscriptionStorageException())
                        return@runOnUiThread
                    }
                    routes.firstOrNull()?.let { SelectedRouteStore(this).save(it) }
                    stopService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
                    AwgTunnelController.stop(this, publishStatus = false)
                    state.setTextColor(if (imported.warnings.isEmpty()) DeyttUi.MINT else DeyttUi.AMBER)
                    state.text = buildString {
                        append(getString(R.string.import_complete, routes.size))
                        if (imported.warnings.isNotEmpty()) {
                            append("\n\n")
                            append(imported.warnings.joinToString("\n"))
                        }
                    }
                    state.announceForAccessibility(state.text)
                    getSharedPreferences(ConnectVpnService.STATE_PREFS, MODE_PRIVATE).edit {
                        putString(ConnectVpnService.STATE_STATUS, VpnStateStore.IDLE_TITLE)
                        remove(ConnectVpnService.STATE_ERROR)
                    }
                    state.animate().alpha(1f).setDuration(220).withEndAction {
                        if (isFinishing || isDestroyed) return@withEndAction
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                        applyFadeTransition()
                    }.start()
                }}
                .onFailure { error -> runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loading = false
                    showLoadFailure(error)
                }}
        }
    }

    private fun showLoadFailure(error: Throwable, expectedSession: String? = null) {
        if (expectedSession != null && error is TelegramPairingException && error.code == "session_expired") {
            TelegramSessionStore.clearIfMatches(this, expectedSession)
        }
        val reference = SubscriptionLoadDiagnostics.record(this, loadingStage, error)
        deviceSlotConflict = SubscriptionErrorText.isAppDeviceSlotConflict(error)
        if (!deviceSlotConflict) transferRequestSent = false
        state.visibility = View.VISIBLE
        state.setTextColor(DeyttUi.CORAL)
        state.text = "${uiCopy(SubscriptionLoadDiagnostics.userMessage(error))}\n\n${uiCopy("код")}: $reference"
        lastFailureMessage = state.text.toString()
        state.announceForAccessibility(state.text)
        accountAction.text = uiCopy(when {
            deviceSlotConflict && transferRequestSent -> "Повторить проверку"
            deviceSlotConflict -> "Обратиться в поддержку"
            pendingImportUrl != null -> "Повторить импорт"
            TelegramSessionStore.read(this) == null -> "Подключить аккаунт Telegram"
            else -> "Повторить загрузку"
        })
        accountAction.isEnabled = true
        accountAction.alpha = 1f
    }

    private fun requestDeviceTransfer() {
        val token = TelegramSessionStore.read(this)
        if (token.isNullOrBlank()) {
            showTelegramPairing()
            return
        }
        AppDialog.Builder(this)
            .setTitle(uiCopy("Перенос приложения"))
            .setMessage(uiCopy("Отправить в поддержку обращение с просьбой перенести deytt.connect на это устройство?"))
            .setNegativeButton(uiCopy("Отмена"), null)
            .setPositiveButton(uiCopy("Отправить обращение")) { _, _ -> sendDeviceTransferRequest(token) }
            .show()
    }

    private fun sendDeviceTransferRequest(token: String) {
        if (loading || isFinishing || isDestroyed) return
        loading = true
        accountAction.isEnabled = false
        accountAction.alpha = .65f
        state.visibility = View.VISIBLE
        state.setTextColor(DeyttUi.BLUE)
        state.text = uiCopy("Отправляем запрос в поддержку…")
        state.announceForAccessibility(state.text)
        val message = "Здравствуйте! Не получается подключить deytt.connect: сервер отвечает HTTP 409 app_device_limit_reached (download/http_409). Прошу проверить и перенести приложение на это устройство."
        executor.execute {
            val result = runCatching {
                check(TelegramSessionStore.read(this) == token) { "Сессия Telegram изменилась" }
                val thread = TelegramPairingClient.supportThread(token)
                val ticket = thread.optJSONObject("ticket")
                val ticketId = ticket?.optInt("id", 0) ?: 0
                if (ticket?.optString("status") == "open" && ticketId > 0) {
                    TelegramPairingClient.sendSupportMessage(token, ticketId, message)
                } else {
                    TelegramPairingClient.createSupportTicket(token, message)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                accountAction.isEnabled = true
                accountAction.alpha = 1f
                result.onSuccess {
                    transferRequestSent = true
                    state.setTextColor(DeyttUi.MINT)
                    state.text = uiCopy("Запрос отправлен. После переноса слота поддержкой нажмите «Повторить проверку».")
                    lastFailureMessage = state.text.toString()
                    accountAction.text = uiCopy("Повторить проверку")
                }.onFailure { error ->
                    state.setTextColor(DeyttUi.CORAL)
                    state.text = "${uiCopy(SubscriptionErrorText.userMessage(error))}\n\n${uiCopy("Не удалось отправить обращение. Проверьте интернет и повторите попытку.")}"
                    lastFailureMessage = state.text.toString()
                    accountAction.text = uiCopy("Обратиться в поддержку")
                }
                state.announceForAccessibility(state.text)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("resume_download", loading)
        outState.putString("pending_import_url", pendingImportUrl)
        outState.putString("failure_message", lastFailureMessage)
        outState.putBoolean("device_slot_conflict", deviceSlotConflict)
        outState.putBoolean("transfer_request_sent", transferRequestSent)
        super.onSaveInstanceState(outState)
    }

    private fun showTelegramPairing() {
        if (pairingDialog?.isShowing == true) return
        val dialog = Dialog(this)
        pairingDialog = dialog
        dialog.setOnDismissListener { pairingDialog = null }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF1B293B.toInt(), 0xFF111A29.toInt()),
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), DeyttUi.LINE)
            }
        }
        val title = text("Добавить приложение", 21f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, dp(4), 0, dp(6))
        }
        sheet.addView(title)
        val detail = text("Укажи username Telegram. Если аккаунт уже есть в боте, код придёт сюда.", 13f, DeyttUi.MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        sheet.addView(detail)
        val stages = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val accountStage = text(uiCopy("Шаг 1 · Аккаунт"), 10f, DeyttUi.SKY, android.graphics.Typeface.BOLD).apply {
            setPadding(dp(9), dp(7), dp(9), dp(7))
            gravity = Gravity.CENTER
            maxLines = 2
            background = rounded(0x263B9EFF, 10f, 0x553B9EFF)
        }
        val codeStage = text(uiCopy("Шаг 2 · Код"), 10f, DeyttUi.MUTED, android.graphics.Typeface.BOLD).apply {
            setPadding(dp(9), dp(7), dp(9), dp(7))
            gravity = Gravity.CENTER
            maxLines = 2
            background = rounded(0x1AFFFFFF, 10f, DeyttUi.LINE)
        }
        stages.addView(accountStage, LinearLayout.LayoutParams(0, -2, 1f))
        stages.addView(View(this), LinearLayout.LayoutParams(dp(7), 1))
        stages.addView(codeStage, LinearLayout.LayoutParams(0, -2, 1f))
        sheet.addView(stages, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        fun field(hintText: String, inputTypeValue: Int, maxLength: Int): EditText =
            EditText(this).apply {
                hint = hintText
                setTextColor(DeyttUi.TEXT)
                setHintTextColor(DeyttUi.MUTED)
                textSize = 15f
                setPadding(dp(15), 0, dp(15), 0)
                background = rounded(DeyttUi.SURFACE_2, 15f, DeyttUi.LINE)
                inputType = inputTypeValue
                isSingleLine = true
                filters = arrayOf(InputFilter.LengthFilter(maxLength))
            }
        val username = field(
            "username",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            33,
        ).apply { contentDescription = uiCopy("Имя пользователя Telegram") }
        val code = field("123456", InputType.TYPE_CLASS_NUMBER, 6).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            letterSpacing = .24f
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = uiCopy("Код подтверждения Telegram")
        }
        sheet.addView(username, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(17)
        })
        sheet.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            topMargin = dp(12)
        })
        val status = text("", 12f, DeyttUi.MUTED).apply {
            setPadding(dp(2), dp(10), dp(2), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        sheet.addView(status)
        val openBot = button("Открыть ./c", secondary = true).apply { visibility = View.GONE }
        sheet.addView(openBot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(11)
        })
        val primary = button("Получить код").apply { tag = "pairing-primary" }
        sheet.addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(9)
        })
        username.imeOptions = EditorInfo.IME_ACTION_GO
        username.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                primary.performClick()
                true
            } else false
        }
        code.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                primary.performClick()
                true
            } else false
        }
        var challenge: String? = null
        var botUrl: String? = null
        var step = 0
        var needsBotStart = false
        val requestNewCode = button("Запросить новый код", secondary = true).apply { visibility = View.GONE }
        sheet.addView(requestNewCode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        fun updateStep(next: Int) {
            step = next
            accountStage.setTextColor(if (step == 0) DeyttUi.SKY else DeyttUi.MUTED)
            accountStage.background = rounded(if (step == 0) 0x263B9EFF else 0x1AFFFFFF, 10f,
                if (step == 0) 0x553B9EFF else DeyttUi.LINE)
            codeStage.setTextColor(if (step == 1) DeyttUi.SKY else DeyttUi.MUTED)
            codeStage.background = rounded(if (step == 1) 0x263B9EFF else 0x1AFFFFFF, 10f,
                if (step == 1) 0x553B9EFF else DeyttUi.LINE)
            username.visibility = if (step == 0) View.VISIBLE else View.GONE
            code.visibility = if (step == 1) View.VISIBLE else View.GONE
            openBot.visibility = if (step == 1 && needsBotStart) View.VISIBLE else View.GONE
            title.text = uiCopy(if (step == 0) "Добавить приложение" else "Введите код")
            detail.text = if (step == 0) {
                uiCopy("Укажи username Telegram. Если аккаунт уже есть в боте, код придёт сюда.")
            } else if (needsBotStart) {
                uiCopy("Этого username пока нет в боте. Открой ./c один раз — код появится здесь.")
            } else {
                uiCopy("Код уже отправлен в Telegram. Переключаться в бот не нужно.")
            }
            primary.text = uiCopy(if (step == 0) "Получить код" else "Подтвердить код")
            requestNewCode.visibility = if (step == 1) View.VISIBLE else View.GONE
        }
        requestNewCode.setOnClickListener {
            if (primary.isEnabled) {
                challenge = null
                code.setText("")
                status.text = ""
                updateStep(0)
            }
        }
        openBot.setOnClickListener {
            botUrl?.let { link ->
                runCatching {
                    val uri = Uri.parse(link)
                    check(uri.scheme?.lowercase() in setOf("https", "tg")) { "Unsupported URL scheme" }
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                    .onFailure { status.text = uiCopy("Не удалось открыть ссылку. Попробуй ещё раз.") }
            }
        }
        primary.setOnClickListener {
            if (step == 0) {
                val value = username.text.toString().trim()
                if (!Regex("@?[A-Za-z0-9_]{5,32}").matches(value)) {
                    status.setTextColor(DeyttUi.CORAL)
                    status.text = uiCopy("Введи username Telegram длиной от 5 до 32 знаков.")
                    return@setOnClickListener
                }
                primary.isEnabled = false
                primary.text = uiCopy("Отправляем…")
                status.setTextColor(DeyttUi.SKY)
                status.text = uiCopy("Проверяю, доступен ли вход через бота.")
                executor.execute {
                    val result = runCatching { TelegramPairingClient.start(value) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                        primary.isEnabled = true
                        result.onSuccess { started ->
                            challenge = started.challenge
                            botUrl = started.botUrl
                            needsBotStart = started.delivery != "sent"
                            updateStep(1)
                            status.setTextColor(DeyttUi.SKY)
                            status.text = uiCopy(when (started.delivery) {
                                "sent" -> "Код отправлен в Telegram и действует 5 минут."
                                "delivery_failed" -> "Не удалось доставить код. Открой ./c ниже, чтобы получить его."
                                else -> "Этого username пока нет в боте. Открой ./c ниже один раз."
                            })
                        }.onFailure { error ->
                            val reference = SubscriptionLoadDiagnostics.record(this, "pair", error)
                            primary.text = uiCopy("Получить код")
                            status.setTextColor(DeyttUi.CORAL)
                            status.text = uiCopy(when ((error as? TelegramPairingException)?.code) {
                                "invalid_username" -> "Проверь username Telegram."
                                "pairing_rate_limited", "rate_limited" -> "Слишком часто. Подожди и попробуй ещё раз."
                                else -> "Не удалось начать вход. Проверь соединение и попробуй снова. Код: $reference"
                            })
                        }
                    }
                }
            } else {
                val currentChallenge = challenge
                val pin = code.text.toString().trim()
                if (currentChallenge == null || !Regex("[0-9]{6}").matches(pin)) {
                    status.setTextColor(DeyttUi.CORAL)
                    status.text = uiCopy("Введи шесть цифр из сообщения ./c.")
                    return@setOnClickListener
                }
                primary.isEnabled = false
                primary.text = uiCopy("Проверяем…")
                status.setTextColor(DeyttUi.SKY)
                status.text = uiCopy("Подтверждаю аккаунт и загружаю доступные профили.")
                executor.execute {
                    val result = runCatching {
                        val session = TelegramPairingClient.verify(currentChallenge, pin)
                        TelegramSessionStore.save(this, session.token)
                        val subscription = runCatching {
                            TelegramPairingClient.subscriptionUrl(session.token)
                        }
                        session to subscription
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                        primary.isEnabled = true
                        result.onSuccess { (session, subscriptionResult) ->
                            dialog.dismiss()
                            loadingStage = "account"
                            val subscription = subscriptionResult.getOrNull()
                            if (subscriptionResult.isFailure) {
                                showLoadFailure(subscriptionResult.exceptionOrNull()!!, session.token)
                            } else if (subscription != null) {
                                accountAction.text = uiCopy("Обновить подписку")
                                importProfile(pendingImportUrl ?: subscription)
                            } else {
                                state.visibility = View.VISIBLE
                                state.setTextColor(if (subscriptionResult.isSuccess) DeyttUi.MINT else DeyttUi.AMBER)
                                state.text = if (subscriptionResult.isSuccess) uiCopy("Активная подписка не найдена. Оформите её в Telegram, затем повторите загрузку.")
                                else uiCopy("Не удалось загрузить подписку. Проверьте соединение и попробуйте ещё раз.")
                                accountAction.text = uiCopy("Повторить загрузку")
                                accountAction.isEnabled = true
                                accountAction.alpha = 1f
                            }
                        }.onFailure { error ->
                            primary.text = uiCopy("Подтвердить код")
                            status.setTextColor(DeyttUi.CORAL)
                            status.text = when ((error as? TelegramPairingException)?.code) {
                                "pair_code_invalid" -> uiCopy("Код неверный или истёк. Запроси новый.")
                                "pair_code_locked" -> uiCopy("Попытки закончились. Начни вход заново.")
                                else -> "${uiCopy(SubscriptionLoadDiagnostics.userMessage(error))} ${uiCopy("Если код уже использован, запроси новый.")}"
                            }
                            SubscriptionLoadDiagnostics.record(this, "pair", error)
                        }
                    }
                }
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(sheet, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .58f }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        pairingDialog?.dismiss()
        state.animate().cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun applyFadeTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    companion object {
        const val EXTRA_SUBSCRIPTION_URL = "subscription_url"
    }
}
