package space.deytt.connect

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
                "AUTO",
                listOf(route),
                selectedId == route.id,
            ) { host.selectRoute(route) }
        }
        routes.firstOrNull { it.protocol == RouteProtocol.RU_DE }?.let { route ->
            addMeasuredRow(
                quickGroup,
                "Россия → Германия",
                "Двойной маршрут · Санкт-Петербург → Франкфурт",
                "2×",
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
            val item = host.row(title, subtitle, if (version == "15") "1.5" else "3.1", if (count == 0) "обновить" else "открыть", emphasis = familyRoutes.any { it.id == selectedId }).apply {
                if (count == 0) alpha = .78f
                setOnClickListener {
                    if (count == 0) host.openSetup()
                    else host.startActivity(
                        Intent(host, ProtocolActivity::class.java)
                            .putExtra("country", "AWG")
                            .putExtra("version", version),
                    )
                }
            }
            appendToGroup(awgGroup, item)
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
        val latency = host.actionLabel()
        val item = host.row(title, subtitle, leading, "", emphasis = emphasis).apply {
            setOnClickListener { onClick() }
        }
        latency.setOnClickListener { measure(candidates, title, latency) }
        item.addView(latency, LinearLayout.LayoutParams(host.dp(68), host.dp(40)))
        appendToGroup(group, item)
    }

    private fun measure(candidates: List<DeyttRoute>, title: String, view: TextView) {
        val route = candidates.firstOrNull() ?: return
        val config = SubscriptionStore(host).readCurrent() ?: return
        val awgConfig = if (route.engine == TunnelEngine.AMNEZIAWG) AwgProfileStore(host).read(route.id) else null
        val target = RouteLatency.target(config, route, awgConfig)
        val generation = System.nanoTime()
        view.tag = generation
        view.text = "проверяю…"
        view.isEnabled = false
        if (target == null) {
            view.text = "нет ответа"
            view.isEnabled = true
            view.setTextColor(DeyttUi.CORAL)
            return
        }
        LatencyExecutor.pool.execute {
            val label = RouteLatency.label(RouteLatency.measure(target))
            host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed || view.tag != generation) return@runOnUiThread
                view.text = label
                view.isEnabled = true
                view.setTextColor(if (label.contains("мс", ignoreCase = true)) DeyttUi.MINT else DeyttUi.CORAL)
                view.contentDescription = "Задержка маршрута $title: $label"
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
        root.addView(host.row("AmneziaWG 1.5", profileCountLabel(awg15), "1.5", "открыть").apply {
            setOnClickListener { host.selectTab(1) }
        })
        root.addView(host.row("AmneziaWG 3.1", profileCountLabel(awg31), "3.1", "открыть").apply {
            setOnClickListener { host.selectTab(1) }
        })

        root.addView(spacer(18, host))
        root.addView(host.sectionLabel("действия"))
        root.addView(host.button("Обновить подписку", secondary = true).apply {
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
        AlertDialog.Builder(host)
            .setTitle("Продолжить сброс в Telegram?")
            .setMessage("Сброс отзывает существующие ключи и создаёт новые. Откроется бот DEYTT, где операция потребует ещё одного подтверждения.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть бота") { _, _ -> openUrl("https://t.me/deyttbot") }
            .show()
    }

    private fun settingsPage(): View {
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("версия ${BuildConfig.VERSION_NAME}", "Настройки"))
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
        val probeMethod = RouteProbePreferences.method(host)
        val probeRow = host.row(
            "Проверка маршрута",
            "Via Proxy · Double · 10 с · ${probeMethod.title}",
            "↻",
            "настроить",
        ).apply {
            val details = getChildAt(1) as? LinearLayout
            val summary = details?.getChildAt(1) as? TextView
            setOnClickListener {
                val methods = RouteProbeMethod.values()
                AlertDialog.Builder(host)
                    .setTitle("Метод проверки через прокси")
                    .setSingleChoiceItems(
                        arrayOf("HEAD · короткий запрос", "GET · запрос с ответом"),
                        methods.indexOf(RouteProbePreferences.method(host)),
                    ) { dialog, which ->
                        methods.getOrNull(which)?.let { method ->
                            RouteProbePreferences.saveMethod(host, method)
                            summary?.text = "Via Proxy · Double · 10 с · ${method.title}"
                            contentDescription = "Проверка маршрута: Via Proxy, Double, тайм-аут 10 секунд, метод ${method.title}"
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Закрыть", null)
                .show()
            }
        }
        root.addView(probeRow)
        root.addView(host.row("Настройки VPN Android", "Системные разрешения и блокировка", "↗", "открыть").apply {
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
                "Примерный город по IP через ipinfo.io. GPS не используется.",
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
            "Карта маршрутов встроена и работает офлайн. Для точки входа приложение отправляет запрос к ipinfo.io; IP-адрес не сохраняется, приблизительное место кэшируется на устройстве. Выключение функции стирает кэш.",
            TEXT,
        ))
        root.addView(spacer(20, host))
        return scrollPage(root)
    }

    private fun checkForUpdate() {
        updateButton?.isEnabled = false
        updateButton?.alpha = .68f
        updateStatus?.apply {
            text = "Проверяем официальный релиз…"
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
                        updateButton?.text = "Проверить снова"
                    } else {
                        latestRelease = release
                        updateStatusText = "Доступна ${release.tag}. Откройте официальный релиз и проверьте APK перед установкой."
                        updateButton?.text = "Открыть официальный релиз"
                    }
                    updateStatus?.apply { text = updateStatusText; setTextColor(DeyttUi.MINT) }
                } }
                .onFailure {
                    host.runOnUiThread {
                        latestRelease = null
                        updateStatusText = "Не удалось проверить официальный релиз. Повторите попытку позже."
                        updateButton?.apply { isEnabled = true; alpha = 1f; text = "Проверить обновления" }
                        updateStatus?.apply { text = updateStatusText; setTextColor(DeyttUi.CORAL) }
                    }
                }
        }
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
        if (value <= 0) return "0 Б"
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    private fun formatDate(seconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale("ru"))
        .format(Date(seconds * 1000))
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
