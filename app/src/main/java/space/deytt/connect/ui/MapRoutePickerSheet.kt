package space.deytt.connect

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.AMBER
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MINT
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.SELECTED
import space.deytt.connect.DeyttUi.SELECTED_LINE
import space.deytt.connect.DeyttUi.SKY
import space.deytt.connect.DeyttUi.SURFACE
import space.deytt.connect.DeyttUi.SURFACE_2
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

internal class MapRoutePickerSheet(
    private val host: MainActivity,
    private val selectRoute: (DeyttRoute) -> Unit,
    private val selectTab: (Int) -> Unit,
    private val currentNetworkLocation: () -> IpNetworkLocation?,
) {
    private var currentMapRouteSheet: Dialog? = null

    fun show(node: String) {
        val english = AppLanguage.current(host) == AppLanguage.EN
        fun mapCopy(ru: String, en: String): String = (if (english) en else ru).lowercase()
        if (node == "user") {
            val place = currentNetworkLocation()?.placeLabel?.let { AppLanguage.locationLabel(host, it) }
                ?: mapCopy("Точка входа с этого устройства", "This device’s entry point")
            AppDialog.Builder(host)
                .setTitle(mapCopy("Точка входа", "Entry point"))
                .setMessage(mapCopy("$place — примерное место по IP. Это не сервер выхода и на выбор маршрута не влияет.", "$place is an approximate location based on your IP. It is not an exit server and does not affect route selection."))
                .setPositiveButton(mapCopy("Понятно", "Got it"), null)
                .show()
            return
        }
        val countryCode = when (node) {
            "nl" -> "NL"
            "de" -> "DE"
            "fi" -> "FI"
            "ru" -> "RU"
            "it" -> "IT"
            else -> return
        }
        val config = SubscriptionStore(host).readCurrent() ?: return
        val routes = RouteCatalog.from(config, AwgProfileStore(host).profiles(), OpenFluxProfileStore(host).profiles(host))
        val automatic = routes.firstOrNull { it.protocol == RouteProtocol.AUTO }
        val countryRoutes = routes.filter {
            it.countryCode == countryCode && (
                it.engine == TunnelEngine.AMNEZIAWG || it.engine == TunnelEngine.OPENFLUX ||
                    it.engine == TunnelEngine.LIBBOX &&
                    it.protocol in setOf(RouteProtocol.VLESS, RouteProtocol.TROJAN, RouteProtocol.HYSTERIA2, RouteProtocol.TUIC, RouteProtocol.LTE)
                )
        }
        val choices = listOfNotNull(automatic) + countryRoutes
        val countryName = when (countryCode) {
            "NL" -> mapCopy("нидерланды", "netherlands")
            "DE" -> mapCopy("германия", "germany")
            "FI" -> mapCopy("финляндия", "finland")
            "IT" -> mapCopy("италия", "italy")
            else -> mapCopy("россия", "russia")
        }
        if (choices.isEmpty()) {
            AppDialog.Builder(host)
                .setTitle(countryName)
                .setMessage(mapCopy("Для этой точки пока нет маршрута в подписке.", "Your subscription has no route for this location yet."))
                .setPositiveButton(mapCopy("Открыть маршруты", "Open routes")) { _, _ -> selectTab(1) }
                .setNegativeButton(mapCopy("Закрыть", "Close"), null)
                .show()
            return
        }

        val selectedId = SelectedRouteStore(host).read().id
        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(10), host.dp(22), host.dp(22))
            background = host.rounded(SURFACE, 26f, LINE)
        }
        View(host).apply {
            background = host.rounded(0xFF465368.toInt(), 2f, 0xFF465368.toInt())
        }.also { panel.addView(it, LinearLayout.LayoutParams(host.dp(34), host.dp(4)).apply { gravity = Gravity.CENTER }) }
        panel.addView(host.mono(mapCopy("выход", "exit") + "  ·  $countryName", 9f, SKY, 600).apply {
            letterSpacing = .08f
            setPadding(0, host.dp(18), 0, host.dp(5))
        })
        panel.addView(host.text(countryName, 23f, TEXT).apply {
            letterSpacing = -.03f
            setPadding(0, 0, 0, host.dp(4))
        })
        panel.addView(host.text(mapCopy("Выберите способ подключения или проверьте протоколы.", "Choose a connection method or compare protocols."), 12f, MUTED).apply {
            setPadding(0, 0, 0, host.dp(12))
        })
        if (ConnectVpnService.isRunning() || AwgTunnelController.isRunning()) {
            panel.addView(host.note(mapCopy("Смена выхода остановит текущее соединение. Новый маршрут нужно будет запустить снова.", "Changing the exit stops your current connection. Connect again to use the new route."), AMBER))
            panel.addView(spacer(8, host))
        }

        val options = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        choices.forEachIndexed { index, route ->
            val selected = route.id == selectedId
            val title = when (route.protocol) {
                RouteProtocol.AUTO -> mapCopy("автоподбор", "auto-select")
                RouteProtocol.LTE -> "lte • белые списки"
                else -> route.protocol.title
            }
            val detail = when (route.protocol) {
                RouteProtocol.AUTO -> mapCopy("Выбрать доступный выход автоматически", "Select an available exit automatically")
                RouteProtocol.LTE -> mapCopy("Белые списки LTE · лимит 10 ГБ в месяц", "Whitelist LTE · 10 GB monthly limit")
                RouteProtocol.HYSTERIA2 -> mapCopy("быстрый quic-маршрут", "fast quic route")
                RouteProtocol.TUIC -> mapCopy("оптимизированный quic-маршрут", "optimized quic route")
                else -> host.uiCopy(route.protocol.detail)
            }
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = host.dp(64)
                setPadding(host.dp(13), host.dp(8), host.dp(13), host.dp(8))
                background = host.rounded(if (selected) SELECTED else SURFACE_2, 16f,
                    if (selected) SELECTED_LINE else LINE)
                isClickable = true
                isFocusable = true
                contentDescription = "$title. $detail${if (selected) mapCopy(". Текущий маршрут", ". Current route") else ""}"
                addView(host.text(route.flag, 19f, TEXT).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(host.dp(42), host.dp(42)).apply { marginEnd = host.dp(11) })
                addView(LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(host.text(title, 15f, TEXT))
                    addView(host.text(detail, 10.5f, MUTED).apply {
                        setPadding(0, host.dp(3), 0, 0)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (selected) addView(host.mono(mapCopy("выбран", "selected"), 8f, MINT, 600))
                setOnClickListener {
                    if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) &&
                        !host.getSharedPreferences("profile_settings", Context.MODE_PRIVATE).getBoolean("reduced_motion", false)) {
                        animate().cancel()
                        scaleX = .985f
                        scaleY = .985f
                        animate().scaleX(1f).scaleY(1f).setDuration(170L).start()
                    }
                    sheetRouteSelection(route)
                }
            }
            options.addView(row)
            if (index < choices.lastIndex) options.addView(spacer(7, host))
            if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()) &&
                !host.getSharedPreferences("profile_settings", Context.MODE_PRIVATE).getBoolean("reduced_motion", false)) {
                row.alpha = 0f
                row.translationY = host.dp(7).toFloat()
                row.postDelayed({
                    if (!host.isFinishing && !host.isDestroyed) row.animate().alpha(1f).translationY(0f).setDuration(180L).start()
                }, index * 24L)
            }
        }
        val scroll = ScrollView(host).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(options)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val comparableCode = countryCode.takeIf { countryRoutes.size > 1 }
        if (comparableCode != null) {
            panel.addView(host.button(mapCopy("Сравнить протоколы", "Compare protocols"), secondary = true).apply {
                setOnClickListener {
                    selectTab(1)
                    currentMapRouteSheet?.dismiss()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(50)).apply { topMargin = host.dp(12) })
        }

        val dialog = Dialog(host)
        currentMapRouteSheet = dialog
        val container = FrameLayout(host).apply {
            setPadding(host.dp(14), 0, host.dp(14), host.dp(20))
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { dialog.dismiss() }
        }
        panel.setOnClickListener { /* prevent dismiss */ }
        dialog.setContentView(container)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.55f)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            DeyttUi.blurBehind(this, host.dp(24))
        }
        val reducedMotion = host.getSharedPreferences("profile_settings", Context.MODE_PRIVATE).getBoolean("reduced_motion", false)
        if (!reducedMotion && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())) {
            panel.alpha = 0f
            panel.translationY = host.dp(26).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(230L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f)).start()
        }
    }

    private fun sheetRouteSelection(route: DeyttRoute) {
        currentMapRouteSheet?.dismiss()
        currentMapRouteSheet = null
        selectRoute(route)
    }

    fun dismiss() {
        currentMapRouteSheet?.dismiss()
        currentMapRouteSheet = null
    }
}
