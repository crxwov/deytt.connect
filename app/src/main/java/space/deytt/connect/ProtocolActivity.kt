package space.deytt.connect

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.PathInterpolator
import android.os.Build
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
        globe.focus(if (code == "AWG") "AUTO" else code, animate = false)
        root.addView(mapPanel(globe), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(232)))
        root.addView(spacer(15, this))
        root.addView(sectionLabel(if (code == "AWG") "серверы · задержка" else "выберите узел · измерить пинг"))
        var anchoredAction: TextView? = null
        if (routes.isEmpty()) {
            root.addView(note("Профили этой версии пока не загружены. Обновите подписку, чтобы загрузить серверы.", DeyttUi.AMBER))
            anchoredAction = button("Обновить подписку").apply {
                setOnClickListener { startActivity(Intent(this@ProtocolActivity, SetupActivity::class.java)) }
            }
        } else {
            var chosenRoute = routes.firstOrNull { it.id == selectedId }
            val routeItems = mutableListOf<Pair<DeyttRoute, LinearLayout>>()
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
                    latency.setOnClickListener { measure(route, latency) }
                    addView(latency, LinearLayout.LayoutParams(dp(68), dp(40)).apply { marginStart = dp(8) })

                    setOnClickListener {
                        if (selecting) return@setOnClickListener
                        renderChoice(route)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ValueAnimator.areAnimatorsEnabled()) {
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ValueAnimator.areAnimatorsEnabled()) {
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

    private fun measure(route: DeyttRoute, view: android.widget.TextView) {
        val generation = ++latencyGeneration
        val config = SubscriptionStore(this).readCurrent() ?: return
        val awg = AwgProfileStore(this)
        val awgConfig = if (route.engine == TunnelEngine.AMNEZIAWG) awg.read(route.id) else null
        val target = RouteLatency.target(config, route, awgConfig)
        view.text = "проверяю…"
        view.isEnabled = false
        view.alpha = .65f
        if (target == null) {
            view.text = "нет ответа"
            view.isEnabled = true
            view.alpha = 1f
            return
        }
        LatencyExecutor.pool.execute {
            val label = RouteLatency.label(RouteLatency.measure(target))
            runOnUiThread {
                if (generation == latencyGeneration && !isFinishing && !isDestroyed) {
                    view.text = label
                    view.isEnabled = true
                    view.alpha = 1f
                    view.textSize = 9f
                    view.setTextColor(if (label.contains("мс", ignoreCase = true)) DeyttUi.MINT else DeyttUi.CORAL)
                    view.contentDescription = "Задержка маршрута ${route.country}: $label"
                }
            }
        }
    }

    override fun onDestroy() {
        latencyGeneration++
        super.onDestroy()
    }

    private fun select(route: DeyttRoute) {
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
}
