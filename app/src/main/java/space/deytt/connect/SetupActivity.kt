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
import android.widget.TextView
import androidx.core.content.edit
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
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
        root.addView(header(uiCopy("аккаунт · подписка · устройства"), uiCopy(if (updating) "Обновить подписку" else "Подключить подписку"), updating))
        root.addView(spacer(8, this))
        root.addView(note(
            uiCopy(if (pendingImportUrl != null) {
                if (updating) "Ссылка готова к импорту. Подписка будет заменена, текущее соединение отключится."
                else "Ссылка готова к импорту. Нажмите кнопку ниже, чтобы загрузить подписку и маршруты."
            } else "Войдите в Telegram, чтобы загрузить подписку и маршруты."),
            DeyttUi.MUTED,
        ))
        root.addView(spacer(14, this))
        accountAction = button(uiCopy(when {
            deviceSlotConflict && transferRequestSent -> "Повторить проверку"
            deviceSlotConflict -> "Обратиться в поддержку"
            pendingImportUrl != null -> "Импортировать подписку"
            sessionToken == null -> "Подключить аккаунт Telegram"
            else -> "Обновить подписку"
        }), secondary = true).apply {
            setOnClickListener {
                when {
                    deviceSlotConflict && !transferRequestSent -> requestDeviceTransfer()
                    pendingImportUrl != null -> pendingImportUrl?.let(::importProfile)
                    else -> loadAccountSubscription()
                }
            }
        }
        state = note("", DeyttUi.MUTED).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        root.addView(state)
        present(root, accountAction)
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
                            "validate" -> "Проверяем подписку…"
                            "awg" -> "Загружаем дополнительные маршруты…"
                            "save" -> "Сохраняем подписку…"
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
                        RouteCatalog.from(config, AwgProfileStore(this@SetupActivity).profiles())
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
        state.text = "${uiCopy(SubscriptionLoadDiagnostics.userMessage(error))}\n\nкод: $reference"
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
                    state.text = "${SubscriptionErrorText.userMessage(error)}\n\n${uiCopy("Не удалось отправить обращение. Проверьте интернет и повторите попытку.")}"
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
            background = rounded(DeyttUi.SURFACE, 24f, DeyttUi.LINE)
        }
        val title = text("Добавить приложение", 21f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, dp(4), 0, dp(6))
        }
        sheet.addView(title)
        val detail = text("Укажи username Telegram. Если аккаунт уже есть в боте, код придёт сюда.", 13f, DeyttUi.MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        sheet.addView(detail)
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
        )
        val code = field("123456", InputType.TYPE_CLASS_NUMBER, 6).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            letterSpacing = .24f
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
        val primary = button("Получить код")
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
        var challenge: String? = null
        var botUrl: String? = null
        var step = 0
        var needsBotStart = false
        val requestNewCode = button("Запросить новый код", secondary = true).apply { visibility = View.GONE }
        sheet.addView(requestNewCode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        fun updateStep(next: Int) {
            step = next
            username.visibility = if (step == 0) View.VISIBLE else View.GONE
            code.visibility = if (step == 1) View.VISIBLE else View.GONE
            openBot.visibility = if (step == 1 && needsBotStart) View.VISIBLE else View.GONE
            title.text = if (step == 0) "Добавить приложение" else "Введите код"
            detail.text = if (step == 0) {
                "Укажи username Telegram. Если аккаунт уже есть в боте, код придёт сюда."
            } else if (needsBotStart) {
                "Этого username пока нет в боте. Открой ./c один раз — код появится здесь."
            } else {
                "Код уже отправлен в Telegram. Переключаться в бот не нужно."
            }
            primary.text = if (step == 0) "Получить код" else "Подтвердить код"
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
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    .onFailure { status.text = "Не удалось открыть ссылку. Попробуй ещё раз." }
            }
        }
        primary.setOnClickListener {
            if (step == 0) {
                val value = username.text.toString().trim()
                if (!Regex("@?[A-Za-z0-9_]{5,32}").matches(value)) {
                    status.setTextColor(DeyttUi.CORAL)
                    status.text = "Введи username Telegram длиной от 5 до 32 знаков."
                    return@setOnClickListener
                }
                primary.isEnabled = false
                primary.text = "Отправляем…"
                status.setTextColor(DeyttUi.SKY)
                status.text = "Проверяю, доступен ли вход через бота."
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
                            status.text = when (started.delivery) {
                                "sent" -> "Код отправлен в Telegram и действует 5 минут."
                                "delivery_failed" -> "Не удалось доставить код. Открой ./c ниже, чтобы получить его."
                                else -> "Этого username пока нет в боте. Открой ./c ниже один раз."
                            }
                        }.onFailure { error ->
                            primary.text = "Получить код"
                            status.setTextColor(DeyttUi.CORAL)
                            status.text = when ((error as? TelegramPairingException)?.code) {
                                "invalid_username" -> "Проверь username Telegram."
                                "pairing_rate_limited", "rate_limited" -> "Слишком часто. Подожди и попробуй ещё раз."
                                else -> "Не удалось начать вход. Проверь соединение и попробуй снова."
                            }
                        }
                    }
                }
            } else {
                val currentChallenge = challenge
                val pin = code.text.toString().trim()
                if (currentChallenge == null || !Regex("[0-9]{6}").matches(pin)) {
                    status.setTextColor(DeyttUi.CORAL)
                    status.text = "Введи шесть цифр из сообщения ./c."
                    return@setOnClickListener
                }
                primary.isEnabled = false
                primary.text = "Проверяем…"
                status.setTextColor(DeyttUi.SKY)
                status.text = "Подтверждаю аккаунт и загружаю доступные профили."
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
                            primary.text = "Подтвердить код"
                            status.setTextColor(DeyttUi.CORAL)
                            status.text = when ((error as? TelegramPairingException)?.code) {
                                "pair_code_invalid" -> "Код неверный или истёк. Запроси новый."
                                "pair_code_locked" -> "Попытки закончились. Начни вход заново."
                                else -> "${SubscriptionLoadDiagnostics.userMessage(error)} Если код уже использован, запроси новый."
                            }
                            SubscriptionLoadDiagnostics.record(this, "pair", error)
                        }
                    }
                }
            }
        }
        dialog.setContentView(sheet)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
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
