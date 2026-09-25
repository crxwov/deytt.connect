package space.deytt.connect

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
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
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class SetupActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var input: EditText
    private lateinit var state: TextView
    private lateinit var importButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val updating = SubscriptionStore(this).readCurrent() != null
        val root = screen()
        root.addView(header("добавить источник", if (updating) "Обновить подписку" else "Подключить подписку", updating))
        root.addView(spacer(8, this))
        root.addView(note(
            "Войдите через Telegram — профили загрузятся сами. Ссылка подписки остаётся запасным способом.",
            DeyttUi.MUTED,
        ))
        root.addView(spacer(14, this))
        root.addView(button("Подключить аккаунт Telegram", secondary = true).apply {
            setOnClickListener { showTelegramPairing() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(spacer(18, this))
        root.addView(sectionLabel("или добавьте ссылку вручную"))
        val incomingUrl = intent.getStringExtra(EXTRA_SUBSCRIPTION_URL)
        input = EditText(this).apply {
            hint = "https://deytt.space/sub/token/…"
            setHintTextColor(DeyttUi.MUTED)
            setTextColor(DeyttUi.TEXT)
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            contentDescription = "Ссылка на подписку, скрытая"
            minHeight = dp(56)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(DeyttUi.SURFACE_2, 12f, DeyttUi.LINE)
            setText(incomingUrl ?: getSharedPreferences("profile_settings", MODE_PRIVATE).getString("subscription_url", ""))
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val revealLink = actionLabel("показать").apply {
            contentDescription = "Показать или скрыть ссылку на подписку"
            setOnClickListener {
                val cursor = input.selectionStart.coerceAtLeast(0)
                val hidden = input.transformationMethod is PasswordTransformationMethod
                input.transformationMethod = if (hidden) null else PasswordTransformationMethod.getInstance()
                contentDescription = if (hidden) "Скрыть ссылку на подписку" else "Показать ссылку на подписку"
                text = if (hidden) "скрыть" else "показать"
                input.setSelection(cursor.coerceAtMost(input.length()))
            }
        }
        root.addView(revealLink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
        })
        root.addView(spacer(14, this))
        importButton = button(if (updating) "Обновить подписку" else "Добавить подписку").apply { setOnClickListener { importProfile() } }
        state = note("", DeyttUi.MUTED).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        root.addView(state)
        present(root, importButton)
    }

    private fun importProfile() {
        val raw = input.text.toString().trim()
        if (raw.isBlank()) {
            state.visibility = View.VISIBLE
            state.setTextColor(DeyttUi.CORAL)
            state.text = "Вставьте ссылку на подписку"
            return
        }
        input.isEnabled = false
        importButton.isEnabled = false
        importButton.alpha = .65f
        state.visibility = View.VISIBLE
        state.setTextColor(DeyttUi.BLUE)
        state.text = "Получаем маршруты…"
        state.announceForAccessibility(state.text)
        executor.execute {
            runCatching { SubscriptionClient.import(this, raw) }
                .onSuccess { imported -> runOnUiThread {
                    getSharedPreferences("profile_settings", MODE_PRIVATE).edit {
                        putString("subscription_url", imported.url)
                        if (imported.warnings.isEmpty()) remove("subscription_warning")
                        else putString("subscription_warning", imported.warnings.joinToString("\n"))
                    }
                    val config = SubscriptionStore(this).readCurrent().orEmpty()
                    val routes = RouteCatalog.from(config, AwgProfileStore(this@SetupActivity).profiles())
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
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                        applyFadeTransition()
                    }.start()
                }}
                .onFailure { error -> runOnUiThread {
                    state.setTextColor(DeyttUi.CORAL)
                    state.text = SubscriptionErrorText.userMessage(error)
                    state.announceForAccessibility(state.text)
                    input.isEnabled = true; importButton.isEnabled = true; importButton.alpha = 1f
                }}
        }
    }

    private fun showTelegramPairing() {
        val dialog = Dialog(this)
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
                        result.onSuccess { (_, subscriptionResult) ->
                            dialog.dismiss()
                            val subscription = subscriptionResult.getOrNull()
                            if (subscription != null) {
                                input.setText(subscription)
                                importProfile()
                            } else {
                                state.visibility = View.VISIBLE
                                state.setTextColor(if (subscriptionResult.isSuccess) DeyttUi.MINT else DeyttUi.AMBER)
                                state.text = if (subscriptionResult.isSuccess) {
                                    "Аккаунт Telegram подключён. Активной подписки нет — добавь ссылку ниже."
                                } else {
                                    "Аккаунт Telegram подключён, но загрузить подписку не удалось. Добавь ссылку ниже."
                                }
                            }
                        }.onFailure { error ->
                            primary.text = "Подтвердить код"
                            status.setTextColor(DeyttUi.CORAL)
                            status.text = when ((error as? TelegramPairingException)?.code) {
                                "pair_code_invalid" -> "Код неверный или истёк. Запроси новый."
                                "pair_code_locked" -> "Попытки закончились. Начни вход заново."
                                else -> "Не удалось подтвердить код. Попробуй ещё раз."
                            }
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

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

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
