package space.deytt.connect

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.BLUE
import space.deytt.connect.DeyttUi.BLUE_DEEP
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.SKY
import space.deytt.connect.DeyttUi.SURFACE
import space.deytt.connect.DeyttUi.SURFACE_2
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

internal class TopLevelViewPagerAdapter(
    private val shouldDeferRouteRefresh: () -> Boolean = { false },
    private val createPage: (Int) -> View,
) : RecyclerView.Adapter<TopLevelViewPagerAdapter.PageHolder>() {
    private val pages = arrayOfNulls<View>(PAGE_COUNT)
    private val routeRefreshGate = RoutePageRefreshGate()

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
        if (position == ROUTE_PAGE && !routeRefreshGate.request(shouldDeferRouteRefresh())) return
        refreshNow(position)
    }

    fun flushDeferredRouteRefresh() {
        if (routeRefreshGate.takeIfReady(shouldDeferRouteRefresh())) refreshNow(ROUTE_PAGE)
    }

    private fun refreshNow(position: Int) {
        pages[position]?.let { oldView ->
            val parent = oldView.parent as? ViewGroup
            val newView = createPage(position)
            pages[position] = newView
            parent?.let {
                val index = it.indexOfChild(oldView)
                it.removeView(oldView)
                it.addView(newView, index)
            }
        }
    }

    class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    companion object {
        const val PAGE_COUNT = 3
        const val ROUTE_PAGE = 1
        const val PROFILE_PAGE = 2
    }
}

internal class RoutePageRefreshGate {
    private var pending = false

    fun request(shouldDefer: Boolean): Boolean = if (shouldDefer) {
        pending = true
        false
    } else {
        pending = false
        true
    }

    fun takeIfReady(shouldDefer: Boolean): Boolean = if (pending && !shouldDefer) {
        pending = false
        true
    } else false
}

