package space.deytt.connect

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import space.deytt.connect.DeyttUi.AMBER
import space.deytt.connect.DeyttUi.BLUE
import space.deytt.connect.DeyttUi.BLUE_DEEP
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.SURFACE
import space.deytt.connect.DeyttUi.SURFACE_2
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.mapPanel
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.AppLanguage.uiCopy

internal class TopLevelViewPagerAdapter(
    private val createPage: (Int) -> View,
) : RecyclerView.Adapter<TopLevelViewPagerAdapter.PageHolder>() {
    private val pages = arrayOfNulls<View>(PAGE_COUNT)

    override fun getItemCount(): Int = PAGE_COUNT

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val page = pages[position] ?: createPage(position).also { pages[position] = it }
        if (page.parent !== holder.container) {
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.removeAllViews()
            holder.container.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.container.removeAllViews()
        super.onViewRecycled(holder)
    }

    fun cachedPages(): List<Pair<Int, View>> = pages.mapIndexedNotNull { index, view ->
        view?.let { index to it }
    }

    fun refresh(position: Int) {
        if (position !in pages.indices) return
        pages[position]?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pages[position] = null
        notifyItemChanged(position)
    }

    class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private companion object {
        const val PAGE_COUNT = 4
    }
}

internal class PrimaryPages(private val host: MainActivity) {
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val pairingExecutor = Executors.newSingleThreadExecutor()
    private var latestRelease: ReleaseInfo? = null
    private var updateStatus: TextView? = null
    private var updateButton: TextView? = null
    private var updateStatusText = "Готово проверить официальный релиз."

    fun create(position: Int): View = when (position) {
        1 -> routePage()
        2 -> profilePage()
        else -> settingsPage()
    }

    fun close() {
        updateExecutor.shutdownNow()
        pairingExecutor.shutdownNow()
    }

    private fun scrollPage(content: LinearLayout): ScrollView = ScrollView(host).apply {
        isFillViewport = true
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun routePage(): View {
        val config = SubscriptionStore(host).readCurrent()
        val profiles = AwgProfileStore(host).profiles()
        val routes = config?.let { RouteCatalog.from(it, profiles) }.orEmpty()
        val selectedId = SelectedRouteStore(host).read().id
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("узлы · протоколы · AmneziaWG", "Маршруты"))
        root.addView(spacer(8, host))
        root.addView(host.text(
            "Выберите выход. Изменение вступит в силу при следующем подключении.",
            13f,
            MUTED,
        ).apply { setPadding(0, 0, 0, host.dp(18)) })

        root.addView(host.sectionLabel("быстрый выбор"))
        val quickGroup = routeGroup()
        routes.firstOrNull { it.protocol == RouteProtocol.AUTO }?.let { route ->
            addMeasuredRow(
                quickGroup,
                "Автоподбор",
                "Выберем доступный узел",
                "ROUTE_AUTO",
                listOf(route),
                selectedId == route.id,
            ) { host.selectRoute(route) }
        }
        routes.firstOrNull { it.protocol == RouteProtocol.RU_DE }?.let { route ->
            addMeasuredRow(
                quickGroup,
                "Россия → Германия",
                "Двойной маршрут · Санкт-Петербург → Франкфурт",
                "ROUTE_RU_DE",
                listOf(route),
                selectedId == route.id,
            ) { host.selectRoute(route) }
        }
        if (quickGroup.childCount > 0) root.addView(quickGroup)

        root.addView(spacer(20, host))
        root.addView(host.sectionLabel("AmneziaWG · готовые профили"))
        val awgGroup = routeGroup()
        listOf("15" to "AmneziaWG 1.5", "31" to "AmneziaWG 3.1").forEach { (version, title) ->
            val familyRoutes = routes.filter {
                it.engine == TunnelEngine.AMNEZIAWG &&
                    ((version == "15" && it.protocol == RouteProtocol.AWG15) ||
                        (version == "31" && it.protocol == RouteProtocol.AWG31))
            }
            val count = familyRoutes.size
            val subtitle = when (count) {
                0 -> "Профили не найдены · обновите подписку"
                1 -> "1 сервер · выбрать точку"
                in 2..4 -> "$count сервера · выбрать точку"
                else -> "$count серверов · выбрать точку"
            }
            val ping = host.actionLabel("пинг")
            val item = host.row(title, subtitle, "AWG_MARK", "", emphasis = familyRoutes.any { it.id == selectedId }).apply {
                if (count == 0) alpha = .78f
                setOnClickListener {
                    if (count == 0) host.openSetup()
                    else host.startActivity(
                        Intent(host, ProtocolActivity::class.java)
                            .putExtra("country", "AWG")
                            .putExtra("version", version),
                    )
                }
                if (familyRoutes.isNotEmpty()) {
                    ping.setOnClickListener { measure(familyRoutes, title, ping) }
                    ping.setOnLongClickListener { showPingDiagnostics(familyRoutes, title); true }
                    ping.contentDescription = host.uiCopy("Пинг $title. Короткое нажатие проверяет один сервер, удержание — все")
                } else {
                    ping.text = "обновить"
                    ping.setOnClickListener { host.openSetup() }
                    ping.contentDescription = "Профили не найдены. Обновить подписку"
                }
                addView(ping, LinearLayout.LayoutParams(host.dp(68), host.dp(40)))
            }
            appendToGroup(awgGroup, item)
            if (familyRoutes.isNotEmpty()) item.post { measureAll(familyRoutes, title, ping) }
        }
        root.addView(awgGroup)

        root.addView(spacer(20, host))
        root.addView(host.sectionLabel("выходы по стране"))
        val countryGroup = routeGroup()
        routes.filter { it.countryCode in setOf("NL", "DE", "RU", "FI") }
            .groupBy { it.countryCode }
            .forEach { (code, countryRoutes) ->
                val first = countryRoutes.first()
                val protocols = countryRoutes.joinToString(" · ") { it.protocol.title }
                addMeasuredRow(
                    countryGroup,
                    first.country,
                    protocols,
                    first.flag,
                    countryRoutes,
                    countryRoutes.any { it.id == selectedId },
                ) {
                    host.startActivity(Intent(host, ProtocolActivity::class.java).putExtra("country", code))
                }
            }
        root.addView(countryGroup)
        root.addView(spacer(18, host))
        return scrollPage(root)
    }

