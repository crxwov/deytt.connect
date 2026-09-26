package space.deytt.connect

import android.app.Activity
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.net.VpnService
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.PathInterpolator
import android.os.Build
import androidx.core.content.ContextCompat
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.mapPanel
import space.deytt.connect.DeyttUi.rounded

class ProtocolActivity : Activity() {
    private var latencyGeneration = 0
    private var selecting = false
    private var pendingProbeRequestId: String? = null
    private var pendingProbeAuthorizationAction: (() -> Unit)? = null
    private var chooseButton: TextView? = null
    private var compareButton: TextView? = null
    private val latencyViews = mutableMapOf<String, TextView>()
    private val routeProbeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent ?: return
            if (event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID) != pendingProbeRequestId) return
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                RouteProbeClient.clear(this@ProtocolActivity, pendingProbeRequestId.orEmpty())
            }
            val tag = event.getStringExtra(RouteProbeClient.EXTRA_ROUTE_TAG).orEmpty()
            val view = latencyViews[tag]
            val elapsed = event.getLongExtra(RouteProbeClient.EXTRA_MILLISECONDS, -1L)
            val bytesPerSecond = event.getLongExtra(RouteProbeClient.EXTRA_BYTES_PER_SECOND, -1L)
            val error = event.getStringExtra(RouteProbeClient.EXTRA_ERROR)
            if (view != null) {
                when {
                    elapsed >= 0L -> {
                        view.text = if (bytesPerSecond >= 0L) {
                            String.format(java.util.Locale.US, "$elapsed мс · %.1f Мбит/с", bytesPerSecond * 8.0 / 1_000_000.0)
                        } else "$elapsed мс"
                        view.setTextColor(DeyttUi.MINT)
                        view.contentDescription = "Задержка через proxy ${view.tag}: $elapsed миллисекунд"
                    }
                    !error.isNullOrBlank() -> {
                        view.text = if (error.contains("10 с")) "тайм-аут" else "нет ответа"
                        view.setTextColor(DeyttUi.CORAL)
                        view.contentDescription = "Проверка через прокси: $error"
                    }
                }
                view.isEnabled = true
                view.alpha = 1f
            } else if (!error.isNullOrBlank() && tag.isBlank()) {
                AppDialog.Builder(this@ProtocolActivity)
                    .setMessage(error)
                    .setPositiveButton("Понятно", null)
                    .show()
            }
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                pendingProbeRequestId = null
                compareButton?.apply {
                    text = "Сравнить протоколы"
                    isEnabled = true
                    alpha = 1f
                }
                chooseButton?.isEnabled = !selecting
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            routeProbeReceiver,
            IntentFilter(RouteProbeClient.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @Deprecated("Android VPN permission API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ROUTE_PROBE_PERMISSION_REQUEST) return
        val action = pendingProbeAuthorizationAction
        pendingProbeAuthorizationAction = null
        if (resultCode == RESULT_OK && !isFinishing && !isDestroyed) action?.invoke()
    }

    override fun onStop() {
        if (pendingProbeRequestId != null && RouteProbeClient.isRunning(this)) {
            runCatching { RouteProbeClient.cancel(this) }
            pendingProbeRequestId = null
            latencyViews.values.forEach { view ->
                view.text = "пинг"
                view.isEnabled = true
                view.alpha = 1f
            }
            compareButton?.apply { text = "Сравнить протоколы"; isEnabled = true; alpha = 1f }
            chooseButton?.isEnabled = !selecting
        }
        runCatching { unregisterReceiver(routeProbeReceiver) }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getStringExtra("country") ?: run { finish(); return }
        val requestedVersion = intent.getStringExtra("version")
        val config = SubscriptionStore(this).readCurrent() ?: run { finish(); return }
        val awg = AwgProfileStore(this)
        val routes = RouteCatalog.from(config, awg.profiles())
            .filter { route ->
                route.countryCode == code &&
                    (requestedVersion == null ||
                        (requestedVersion == "15" && route.protocol == RouteProtocol.AWG15) ||
                        (requestedVersion == "31" && route.protocol == RouteProtocol.AWG31))
            }
        val title = if (code == "AWG") {
            requestedVersion?.let { "AmneziaWG ${if (it == "31") "3.1" else "1.5"}" } ?: "AmneziaWG"
        } else routes.firstOrNull()?.country ?: "Протокол"
        val root = screen()
        val selectedId = SelectedRouteStore(this).read().id
        root.addView(header(if (code == "AWG") "AmneziaWG" else "маршрут", title, true))
        root.addView(spacer(5, this))
        val globe = RouteGlobeView(this)
        globe.onMapNodeTapped = { node ->
            if (node == "user") {
                AppDialog.Builder(this)
                    .setMessage("Точка входа показывает приблизительный регион этого устройства; выбрать её как VPN-выход нельзя.")
                    .setPositiveButton("Понятно", null)
                    .show()
            } else {
                val tappedCountry = when (node) {
                    "nl" -> "NL"
                    "de" -> "DE"
                    "fi" -> "FI"
                    "ru" -> "RU"
                    else -> null
                }
                if (tappedCountry != null && tappedCountry != code) {
                    startActivity(Intent(this, ProtocolActivity::class.java).putExtra("country", tappedCountry))
                }
            }
        }
        globe.focus(if (code == "AWG") "AUTO" else code, animate = false)
        root.addView(mapPanel(globe), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(232)))
        root.addView(spacer(15, this))
        root.addView(sectionLabel(if (code == "AWG") "серверы · tcp-проверка" else "выход · via proxy"))
        var anchoredAction: TextView? = null
        if (routes.isEmpty()) {
            root.addView(note("Профили этой версии пока не загружены. Обновите подписку, чтобы загрузить серверы.", DeyttUi.AMBER))
            anchoredAction = button("Обновить подписку").apply {
                setOnClickListener { startActivity(Intent(this@ProtocolActivity, SetupActivity::class.java)) }
            }
        } else {
            var chosenRoute = routes.firstOrNull { it.id == selectedId }
            val routeItems = mutableListOf<Pair<DeyttRoute, LinearLayout>>()
            val probeRoutes = routes.filter { it.engine == TunnelEngine.LIBBOX }
            if (probeRoutes.size > 1) {
                compareButton = button("Сравнить протоколы", secondary = true).apply {
                    textSize = 13f
                    setOnClickListener { compareProtocols(probeRoutes) }
                }
                root.addView(compareButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
                root.addView(spacer(9, this))
            }
            val chooseAction = button(chosenRoute?.let { "Использовать ${it.protocol.title}" } ?: "Выберите протокол").apply {
                isEnabled = chosenRoute != null
                contentDescription = text
                setOnClickListener {
                    val route = chosenRoute ?: return@setOnClickListener
                    if (selecting) return@setOnClickListener
                    selecting = true
                    isEnabled = false
                    select(route)
                }
            }
            chooseButton = chooseAction

            fun styleChooseAction(enabled: Boolean) {
                chooseAction.isEnabled = enabled
                chooseAction.alpha = 1f
                if (enabled) {
                    chooseAction.background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(DeyttUi.BLUE, DeyttUi.BLUE_DEEP),
                    ).apply { cornerRadius = dp(16).toFloat() }
                    chooseAction.setTextColor(Color.WHITE)
                    chooseAction.elevation = dp(2).toFloat()
                } else {
                    chooseAction.background = rounded(DeyttUi.SURFACE_2, 16f, DeyttUi.LINE)
                    chooseAction.setTextColor(DeyttUi.MUTED)
                    chooseAction.elevation = 0f
                }
            }
            styleChooseAction(chosenRoute != null)

            val routeList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(DeyttUi.SURFACE, 20f, DeyttUi.LINE)
            }

            fun renderChoice(choice: DeyttRoute?) {
                chosenRoute = choice
                routeItems.forEach { (candidate, row) ->
                    val selected = candidate.id == choice?.id
                    row.background = if (selected) rounded(DeyttUi.SELECTED, 15f, DeyttUi.SELECTED_LINE) else ColorDrawable(Color.TRANSPARENT)
                    val title = if (candidate.engine == TunnelEngine.AMNEZIAWG) candidate.country else candidate.protocol.title
                    val detail = if (candidate.engine == TunnelEngine.AMNEZIAWG) "${candidate.protocol.title} · ${candidate.protocol.detail}" else candidate.protocol.detail
                    row.contentDescription = "$title, $detail${if (selected) ", выбран" else ""}"
                }
                chooseAction.text = choice?.let { "Использовать ${it.protocol.title}" } ?: "Выберите протокол"
                chooseAction.contentDescription = chooseAction.text
                styleChooseAction(choice != null)
            }

            routes.forEachIndexed { index, route ->
                val rowTitle = if (route.engine == TunnelEngine.AMNEZIAWG) route.country else route.protocol.title
                val rowDetail = if (route.engine == TunnelEngine.AMNEZIAWG) "${route.protocol.title} · ${route.protocol.detail}" else route.protocol.detail
                val selected = route.id == chosenRoute?.id
                val accent = when (route.protocol) {
                    RouteProtocol.TROJAN -> DeyttUi.MINT
                    RouteProtocol.HYSTERIA2 -> DeyttUi.SKY
                    else -> DeyttUi.BLUE
                }
                val badgeFill = when (route.protocol) {
                    RouteProtocol.TROJAN -> DeyttUi.MINT_SURFACE
                    RouteProtocol.HYSTERIA2 -> DeyttUi.SKY_SURFACE
                    else -> DeyttUi.BLUE_SURFACE
                }
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(78)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = if (selected) rounded(DeyttUi.SELECTED, 15f, DeyttUi.SELECTED_LINE) else ColorDrawable(Color.TRANSPARENT)
                    contentDescription = "$rowTitle, $rowDetail${if (selected) ", выбран" else ""}"
                    isClickable = true
                    isFocusable = true

                    addView(mono("%02d".format(index + 1), 9f, accent, 650).apply {
                        gravity = android.view.Gravity.CENTER
                        background = rounded(badgeFill, 12f, badgeFill)
                    }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })

                    addView(LinearLayout(this@ProtocolActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(text(rowTitle, 16f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        addView(mono(rowDetail.uppercase(), 8f, DeyttUi.MUTED, 540).apply {
                            setPadding(0, dp(4), 0, 0)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    val latency = mono("пинг", 8f, DeyttUi.SKY, 650).apply {
                        gravity = android.view.Gravity.CENTER
                        minHeight = dp(40)
                        setPadding(dp(9), dp(8), dp(9), dp(8))
                        background = rounded(DeyttUi.SURFACE_2, 12f, DeyttUi.LINE)
                        contentDescription = "Проверить задержку маршрута $rowTitle"
                        isClickable = true
                        isFocusable = true
                    }
                    latency.tag = route.id
                    latencyViews[if (route.engine == TunnelEngine.LIBBOX) route.configTag else route.id] = latency
                    latency.setOnClickListener { measure(route, latency) }
                    addView(latency, LinearLayout.LayoutParams(dp(68), dp(40)).apply { marginStart = dp(8) })

                    setOnClickListener {
                        if (selecting) return@setOnClickListener
                        renderChoice(route)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && motionEnabled()) {
                            animate().cancel()
                            scaleX = .985f
                            scaleY = .985f
                            animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
                        }
                        globe.focus(if (route.engine == TunnelEngine.AMNEZIAWG) route.id.uppercase() else route.countryCode)
                    }
                }
                routeItems += route to item
                routeList.addView(item)
                if (index < routes.lastIndex) {
                    routeList.addView(View(this).apply { setBackgroundColor(DeyttUi.LINE) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            leftMargin = dp(64)
                            rightMargin = dp(16)
                        })
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && motionEnabled()) {
                    item.alpha = 0f
                    item.translationY = dp(8).toFloat()
                    item.post {
                        item.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setStartDelay(index * 24L)
                            .setDuration(240L)
                            .setInterpolator(PathInterpolator(.22f, 1f, .36f, 1f))
                            .start()
                    }
                }
            }
            renderChoice(chosenRoute)
            root.addView(routeList)
            anchoredAction = chooseAction
        }
        present(root, anchoredAction)
    }

    private fun motionEnabled(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) &&
            !getSharedPreferences("profile_settings", MODE_PRIVATE).getBoolean("reduced_motion", false)

    private fun requestProbeAuthorization(afterGrant: () -> Unit): Boolean {
        val permission = VpnService.prepare(this) ?: return false
        AppDialog.Builder(this)
            .setTitle("Разрешить диагностику?")
            .setMessage("Android попросит системное разрешение VPN для проверки маршрутов. Проверка использует временный локальный прокси и не запускает VPN-туннель.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить") { _, _ ->
                pendingProbeAuthorizationAction = afterGrant
                startActivityForResult(permission, ROUTE_PROBE_PERMISSION_REQUEST)
            }
            .show()
        return true
    }

    private fun compareProtocols(routes: List<DeyttRoute>) {
        if (RouteProbeClient.isRunning(this)) return
        val config = SubscriptionStore(this).readCurrent() ?: return
        val candidates = routes.filter { it.engine == TunnelEngine.LIBBOX && it.configTag.isNotBlank() }
        if (candidates.size < 2) return
        if (requestProbeAuthorization { compareProtocols(routes) }) return
        val method = RouteProbePreferences.method(this)
        candidates.forEach { route ->
            latencyViews[route.configTag]?.apply {
                text = "проверка…"
                isEnabled = false
                alpha = .68f
                setTextColor(DeyttUi.SKY)
            }
        }
        compareButton?.apply {
            text = "Сравниваю…"
            isEnabled = false
            alpha = .7f
        }
        chooseButton?.isEnabled = false
        try {
            pendingProbeRequestId = RouteProbeClient.start(
                this,
                config,
                candidates.map(DeyttRoute::configTag),
                method,
                TelegramSessionStore.read(this),
            )
        } catch (_: Throwable) {
            candidates.forEach { route ->
                latencyViews[route.configTag]?.apply { text = "нет ответа"; isEnabled = true; alpha = 1f }
            }
            compareButton?.apply { text = "Сравнить протоколы"; isEnabled = true; alpha = 1f }
            chooseButton?.isEnabled = !selecting
            AppDialog.Builder(this)
                .setMessage("Не удалось запустить проверку маршрутов.")
                .setPositiveButton("Понятно", null)
                .show()
        }
    }

    private fun measure(route: DeyttRoute, view: TextView) {
        if (RouteProbeClient.isRunning(this)) return
        val generation = ++latencyGeneration
        val config = SubscriptionStore(this).readCurrent() ?: return
        val selected = SelectedRouteStore(this).read()
        val active = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        val method = RouteProbePreferences.method(this)
        if (!active && route.engine == TunnelEngine.LIBBOX && requestProbeAuthorization { measure(route, view) }) return
        view.text = "проверка…"
        view.isEnabled = false
        view.alpha = .65f

        if (active && route.id != selected.id) {
            view.text = "отключите VPN"
            view.isEnabled = true
            view.alpha = 1f
            view.contentDescription = "Чтобы проверить другой выход, сначала отключите текущий VPN"
            return
        }

        if (active && route.engine == TunnelEngine.LIBBOX &&
            !RouteProxyProbe.isApplicationRoutedByTunnel(config, packageName)
        ) {
            view.text = "нет ответа"
            view.isEnabled = true
            view.alpha = 1f
            view.contentDescription = "Туннель исключает приложение из маршрута, поэтому измерение не запущено"
            return
        }

        if (route.engine == TunnelEngine.AMNEZIAWG && !active) {
            view.text = "HTTP после подключения"
            view.isEnabled = true
            view.alpha = 1f
            view.setTextColor(DeyttUi.AMBER)
            view.contentDescription = "HTTP через этот профиль будет измерен после подключения"
            return
        }

        if (active) {
            LatencyExecutor.pool.execute {
                val elapsed = runCatching { RouteProxyProbe.measureThroughSystemVpn(method) }.getOrNull()
                runOnUiThread {
                    if (generation == latencyGeneration && !isFinishing && !isDestroyed) {
                        view.text = elapsed?.let { "$it мс" } ?: "нет ответа"
                        view.isEnabled = true
                        view.alpha = 1f
                        view.textSize = 9f
                        view.setTextColor(if (elapsed != null) DeyttUi.MINT else DeyttUi.CORAL)
                        view.contentDescription = if (elapsed != null) "HTTP $elapsed миллисекунд через активный VPN" else "HTTP через активный VPN не ответил"
                    }
                }
            }
            return
        }

        try {
            pendingProbeRequestId = RouteProbeClient.start(
                this, config, listOf(route.configTag), method, TelegramSessionStore.read(this),
            )
            view.text = "proxy…"
            view.isEnabled = true
            view.alpha = 1f
            view.setTextColor(DeyttUi.SKY)
            view.contentDescription = "Два HTTPS-запроса ${method.wireValue} через выбранный выход, тайм-аут 10 секунд"
            compareButton?.apply { text = "Проверяю…"; isEnabled = false; alpha = .7f }
            chooseButton?.isEnabled = false
        } catch (_: Throwable) {
            view.text = "ошибка"
            view.isEnabled = true
            view.alpha = 1f
            view.setTextColor(DeyttUi.CORAL)
            view.contentDescription = "Не удалось запустить проверку через прокси"
            compareButton?.apply { text = "Сравнить протоколы"; isEnabled = true; alpha = 1f }
            chooseButton?.isEnabled = !selecting
        }
    }

    override fun onDestroy() {
        latencyGeneration++
        super.onDestroy()
    }

    private fun select(route: DeyttRoute) {
        if (ConnectVpnService.isRunning() || AwgTunnelController.isRunning()) {
            fun resumeSelection() {
                selecting = false
                chooseButton?.isEnabled = true
            }
            val confirmation = AppDialog.Builder(this)
                .setTitle("Сменить VPN-выход?")
                .setMessage("Текущее соединение остановится. После выбора запустите подключение снова, чтобы применить новый маршрут.")
                .setNegativeButton("Оставить подключение") { _, _ -> resumeSelection() }
                .setPositiveButton("Остановить и сменить") { _, _ -> applySelection(route) }
                .create()
            confirmation.setOnCancelListener { resumeSelection() }
            confirmation.show()
            return
        }
        applySelection(route)
    }

    private fun applySelection(route: DeyttRoute) {
        if (RouteProbeClient.isRunning(this)) {
            runCatching { RouteProbeClient.cancel(this) }
            pendingProbeRequestId = null
        }
        if (ConnectVpnService.isRunning()) {
            startService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
        }
        AwgTunnelController.stop(this)
        if (route.engine == TunnelEngine.LIBBOX) {
            val config = SubscriptionStore(this).readCurrent() ?: return
            SubscriptionStore(this).saveValidated(ProfileRoutes.select(config, route.configTag))
        }
        SelectedRouteStore(this).save(route)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private companion object {
        const val ROUTE_PROBE_PERMISSION_REQUEST = 704
    }
}