internal class PrimaryPages(
    private val host: MainActivity,
    private val onRouteDiagnosticsIdle: () -> Unit = {},
) {
    private val expandedCountryCodes = mutableSetOf<String>()
    private var routeScrollY = 0

    private fun copy(ru: String, en: String): String =
        if (AppLanguage.current(host) == AppLanguage.EN) en else ru

    private val routeProbePresenter = RouteProbePresenter(host, ::copy, onRouteDiagnosticsIdle)
    private val routePageView = RoutePageView(host, ::copy, routeProbePresenter)
    private val splitTunnelingSheet = SplitTunnelingSheet(host, ::copy)
    private val telegramPairingSheet = TelegramPairingSheet(host, ::copy)
    private val inAppUpdateFlow = InAppUpdateFlow(host, ::copy, ::openUrl)
    private val profileFlows = ProfileFlows(
        host,
        telegramPairingSheet::showTelegramPairing,
        telegramPairingSheet::showLinkedTelegramAccount,
    )

    private var settingsTelegramIcon: ImageView? = null

    fun create(position: Int): View = when (position) {
        1 -> routePageView.build(expandedCountryCodes, { routeScrollY = it }, routeScrollY)
        2 -> profileFlows.page()
        else -> settingsPage()
    }

    fun createUpdateBanner(): View = inAppUpdateFlow.createUpdateBanner()

    fun updateTelegramAvatar(avatar: android.graphics.Bitmap?) {
        settingsTelegramIcon?.apply {
            clipToOutline = true
            if (avatar != null) {
                setImageBitmap(avatar)
                imageTintList = null
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 0, 0)
            } else {
                setImageResource(if (TelegramSessionStore.read(host) != null) R.drawable.ic_user_round else R.drawable.ic_send)
                imageTintList = ColorStateList.valueOf(SKY)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
            }
        }
    }

    private fun settingsPage(): View {
        val root = host.screen(withBackdrop = true)
        root.addView(host.header("", "Настройки"))
        root.addView(spacer(12, host))
        root.addView(host.sectionLabel(copy("язык и аккаунт", "language and account")))
        val accountGroup = routeGroup()
        appendToGroup(accountGroup, host.row(copy("Язык приложения", "App language"), AppLanguage.label(host), "LANGUAGE_ICON", "›").apply {
            setOnClickListener {
                val codes = listOf(AppLanguage.RU, AppLanguage.EN)
                AppDialog.Builder(host).setTitle(copy("Язык приложения", "App language"))
                    .setSingleChoiceItems(arrayOf("Русский", "English"), codes.indexOf(AppLanguage.current(host))) { dialog, index ->
                        dialog.dismiss()
                        if (codes[index] != AppLanguage.current(host)) {
                            AppLanguage.set(host, codes[index])
                            host.recreate()
                        }
                    }.setNegativeButton(host.uiCopy("Отмена"), null).show()
            }
        })
        val linked = TelegramSessionStore.read(host) != null
        appendToGroup(accountGroup, host.row(
            if (linked) copy("Telegram подключён", "Telegram connected") else copy("Войти через Telegram", "Sign in with Telegram"),
            if (linked) copy("Управление аккаунтом", "Manage account") else copy("Подписка и устройства", "Subscription and devices"),
            if (linked) "ACCOUNT_ICON" else "TELEGRAM_ICON", "›", emphasis = linked,
            avatar = TelegramIdentityCache.avatar.takeIf { linked },
        ).apply {
            settingsTelegramIcon = getChildAt(0) as ImageView
            setOnClickListener {
                if (TelegramSessionStore.read(host) == null) telegramPairingSheet.showTelegramPairing()
                else telegramPairingSheet.showLinkedTelegramAccount()
            }
        })

        root.addView(accountGroup)
        root.addView(spacer(16, host))
        root.addView(host.sectionLabel(copy("подключение", "connection")))
        val connectionGroup = routeGroup()
        val splitStore = UserSplitTunnelStore(host)
        fun splitTunnelSummary(): String {
            val count = splitStore.readDomains().size + splitStore.readPackages().size
            val mode = if (splitStore.readMode() == UserSplitTunnelStore.MODE_VPN_ONLY) copy("VPN только для списка", "VPN only for list")
                else copy("обход VPN", "bypass VPN")
            val preset = splitStore.activePreset()
            val presetName = if (preset.id == "default") copy(preset.name, "Main") else preset.name
            return presetName + " · " + mode + " · " + count
        }
        val splitRow = host.row(
            copy("Раздельное туннелирование", "Split tunneling"),
            splitTunnelSummary(), "SPLIT_ICON", "›",
        )
        val splitSummaryView = (splitRow.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView
        splitRow.setOnClickListener {
            splitTunnelingSheet.show {
                splitSummaryView?.text = splitTunnelSummary()
                splitRow.contentDescription = copy("Раздельное туннелирование", "Split tunneling") + ". " + splitTunnelSummary()
            }
        }
        appendToGroup(connectionGroup, splitRow)
        val probeSummary = { copy("HTTP ${RouteProbePreferences.method(host).title} · быстрая проверка, загрузка 1 с", "HTTP ${RouteProbePreferences.method(host).title} · fast check, 1 s download") }
        appendToGroup(connectionGroup, host.row(copy("Проверка маршрута", "Route check"), probeSummary(), "ROUTE_CHECK_ICON", "›").apply {
            val summary = (getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView
            setOnClickListener {
                val methods = RouteProbeMethod.values()
                AppDialog.Builder(host).setTitle(copy("Метод проверки", "Check method"))
                    .setSingleChoiceItems(arrayOf(copy("HEAD · только заголовки", "HEAD · headers only"), copy("GET · с ответом", "GET · response body")), methods.indexOf(RouteProbePreferences.method(host))) { dialog, index ->
                        RouteProbePreferences.saveMethod(host, methods[index])
                        summary?.text = probeSummary()
                        contentDescription = copy("Проверка маршрута", "Route check") + ". " + probeSummary()
                        dialog.dismiss()
                    }.setNegativeButton(host.uiCopy("Отмена"), null).show()
            }
        })
        appendToGroup(connectionGroup, host.row(copy("Разрешения VPN", "VPN permissions"), copy("Системные настройки Android", "Android system settings"), "VPN_PERMISSION_ICON", "›").apply {
            setOnClickListener { runCatching { host.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } }
        })
        appendToGroup(connectionGroup, host.row(copy("Бот DEYTT", "DEYTT bot"), copy("Другие действия в Telegram", "More actions in Telegram"), "BOT_ICON", "›").apply {
            setOnClickListener { openUrl("https://t.me/deyttbot") }
        })
        root.addView(connectionGroup)

        root.addView(spacer(16, host))
        root.addView(host.sectionLabel(copy("вид и приватность", "appearance and privacy")))
        root.addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(15), host.dp(3), host.dp(15), host.dp(3))
            background = host.rounded(SURFACE, 18f, LINE)
            clipToOutline = true
            addView(preferenceSwitch(
                copy("Регион на карте", "Region on the map"),
                copy("По IP через ipinfo.io · без GPS и сохранения координат", "IP lookup via ipinfo.io · no GPS or saved coordinates"),
                host.isNetworkLocationEnabled(), host::setNetworkLocationEnabled,
            ))
            addView(View(host).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, host.dp(1)))
            addView(preferenceSwitch(
                copy("Меньше движения", "Reduce motion"), copy("Остановить анимацию фона", "Pause background animation"),
                host.isReducedMotionEnabled(), host::setReducedMotionEnabled,
            ))
        })

        root.addView(spacer(16, host))
        root.addView(host.sectionLabel(copy("обновления", "updates")))
        root.addView(LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(16), host.dp(13), host.dp(16), host.dp(13))
            background = host.rounded(SURFACE, 18f, LINE)
            clipToOutline = true
            addView(host.text("deytt.connect · ${BuildConfig.VERSION_NAME}", 13f, TEXT, android.graphics.Typeface.BOLD))
            val updateStatus = host.text(inAppUpdateFlow.updateStatusText, 11f, MUTED).apply { setPadding(0, host.dp(5), 0, host.dp(10)) }
            addView(updateStatus)
            val updateButton = host.button(copy("Проверить обновления", "Check for updates"), secondary = true).apply {
                setOnClickListener { inAppUpdateFlow.updateAction() }
            }
            addView(updateButton, LinearLayout.LayoutParams(-1, host.dp(46)))
            inAppUpdateFlow.bindSettingsViews(updateStatus, updateButton)
        })
        root.addView(spacer(18, host))
        return ScrollView(host).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun preferenceSwitch(
        title: String,
        description: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(host.dp(1), host.dp(12), host.dp(1), host.dp(12))
        val textContainer = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(host.text(title, 14f, TEXT, android.graphics.Typeface.BOLD))
            addView(host.text(description, 11f, MUTED).apply {
                setPadding(0, host.dp(4), host.dp(8), 0)
            })
        }
        val toggle = Switch(host).apply {
            isChecked = checked
            contentDescription = host.uiCopy(title) + ". " + host.uiCopy(description)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(DeyttUi.MINT, 0xFF8C96A5.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0xA63CDCC0.toInt(), 0x665B6675.toInt()))
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        addView(textContainer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        isClickable = true
        isFocusable = false
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }

    private fun openUrl(url: String) {
        runCatching {
            val uri = Uri.parse(url)
            if (uri.scheme?.lowercase() !in setOf("https", "tg")) return
            host.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun routeGroup(): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private fun appendToGroup(group: LinearLayout, item: View) {
        group.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (group.childCount > 0) topMargin = host.dp(7)
        })
    }

    fun onRouteProbeEvent(event: Intent) = routeProbePresenter.onRouteProbeEvent(event)

    fun startForegroundUpdates() {
        routeProbePresenter.startForegroundDiagnostics()
        inAppUpdateFlow.startForegroundUpdates()
    }

    fun hasActiveRouteDiagnostics(): Boolean = routeProbePresenter.hasActiveRouteDiagnostics()

    fun stopForegroundUpdates() {
        routeProbePresenter.stopForegroundDiagnostics()
        inAppUpdateFlow.stopForegroundUpdates()
    }

    fun cancelRouteDiagnosticsForSelection() = routeProbePresenter.cancelRouteDiagnosticsForSelection()

    fun close() {
        routeProbePresenter.close()
        inAppUpdateFlow.close()
        telegramPairingSheet.close()
        profileFlows.close()
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
