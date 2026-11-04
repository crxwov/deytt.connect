package space.deytt.connect

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import java.util.concurrent.Executors
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.BLUE
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.SURFACE_2
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.text

internal class TelegramPairingSheet(
    private val host: MainActivity,
    private val copy: (String, String) -> String,
) {
    private val pairingExecutor = Executors.newSingleThreadExecutor()

    fun showTelegramPairing() {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        sheet.addView(host.mono("./c · АККАУНТ", 9f, MUTED, 650))
        val title = host.text("Добавить приложение", 21f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(6))
        }
        sheet.addView(title)
        val detail = host.text(
            "Укажи Telegram username. Бот подтвердит вход одноразовым кодом и подключит существующие профили.",
            13f,
            MUTED,
        ).apply { setLineSpacing(host.dp(3).toFloat(), 1f) }
        sheet.addView(detail)

        fun inputField(hintText: String, inputType: Int, maximumLength: Int): EditText =
            EditText(host).apply {
                hint = hintText
                setTextColor(TEXT)
                setHintTextColor(MUTED)
                textSize = 15f
                setPadding(host.dp(15), 0, host.dp(15), 0)
                background = host.rounded(SURFACE_2, 15f, LINE)
                this.inputType = inputType
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE
                filters = arrayOf(InputFilter.LengthFilter(maximumLength))
            }

        val usernameField = inputField("@username", InputType.TYPE_CLASS_TEXT, 33)
        val codeField = inputField("123456", InputType.TYPE_CLASS_NUMBER, 6).apply {
            gravity = Gravity.CENTER
            letterSpacing = .24f
        }
        sheet.addView(usernameField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(52),
        ).apply { topMargin = host.dp(18) })
        sheet.addView(codeField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(58),
        ).apply { topMargin = host.dp(14) })

        val status = host.text("", 12f, MUTED).apply {
            setPadding(host.dp(2), host.dp(11), host.dp(2), 0)
            setLineSpacing(host.dp(2).toFloat(), 1f)
        }
        sheet.addView(status)
        val openBot = host.button("Открыть ./c", secondary = true)
        sheet.addView(openBot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48),
        ).apply { topMargin = host.dp(13) })
        val primary = host.button("Продолжить")
        sheet.addView(primary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(50),
        ).apply { topMargin = host.dp(9) })
        usernameField.imeOptions = EditorInfo.IME_ACTION_GO
        usernameField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                primary.performClick()
                true
            } else false
        }

        var challenge: String? = null
        var botUrl: String? = null
        var needsBotStart = false
        var step = 0
        fun setStep(value: Int) {
            step = value
            usernameField.visibility = if (step == 0) View.VISIBLE else View.GONE
            codeField.visibility = if (step == 1) View.VISIBLE else View.GONE
            openBot.visibility = if (step == 1 && needsBotStart) View.VISIBLE else View.GONE
            primary.text = host.uiCopy(when (step) {
                0 -> "Получить код"
                1 -> "Подтвердить код"
                else -> "Готово"
            })
            title.text = host.uiCopy(when (step) {
                0 -> "Добавить приложение"
                1 -> if (needsBotStart) "Сначала открой ./c" else "Код отправлен"
                else -> "Аккаунт подключён"
            })
            detail.text = host.uiCopy(when (step) {
                0 -> "Укажи Telegram username. Бот подтвердит вход одноразовым кодом и подключит существующие профили."
                1 -> if (needsBotStart) {
                    "Этот аккаунт ещё не начинал чат с ботом. Открой ./c один раз — код придёт сюда."
                } else {
                    "Код уже отправлен в Telegram. Оставайся в приложении и введи его ниже."
                }
                else -> ""
            })
        }
        fun openBotLink() {
            val link = botUrl ?: return
            runCatching {
                val uri = Uri.parse(link)
                check(uri.scheme?.lowercase() in setOf("https", "tg")) { "Unsupported URL scheme" }
                host.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
                .onFailure { status.text = host.uiCopy("Ссылка готова. Открой бота ещё раз кнопкой ниже.") }
        }

        openBot.setOnClickListener { openBotLink() }
        primary.setOnClickListener {
            when (step) {
                0 -> {
                    val username = usernameField.text.toString().trim()
                    if (!Regex("@?[A-Za-z0-9_]{5,32}").matches(username)) {
                        status.text = host.uiCopy("Введи username Telegram длиной от 5 до 32 знаков.")
                        status.setTextColor(DeyttUi.CORAL)
                        return@setOnClickListener
                    }
                    primary.isEnabled = false
                    primary.text = host.uiCopy("Создаём ссылку…")
                    status.setTextColor(BLUE)
                    status.text = host.uiCopy("Подготавливаем одноразовое подтверждение.")
                    pairingExecutor.execute {
                        val result = runCatching { TelegramPairingClient.start(username) }
                        host.runOnUiThread {
                            if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                            primary.isEnabled = true
                            result.onSuccess {
                                challenge = it.challenge
                                botUrl = it.botUrl
                                needsBotStart = it.delivery != "sent"
                                setStep(1)
                                status.setTextColor(BLUE)
                                status.text = host.uiCopy(when (it.delivery) {
                                    "sent" -> "Код отправлен в Telegram и действует 5 минут. Переключаться в бот не нужно."
                                    "delivery_failed" -> "Не получилось доставить сообщение. Открой ./c ниже, чтобы получить код."
                                    else -> "Этого аккаунта пока нет в боте. Открой ./c ниже один раз — код придёт в чат."
                                })
                            }.onFailure {
                                val reference = SubscriptionLoadDiagnostics.record(host, "pair", it)
                                primary.text = host.uiCopy("Продолжить")
                                status.setTextColor(DeyttUi.CORAL)
                                status.text = "${host.uiCopy(pairingError(it))} ${host.uiCopy("код")}: $reference"
                            }
                        }
                    }
                }
                1 -> {
                    val currentChallenge = challenge
                    val code = codeField.text.toString().trim()
                    if (currentChallenge == null || !Regex("\\d{6}").matches(code)) {
                        status.text = host.uiCopy("Введи шесть цифр из сообщения бота.")
                        status.setTextColor(DeyttUi.CORAL)
                        return@setOnClickListener
                    }
                    primary.isEnabled = false
                    primary.text = host.uiCopy("Проверяем код…")
                    status.text = host.uiCopy("Подтверждаем Telegram-аккаунт и загружаем профили.")
                    status.setTextColor(BLUE)
                    pairingExecutor.execute {
                        val result = runCatching {
                            val session = TelegramPairingClient.verify(currentChallenge, code)
                            TelegramSessionStore.save(host, session.token)
                            var importedCount: Int? = null
                            var importFailed = false
                            runCatching {
                                val subscriptionUrl = TelegramPairingClient.subscriptionUrl(session.token)
                                if (subscriptionUrl != null) {
                                    val currentRouteId = SelectedRouteStore(host).read().id
                                    val imported = SubscriptionClient.import(host, subscriptionUrl)
                                    SubscriptionClient.saveSubscriptionUrl(host, imported.url)
                                    val routes = RouteCatalog.from(
                                        SubscriptionStore(host).readCurrent().orEmpty(),
                                        AwgProfileStore(host).profiles(),
                                        OpenFluxProfileStore(host).profiles(host),
                                    )
                                    if (routes.none { it.id == currentRouteId }) {
                                        routes.firstOrNull()?.let { SelectedRouteStore(host).save(it) }
                                    }
                                    importedCount = routes.size
                                }
                            }.onFailure { importFailed = true }
                            session to when {
                                importFailed -> "Аккаунт подключён. Не удалось обновить профили — повтори вход позже."
                                importedCount != null -> "Загружено маршрутов: $importedCount. Текущий туннель не прерывался."
                                else -> "Аккаунт подключён. Активной подписки для импорта пока нет."
                            }
                        }
                        host.runOnUiThread {
                            if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                            primary.isEnabled = true
                            result.onSuccess { (session, message) ->
                                status.setTextColor(DeyttUi.MINT)
                                status.text = host.uiCopy(message)
                                setStep(2)
                                host.updateTelegramUsername(session.username)
                                host.refreshTelegramAccount()
                                host.refreshAccountViews()
                            }.onFailure {
                                val reference = SubscriptionLoadDiagnostics.record(host, "pair", it)
                                primary.text = host.uiCopy("Подтвердить код")
                                status.setTextColor(DeyttUi.CORAL)
                                status.text = "${host.uiCopy(pairingError(it))} ${host.uiCopy("код")}: $reference"
                            }
                        }
                    }
                }
                else -> dialog.dismiss()
            }
        }
        setStep(0)
        BottomSheetHelper.show(host, dialog, sheet)
    }

    fun showLinkedTelegramAccount() {
        val token = TelegramSessionStore.read(host) ?: run {
            showTelegramPairing()
            return
        }
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        sheet.addView(host.mono("./c · TELEGRAM", 9f, MUTED, 650))
        sheet.addView(host.text(copy("Аккаунт подключён", "Account connected"), 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(7))
        })
        sheet.addView(host.text(
            copy("Профиль и сессия связаны с Telegram. Переподключение загрузит свежие профили; отключение завершит эту сессию.", "Your profile and session are linked to Telegram. Reconnect to refresh profiles, or disconnect to end this account session."),
            13f, MUTED,
        ).apply { setLineSpacing(host.dp(3).toFloat(), 1f) })
        sheet.addView(host.button(copy("Переподключить", "Reconnect"), secondary = true).apply {
            setOnClickListener {
                dialog.dismiss()
                showTelegramPairing()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
            topMargin = host.dp(14)
        })
        sheet.addView(host.button(copy("Отключить аккаунт", "Disconnect account"), secondary = true).apply {
            setOnClickListener {
                dialog.dismiss()
                confirmTelegramLogout(token)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
            topMargin = host.dp(8)
        })
        BottomSheetHelper.show(host, dialog, sheet)
    }

    private fun confirmTelegramLogout(token: String) {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        sheet.addView(host.mono("./c · СЕССИЯ", 9f, MUTED, 650))
        sheet.addView(host.text("Отключить Telegram?", 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(7))
        })
        sheet.addView(host.text("Приложение перестанет получать профиль и обновления ключей.", 13f, MUTED))
        val status = host.text("", 12f, DeyttUi.CORAL).apply { setPadding(0, host.dp(10), 0, 0) }
        sheet.addView(status)
        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, host.dp(14), 0, 0)
        }
        val cancel = host.button("Отмена", secondary = true).apply { setOnClickListener { dialog.dismiss() } }
        actions.addView(cancel, LinearLayout.LayoutParams(0, host.dp(48), 1f).apply { marginEnd = host.dp(8) })
        val unlink = host.button("Отключить")
        actions.addView(unlink, LinearLayout.LayoutParams(0, host.dp(48), 1f))
        unlink.setOnClickListener {
            unlink.isEnabled = false
            unlink.text = host.uiCopy("Завершаем…")
            pairingExecutor.execute {
                val result = runCatching { TelegramPairingClient.logout(token) }
                host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    result.onSuccess {
                        TelegramSessionStore.clear(host)
                        dialog.dismiss()
                        host.updateTelegramIdentity(null, null)
                        host.refreshTelegramAccount()
                        host.refreshAccountViews()
                    }.onFailure {
                        unlink.isEnabled = true
                        unlink.text = host.uiCopy("Повторить")
                        status.text = host.uiCopy("Не удалось отозвать сессию. Проверь соединение и повтори.")
                    }
                }
            }
        }
        sheet.addView(actions)
        BottomSheetHelper.show(host, dialog, sheet)
    }

    private fun pairingError(error: Throwable): String = when ((error as? TelegramPairingException)?.code) {
        "invalid_username" -> "Проверь username Telegram."
        "pairing_rate_limited", "rate_limited" -> "Слишком частые попытки. Подожди немного и повтори."
        "pair_code_locked" -> "Лимит попыток исчерпан. Создай новую ссылку."
        "pair_code_invalid" -> "Код неверный или уже истёк. Проверь Telegram или создай новую ссылку."
        "account_blocked", "blocked" -> "Для этого аккаунта вход недоступен. Напиши в поддержку."
        else -> "Не удалось завершить вход. Проверь соединение и попробуй ещё раз."
    }

    fun close() {
        pairingExecutor.shutdownNow()
    }
}