    private fun routeGroup(): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        background = host.rounded(SURFACE, 20f, LINE)
        clipToOutline = true
    }

    private fun appendToGroup(group: LinearLayout, item: View) {
        if (group.childCount > 0) {
            group.addView(View(host).apply { setBackgroundColor(LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)).apply {
                    leftMargin = host.dp(54)
                    rightMargin = host.dp(16)
                })
        }
        group.addView(item)
    }

    private fun addMeasuredRow(
        group: LinearLayout,
        title: String,
        subtitle: String,
        leading: String,
        candidates: List<DeyttRoute>,
        emphasis: Boolean = false,
        onClick: () -> Unit,
    ) {
        val latency = host.actionLabel("пинг")
        val item = host.row(title, subtitle, leading, "", emphasis = emphasis).apply {
            setOnClickListener { onClick() }
        }
        latency.setOnClickListener { measure(candidates, title, latency) }
        latency.setOnLongClickListener { showPingDiagnostics(candidates, title); true }
        latency.contentDescription = "Пинг $title. Короткое нажатие проверяет один сервер, удержание — все"
        item.addView(latency, LinearLayout.LayoutParams(host.dp(68), host.dp(40)))
        appendToGroup(group, item)
        if (candidates.isNotEmpty()) item.post { measureAll(candidates, title, latency) }
    }

    private fun measure(candidates: List<DeyttRoute>, title: String, view: TextView) {
        val route = candidates.firstOrNull() ?: return
        val config = SubscriptionStore(host).readCurrent() ?: return
        val awgConfig = if (route.engine == TunnelEngine.AMNEZIAWG) AwgProfileStore(host).read(route.id) else null
        val target = RouteLatency.target(config, route, awgConfig)
        val generation = System.nanoTime()
        view.tag = generation
        view.text = host.uiCopy("проверяю…")
        view.isEnabled = false
        if (target == null) {
            view.text = host.uiCopy("нет ответа")
            view.isEnabled = true
            view.setTextColor(DeyttUi.CORAL)
            return
        }
        LatencyExecutor.pool.execute {
            val result = RouteLatency.measureDetailed(target)
            val label = result?.let { if (AppLanguage.current(host) == AppLanguage.EN) "${it.milliseconds} ms" else "${it.milliseconds}мс" }
                ?: host.uiCopy("нет ответа")
            host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed || view.tag != generation) return@runOnUiThread
                view.text = label
                view.isEnabled = true
                view.setTextColor(if (result != null) DeyttUi.MINT else DeyttUi.CORAL)
                view.contentDescription = result?.let {
                    host.uiCopy("${it.method}-проверка маршрута $title: ${it.milliseconds} миллисекунд")
                } ?: host.uiCopy("Сервер маршрута $title не ответил")
            }
        }
    }

    private fun measureAll(candidates: List<DeyttRoute>, title: String, view: TextView) {
        val config = SubscriptionStore(host).readCurrent() ?: return
        val routes = candidates.distinctBy { it.id }
        val probes = routes.mapNotNull { route ->
            val awgConfig = if (route.engine == TunnelEngine.AMNEZIAWG) AwgProfileStore(host).read(route.id) else null
            RouteLatency.target(config, route, awgConfig)?.let { route to it }
        }
        val generation = System.nanoTime()
        view.tag = generation
        if (probes.isEmpty()) {
            view.text = "—"
            view.contentDescription = host.uiCopy("Для маршрута $title нет проверяемых серверов")
            return
        }
        val finished = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<Pair<DeyttRoute, RouteLatencyResult?>>())
        view.text = "0/${probes.size}"
        view.setTextColor(DeyttUi.SKY)
        probes.forEach { (route, target) ->
            LatencyExecutor.pool.execute {
                val result = runCatching { RouteLatency.measureDetailed(target) }.getOrNull()
                results += route to result
                val completed = finished.incrementAndGet()
                host.runOnUiThread {
                    if (host.isFinishing || host.isDestroyed || view.tag != generation) return@runOnUiThread
                    val snapshot = synchronized(results) { results.toList() }
                    val successful = snapshot.mapNotNull { it.second }
                    if (completed < probes.size) view.text = "$completed/${probes.size}"
                    else view.text = successful.minOfOrNull { it.milliseconds }?.let { latency ->
                        if (AppLanguage.current(host) == AppLanguage.EN) "$latency ms" else "$latency мс"
                    } ?: host.uiCopy("нет")
                    view.setTextColor(when {
                        successful.isNotEmpty() -> DeyttUi.MINT
                        completed >= probes.size -> DeyttUi.CORAL
                        else -> DeyttUi.SKY
                    })
                    val details = snapshot.joinToString("; ") { (entry, measurement) ->
                        "${entry.protocol.title}: " + (measurement?.let { "${it.method} ${it.milliseconds} мс" } ?: "нет ответа")
                    }
                    view.contentDescription = host.uiCopy("Проверка всех серверов $title: $details")
                }
            }
        }
    }

    private fun showPingDiagnostics(candidates: List<DeyttRoute>, title: String) {
        val config = SubscriptionStore(host).readCurrent() ?: return
        val catalog = runCatching { RouteCatalog.from(config, AwgProfileStore(host).profiles()) }.getOrDefault(candidates)
        val routes = catalog.filter { it.protocol != RouteProtocol.AUTO }.distinctBy { it.id }
        if (routes.isEmpty()) return
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(20), host.dp(20), host.dp(20), host.dp(18))
            background = host.rounded(SURFACE, 24f, LINE)
        }
        sheet.addView(host.mono("ДИАГНОСТИКА · ВСЕ ТОЧКИ", 9f, MUTED, 650))
        sheet.addView(host.text("Задержка · все серверы", 19f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(6))
        })
        sheet.addView(host.text("Запрос от «$title». ICMP, затем TCP при необходимости; результаты появятся по мере ответа.", 12f, MUTED).apply {
            setPadding(0, 0, 0, host.dp(14))
        })
        val resultViews = mutableMapOf<String, TextView>()
        val list = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = host.rounded(SURFACE_2, 18f, LINE)
            clipToOutline = true
        }
        routes.forEachIndexed { index, route ->
            if (index > 0) list.addView(View(host).apply { setBackgroundColor(LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)).apply {
                    leftMargin = host.dp(54)
                    rightMargin = host.dp(14)
                })
            val result = host.actionLabel("проверяю…").apply {
                isClickable = false
                contentDescription = "Проверяю задержку ${route.country} ${route.protocol.title}"
            }
            resultViews[route.id] = result
            list.addView(host.row(
                route.country,
                route.protocol.title,
                when {
                    route.engine == TunnelEngine.AMNEZIAWG -> "AWG_MARK"
                    route.protocol == RouteProtocol.RU_DE -> "ROUTE_RU_DE"
                    else -> route.flag
                },
                "",
                interactive = false,
            ).apply { addView(result, LinearLayout.LayoutParams(host.dp(92), host.dp(40))) })
        }
        val screenHeightDp = (host.resources.displayMetrics.heightPixels / host.resources.displayMetrics.density).toInt()
        val listHeightDp = (screenHeightDp * .45f).toInt().coerceIn(240, 440)
        sheet.addView(ScrollView(host).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(listHeightDp)))
        sheet.addView(host.button("Готово", secondary = true).apply { setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply { topMargin = host.dp(16) })
        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(host.dp(14), 0, host.dp(14), host.dp(20))
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        routes.forEach { route ->
            val view = resultViews[route.id] ?: return@forEach
            val awgConfig = if (route.engine == TunnelEngine.AMNEZIAWG) AwgProfileStore(host).read(route.id) else null
            val target = RouteLatency.target(config, route, awgConfig)
            if (target == null) {
                view.text = "нет ответа"
                view.setTextColor(DeyttUi.CORAL)
                return@forEach
            }
            LatencyExecutor.pool.execute {
                val measurement = runCatching { RouteLatency.measureDetailed(target) }.getOrNull()
                host.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    view.text = measurement?.let { "${it.method} ${it.milliseconds}мс" } ?: "нет ответа"
                    view.setTextColor(if (measurement != null) DeyttUi.MINT else DeyttUi.CORAL)
                    view.contentDescription = measurement?.let {
                        "${it.method}-задержка ${route.country} ${route.protocol.title}: ${it.milliseconds} миллисекунд"
                    } ?: "Сервер ${route.country} ${route.protocol.title} не ответил"
                }
            }
        }
    }

    private fun profilePage(): View {
        val metadata = SubscriptionMetadataStore(host).read()
        val profiles = AwgProfileStore(host).profiles()
        val usage = metadata.usedBytes
        val limit = metadata.totalBytes
        val fraction = if (limit > 0) (usage.toDouble() / limit).toFloat().coerceIn(0f, 1f) else 0f
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("подписка · устройства · ключи", "Профиль"))
        root.addView(spacer(10, host))
        root.addView(host.sectionLabel("трафик подписки"))
        val subscription = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(19), host.dp(18), host.dp(19), host.dp(17))
            background = host.rounded(SURFACE, 20f, LINE)
            addView(host.text(metadata.title, 13f, MUTED).apply { maxLines = 2 })
            addView(host.text(formatBytes(usage), 32f, TEXT, android.graphics.Typeface.BOLD).apply {
                setPadding(0, host.dp(12), 0, 0)
            })
            addView(host.text(
                if (limit > 0) "из ${formatBytes(limit)} использовано" else "использовано · без установленного лимита",
                12f,
                MUTED,
            ).apply { setPadding(0, host.dp(3), 0, 0) })
            if (limit > 0) addView(UsageBar(host, fraction),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(6)).apply { topMargin = host.dp(15) })
            addView(View(host).apply { setBackgroundColor(LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)).apply { topMargin = host.dp(16) })
            addView(LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, host.dp(13), 0, 0)
                addView(stat("ЗАГРУЖЕНО", formatBytes(metadata.downloadBytes)),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(stat("ОТДАНО", formatBytes(metadata.uploadBytes)),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, host.dp(15), 0, 0)
                addView(stat("ЛИМИТ", if (limit > 0) formatBytes(limit) else "Без лимита"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(stat("ДЕЙСТВУЕТ ДО", metadata.expiresAtSeconds?.let(::formatDate) ?: "Без срока"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        root.addView(subscription)

        root.addView(spacer(20, host))
        root.addView(host.sectionLabel("конфигурации на устройстве"))
        val awg15 = profiles.count { it.version == "15" }
        val awg31 = profiles.count { it.version == "31" }
        root.addView(host.row("AmneziaWG 1.5", profileCountLabel(awg15), "AWG_MARK", "открыть").apply {
            setOnClickListener { host.selectTab(1) }
        })
        root.addView(host.row("AmneziaWG 3.1", profileCountLabel(awg31), "AWG_MARK", "открыть").apply {
            setOnClickListener { host.selectTab(1) }
        })

        root.addView(spacer(18, host))
        root.addView(host.sectionLabel("действия"))
        root.addView(host.row("Продлить или сменить тариф", "Тарифы и увеличение лимита в Telegram", "↗", "бот").apply {
            setOnClickListener { openUrl("https://t.me/deyttbot") }
        })
        root.addView(spacer(6, host))
        root.addView(host.button("Импортировать подписку", secondary = true).apply {
            setOnClickListener { host.openSetup() }
        })
        root.addView(spacer(10, host))
        root.addView(host.row("Сбросить ключи в Telegram", "Переход к подтверждению в боте", "↻", "открыть").apply {
            setOnClickListener { confirmTelegramReset() }
        })
        root.addView(host.row("Поддержка", "Написать команде DEYTT", "↗", "Telegram").apply {
            setOnClickListener { openUrl("https://t.me/deytt_support") }
        })
        root.addView(spacer(12, host))
        root.addView(host.note("Сброс — только после подтверждения в Telegram.", MUTED))
        root.addView(spacer(20, host))
        return scrollPage(root)
    }

    private fun stat(label: String, value: String): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        addView(host.mono(label, 9f, MUTED, 560))
        addView(host.text(value, 14f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(5), host.dp(7), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }

    private fun profileCountLabel(count: Int): String = when (count) {
        0 -> "Нет профилей · обновите подписку"
        1 -> "1 профиль загружен"
        in 2..4 -> "$count профиля загружено"
        else -> "$count профилей загружено"
    }

    private fun confirmTelegramReset() {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(SURFACE, 24f, LINE)
        }
        sheet.addView(host.mono("КЛЮЧИ · ПОДТВЕРЖДЕНИЕ", 9f, MUTED, 650))
        sheet.addView(host.text("Открыть управление ключами?", 19f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(7))
        })
        sheet.addView(host.text(
            "Бот покажет действие сброса и попросит подтвердить его отдельно. Существующие ключи не изменятся, пока вы не подтвердите операцию там.",
            13f,
            MUTED,
        ).apply { setLineSpacing(host.dp(3).toFloat(), 1f) })
        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, host.dp(16), 0, 0)
        }
        actions.addView(host.button("Отмена", secondary = true).apply {
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, host.dp(48), 1f).apply { marginEnd = host.dp(8) })
        actions.addView(host.button("Открыть бота").apply {
            setOnClickListener {
                dialog.dismiss()
                openUrl("https://t.me/deyttbot")
            }
        }, LinearLayout.LayoutParams(0, host.dp(48), 1f))
        sheet.addView(actions)
        showSheet(dialog, sheet)
    }

    private fun showSheet(dialog: Dialog, content: View) {
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.58f)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.setPadding(host.dp(14), 0, host.dp(14), host.dp(18))
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun settingsPage(): View {
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("версия ${BuildConfig.VERSION_NAME}", "Настройки"))
        root.addView(spacer(12, host))
        root.addView(host.sectionLabel("язык"))
        root.addView(host.row("Язык приложения", AppLanguage.label(host), "Aa", AppLanguage.label(host)).apply {
            setOnClickListener {
                val dialog = Dialog(host)
                val sheet = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(host.dp(20), host.dp(20), host.dp(20), host.dp(18))
                    background = host.rounded(SURFACE, 24f, LINE)
                }
                sheet.addView(host.mono("НАСТРОЙКИ · ЯЗЫК", 9f, MUTED, 650))
                sheet.addView(host.text("Язык приложения", 20f, TEXT, android.graphics.Typeface.BOLD).apply {
                    setPadding(0, host.dp(10), 0, host.dp(8))
                })
                sheet.addView(host.text("Смена применяется сразу ко всем вкладкам.", 12f, MUTED).apply {
                    setPadding(0, 0, 0, host.dp(12))
                })
                listOf(AppLanguage.RU to "Русский", AppLanguage.EN to "English").forEach { (code, label) ->
                    val selected = code == AppLanguage.current(host)
                    sheet.addView(host.row(
                        label,
                        if (selected) "Текущий язык" else "Выбрать",
                        if (selected) "✓" else "○",
                        if (selected) "выбрано" else "",
                        emphasis = selected,
                    ).apply {
                        setOnClickListener {
                            dialog.dismiss()
                            if (code != AppLanguage.current(host)) {
                                AppLanguage.set(host, code)
                                host.recreate()
                            }
                        }
                    })
                }
                sheet.addView(host.button("Готово", secondary = true).apply {
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
                    topMargin = host.dp(12)
                })
                showSheet(dialog, sheet)
            }
        })
        root.addView(spacer(12, host))
        root.addView(host.sectionLabel("аккаунт"))
        val sessionToken = TelegramSessionStore.read(host)
        root.addView(host.row(
            if (sessionToken == null) "Добавить приложение" else "Telegram подключён",
            if (sessionToken == null) "Подтвердить аккаунт и загрузить профили из бота"
            else "Профиль и подписка связаны с этим устройством",
            if (sessionToken == null) "↗" else "✓",
            if (sessionToken == null) "добавить" else "управлять",
            emphasis = sessionToken != null,
        ).apply {
            setOnClickListener {
                if (TelegramSessionStore.read(host) == null) showTelegramPairing()
                else showLinkedTelegramAccount()
            }
        })
        root.addView(spacer(12, host))
        root.addView(host.sectionLabel("официальный релиз"))
        updateButton = host.button("Проверить обновления").apply {
            setOnClickListener { if (latestRelease == null) checkForUpdate() else openLatest() }
        }
        updateStatus = host.text(updateStatusText, 12f, MUTED).apply { setPadding(host.dp(2), host.dp(10), 0, 0) }
        root.addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(16), host.dp(15), host.dp(16), host.dp(16))
            background = host.rounded(SURFACE, 20f, LINE)
            addView(updateStatus)
            addView(updateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(54)).apply {
                topMargin = host.dp(15)
            })
        })
        root.addView(spacer(20, host))
        root.addView(host.sectionLabel("подключение"))
        val probeSummary = { host.uiCopy("Через двойной маршрут · 10 с · ${RouteProbePreferences.method(host).title}") }
        val probeRow = host.row(
            "Проверка маршрута",
            probeSummary(),
            "↻",
            "метод",
        ).apply {
            val details = getChildAt(1) as? LinearLayout
            val summary = details?.getChildAt(1) as? TextView
            setOnClickListener {
                val methods = RouteProbeMethod.values()
                val selectedMethod = RouteProbePreferences.method(host)
                val dialog = Dialog(host)
                val sheet = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(host.dp(20), host.dp(20), host.dp(20), host.dp(18))
                    background = host.rounded(SURFACE, 24f, LINE)
                }
                sheet.addView(host.mono("ПОДКЛЮЧЕНИЕ · ДИАГНОСТИКА", 9f, MUTED, 650))
                sheet.addView(host.text("Метод проверки", 20f, TEXT, android.graphics.Typeface.BOLD).apply {
                    setPadding(0, host.dp(10), 0, host.dp(8))
                })
                sheet.addView(host.text("Проверка идёт через выбранный двойной маршрут. Запрос ограничен десятью секундами.", 12f, MUTED).apply {
                    setPadding(0, 0, 0, host.dp(12))
                })
                methods.forEach { method ->
                    val isSelected = method == selectedMethod
                    val label = if (method == RouteProbeMethod.HEAD) "Короткий запрос HEAD" else "Запрос GET с ответом"
                    val detail = if (method == RouteProbeMethod.HEAD) "Проверяет заголовки, меньше данных" else "Проверяет доступность ответа целиком"
                    sheet.addView(host.row(label, detail, if (isSelected) "✓" else "○", if (isSelected) "выбрано" else "",
                        emphasis = isSelected).apply {
                        setOnClickListener {
                            RouteProbePreferences.saveMethod(host, method)
                            summary?.text = probeSummary()
                            contentDescription = host.uiCopy("Проверка маршрута: через двойной маршрут, тайм-аут 10 секунд, метод ${method.title}")
                            dialog.dismiss()
                        }
                    })
                }
                sheet.addView(host.button("Готово", secondary = true).apply {
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
                    topMargin = host.dp(14)
                })
                showSheet(dialog, sheet)
            }
        }
        root.addView(probeRow)
        root.addView(host.row("Разрешения соединения Android", "Системные разрешения и блокировка", "↗", "открыть").apply {
            setOnClickListener {
                runCatching { host.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }
        })
        root.addView(host.row("Бот DEYTT", "Ключи, подписка и другие действия", "↗", "Telegram").apply {
            setOnClickListener { openUrl("https://t.me/deyttbot") }
        })
        root.addView(spacer(16, host))
        root.addView(host.sectionLabel("приватность"))
        val preferences = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(15), host.dp(5), host.dp(15), host.dp(5))
            background = host.rounded(SURFACE, 20f, LINE)
            addView(preferenceSwitch(
                "Показывать сеть на карте",
                "ipinfo.io видит IP запроса; координаты в приложении не сохраняются. GPS не используется.",
                host.isNetworkLocationEnabled(),
                host::setNetworkLocationEnabled,
            ))
            addView(View(host).apply { setBackgroundColor(LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(1)).apply {
                    leftMargin = host.dp(1)
                    rightMargin = host.dp(1)
                })
            addView(preferenceSwitch(
                "Сократить анимацию фона",
                "Остановить мерцание звёздного неба.",
                host.isReducedMotionEnabled(),
                host::setReducedMotionEnabled,
            ))
        }
        root.addView(preferences)
        root.addView(spacer(10, host))
        root.addView(host.note(
            "Карта маршрутов встроена и работает офлайн. Чтобы определить регион, запрос с IP-адресом получает ipinfo.io. Приложение не сохраняет IP или координаты: место остаётся только в памяти до закрытия. Выключение функции сразу убирает точку с карты.",
            TEXT,
        ))
        root.addView(spacer(20, host))
        return scrollPage(root)
    }

    private fun checkForUpdate() {
        updateButton?.isEnabled = false
        updateButton?.alpha = .68f
        updateStatus?.apply {
            text = host.uiCopy("Проверяем официальный релиз…")
            setTextColor(DeyttUi.SKY)
        }
        updateStatusText = "Проверяем официальный релиз…"
        updateExecutor.execute {
            runCatching { UpdateChecker.latest() }
                .onSuccess { release -> host.runOnUiThread {
                    updateButton?.isEnabled = true
                    updateButton?.alpha = 1f
                    if (!ReleaseVersion.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                        latestRelease = null
                        updateStatusText = "Установлена последняя версия."
                        updateButton?.text = host.uiCopy("Проверить снова")
                    } else {
                        latestRelease = release
                        updateStatusText = "Доступна ${release.tag}. Откройте официальный релиз и проверьте APK перед установкой."
                        updateButton?.text = host.uiCopy("Открыть официальный релиз")
                    }
                    updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(DeyttUi.MINT) }
                } }
                .onFailure {
                    host.runOnUiThread {
                        latestRelease = null
                        updateStatusText = "Не удалось проверить официальный релиз. Повторите попытку позже."
                        updateButton?.apply { isEnabled = true; alpha = 1f; text = host.uiCopy("Проверить обновления") }
                        updateStatus?.apply { text = host.uiCopy(updateStatusText); setTextColor(DeyttUi.CORAL) }
                    }
                }
        }
    }

    private fun showTelegramPairing() {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(SURFACE, 24f, LINE)
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
        val openBot = host.button("Открыть бота и нажать Start", secondary = true)
        sheet.addView(openBot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48),
        ).apply { topMargin = host.dp(13) })
        val primary = host.button("Продолжить")
        sheet.addView(primary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, host.dp(50),
        ).apply { topMargin = host.dp(9) })

        var challenge: String? = null
        var botUrl: String? = null
        var step = 0
        fun setStep(value: Int) {
            step = value
            usernameField.visibility = if (step == 0) View.VISIBLE else View.GONE
            codeField.visibility = if (step == 1) View.VISIBLE else View.GONE
            openBot.visibility = if (step == 1) View.VISIBLE else View.GONE
            primary.text = when (step) {
                0 -> "Получить код"
                1 -> "Подтвердить код"
                else -> "Готово"
            }
            title.text = when (step) {
                0 -> "Добавить приложение"
                1 -> "Проверь Telegram"
                else -> "Аккаунт подключён"
            }
            detail.text = when (step) {
                0 -> "Укажи Telegram username. Бот подтвердит вход одноразовым кодом и подключит существующие профили."
                1 -> "Проверь личный чат с ./c. Если кода нет, открой бота кнопкой ниже и нажми Start."
                else -> ""
            }
        }
        fun openBotLink() {
            val link = botUrl ?: return
            runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                .onFailure { status.text = "Ссылка готова. Открой бота ещё раз кнопкой ниже." }
        }

        openBot.setOnClickListener { openBotLink() }
        primary.setOnClickListener {
            when (step) {
                0 -> {
                    val username = usernameField.text.toString().trim()
                    if (!Regex("@?[A-Za-z0-9_]{5,32}").matches(username)) {
                        status.text = "Введи username Telegram длиной от 5 до 32 знаков."
                        status.setTextColor(DeyttUi.CORAL)
                        return@setOnClickListener
                    }
                    primary.isEnabled = false
                    primary.text = "Создаём ссылку…"
                    status.setTextColor(BLUE)
                    status.text = "Подготавливаем одноразовое подтверждение."
                    pairingExecutor.execute {
                        val result = runCatching { TelegramPairingClient.start(username) }
                        host.runOnUiThread {
                            if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                            primary.isEnabled = true
                            result.onSuccess {
                                challenge = it.challenge
                                botUrl = it.botUrl
                                setStep(1)
                                status.setTextColor(BLUE)
                                status.text = "Проверь Telegram: код действует 5 минут. Если сообщения нет, открой бота кнопкой ниже и нажми Start."
                            }.onFailure {
                                primary.text = "Продолжить"
                                status.setTextColor(DeyttUi.CORAL)
                                status.text = pairingError(it)
                            }
                        }
                    }
                }
                1 -> {
                    val currentChallenge = challenge
                    val code = codeField.text.toString().trim()
                    if (currentChallenge == null || !Regex("\\d{6}").matches(code)) {
                        status.text = "Введи шесть цифр из сообщения бота."
                        status.setTextColor(DeyttUi.CORAL)
                        return@setOnClickListener
                    }
                    primary.isEnabled = false
                    primary.text = "Проверяем код…"
                    status.text = "Подтверждаем Telegram-аккаунт и загружаем профили."
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
                                    host.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE)
                                        .edit().putString("subscription_url", imported.url).apply()
                                    val routes = RouteCatalog.from(
                                        SubscriptionStore(host).readCurrent().orEmpty(),
                                        AwgProfileStore(host).profiles(),
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
                                status.text = message
                                setStep(2)
                                host.updateTelegramIdentity(session.username, null)
                                host.refreshTelegramAccount()
                                host.refreshAccountViews()
                            }.onFailure {
                                primary.text = "Подтвердить код"
                                status.setTextColor(DeyttUi.CORAL)
                                status.text = pairingError(it)
                            }
                        }
                    }
                }
                else -> dialog.dismiss()
            }
        }
        setStep(0)
        showSheet(dialog, sheet)
    }

    private fun pairingError(error: Throwable): String = when ((error as? TelegramPairingException)?.code) {
        "invalid_username" -> "Проверь username Telegram."
        "pairing_rate_limited", "rate_limited" -> "Слишком частые попытки. Подожди немного и повтори."
        "pair_code_locked" -> "Лимит попыток исчерпан. Создай новую ссылку."
        "pair_code_invalid" -> "Код неверный или уже истёк. Проверь Telegram или создай новую ссылку."
        "account_blocked", "blocked" -> "Для этого аккаунта вход недоступен. Напиши в поддержку."
        else -> "Не удалось завершить вход. Проверь соединение и попробуй ещё раз."
    }

    private fun showLinkedTelegramAccount() {
        val token = TelegramSessionStore.read(host) ?: run {
            showTelegramPairing()
            return
        }
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(SURFACE, 24f, LINE)
        }
        sheet.addView(host.mono("./c · TELEGRAM", 9f, MUTED, 650))
        sheet.addView(host.text("Аккаунт подключён", 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(7))
        })
        sheet.addView(host.text(
            "Профиль и сессия связаны с Telegram. Переподключение загрузит свежие профили; отключение завершит эту сессию.",
            13f, MUTED,
        ).apply { setLineSpacing(host.dp(3).toFloat(), 1f) })
        sheet.addView(host.button("Переподключить", secondary = true).apply {
            setOnClickListener {
                dialog.dismiss()
                showTelegramPairing()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
            topMargin = host.dp(14)
        })
        sheet.addView(host.button("Отключить аккаунт", secondary = true).apply {
            setOnClickListener {
                dialog.dismiss()
                confirmTelegramLogout(token)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
            topMargin = host.dp(8)
        })
        showSheet(dialog, sheet)
    }

    private fun confirmTelegramLogout(token: String) {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(SURFACE, 24f, LINE)
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
            unlink.text = "Завершаем…"
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
                        unlink.text = "Повторить"
                        status.text = "Не удалось отозвать сессию. Проверь соединение и повтори."
                    }
                }
            }
        }
        sheet.addView(actions)
        showSheet(dialog, sheet)
    }

    private fun openLatest() {
        latestRelease?.pageUrl?.takeIf(ReleaseUrlPolicy::isOfficialPage)?.let(::openUrl)
            ?: updateStatus?.apply { text = "Ссылка на релиз не прошла проверку." }
    }

    private fun preferenceSwitch(
        title: String,
        description: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(1), host.dp(11), host.dp(1), host.dp(11))
        val copy = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(host.text(title, 14f, TEXT, android.graphics.Typeface.BOLD))
            addView(host.text(description, 11f, MUTED).apply {
                setPadding(0, host.dp(4), host.dp(8), 0)
            })
        }
        val toggle = Switch(host).apply {
            isChecked = checked
            contentDescription = title
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(DeyttUi.MINT, 0xFF8C96A5.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0xA63CDCC0.toInt(), 0x665B6675.toInt()))
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun openUrl(url: String) {
        runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun formatBytes(value: Long): String {
        val english = AppLanguage.current(host) == AppLanguage.EN
        if (value <= 0) return if (english) "0 B" else "0 Б"
        val units = if (english) arrayOf("B", "KB", "MB", "GB", "TB") else arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    private fun formatDate(seconds: Long): String {
        val locale = if (AppLanguage.current(host) == AppLanguage.EN) Locale.ENGLISH else Locale("ru")
        return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(seconds * 1000))
    }
}

private class UsageBar(context: MainActivity, private val fraction: Float) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        paint.color = SURFACE_2
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        rect.right = (width * fraction).coerceAtLeast(if (fraction > 0) h else 0f)
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(BLUE, BLUE_DEEP),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        paint.shader = null
    }
}
