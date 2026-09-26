package space.deytt.connect

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.AMBER
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.SURFACE
import space.deytt.connect.DeyttUi.SURFACE_2
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

internal class ProfileFlows(
    private val host: MainActivity,
    private val onPairAccount: () -> Unit,
    private val onManageAccount: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var subscriptionText: TextView? = null
    private var usageText: TextView? = null
    private var deviceSummaryText: TextView? = null
    private var happDevices: LinearLayout? = null
    private var paymentStatusText: TextView? = null
    private var pendingPaymentId: String? = null
    private var refreshGeneration = 0

    fun close() {
        executor.shutdownNow()
    }

    fun page(): View {
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("аккаунт · подписка · устройства", "Профиль"))
        root.addView(spacer(12, host))
        root.addView(host.sectionLabel("аккаунт"))
        val token = TelegramSessionStore.read(host)
        root.addView(host.row(
            if (token == null) "Подключить Telegram" else "Telegram подключён",
            if (token == null) "Войти по username, чтобы открыть подписку и устройства"
            else "Профиль привязан к этому устройству",
            if (token == null) "↗" else "✓",
            if (token == null) "подключить" else "управлять",
            emphasis = token != null,
        ).apply {
            setOnClickListener { if (TelegramSessionStore.read(host) == null) onPairAccount() else onManageAccount() }
        })

        root.addView(spacer(18, host))
        root.addView(host.sectionLabel("подписка"))
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(18), host.dp(16), host.dp(18), host.dp(16))
            background = host.rounded(SURFACE, 20f, LINE)
        }
        subscriptionText = host.text(
            if (token == null) "Подключите Telegram, чтобы загрузить тариф и срок действия."
            else "Загружаем данные подписки…",
            14f,
            if (token == null) MUTED else TEXT,
            if (token == null) Typeface.NORMAL else Typeface.BOLD,
        ).apply { maxLines = 2 }
        usageText = host.text("", 12f, MUTED).apply { setPadding(0, host.dp(6), 0, 0) }
        card.addView(subscriptionText)
        card.addView(usageText)
        paymentStatusText = host.text("", 12f, AMBER).apply {
            visibility = View.GONE
            setPadding(0, host.dp(8), 0, 0)
            setOnClickListener { checkPaymentStatus() }
        }
        card.addView(paymentStatusText)
        root.addView(card)
        root.addView(host.row("Тарифы и оплата", "Сумму подтвердит сервер перед оформлением", "₽", "открыть").apply {
            setOnClickListener { showPlanPicker() }
        })

        root.addView(spacer(18, host))
        root.addView(host.sectionLabel("устройства и сессии"))
        deviceSummaryText = host.text(
            if (token == null) "Подключите аккаунт, чтобы увидеть устройства."
            else "Загружаем список…",
            12f,
            MUTED,
        ).apply { setPadding(host.dp(2), host.dp(4), host.dp(2), host.dp(8)) }
        root.addView(deviceSummaryText)
        happDevices = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(happDevices)
        root.addView(host.row("Сбросить ключи", "Отозвать действующие ключи выбранного типа", "↻", "управлять").apply {
            setOnClickListener { showResetChoices() }
        })
        root.addView(host.note(
            "Список AWG-конфигураций не отображается. Здесь видны только общие счётчики и устройства Happ; сброс требует отдельного подтверждения.",
            MUTED,
        ))

        root.addView(spacer(18, host))
        root.addView(host.sectionLabel("помощь и документы"))
        root.addView(host.row("Поддержка", "Диалог с командой DEYTT", "↗", "открыть").apply {
            setOnClickListener { showSupportChat() }
        })
        root.addView(host.row("Условия использования", "deytt.space/terms", "↗", "открыть").apply {
            setOnClickListener { openHttps("https://deytt.space/terms/") }
        })
        root.addView(host.row("Политика конфиденциальности", "deytt.space/privacy", "↗", "открыть").apply {
            setOnClickListener { openHttps("https://deytt.space/privacy/") }
        })
        root.addView(spacer(20, host))

        if (token != null) refreshProfile()
        return ScrollView(host).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun refreshProfile() {
        val token = TelegramSessionStore.read(host) ?: return
        val generation = ++refreshGeneration
        executor.execute {
            runCatching {
                TelegramPairingClient.accountSnapshot(token) to TelegramPairingClient.keys(token)
            }.onSuccess { (snapshot, keys) ->
                host.runOnUiThread {
                    if (generation != refreshGeneration || host.isFinishing || host.isDestroyed) return@runOnUiThread
                    val profile = snapshot.optJSONObject("profile")
                    val subscription = profile?.optJSONObject("subscription")
                    val devices = snapshot.optJSONObject("devices")
                    if (profile == null || subscription == null) {
                        subscriptionText?.text = host.uiCopy("Не удалось загрузить подписку.")
                        return@runOnUiThread
                    }
                    val tariff = subscription.optString("tariff_name").takeIf(String::isNotBlank)
                        ?: if (subscription.optBoolean("active")) host.uiCopy("Активный доступ") else host.uiCopy("Нет активной подписки")
                    val expiry = when {
                        subscription.optBoolean("unlimited_time") -> host.uiCopy("без срока")
                        !subscription.isNull("expires_at") -> subscription.optString("expires_at").substringBefore('T')
                        else -> host.uiCopy("срок не указан")
                    }
                    subscriptionText?.text = "$tariff · $expiry"
                    val used = subscription.optLong("traffic_used_bytes", 0L).coerceAtLeast(0L)
                    val limit = if (subscription.isNull("traffic_limit_bytes")) 0L
                        else subscription.optLong("traffic_limit_bytes", 0L).coerceAtLeast(0L)
                    usageText?.text = if (limit > 0L) {
                        host.uiCopy("Использовано ${formatBytes(used)} из ${formatBytes(limit)}")
                    } else host.uiCopy("Передано ${formatBytes(used)} · без установленного лимита")
                    val awgCount = devices?.optInt("awg", 0)?.coerceAtLeast(0) ?: 0
                    val happCount = devices?.optInt("happ_devices", 0)?.coerceAtLeast(0) ?: 0
                    deviceSummaryText?.text = host.uiCopy("AmneziaWG: $awgCount · Happ: $happCount")
                    renderHappDevices(keys.optJSONObject("happ")?.optJSONArray("devices"))
                }
            }.onFailure {
                host.runOnUiThread {
                    if (generation == refreshGeneration && !host.isFinishing && !host.isDestroyed) {
                        subscriptionText?.text = host.uiCopy("Не удалось загрузить данные аккаунта.")
                        deviceSummaryText?.text = host.uiCopy("Проверьте подключение и откройте профиль снова.")
                    }
                }
            }
        }
    }

    private fun renderHappDevices(devices: JSONArray?) {
        val container = happDevices ?: return
        container.removeAllViews()
        for (index in 0 until (devices?.length() ?: 0)) {
            val device = devices?.optJSONObject(index) ?: continue
            val model = device.optString("model").ifBlank { host.uiCopy("Устройство Happ") }
            val os = listOf(device.optString("os"), device.optString("os_version"))
                .filter(String::isNotBlank).joinToString(" ")
            val lastSeen = device.optString("last_seen").takeIf(String::isNotBlank)?.substringBefore('T')
            val detail = listOf(os, lastSeen).filterNotNull().filter(String::isNotBlank).joinToString(" · ")
            container.addView(host.row(model.take(64), detail, "H", "" , interactive = false))
        }
        if (container.childCount == 0) {
            container.addView(host.note("Пока нет зарегистрированных устройств Happ.", MUTED))
        }
    }

    private fun showResetChoices() {
        if (TelegramSessionStore.read(host) == null) {
            onPairAccount()
            return
        }
        AlertDialog.Builder(host)
            .setTitle(host.uiCopy("Что сбросить?"))
            .setItems(arrayOf(host.uiCopy("Все ключи"), "AmneziaWG", "Happ")) { _, index ->
                val scope = listOf("all", "awg", "happ")[index]
                val label = listOf(host.uiCopy("все ключи"), "AmneziaWG", "Happ")[index]
                AlertDialog.Builder(host)
                    .setTitle(host.uiCopy("Сбросить $label?"))
                    .setMessage(host.uiCopy("Действие отзовёт текущие ключи $label и может временно отключить устройства. Продолжить?"))
                    .setNegativeButton(host.uiCopy("Отмена"), null)
                    .setPositiveButton(host.uiCopy("Сбросить")) { _, _ -> resetKeys(scope) }
                    .show()
            }
            .setNegativeButton(host.uiCopy("Отмена"), null)
            .show()
    }

    private fun resetKeys(scope: String) {
        val token = TelegramSessionStore.read(host) ?: return
        subscriptionText?.text = host.uiCopy("Сбрасываем ключи…")
        executor.execute {
            runCatching { TelegramPairingClient.resetKeys(token, scope) }
                .onSuccess {
                    host.runOnUiThread {
                        if (!host.isFinishing && !host.isDestroyed) {
                            subscriptionText?.text = host.uiCopy("Ключи сброшены. Обновляем аккаунт…")
                            refreshProfile()
                        }
                    }
                }
                .onFailure { failure -> host.runOnUiThread {
                    if (!host.isFinishing && !host.isDestroyed) {
                        subscriptionText?.text = host.uiCopy(apiError(failure))
                    }
                } }
        }
    }

    private fun showPlanPicker() {
        val token = TelegramSessionStore.read(host) ?: run { onPairAccount(); return }
        subscriptionText?.text = host.uiCopy("Загружаем доступные планы…")
        executor.execute {
            runCatching {
                val snapshot = TelegramPairingClient.accountSnapshot(token)
                val tariffs = TelegramPairingClient.tariffs().optJSONArray("tariffs") ?: JSONArray()
                val profile = snapshot.optJSONObject("profile")
                val active = profile?.optJSONObject("subscription")?.optBoolean("paid_active") == true
                active to tariffs
            }.onSuccess { (active, tariffs) -> host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                if (active) {
                    val months = listOf(1, 3, 6, 12)
                    AlertDialog.Builder(host)
                        .setTitle(host.uiCopy("Продлить подписку"))
                        .setItems(months.map { host.uiCopy("$it мес.") }.toTypedArray()) { _, index ->
                            requestQuote(token, "add_time", null, months[index])
                        }
                        .setNegativeButton(host.uiCopy("Отмена"), null)
                        .show()
                    return@runOnUiThread
                }
                val options = (0 until tariffs.length()).mapNotNull { tariffs.optJSONObject(it) }
                if (options.isEmpty()) {
                    subscriptionText?.text = host.uiCopy("Сейчас планы недоступны. Попробуйте позже.")
                    return@runOnUiThread
                }
                val labels = options.map { plan ->
                    val name = plan.optString("name").ifBlank { plan.optString("code") }
                    val devices = plan.optInt("devices", 0)
                    val months = plan.optInt("months", 0)
                    val rubles = plan.optInt("rubles", 0)
                    host.uiCopy("$name · $devices устройств · $months мес. · $rubles ₽")
                }.toTypedArray()
                AlertDialog.Builder(host)
                    .setTitle(host.uiCopy("Выберите план"))
                    .setItems(labels) { _, index -> requestQuote(token, "preset", options[index].optString("code"), null) }
                    .setNegativeButton(host.uiCopy("Отмена"), null)
                    .show()
            } }.onFailure { failure -> host.runOnUiThread {
                if (!host.isFinishing && !host.isDestroyed) subscriptionText?.text = host.uiCopy(apiError(failure))
            } }
        }
    }

    private fun requestQuote(token: String, kind: String, plan: String?, months: Int?) {
        executor.execute {
            runCatching { TelegramPairingClient.quote(token, kind, plan = plan, months = months) }
                .onSuccess { quote -> host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    val name = quote.optString("name").ifBlank { plan ?: host.uiCopy("Подписка") }
                    val detail = host.uiCopy(
                        "$name · ${quote.optInt("devices")} устройств · ${quote.optInt("months")} мес.\n" +
                            "${quote.optInt("rubles")} ₽ · ⭐ ${quote.optInt("stars")}",
                    )
                    AlertDialog.Builder(host)
                        .setTitle(host.uiCopy("Подтвердите сумму"))
                        .setMessage(detail)
                        .setNegativeButton(host.uiCopy("Отмена"), null)
                        .setNeutralButton(host.uiCopy("Telegram Stars")) { _, _ -> createCheckout(token, kind, plan, months, "stars") }
                        .setPositiveButton(host.uiCopy("Оплатить картой")) { _, _ -> createCheckout(token, kind, plan, months, "platega") }
                        .show()
                } }
                .onFailure { failure -> host.runOnUiThread {
                    if (!host.isFinishing && !host.isDestroyed) subscriptionText?.text = host.uiCopy(apiError(failure))
                } }
        }
    }

    private fun createCheckout(token: String, kind: String, plan: String?, months: Int?, method: String) {
        subscriptionText?.text = host.uiCopy("Готовим безопасный счёт…")
        executor.execute {
            runCatching { TelegramPairingClient.checkout(token, kind, method, plan, months) }
                .onSuccess { result -> host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    val url = if (method == "stars") result.optString("invoice_url") else result.optString("pay_url")
                    val externalId = result.optString("external_id").takeIf(String::isNotBlank)
                    pendingPaymentId = externalId
                    paymentStatusText?.apply {
                        visibility = if (externalId == null) View.GONE else View.VISIBLE
                        text = host.uiCopy("Платёж ожидает подтверждения · нажмите, чтобы проверить")
                    }
                    AlertDialog.Builder(host)
                        .setTitle(host.uiCopy("Счёт готов"))
                        .setMessage(host.uiCopy("Откройте защищённую страницу оплаты. Приложение не запрашивает данные карты."))
                        .setNegativeButton(host.uiCopy("Позже"), null)
                        .setNeutralButton(host.uiCopy("Проверить статус")) { _, _ -> checkPaymentStatus() }
                        .setPositiveButton(host.uiCopy("Открыть оплату")) { _, _ ->
                            if (isHttpsUrl(url)) openHttps(url)
                            else subscriptionText?.let { it.text = host.uiCopy("Платёжная ссылка недоступна.") }
                        }
                        .show()
                } }
                .onFailure { failure -> host.runOnUiThread {
                    if (!host.isFinishing && !host.isDestroyed) subscriptionText?.text = host.uiCopy(apiError(failure))
                } }
        }
    }

    private fun checkPaymentStatus() {
        val token = TelegramSessionStore.read(host) ?: return
        val externalId = pendingPaymentId ?: return
        paymentStatusText?.text = host.uiCopy("Проверяем платёж…")
        executor.execute {
            runCatching { TelegramPairingClient.paymentStatus(token, externalId).optString("status") }
                .onSuccess { status -> host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                    if (status == "paid") {
                        paymentStatusText?.apply { text = host.uiCopy("Оплата подтверждена"); setTextColor(DeyttUi.MINT) }
                        refreshProfile()
                    } else paymentStatusText?.text = host.uiCopy("Платёж ещё не подтверждён. Проверьте позже.")
                } }
                .onFailure { failure -> host.runOnUiThread {
                    if (!host.isFinishing && !host.isDestroyed) paymentStatusText?.text = host.uiCopy(apiError(failure))
                } }
        }
    }

    private fun showSupportChat() {
        val token = TelegramSessionStore.read(host) ?: run { onPairAccount(); return }
        val dialog = Dialog(host)
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(20), host.dp(18), host.dp(20), host.dp(14))
            background = host.rounded(SURFACE, 24f, LINE)
        }
        root.addView(host.mono("DEYTT · SUPPORT", 9f, MUTED, 650))
        root.addView(host.text("Поддержка", 21f, TEXT, Typeface.BOLD).apply { setPadding(0, host.dp(8), 0, host.dp(4)) })
        val status = host.text("Загружаем переписку…", 12f, MUTED).apply { setPadding(0, 0, 0, host.dp(10)) }
        root.addView(status)
        val messageList = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val messageScroll = ScrollView(host).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(messageList)
        }
        val maxHeight = (host.resources.displayMetrics.heightPixels / host.resources.displayMetrics.density * .42f).toInt().coerceIn(220, 380)
        root.addView(messageScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(maxHeight)))
        val input = EditText(host).apply {
            hint = host.uiCopy("Сообщение команде")
            minLines = 2
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(4000))
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
            background = host.rounded(SURFACE_2, 14f, LINE)
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(10)
        })
        val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val closeTicket = host.button("Закрыть обращение", secondary = true).apply { visibility = View.GONE }
        val send = host.button("Отправить")
        actions.addView(closeTicket, LinearLayout.LayoutParams(0, host.dp(46), 1f).apply { marginEnd = host.dp(7) })
        actions.addView(send, LinearLayout.LayoutParams(0, host.dp(46), 1f))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(8)
        })

        var ticketId: Int? = null
        var ticketOpen = false
        var loading = false
        val handler = Handler(Looper.getMainLooper())
        fun setStatus(value: String, error: Boolean = false) {
            status.text = host.uiCopy(value)
            status.setTextColor(if (error) DeyttUi.CORAL else MUTED)
        }
        fun render(payload: JSONObject) {
            val ticket = payload.optJSONObject("ticket")
            ticketId = if (ticket == null) null else ticket.optInt("id").takeIf { it > 0 }
            ticketOpen = ticket?.optString("status") == "open"
            closeTicket.visibility = if (ticketOpen) View.VISIBLE else View.GONE
            setStatus(when {
                ticket == null -> "Новое обращение создастся после первого сообщения."
                ticketOpen -> "Обращение №${ticketId} · открыто"
                else -> "Обращение закрыто. Новое сообщение откроет новое обращение."
            })
            messageList.removeAllViews()
            val messages = payload.optJSONArray("messages") ?: JSONArray()
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val fromSupport = message.optString("sender") == "support"
                val bubble = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(host.dp(12), host.dp(9), host.dp(12), host.dp(9))
                    background = host.rounded(if (fromSupport) SURFACE_2 else SURFACE, 14f, LINE)
                }
                bubble.addView(host.mono(if (fromSupport) "DEYTT" else "ВЫ", 8f, MUTED, 600))
                bubble.addView(host.text(message.optString("text"), 13f, TEXT).apply {
                    setPadding(0, host.dp(4), 0, 0)
                    setTextIsSelectable(true)
                })
                messageList.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = host.dp(7)
                })
            }
            messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
        }
        fun refresh() {
            if (!dialog.isShowing || loading) return
            loading = true
            executor.execute {
                runCatching { TelegramPairingClient.supportThread(token) }
                    .onSuccess { payload -> host.runOnUiThread {
                        loading = false
                        if (dialog.isShowing && !host.isFinishing && !host.isDestroyed) render(payload)
                    } }
                    .onFailure { failure -> host.runOnUiThread {
                        loading = false
                        if (dialog.isShowing && !host.isFinishing && !host.isDestroyed) setStatus(apiError(failure), true)
                    } }
            }
        }
        val poll = object : Runnable {
            override fun run() {
                if (dialog.isShowing) {
                    refresh()
                    handler.postDelayed(this, SUPPORT_REFRESH_INTERVAL_MS)
                }
            }
        }
        send.setOnClickListener {
            val text = input.text?.toString().orEmpty().trim()
            if (text.length < 5) {
                setStatus("Напишите сообщение минимум из пяти символов.", true)
                return@setOnClickListener
            }
            if (loading) return@setOnClickListener
            loading = true
            send.isEnabled = false
            setStatus("Отправляем сообщение…")
            executor.execute {
                runCatching {
                    val currentTicket = ticketId
                    if (ticketOpen && currentTicket != null) TelegramPairingClient.sendSupportMessage(token, currentTicket, text)
                    else TelegramPairingClient.createSupportTicket(token, text)
                }.onSuccess { host.runOnUiThread {
                    loading = false
                    send.isEnabled = true
                    if (dialog.isShowing && !host.isFinishing && !host.isDestroyed) {
                        input.text?.clear()
                        refresh()
                    }
                } }.onFailure { failure -> host.runOnUiThread {
                    loading = false
                    send.isEnabled = true
                    if (dialog.isShowing && !host.isFinishing && !host.isDestroyed) setStatus(apiError(failure), true)
                } }
            }
        }
        closeTicket.setOnClickListener {
            val id = ticketId ?: return@setOnClickListener
            AlertDialog.Builder(host)
                .setTitle(host.uiCopy("Закрыть обращение?"))
                .setMessage(host.uiCopy("Новые ответы не будут приниматься. При необходимости вы сможете создать новое обращение."))
                .setNegativeButton(host.uiCopy("Отмена"), null)
                .setPositiveButton(host.uiCopy("Закрыть")) { _, _ ->
                    if (loading) return@setPositiveButton
                    loading = true
                    executor.execute {
                        runCatching { TelegramPairingClient.closeSupportTicket(token, id) }
                            .onSuccess { host.runOnUiThread { loading = false; refresh() } }
                            .onFailure { failure -> host.runOnUiThread {
                                loading = false
                                if (dialog.isShowing) setStatus(apiError(failure), true)
                            } }
                    }
                }
                .show()
        }
        showSheet(dialog, root)
        refresh()
        handler.postDelayed(poll, SUPPORT_REFRESH_INTERVAL_MS)
        dialog.setOnDismissListener { handler.removeCallbacks(poll) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(java.util.Locale.US, "%.1f kB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun apiError(failure: Throwable): String = when ((failure as? TelegramPairingException)?.code) {
        "rate_limited" -> "Слишком частые запросы. Повторите позже."
        "ticket_not_open" -> "Обращение закрыто. Обновите переписку."
        "subscription_active_extend_only" -> "Для активной подписки выберите продление."
        "channel_required" -> "Для этого действия требуется подключить канал DEYTT."
        else -> "Не удалось выполнить запрос. Проверьте подключение и повторите позже."
    }

    private fun isHttpsUrl(raw: String): Boolean = runCatching {
        val uri = Uri.parse(raw)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private fun openHttps(url: String) {
        if (!isHttpsUrl(url)) return
        runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun showSheet(dialog: Dialog, content: View) {
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(host.dp(14), 0, host.dp(14), host.dp(18))
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private companion object {
        const val SUPPORT_REFRESH_INTERVAL_MS = 15_000L
    }
}
