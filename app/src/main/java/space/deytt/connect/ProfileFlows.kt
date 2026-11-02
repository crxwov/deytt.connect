package space.deytt.connect

import android.app.Dialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.text.InputFilter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.WindowManager
import android.widget.ImageView
import android.widget.FrameLayout
import android.graphics.BitmapFactory
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
import space.deytt.connect.DeyttUi.glassRowBackground
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.prefersReducedMotion
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
    @Volatile private var closed = false
    private var pageToken: String? = null
    private var closeSupport: (() -> Unit)? = null
    private var identityName: TextView? = null
    private var identityHandle: TextView? = null
    private var identitySince: TextView? = null
    private var identityAvatar: ImageView? = null
    private var identityFallback: TextView? = null
    private var subscriptionText: TextView? = null
    private var usageText: TextView? = null
    private var quotaUsageBar: FrameLayout? = null
    private var quotaUsageFill: View? = null
    private var quotaUsageAnimator: ValueAnimator? = null
    private var historyPeak: TextView? = null
    private var historyChart: LinearLayout? = null
    private var historyAxis: LinearLayout? = null
    private var historyRows: LinearLayout? = null
    private var historyEmpty: TextView? = null
    private var historyNote: TextView? = null
    private var paymentAction: View? = null
    private var deviceSummaryText: TextView? = null
    private var happDevices: LinearLayout? = null
    private var paymentStatusText: TextView? = null
    private var actionStatus: TextView? = null
    private var appSessions: LinearLayout? = null
    private var supportUrl = "https://t.me/deyttbot"
    private var pendingPaymentId: String? = null
    private var refreshGeneration = 0
    private val deviceChangesInFlight = mutableSetOf<Long>()
    private var currentDeviceLimit = 1
    private var unlimitedTime = false
    private var unlimitedDevices = false

    fun close() {
        closed = true
        refreshGeneration++
        quotaUsageAnimator?.cancel()
        closeSupport?.invoke()
        closeSupport = null
        executor.shutdownNow()
    }

    private fun isCurrent(token: String): Boolean =
        !closed && !host.isFinishing && !host.isDestroyed && TelegramSessionStore.read(host) == token

    private fun executeFor(token: String, action: () -> Unit) {
        if (!isCurrent(token)) return
        executor.execute {
            if (isCurrent(token)) action()
        }
    }

    fun page(): View {
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("", "Профиль"))
        root.addView(spacer(8, host))
        val token = TelegramSessionStore.read(host)
        // Rebuilding the signed-out page must invalidate requests issued before logout too.
        refreshGeneration++
        if (pageToken != token) {
            pendingPaymentId = null
            closeSupport?.invoke()
            closeSupport = null
            pageToken = token
        }
        root.addView(identityCard(token))

        root.addView(spacer(12, host))
        root.addView(host.sectionLabel("подписка"))
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(14), host.dp(13), host.dp(14), host.dp(13))
            background = host.glassRowBackground(false, 20f)
            if (token != null) {
                isClickable = true
                isFocusable = true
                with(DeyttUi) {
                    foreground = host.ripple(20f)
                    host.pressFeedback(this@apply, .99f)
                }
                setOnClickListener {
                    subscriptionText?.text = host.uiCopy("обновляем подписку…")
                    refreshProfile()
                    host.refreshSubscriptionManually()
                }
            }
        }
        subscriptionText = host.text(
            if (token == null) "подключите telegram, чтобы загрузить тариф и срок действия."
            else "загружаем данные подписки…",
            14f,
            if (token == null) MUTED else TEXT,
            if (token == null) Typeface.NORMAL else Typeface.BOLD,
        ).apply { maxLines = 2 }
        usageText = host.text("", 12f, MUTED).apply { setPadding(0, host.dp(6), 0, 0) }
        val quotaBar = FrameLayout(host).apply {
            visibility = View.GONE
            background = host.rounded(0x402A3D51, 4f)
            clipToOutline = true
        }
        quotaUsageBar = quotaBar
        val quotaFill = View(host).apply {
            background = host.rounded(0xFF70C9FF.toInt(), 4f)
            pivotX = 0f
            scaleX = 0f
        }
        quotaUsageFill = quotaFill
        quotaBar.addView(quotaFill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(5), Gravity.START or Gravity.CENTER_VERTICAL))
        card.addView(subscriptionText)
        card.addView(usageText)
        card.addView(quotaBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(5)).apply {
            topMargin = host.dp(9)
        })
        paymentStatusText = host.text("", 12f, AMBER).apply {
            visibility = View.GONE
            setPadding(0, host.dp(8), 0, 0)
            setOnClickListener { checkPaymentStatus() }
        }
        card.addView(paymentStatusText)
        actionStatus = host.text("", 12f, MUTED).apply {
            visibility = View.GONE
            setPadding(0, host.dp(10), 0, 0)
        }
        card.addView(actionStatus)
        root.addView(card)
        root.addView(spacer(10, host))
        root.addView(trafficHistoryGroup())
        root.addView(spacer(8, host))
        paymentAction = host.row(
            copy("выбрать тариф", "choose a plan"),
            copy("тарифы и способы оплаты", "plans and payment methods"),
            "PAYMENTS_ICON",
            emphasis = true,
        ).apply {
            setOnClickListener { showPlanPicker() }
        }
        root.addView(paymentAction)

        root.addView(spacer(12, host))
        val devicesGroup = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        deviceSummaryText = host.text(
            if (token == null) "подключите аккаунт, чтобы увидеть устройства."
            else "загружаем список…",
            12f,
            MUTED,
        ).apply { setPadding(host.dp(2), host.dp(4), host.dp(2), host.dp(8)) }
        devicesGroup.addView(deviceSummaryText)
        happDevices = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        devicesGroup.addView(happDevices)
        appSessions = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        devicesGroup.addView(appSessions)
        devicesGroup.addView(host.row("сбросить ключи", "отозвать действующие ключи выбранного типа", "↻", "управлять").apply {
            setOnClickListener { showResetChoices() }
        })
        devicesGroup.addView(host.note(
            copy(
                "отзыв старого ключа блокирует новые подключения. активный happ-туннель может оставаться подключённым до переподключения. выход из сессии приложения отзывает только доступ к аккаунту.",
                "revoking an old key blocks new connections. an active happ tunnel may stay connected until it reconnects. ending an app session only removes account access.",
            ),
            MUTED,
        ))

        root.addView(accordion(copy("устройства и сессии", "devices and sessions"), copy("подключения и управление доступом", "connections and access"), devicesGroup))
        root.addView(spacer(10, host))
        root.addView(host.row(
            copy("поддержка", "support"),
            copy("написать команде в приложении", "message the team in the app"),
            "BOT_ICON",
            copy("написать", "chat"),
            emphasis = true,
        ).apply { setOnClickListener { showSupportChat() } })
        root.addView(spacer(8, host))
        val helpGroup = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        helpGroup.addView(host.row(
            copy("поддержка в telegram", "telegram support"),
            copy("открыть запасной канал", "open the external channel"),
            "TELEGRAM_ICON",
            "↗",
        ).apply { setOnClickListener { openHttps(supportUrl) } })
        helpGroup.addView(host.row("условия использования", copy("читать в приложении", "read in the app"), "↗", "открыть").apply {
            setOnClickListener { openDocument("terms") }
        })
        helpGroup.addView(host.row("политика конфиденциальности", copy("читать в приложении", "read in the app"), "↗", "открыть").apply {
            setOnClickListener { openDocument("privacy") }
        })
        root.addView(accordion(copy("документы и другие каналы", "documents and other channels"), copy("условия, конфиденциальность, telegram", "terms, privacy, telegram"), helpGroup))
        root.addView(spacer(20, host))

        if (token != null) refreshProfile()
        return ScrollView(host).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun trafficHistoryGroup(): View {
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val historyEmptyView = host.text(
            copy(
                "история начнёт собираться после обновления сервера. прошлые дни восстановить нельзя.",
                "history starts after the server is updated. earlier days cannot be recovered.",
            ),
            12f,
            MUTED,
        ).apply { setPadding(0, host.dp(6), 0, host.dp(5)) }
        historyEmpty = historyEmptyView
        val historyPeakView = host.text("", 13f, TEXT, Typeface.BOLD).apply {
            visibility = View.GONE
            setPadding(0, host.dp(6), 0, host.dp(10))
        }
        historyPeak = historyPeakView
        val historyChartView = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            visibility = View.GONE
        }
        historyChart = historyChartView
        val historyAxisView = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(host.text("", 10f, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            addView(host.text("", 10f, MUTED).apply { gravity = Gravity.END })
        }
        historyAxis = historyAxisView
        val historyRowsView = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, host.dp(8), 0, 0)
        }
        historyRows = historyRowsView
        val historyNoteView = host.text(
            copy(
                "общий vpn-расход; первый день может быть неполным. отдельный счётчик lte сервер пока не передаёт.",
                "total vpn usage; the first day may be partial. the server does not report lte whitelist usage separately.",
            ),
            11f,
            MUTED,
        ).apply {
            visibility = View.GONE
            setPadding(0, host.dp(10), 0, 0)
        }
        historyNote = historyNoteView
        content.addView(historyEmptyView)
        content.addView(historyPeakView)
        content.addView(historyChartView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(64)))
        content.addView(historyAxisView)
        content.addView(historyRowsView)
        content.addView(historyNoteView)
        return accordion(
            copy("расход по дням", "daily usage"),
            copy("история за последние 30 дней", "history for the last 30 days"),
            content,
        )
    }

    private fun renderTrafficHistory(subscription: JSONObject) {
        val chart = historyChart ?: return
        val rows = historyRows ?: return
        val days = subscription.optJSONArray("traffic_daily_history")
        val entries = (0 until (days?.length() ?: 0)).mapNotNull { index ->
            val item = days?.optJSONObject(index) ?: return@mapNotNull null
            val date = item.optString("date")
            val bytes = item.optLong("bytes", -1L)
            if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || bytes < 0L) null
            else date to bytes
        }.sortedBy { it.first }.takeLast(30)
        val empty = entries.isEmpty()
        historyEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
        historyPeak?.visibility = if (empty) View.GONE else View.VISIBLE
        historyAxis?.visibility = if (empty) View.GONE else View.VISIBLE
        chart.visibility = if (empty) View.GONE else View.VISIBLE
        rows.visibility = if (empty) View.GONE else View.VISIBLE
        historyNote?.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) return

        val peak = entries.maxBy { it.second }
        historyPeak?.text = copy("пик за день: ", "daily peak: ") +
            "${formatBytes(peak.second)} · ${displayDate("${peak.first}T00:00:00", false)}"
        chart.removeAllViews()
        val maxBytes = entries.maxOf { it.second }.coerceAtLeast(1L)
        entries.forEachIndexed { index, (date, bytes) ->
            val track = FrameLayout(host).apply {
                background = host.rounded(0x392D455E, 3f)
                clipToOutline = true
            }
            val fill = View(host).apply {
                background = host.rounded(
                    if (bytes == peak.second && peak.second > 0L) DeyttUi.MINT else DeyttUi.SKY,
                    3f,
                )
            }
            val height = (host.dp(56) * bytes.toDouble() / maxBytes).toInt().coerceAtLeast(host.dp(2))
            track.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))
            chart.addView(FrameLayout(host).apply {
                addView(track, FrameLayout.LayoutParams(host.dp(5), host.dp(58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                contentDescription = "${displayDate("${date}T00:00:00", false)} · ${formatBytes(bytes)}"
                post {
                    if (!host.prefersReducedMotion() &&
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
                    ) {
                        track.pivotY = track.height.toFloat()
                        track.scaleY = .35f
                        track.animate().scaleY(1f).setStartDelay(index * 2L).setDuration(170L).start()
                    }
                }
            }, LinearLayout.LayoutParams(0, host.dp(62), 1f))
        }
        historyAxis?.let { labels ->
            (labels.getChildAt(0) as? TextView)?.text = entries.first().first.substring(5)
            (labels.getChildAt(1) as? TextView)?.text = entries.last().first.substring(5)
            labels.visibility = View.VISIBLE
        }

        rows.removeAllViews()
        entries.asReversed().forEach { (date, bytes) ->
            val isPeak = bytes == peak.second && peak.second > 0L
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = host.dp(38)
                setPadding(host.dp(8), host.dp(4), host.dp(8), host.dp(4))
                if (isPeak) background = host.glassRowBackground(true, 12f)
                contentDescription = "${displayDate("${date}T00:00:00", false)} · ${formatBytes(bytes)}"
            }
            row.addView(host.text(displayDate("${date}T00:00:00", false), 12f, if (isPeak) DeyttUi.MINT else TEXT))
            row.addView(host.text(formatBytes(bytes), 12f, if (isPeak) DeyttUi.MINT else MUTED).apply {
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rows.addView(row)
            rows.addView(View(host).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, host.dp(1)))
        }
    }

    private fun identityCard(token: String?): View {
        TelegramIdentityCache.bind(token)
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(14))
            background = host.glassRowBackground(false, 20f)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (TelegramSessionStore.read(host) == null) onPairAccount() else onManageAccount() }
        }
        val identity = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val avatar = FrameLayout(host).apply { background = host.rounded(SURFACE_2, 32f); clipToOutline = true }
        identityFallback = host.text(TelegramIdentityCache.username?.take(1)?.uppercase() ?: "•", 24f, TEXT, Typeface.BOLD).apply { gravity = Gravity.CENTER }
        identityAvatar = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        avatar.addView(identityFallback, FrameLayout.LayoutParams(-1, -1))
        avatar.addView(identityAvatar, FrameLayout.LayoutParams(-1, -1))
        identity.addView(avatar, LinearLayout.LayoutParams(host.dp(60), host.dp(60)).apply { marginEnd = host.dp(14) })
        val words = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        identityName = host.text(if (token == null) host.uiCopy("подключить telegram") else TelegramIdentityCache.username ?: "telegram", 19f, TEXT, Typeface.BOLD)
        identityHandle = host.text(if (token == null) copy("войти в аккаунт", "sign in") else copy("загружаем профиль…", "loading profile…"), 12f, MUTED)
        words.addView(identityName)
        words.addView(identityHandle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(4) })
        identity.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        identity.addView(host.text("›", 22f, MUTED))
        card.addView(identity)
        identitySince = host.text("", 12f, MUTED).apply { visibility = View.GONE; setPadding(0, host.dp(13), 0, 0) }
        card.addView(identitySince)
        renderIdentityAvatar()
        return card
    }

    private fun renderIdentityAvatar() {
        val bitmap = TelegramIdentityCache.avatar
        identityAvatar?.setImageBitmap(bitmap)
        identityAvatar?.visibility = if (bitmap == null) View.GONE else View.VISIBLE
        identityFallback?.visibility = if (bitmap == null) View.VISIBLE else View.GONE
    }

    private fun renderIdentity(profile: JSONObject) {
        val username = profile.optString("username").takeIf { it.isNotBlank() && it != "null" }
        val name = listOf(profile.optString("first_name"), profile.optString("last_name"))
            .filter { it.isNotBlank() && it != "null" }.joinToString(" ")
        identityName?.text = name.ifBlank { username ?: "telegram" }
        identityHandle?.text = username?.let { "@$it" } ?: copy("telegram подключён", "telegram connected")
        identityFallback?.text = (name.ifBlank { username ?: "•" }).take(1).uppercase()
        val since = profile.optString("registered_at").takeIf { it.isNotBlank() && it != "null" }
        val days = profile.optLong("service_days", -1L)
        identitySince?.apply {
            visibility = if (since == null) View.GONE else View.VISIBLE
            text = if (since == null) "" else copy("с нами с ", "with us since ") + displayDate(since, withTime = false) +
                if (days >= 0) " · " + serviceDaysLabel(days) else ""
        }
        renderIdentityAvatar()
    }

    private fun serviceDaysLabel(days: Long): String {
        val ending = when {
            days % 100 in 11L..14L -> "дней"
            days % 10 == 1L -> "день"
            days % 10 in 2L..4L -> "дня"
            else -> "дней"
        }
        return copy("$days $ending в сервисе", "$days " + if (days == 1L) "day with deytt" else "days with deytt")
    }

    private fun accordion(title: String, subtitle: String, content: LinearLayout): View {
        val group = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = host.glassRowBackground(false, 18f)
            clipToOutline = true
            layoutTransition = android.animation.LayoutTransition().apply {
                enableTransitionType(android.animation.LayoutTransition.CHANGING)
                setDuration(190L)
            }
        }
        content.visibility = View.GONE
        var expanded = false
        val indicator = host.text("+", 20f, MUTED).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val toggle = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = host.dp(58)
            setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8))
            background = ColorDrawable(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            host.pressFeedback(this, .99f)
            val labels = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                addView(host.text(title, 14f, TEXT, Typeface.BOLD))
                addView(host.text(subtitle, 11f, MUTED).apply { setPadding(0, host.dp(3), host.dp(8), 0) })
            }
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            addView(indicator, LinearLayout.LayoutParams(host.dp(28), host.dp(32)))
            contentDescription = "$title · " + copy("свернуто", "collapsed")
            setOnClickListener {
                expanded = !expanded
                indicator.text = if (expanded) "−" else "+"
                contentDescription = "$title · " + if (expanded) copy("развернуто", "expanded") else copy("свернуто", "collapsed")
                announceForAccessibility(contentDescription)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                content.visibility = if (expanded) View.VISIBLE else View.GONE
                (content.layoutParams as? LinearLayout.LayoutParams)?.let { p ->
                    p.height = if (expanded) ViewGroup.LayoutParams.WRAP_CONTENT else 0
                    content.layoutParams = p
                }
            }
        }
        group.addView(toggle)
        content.setPadding(host.dp(14), 0, host.dp(14), host.dp(10))
        group.addView(content)
        return group
    }

    private fun openDocument(section: String) {
        host.startActivity(Intent(host, NativeDocumentActivity::class.java).putExtra(NativeDocumentActivity.EXTRA_SECTION, section))
    }

    private fun refreshProfile() {
        val token = TelegramSessionStore.read(host) ?: return
        val generation = ++refreshGeneration
        actionStatus?.visibility = View.GONE
        executeFor(token) {
            runCatching {
                val snapshot = TelegramPairingClient.accountSnapshot(token)
                if (TelegramIdentityCache.avatar == null) {
                    runCatching { TelegramPairingClient.avatar(token) }.getOrNull()?.let { bytes ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) {
                            val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 256).coerceAtLeast(1) }
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            if (isCurrent(token)) TelegramIdentityCache.update(token, null, bitmap)
                        }
                    }
                }
                snapshot to TelegramPairingClient.keys(token)
            }.onSuccess { (snapshot, keys) ->
                host.runOnUiThread {
                    if (generation != refreshGeneration || !isCurrent(token)) return@runOnUiThread
                    val profile = snapshot.optJSONObject("profile")
                    val subscription = profile?.optJSONObject("subscription")
                    val devices = snapshot.optJSONObject("devices")
                    if (profile == null || subscription == null) {
                        subscriptionText?.text = host.uiCopy("не удалось загрузить подписку.")
                        return@runOnUiThread
                    }
                    renderIdentity(profile)
                    currentDeviceLimit = subscription.optInt("device_limit", 1).coerceAtLeast(1)
                    unlimitedDevices = subscription.isNull("device_limit")
                    unlimitedTime = subscription.optBoolean("unlimited_time")
                    val tariff = subscription.optString("tariff_name").takeIf(String::isNotBlank)
                        ?: if (subscription.optBoolean("active")) host.uiCopy("активный доступ") else host.uiCopy("нет активной подписки")
                    val expiry = when {
                        subscription.optBoolean("unlimited_time") -> host.uiCopy("без срока")
                        !subscription.isNull("expires_at") -> subscription.optString("expires_at").substringBefore('T')
                        else -> host.uiCopy("срок не указан")
                    }
                    subscriptionText?.text = "$tariff · $expiry"
                    val traffic = ProfileTraffic.from(subscription)
                    val used = traffic.totalBytes
                    val limit = traffic.quotaLimitBytes
                    usageText?.text = if (limit > 0L) {
                        host.uiCopy("использовано ${formatBytes(traffic.quotaUsedBytes)} из ${formatBytes(limit)}") + "\n" + copy("всего передано", "total transferred") + " ${formatBytes(used)}"
                    } else host.uiCopy("передано ${formatBytes(used)} · без установленного лимита")
                    renderQuotaUsage(traffic.quotaUsedBytes, limit)
                    renderTrafficHistory(subscription)
                    val paymentLabels = (paymentAction as? ViewGroup)?.getChildAt(1) as? LinearLayout
                    (paymentLabels?.getChildAt(0) as? TextView)?.text = when {
                        !subscription.optBoolean("paid_active") -> copy("выбрать тариф", "choose a plan")
                        subscription.optBoolean("unlimited_time") -> copy("управление тарифом", "manage plan")
                        else -> copy("продлить подписку", "renew subscription")
                    }
                    (paymentLabels?.getChildAt(1) as? TextView)?.text = when {
                        !subscription.optBoolean("paid_active") -> copy("тарифы и способы оплаты", "plans and payment methods")
                        subscription.optBoolean("unlimited_time") -> copy("для изменения условий напишите в поддержку", "contact support to change your plan")
                        else -> copy("продление или дополнительное устройство", "renew or add a device")
                    }
                    val awgCount = devices?.optInt("awg", 0)?.coerceAtLeast(0) ?: 0
                    val happCount = devices?.optInt("happ_devices", 0)?.coerceAtLeast(0) ?: 0
                    deviceSummaryText?.text = host.uiCopy("amneziawg: $awgCount · happ: $happCount")
                    renderHappDevices(keys.optJSONObject("happ")?.optJSONArray("devices"))
                    snapshot.optString("support_url").takeIf { isHttpsUrl(it) }?.let { supportUrl = it }
                    refreshSessions(generation, token)
                }
            }.onFailure {
                host.runOnUiThread {
                    if (generation == refreshGeneration && isCurrent(token)) {
                        subscriptionText?.text = host.uiCopy("не удалось загрузить данные аккаунта.")
                        deviceSummaryText?.text = host.uiCopy("проверьте подключение и откройте профиль снова.")
                        setActionStatus(copy("повторить загрузку", "retry loading"), true) { refreshProfile() }
                    }
                }
            }
        }
    }

    private fun renderQuotaUsage(usedBytes: Long, limitBytes: Long) {
        val bar = quotaUsageBar ?: return
        val fill = quotaUsageFill ?: return
        if (limitBytes <= 0L) {
            bar.visibility = View.GONE
            quotaUsageAnimator?.cancel()
            fill.scaleX = 0f
            return
        }
        bar.visibility = View.VISIBLE
        val fraction = (usedBytes.toDouble() / limitBytes).coerceIn(0.0, 1.0)
        bar.contentDescription = copy(
            "Использовано ${(fraction * 100).toInt()}% лимита",
            "${(fraction * 100).toInt()}% of the quota used",
        )
        bar.post {
            if (bar.width <= 0 || host.isFinishing || host.isDestroyed) return@post
            val target = fraction.toFloat()
            quotaUsageAnimator?.cancel()
            if (host.prefersReducedMotion() ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())
            ) {
                fill.scaleX = target
                return@post
            }
            quotaUsageAnimator = ValueAnimator.ofFloat(fill.scaleX, target).apply {
                duration = 230L
                addUpdateListener { value ->
                    fill.scaleX = value.animatedValue as Float
                }
                start()
            }
        }
    }

    private fun renderHappDevices(devices: JSONArray?) {
        val container = happDevices ?: return
        container.removeAllViews()
        for (index in 0 until (devices?.length() ?: 0)) {
            val device = devices?.optJSONObject(index) ?: continue
            val model = device.optString("model").ifBlank { host.uiCopy("Устройство Happ") }.take(64)
            val platform = device.optString("os").trim()
            val version = device.optString("os_version").trim()
            val os = if (platform.isNotEmpty() && version.startsWith(platform, ignoreCase = true)) version
                else listOf(platform, version).filter(String::isNotBlank).joinToString(" ")
            val blocked = device.optBoolean("blocked")
            val id = device.optLong("id").takeIf { it > 0L }
            val state = if (blocked) copy("обновления заблокированы", "updates blocked") else copy("доступ разрешён", "access allowed")
            val detail = listOf(state, os).filter(String::isNotBlank).joinToString(" · ")
            val deviceIcon = if (platform.contains("windows", true) || platform.contains("desktop", true) ||
                platform.contains("mac", true) || platform.contains("linux", true)
            ) "DEVICE_DESKTOP" else "DEVICE_PHONE"
            container.addView(host.row(model, detail, deviceIcon, "›").apply {
                setOnClickListener {
                    val information = buildList {
                        add("Happ · $os")
                        add(state)
                        add(copy("последнее подключение", "last connection") + ": " + displayDate(device.optString("last_seen")))
                        if (blocked) add(copy("заблокировано", "blocked on") + ": " + displayDate(device.optString("blocked_at")))
                    }.joinToString("\n")
                    val dialog = AppDialog.Builder(host).setTitle(model).setMessage(information)
                        .setNegativeButton(host.uiCopy("закрыть"), null)
                    if (id != null) dialog.setPositiveButton(
                        if (blocked) copy("восстановить доступ", "restore access") else copy("заблокировать", "block"),
                    ) { _, _ -> confirmHappDeviceChange(id, model, !blocked) }
                    dialog.show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = host.dp(7)
            })
        }
        if (container.childCount == 0) {
            container.addView(host.note("пока нет зарегистрированных устройств happ.", MUTED))
        }
    }

    private fun confirmHappDeviceChange(id: Long, label: String, block: Boolean) {
        if (id in deviceChangesInFlight) return
        val title = if (block) copy("заблокировать устройство?", "block this device?") else copy("восстановить доступ?", "restore access?")
        val explanation = if (block) copy(
            "устройство больше не сможет обновлять подписку. уже загруженные ключи и активное vpn-подключение продолжат работать. чтобы отозвать ключи, используйте общий сброс happ.",
            "this device will no longer be able to refresh its subscription. downloaded keys and an active vpn connection will keep working. use the happ key reset to revoke keys.",
        ) else copy(
            "устройство снова сможет обновлять подписку. сервер проверит, есть ли свободное место в вашем плане.",
            "this device will be able to refresh its subscription again. the server will check for a free device slot in your plan.",
        )
        AppDialog.Builder(host).setTitle(title).setMessage("$label\n\n$explanation")
            .setNegativeButton(host.uiCopy("отмена"), null)
            .setPositiveButton(if (block) copy("заблокировать", "block") else copy("восстановить", "restore")) { _, _ ->
                changeHappDevice(id, label, block)
            }.show()
    }

    private fun changeHappDevice(id: Long, label: String, block: Boolean) {
        val token = TelegramSessionStore.read(host) ?: run { onPairAccount(); return }
        if (!deviceChangesInFlight.add(id)) return
        setActionStatus(copy("обновляем доступ устройства…", "updating device access…"))
        executeFor(token) {
            runCatching { TelegramPairingClient.setHappDeviceBlocked(token, id, block) }
                .onSuccess { host.runOnUiThread {
                    deviceChangesInFlight.remove(id)
                    if (!isCurrent(token)) return@runOnUiThread
                    refreshProfile()
                } }.onFailure { failure -> host.runOnUiThread {
                    deviceChangesInFlight.remove(id)
                    if (!isCurrent(token)) return@runOnUiThread
                    val message = when ((failure as? TelegramPairingException)?.code) {
                        "device_limit_reached" -> copy("нет свободного места в плане. заблокируйте другое устройство или увеличьте лимит.", "your plan has no free device slots. block another device or increase the limit.")
                        "device_not_found" -> copy("устройство больше не найдено. обновите профиль.", "this device is no longer available. refresh your profile.")
                        else -> host.uiCopy(apiError(failure))
                    }
                    setActionStatus(message, true) { refreshProfile() }
                    AppDialog.Builder(host).setTitle(label).setMessage(message)
                        .setNegativeButton(host.uiCopy("закрыть"), null)
                        .setPositiveButton(copy("повторить", "retry")) { _, _ -> confirmHappDeviceChange(id, label, block) }.show()
                } }
        }
    }

    private fun copy(ru: String, en: String): String = if (AppLanguage.current(host) == AppLanguage.EN) en else ru

    private fun setActionStatus(message: String, error: Boolean = false, retry: (() -> Unit)? = null) {
        actionStatus?.apply {
            visibility = View.VISIBLE
            text = message
            setTextColor(if (error) DeyttUi.CORAL else MUTED)
            setOnClickListener { retry?.invoke() }
            isClickable = retry != null
        }
    }

    private fun displayDate(raw: String, withTime: Boolean = true): String = runCatching {
        val normalized = raw.replace(Regex("\\.[0-9]+"), "")
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = parser.parse(normalized) ?: return@runCatching raw
        val locale = java.util.Locale.forLanguageTag(AppLanguage.current(host))
        val formatter = if (withTime) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale)
            else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
        formatter.format(date)
    }.getOrDefault(raw).ifBlank { "—" }

    private fun refreshSessions(generation: Int, token: String) {
        if (!isCurrent(token)) return
        executeFor(token) {
            runCatching {
                val response = TelegramPairingClient.sessions(token).optJSONArray("sessions") ?: JSONArray()
                JSONArray().apply {
                    for (index in 0 until response.length()) {
                        val session = response.optJSONObject(index) ?: continue
                        val client = session.optString("client").ifBlank {
                            if (session.optString("label") == "deytt./connect") "deytt-connect" else "telegram"
                        }
                        if (client == "deytt-connect") put(session)
                    }
                }
            }
                .onSuccess { sessions -> host.runOnUiThread {
                    if (generation != refreshGeneration || !isCurrent(token)) return@runOnUiThread
                    val container = appSessions ?: return@runOnUiThread
                    container.removeAllViews()
                    container.addView(host.sectionLabel(copy("сессии приложения", "app sessions")))
                    if (sessions.length() == 0) {
                        container.addView(host.note(
                            copy("активных сессий приложения нет.", "there are no active app sessions."),
                            MUTED,
                        ))
                    }
                    for (index in 0 until sessions.length()) {
                        val session = sessions.optJSONObject(index) ?: continue
                        val current = session.optBoolean("current")
                        val os = session.optString("device_os").takeIf { it.isNotBlank() && it != "null" }
                        val model = session.optString("device_model").takeIf { it.isNotBlank() && it != "null" }
                        val label = model ?: session.optString("label").ifBlank { "deytt.connect" }
                        val deviceDescription = listOfNotNull(os, if (current) copy("это устройство", "this device") else displayDate(session.optString("created_at")))
                            .filter(String::isNotBlank).joinToString(" · ")
                        val deviceIcon = if (os?.contains("windows", ignoreCase = true) == true ||
                            os?.contains("desktop", ignoreCase = true) == true ||
                            os?.contains("mac", ignoreCase = true) == true ||
                            os?.contains("linux", ignoreCase = true) == true
                        ) "DEVICE_DESKTOP" else "DEVICE_PHONE"
                        val id = session.optString("id")
                        container.addView(host.row(label, deviceDescription, deviceIcon, "›").apply {
                            setOnClickListener {
                                val builder = AppDialog.Builder(host).setTitle(label)
                                    .setMessage(listOfNotNull(os, copy("вход", "signed in") + ": " + displayDate(session.optString("created_at"))).joinToString("\n") + "\n" +
                                        copy("действует до", "valid until") + ": " + displayDate(session.optString("expires_at")))
                                    .setNegativeButton(host.uiCopy("закрыть"), null)
                                if (!current && id.isNotBlank()) builder.setPositiveButton(copy("завершить сессию", "end session")) { _, _ ->
                                    AppDialog.Builder(host).setTitle(copy("завершить сессию?", "end this session?"))
                                        .setMessage(copy("на этом устройстве потребуется снова войти через telegram. vpn-ключи останутся действительными.", "this device will need to sign in through telegram again. vpn keys will remain valid."))
                                        .setNegativeButton(host.uiCopy("отмена"), null)
                                        .setPositiveButton(copy("завершить", "end session")) { _, _ -> revokeSession(token, id) }.show()
                                }
                                builder.show()
                            }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = host.dp(7)
                        })
                    }
                } }.onFailure { host.runOnUiThread {
                    if (generation != refreshGeneration || !isCurrent(token)) return@runOnUiThread
        appSessions?.apply {
                        removeAllViews()
                        addView(host.row(copy("сессии не загружены", "sessions unavailable"), copy("нажмите, чтобы повторить", "tap to retry"), "↻", "").apply {
                            setOnClickListener { refreshSessions(generation, token) }
                        })
                    }
                } }
        }
    }

    private fun revokeSession(token: String, id: String) {
        if (!isCurrent(token)) return
        setActionStatus(copy("завершаем сессию…", "ending session…"))
        executeFor(token) {
            runCatching { TelegramPairingClient.revokeSession(token, id) }
                .onSuccess { host.runOnUiThread { if (isCurrent(token)) refreshProfile() } }
                .onFailure { failure -> host.runOnUiThread {
                    if (isCurrent(token)) setActionStatus(host.uiCopy(apiError(failure)), true) { revokeSession(token, id) }
                } }
        }
    }

    private fun showDurationPicker(token: String, devices: Int? = null) {
        if (!isCurrent(token)) return
        val months = listOf(1, 3, 6, 12)
        AppDialog.Builder(host).setTitle(copy("срок подписки", "subscription duration"))
            .setItems(months.map { copy("$it мес.", if (it == 1) "$it month" else "$it months") }.toTypedArray()) { _, index ->
                requestQuote(token, if (devices == null) "add_time" else "custom", null, months[index], devices)
            }.setNeutralButton(copy("другой срок", "other duration")) { _, _ ->
                val picker = android.widget.NumberPicker(host).apply { minValue = 1; maxValue = 36; value = 1; wrapSelectorWheel = false }
                AppDialog.Builder(host).setTitle(copy("количество месяцев", "number of months"))
                    .setView(picker).setNegativeButton(host.uiCopy("отмена"), null)
                    .setPositiveButton(copy("рассчитать", "calculate")) { _, _ ->
                        requestQuote(token, if (devices == null) "add_time" else "custom", null, picker.value, devices)
                    }.show()
            }.setNegativeButton(host.uiCopy("отмена"), null).show()
    }

    private fun showQuantityPicker(token: String, kind: String) {
        if (!isCurrent(token)) return
        val maximum = if (kind == "custom") 10 else if (unlimitedDevices) 0 else (10 - currentDeviceLimit).coerceAtLeast(0)
        if (maximum == 0) {
            AppDialog.Builder(host).setTitle(copy("лимит устройств", "device limit"))
                .setMessage(copy("для вашего плана увеличение лимита не требуется или недоступно. изменить условия поможет поддержка.", "your plan does not need or support a higher device limit. contact support to change its terms."))
                .setPositiveButton(host.uiCopy("закрыть"), null).show()
            return
        }
        val picker = android.widget.NumberPicker(host).apply { minValue = 1; maxValue = maximum; value = 1; wrapSelectorWheel = false }
        AppDialog.Builder(host).setTitle(if (kind == "custom") copy("Количество устройств", "Number of devices") else copy("Сколько добавить?", "How many to add?"))
            .setView(picker).setNegativeButton(host.uiCopy("Отмена"), null)
            .setPositiveButton(copy("Продолжить", "Continue")) { _, _ ->
                if (kind == "custom") showDurationPicker(token, picker.value)
                else requestQuote(token, kind, null, null, extra = picker.value)
            }.show()
    }

    private fun showResetChoices() {
        val token = TelegramSessionStore.read(host) ?: run { onPairAccount(); return }
        AppDialog.Builder(host)
            .setTitle(host.uiCopy("Что сбросить?"))
            .setItems(arrayOf(host.uiCopy("Все ключи"), "AmneziaWG", "Happ")) { _, index ->
                val scope = listOf("all", "awg", "happ")[index]
                val label = listOf(host.uiCopy("все ключи"), "AmneziaWG", "Happ")[index]
                AppDialog.Builder(host)
                    .setTitle(host.uiCopy("Сбросить $label?"))
                    .setMessage(host.uiCopy("Действие отзовёт текущие ключи $label и может временно отключить устройства. Продолжить?"))
                    .setNegativeButton(host.uiCopy("Отмена"), null)
                    .setPositiveButton(host.uiCopy("Сбросить")) { _, _ -> resetKeys(token, scope) }
                    .show()
            }
            .setNegativeButton(host.uiCopy("Отмена"), null)
            .show()
    }

    private fun resetKeys(token: String, scope: String) {
        if (!isCurrent(token)) return
        setActionStatus(host.uiCopy("Сбрасываем ключи…"))
        executeFor(token) {
            runCatching { TelegramPairingClient.resetKeys(token, scope) }
                .onSuccess {
                    host.runOnUiThread {
                        if (isCurrent(token)) {
                            setActionStatus(host.uiCopy("Ключи сброшены. Обновляем аккаунт…"))
                            refreshProfile()
                        }
                    }
                }
                .onFailure { failure -> host.runOnUiThread {
                    if (isCurrent(token)) {
                        setActionStatus(host.uiCopy(apiError(failure)), true)
                    }
                } }
        }
    }

    private fun showPlanPicker() {
        val token = TelegramSessionStore.read(host) ?: run { onPairAccount(); return }
        setActionStatus(host.uiCopy("Загружаем доступные планы…"))
        executeFor(token) {
            runCatching {
                val snapshot = TelegramPairingClient.accountSnapshot(token)
                val tariffs = TelegramPairingClient.tariffs().optJSONArray("tariffs") ?: JSONArray()
                val profile = snapshot.optJSONObject("profile")
                val subscription = profile?.optJSONObject("subscription")
                val active = subscription?.optBoolean("paid_active") == true
                unlimitedTime = subscription?.optBoolean("unlimited_time") == true
                unlimitedDevices = subscription?.isNull("device_limit") == true
                currentDeviceLimit = subscription?.optInt("device_limit", 1)?.coerceAtLeast(1) ?: 1
                active to tariffs
            }.onSuccess { (active, tariffs) -> host.runOnUiThread {
                if (!isCurrent(token)) return@runOnUiThread
                actionStatus?.visibility = View.GONE
                if (active && unlimitedTime) {
                    AppDialog.Builder(host).setTitle(copy("Бессрочная подписка", "Lifetime subscription"))
                        .setMessage(copy("Продлевать срок не нужно. Для изменения условий напишите в поддержку.", "No renewal is needed. Contact support to change your plan."))
                        .setNeutralButton(host.uiCopy("Поддержка")) { _, _ -> showSupportChat() }
                        .setPositiveButton(host.uiCopy("Закрыть"), null).show()
                    return@runOnUiThread
                }
                if (active && unlimitedDevices) {
                    AppDialog.Builder(host).setTitle(copy("План без лимита устройств", "Unlimited device plan"))
                        .setMessage(copy("У вас индивидуальные условия без лимита устройств. Поддержка поможет продлить этот план, сохранив все его возможности.", "Your plan has individual terms with unlimited devices. Support can renew it while preserving all of its benefits."))
                        .setNeutralButton(host.uiCopy("Поддержка")) { _, _ -> showSupportChat() }
                        .setPositiveButton(host.uiCopy("Закрыть"), null).show()
                    return@runOnUiThread
                }
                if (active) {
                    AppDialog.Builder(host).setTitle(copy("Управление подпиской", "Manage subscription"))
                        .setItems(arrayOf(copy("Продлить срок", "Extend subscription"), copy("Добавить устройства", "Add devices"))) { _, action ->
                            if (action == 1) {
                                showQuantityPicker(token, "add_devices")
                            } else showDurationPicker(token)
                        }.setNegativeButton(host.uiCopy("Отмена"), null).show()
                    return@runOnUiThread
                }
                val options = (0 until tariffs.length()).mapNotNull { tariffs.optJSONObject(it) }
                if (options.isEmpty()) {
                    setActionStatus(host.uiCopy("Сейчас планы недоступны. Попробуйте позже."))
                    return@runOnUiThread
                }
                val labels = options.map { plan ->
                    val name = plan.optString("name").ifBlank { plan.optString("code") }
                    val devices = plan.optInt("devices", 0)
                    val months = plan.optInt("months", 0)
                    val rubles = plan.optInt("rubles", 0)
                    host.uiCopy("$name · $devices устройств · $months мес. · $rubles ₽")
                }.toTypedArray()
                AppDialog.Builder(host)
                    .setTitle(host.uiCopy("Выберите план"))
                    .setItems(labels) { _, index -> requestQuote(token, "preset", options[index].optString("code"), null) }
                    .setNeutralButton(copy("Свой план", "Custom plan")) { _, _ -> showQuantityPicker(token, "custom") }
                    .setNegativeButton(host.uiCopy("Отмена"), null)
                    .show()
            } }.onFailure { failure -> host.runOnUiThread {
                if (isCurrent(token)) setActionStatus(host.uiCopy(apiError(failure)) + " · " + copy("повторить", "retry"), true) { showPlanPicker() }
            } }
        }
    }

    private fun requestQuote(token: String, kind: String, plan: String?, months: Int?, devices: Int? = null, extra: Int? = null) {
        if (!isCurrent(token)) return
        setActionStatus(copy("Рассчитываем стоимость…", "Calculating price…"))
        executeFor(token) {
            runCatching { TelegramPairingClient.quote(token, kind, plan = plan, months = months, devices = devices, extra = extra) }
                .onSuccess { quote -> host.runOnUiThread {
                    if (!isCurrent(token)) return@runOnUiThread
                    actionStatus?.visibility = View.GONE
                    val name = quote.optString("name").ifBlank { plan ?: host.uiCopy("Подписка") }
                    val duration = if (kind == "add_devices") copy("до конца текущей подписки", "until your current subscription ends")
                        else copy("${quote.optInt("months")} мес.", "${quote.optInt("months")} " + if (quote.optInt("months") == 1) "month" else "months")
                    val detail = "$name\n" + copy("${quote.optInt("devices")} устройств", "${quote.optInt("devices")} " + if (quote.optInt("devices") == 1) "device" else "devices") +
                        " · $duration\n${quote.optInt("rubles")} ₽ · ⭐ ${quote.optInt("stars")}"

                    AppDialog.Builder(host)
                        .setTitle(host.uiCopy("Подтвердите сумму"))
                        .setMessage(detail)
                        .setNegativeButton(host.uiCopy("Отмена"), null)
                        .setNeutralButton(host.uiCopy("Telegram Stars")) { _, _ -> createCheckout(token, kind, plan, months, "stars", devices, extra) }
                        .setPositiveButton(host.uiCopy("Оплатить картой")) { _, _ -> createCheckout(token, kind, plan, months, "platega", devices, extra) }
                        .show()
                } }
                .onFailure { failure -> host.runOnUiThread {
                    if (isCurrent(token)) setActionStatus(host.uiCopy(apiError(failure)) + " · " + copy("повторить", "retry"), true) { requestQuote(token, kind, plan, months, devices, extra) }
                } }
        }
    }

    private fun createCheckout(token: String, kind: String, plan: String?, months: Int?, method: String, devices: Int? = null, extra: Int? = null) {
        if (!isCurrent(token)) return
        setActionStatus(host.uiCopy("Готовим безопасный счёт…"))
        executeFor(token) {
            runCatching { TelegramPairingClient.checkout(token, kind, method, plan, months, devices, extra) }
                .onSuccess { result -> host.runOnUiThread {
                    if (!isCurrent(token)) return@runOnUiThread
                    val url = if (method == "stars") result.optString("invoice_url") else result.optString("pay_url")
                    val externalId = result.optString("external_id").takeIf(String::isNotBlank)
                    pendingPaymentId = externalId
                    paymentStatusText?.apply {
                        visibility = if (externalId == null) View.GONE else View.VISIBLE
                        text = host.uiCopy("Платёж ожидает подтверждения · нажмите, чтобы проверить")
                    }
                    AppDialog.Builder(host)
                        .setTitle(host.uiCopy("Счёт готов"))
                        .setMessage(host.uiCopy("Откройте защищённую страницу оплаты. Приложение не запрашивает данные карты."))
                        .setNegativeButton(host.uiCopy("Позже"), null)
                        .setNeutralButton(host.uiCopy("Проверить статус")) { _, _ -> checkPaymentStatus() }
                        .setPositiveButton(host.uiCopy("Открыть оплату")) { _, _ ->
                            if (isCurrent(token) && isHttpsUrl(url)) openHttps(url)
                            else setActionStatus(host.uiCopy("Платёжная ссылка недоступна."), true)
                        }
                        .show()
                } }
                .onFailure { failure -> host.runOnUiThread {
                    if (isCurrent(token)) setActionStatus(host.uiCopy(apiError(failure)), true)
                } }
        }
    }

    private fun checkPaymentStatus() {
        val token = TelegramSessionStore.read(host) ?: return
        val externalId = pendingPaymentId ?: return
        paymentStatusText?.text = host.uiCopy("Проверяем платёж…")
        executeFor(token) {
            runCatching { TelegramPairingClient.paymentStatus(token, externalId).optString("status") }
                .onSuccess { status -> host.runOnUiThread {
                    if (!isCurrent(token)) return@runOnUiThread
                    if (status == "paid") {
                        paymentStatusText?.apply { text = host.uiCopy("Оплата подтверждена"); setTextColor(DeyttUi.MINT) }
                        refreshProfile()
                    } else paymentStatusText?.text = host.uiCopy("Платёж ещё не подтверждён. Проверьте позже.")
                } }
                .onFailure { failure -> host.runOnUiThread {
                    if (isCurrent(token)) paymentStatusText?.text = host.uiCopy(apiError(failure))
                } }
        }
    }

    private fun showSupportChat() {
        val token = TelegramSessionStore.read(host) ?: run { onPairAccount(); return }
        closeSupport?.invoke()
        val dialog = Dialog(host)
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(20), host.dp(18), host.dp(20), host.dp(14))
            background = host.glassRowBackground(true, 24f)
        }
        root.addView(host.mono("deytt · support", 9f, MUTED, 650))
        root.addView(host.text("поддержка", 21f, TEXT, Typeface.BOLD).apply { setPadding(0, host.dp(8), 0, host.dp(4)) })
        val status = host.text("загружаем переписку…", 12f, MUTED).apply { setPadding(0, 0, 0, host.dp(10)) }
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
            hint = host.uiCopy("сообщение команде")
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            minLines = 2
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(4000))
            setPadding(host.dp(12), host.dp(8), host.dp(12), host.dp(8))
            background = host.glassRowBackground(false, 14f)
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(10)
        })
        val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val closeTicket = host.button("закрыть обращение", secondary = true).apply { visibility = View.GONE }
        val send = host.button("отправить")
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
                ticket == null -> "новое обращение создастся после первого сообщения."
                ticketOpen -> "обращение №${ticketId} · открыто"
                else -> "обращение закрыто. новое сообщение откроет новое обращение."
            })
            messageList.removeAllViews()
            val messages = payload.optJSONArray("messages") ?: JSONArray()
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val fromSupport = message.optString("sender") == "support"
                val bubble = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(host.dp(12), host.dp(9), host.dp(12), host.dp(9))
                    background = host.glassRowBackground(!fromSupport, 14f)
                }
                bubble.addView(host.mono(if (fromSupport) "deytt" else copy("вы", "you"), 8f, MUTED, 600))
                bubble.addView(host.text("", 13f, TEXT).apply {
                    text = message.optString("text")
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
            if (!dialog.isShowing || loading || !isCurrent(token)) return
            loading = true
            executeFor(token) {
                runCatching { TelegramPairingClient.supportThread(token) }
                    .onSuccess { payload -> host.runOnUiThread {
                        loading = false
                        if (dialog.isShowing && isCurrent(token)) render(payload)
                    } }
                    .onFailure { failure -> host.runOnUiThread {
                        loading = false
                        if (dialog.isShowing && isCurrent(token)) setStatus(host.uiCopy(apiError(failure)) + " · " + copy("нажмите, чтобы повторить", "tap to retry"), true)
                    } }
            }
        }
        status.setOnClickListener { refresh() }
        val poll = object : Runnable {
            override fun run() {
                if (dialog.isShowing && isCurrent(token)) {
                    refresh()
                    handler.postDelayed(this, SUPPORT_REFRESH_INTERVAL_MS)
                }
            }
        }
        send.setOnClickListener {
            val text = input.text?.toString().orEmpty().trim()
            if (text.length < 5) {
                setStatus("напишите сообщение минимум из пяти символов.", true)
                return@setOnClickListener
            }
            if (loading || !isCurrent(token)) return@setOnClickListener
            loading = true
            send.isEnabled = false
            setStatus("отправляем сообщение…")
            executeFor(token) {
                runCatching {
                    val currentTicket = ticketId
                    if (ticketOpen && currentTicket != null) TelegramPairingClient.sendSupportMessage(token, currentTicket, text)
                    else TelegramPairingClient.createSupportTicket(token, text)
                }.onSuccess { host.runOnUiThread {
                    if (!dialog.isShowing || !isCurrent(token)) return@runOnUiThread
                    loading = false
                    send.isEnabled = true
                    if (dialog.isShowing && isCurrent(token)) {
                        input.text?.clear()
                        refresh()
                    }
                } }.onFailure { failure -> host.runOnUiThread {
                    if (!dialog.isShowing || !isCurrent(token)) return@runOnUiThread
                    loading = false
                    send.isEnabled = true
                    if (dialog.isShowing && isCurrent(token)) setStatus(host.uiCopy(apiError(failure)) + " · " + copy("нажмите, чтобы повторить", "tap to retry"), true)
                } }
            }
        }
        closeTicket.setOnClickListener {
            val id = ticketId ?: return@setOnClickListener
            AppDialog.Builder(host)
                .setTitle(host.uiCopy("закрыть обращение?"))
                .setMessage(host.uiCopy("новые ответы не будут приниматься. при необходимости вы сможете создать новое обращение."))
                .setNegativeButton(host.uiCopy("отмена"), null)
                .setPositiveButton(host.uiCopy("закрыть")) { _, _ ->
                    if (loading || !isCurrent(token)) return@setPositiveButton
                    loading = true
                    executeFor(token) {
                        runCatching { TelegramPairingClient.closeSupportTicket(token, id) }
                            .onSuccess { host.runOnUiThread { loading = false; refresh() } }
                            .onFailure { failure -> host.runOnUiThread {
                                loading = false
                                if (dialog.isShowing && isCurrent(token)) setStatus(apiError(failure), true)
                            } }
                    }
                }
                .show()
        }
        val cleanup: () -> Unit = {
            handler.removeCallbacksAndMessages(null)
            if (dialog.isShowing) dialog.dismiss()
        }
        closeSupport = cleanup
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (closeSupport === cleanup) closeSupport = null
        }
        showSheet(dialog, root)
        refresh()
        handler.postDelayed(poll, SUPPORT_REFRESH_INTERVAL_MS)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(java.util.Locale.US, "%.1f kB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun apiError(failure: Throwable): String = when ((failure as? TelegramPairingException)?.code) {
        "unauthorized", "invalid_session", "session_expired", "auth_required" -> copy("Сессия истекла. Подключите Telegram заново в профиле.", "Your session expired. Reconnect Telegram in your profile.")
        "unlimited_devices_plan" -> copy("Продление плана без лимита устройств доступно через поддержку. Ваши условия сохранятся.", "Contact support to renew your unlimited-device plan and preserve its terms.")
        "subscription_unlimited", "unlimited_subscription" -> copy("Бессрочный доступ не требует продления.", "Lifetime access does not need renewal.")
        "device_limit_exceeded", "invalid_extra" -> copy("Выберите меньше дополнительных устройств.", "Choose fewer additional devices.")
        "rate_limited" -> copy("Слишком частые запросы. Повторите позже.", "Too many requests. Try again later.")
        "ticket_not_open" -> copy("Обращение закрыто. Обновите переписку.", "This conversation is closed. Refresh your messages.")
        "subscription_active_extend_only" -> copy("Для активной подписки выберите продление.", "Choose renewal for your active subscription.")
        "extend_unavailable" -> copy("Этот план нельзя продлить. Обновите профиль или напишите в поддержку.", "This plan cannot be renewed. Refresh your profile or contact support.")
        "bad_custom_params", "bad_plan", "payment_unavailable" -> copy("Не удалось рассчитать выбранный план. Выберите другие параметры или напишите в поддержку.", "This plan could not be priced. Choose other options or contact support.")
        "devices_max" -> copy("Достигнут максимальный лимит устройств.", "The maximum device limit has been reached.")
        "channel_required" -> copy("Для этого действия требуется подключить канал DEYTT.", "Join the DEYTT channel to continue.")
        else -> copy("Не удалось выполнить запрос. Проверьте подключение и повторите позже.", "Request failed. Check your connection and try again later.")
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
        val container = FrameLayout(host).apply {
            setPadding(host.dp(14), 0, host.dp(14), host.dp(20))
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { dialog.dismiss() }
        }
        content.setOnClickListener { /* prevent dismissal on clicking inside content */ }
        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            DeyttUi.blurBehind(this, host.dp(20))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private companion object {
        const val SUPPORT_REFRESH_INTERVAL_MS = 15_000L
    }
}
