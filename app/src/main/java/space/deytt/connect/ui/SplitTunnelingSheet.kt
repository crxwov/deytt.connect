package space.deytt.connect

import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.util.Locale
import java.util.concurrent.Executors
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.CORAL
import space.deytt.connect.DeyttUi.LINE
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.SKY
import space.deytt.connect.DeyttUi.SURFACE
import space.deytt.connect.DeyttUi.SURFACE_2
import space.deytt.connect.DeyttUi.TEXT
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.glassButtonBackground
import space.deytt.connect.DeyttUi.glassRowBackground
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.pressFeedback
import space.deytt.connect.DeyttUi.ripple
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

internal class SplitTunnelingSheet(
    private val host: MainActivity,
    private val copy: (String, String) -> String,
) {
    fun show(onChanged: () -> Unit) {
        val dialog = Dialog(host)
        val store = UserSplitTunnelStore(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(20), host.dp(18), host.dp(20), host.dp(18))
            background = host.rounded(0xF40D1420.toInt(), 24f, 0x3060A5FA.toInt())
        }

        sheet.addView(host.mono(copy("./c · РАЗДЕЛЬНОЕ ТУННЕЛИРОВАНИЕ", "./c · SPLIT TUNNELING"), 9f, MUTED, 650))
        sheet.addView(host.text(copy("Раздельное туннелирование", "Split tunneling"), 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(6), 0, host.dp(4))
        })
        val modeDescription = host.text("", 12f, TEXT).apply {
            setLineSpacing(host.dp(2).toFloat(), 1f)
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
            background = host.rounded(0x45162438, 14f, 0x22FFFFFF)
        }
        sheet.addView(modeDescription)

        sheet.addView(host.text(copy("НАБОР ПРИЛОЖЕНИЙ", "APP PRESET"), 10f, MUTED, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(14), 0, host.dp(6))
            letterSpacing = .06f
        })
        val presetName = host.text("", 12f, TEXT, android.graphics.Typeface.BOLD).apply {
            minimumHeight = host.dp(44)
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
            background = host.rounded(0x551E324E, 14f, 0x5060A5FA.toInt())
            foreground = host.ripple(14f)
            host.pressFeedback(this, .985f)
            isClickable = true
        }
        val modeButton = host.text("", 12f, TEXT, android.graphics.Typeface.BOLD).apply {
            minimumHeight = host.dp(44)
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
            background = host.rounded(0x551E324E, 14f, 0x5060A5FA.toInt())
            foreground = host.ripple(14f)
            host.pressFeedback(this, .985f)
            isClickable = true
        }
        val addPreset = host.text("+", 16f, SKY, android.graphics.Typeface.BOLD).apply {
            setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8))
            background = host.rounded(0x551E324E, 14f, 0x5060A5FA.toInt())
            foreground = host.ripple(14f)
            host.pressFeedback(this, .985f)
            isClickable = true
            contentDescription = copy("Создать набор приложений", "Create an app preset")
        }
        val deletePreset = host.text("✕", 14f, DeyttUi.CORAL, android.graphics.Typeface.BOLD).apply {
            setPadding(host.dp(14), host.dp(8), host.dp(14), host.dp(8))
            background = host.rounded(0x50381E24, 14f, 0x45F43F5E.toInt())
            foreground = host.ripple(14f)
            host.pressFeedback(this, .985f)
            isClickable = true
            contentDescription = copy("Удалить набор приложений", "Delete app preset")
        }
        val presetRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, host.dp(10), 0, host.dp(2))
            addView(presetName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(addPreset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = host.dp(6) })
            addView(deletePreset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = host.dp(6) })
        }
        sheet.addView(presetRow)
        sheet.addView(modeButton)

        lateinit var renderContent: () -> Unit
        fun updatePresetControls() {
            val active = store.activePreset()
            val vpnOnly = active.mode == UserSplitTunnelStore.MODE_VPN_ONLY
            val name = if (active.id == "default") copy(active.name, "Main") else active.name
            presetName.text = copy("Набор: ", "Set: ") + name + "  ▾"
            presetName.contentDescription = copy("Выбран набор $name. Изменить", "Selected set $name. Change")
            deletePreset.visibility = if (active.id == "default") View.GONE else View.VISIBLE
            modeButton.text = copy("Режим: ", "Mode: ") + copy(
                if (vpnOnly) "VPN только для списка" else "Обход VPN для списка",
                if (vpnOnly) "VPN only for list" else "Bypass VPN for list"
            ) + "  ▾"
            modeDescription.text = copy(
                if (vpnOnly) "Через VPN идут только выбранные сайты и приложения. Базовые правила подписки остаются активны." else "Выбранные сайты и приложения идут напрямую без VPN. Базовые правила подписки остаются активны.",
                if (vpnOnly) "Only selected sites and apps use VPN. Subscription rules stay active." else "Selected sites and apps bypass VPN. Subscription rules stay active."
            )
        }

        presetName.setOnClickListener {
            val presets = store.readPresets()
            AppDialog.Builder(host).setTitle(copy("Набор приложений", "App preset"))
                .setItems(presets.map { if (it.id == "default") copy(it.name, "Main") else it.name }.toTypedArray()) { _, index ->
                    store.selectPreset(presets[index].id)
                    updatePresetControls()
                    renderContent()
                    onChanged()
                }.show()
        }
        addPreset.setOnClickListener {
            val input = EditText(host).apply {
                hint = copy("Например, Работа", "For example, Work")
                filters = arrayOf(InputFilter.LengthFilter(32))
            }
            AppDialog.Builder(host).setTitle(copy("Создать набор приложений", "Create app preset")).setView(input)
                .setNegativeButton(host.uiCopy("Отмена"), null)
                .setPositiveButton(host.uiCopy("Создать")) { _, _ ->
                    if (store.createPreset(input.text.toString())) {
                        updatePresetControls()
                        renderContent()
                        onChanged()
                    } else AppDialog.Builder(host).setMessage(copy("Имя пустое или уже занято.", "Name is empty or already used."))
                        .setPositiveButton("OK", null).show()
                }.show()
        }
        deletePreset.setOnClickListener {
            AppDialog.Builder(host).setTitle(copy("Удалить пресет?", "Delete preset?"))
                .setNegativeButton(host.uiCopy("Отмена"), null)
                .setPositiveButton(host.uiCopy("Удалить")) { _, _ ->
                    store.deleteActivePreset()
                    updatePresetControls()
                    renderContent()
                    onChanged()
                }.show()
        }
        modeButton.setOnClickListener {
            val modes = arrayOf(copy("Обход VPN для списка", "Bypass VPN for list"), copy("VPN только для списка", "VPN only for list"))
            val selected = if (store.readMode() == UserSplitTunnelStore.MODE_VPN_ONLY) 1 else 0
            AppDialog.Builder(host).setTitle(copy("Режим", "Mode")).setSingleChoiceItems(modes, selected) { choice, index ->
                store.setMode(if (index == 1) UserSplitTunnelStore.MODE_VPN_ONLY else UserSplitTunnelStore.MODE_BYPASS)
                choice.dismiss()
                updatePresetControls()
                renderContent()
                onChanged()
            }.setNegativeButton(host.uiCopy("Отмена"), null).show()
        }
        updatePresetControls()

        val scroll = ScrollView(host).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, host.dp(12), 0, host.dp(12))
        }
        scroll.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        renderContent = {
            content.removeAllViews()
            val vpnOnly = store.readMode() == UserSplitTunnelStore.MODE_VPN_ONLY

            // 1. Свои сайты
            val sitesHeader = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(host.sectionLabel(if (vpnOnly) copy("сайты через vpn", "sites through vpn") else copy("сайты без vpn", "bypass sites")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val addBtn = host.text("+ " + copy("Добавить", "Add"), 12f, SKY, android.graphics.Typeface.BOLD).apply {
                    setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
                    background = host.glassButtonBackground(0x303B82F6.toInt(), 0x181D4ED8.toInt(), 12f)
                    foreground = host.ripple(12f)
                    host.pressFeedback(this, .985f)
                    isClickable = true
                    setOnClickListener {
                        val input = EditText(host).apply {
                            hint = "notion.so " + copy("или", "or") + " *.domain.com"
                            setTextColor(TEXT)
                            setHintTextColor(MUTED)
                            textSize = 14f
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                            background = host.rounded(SURFACE_2, 12f, LINE)
                            setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
                        }
                        val container = FrameLayout(host).apply {
                            setPadding(host.dp(20), host.dp(12), host.dp(20), host.dp(4))
                            addView(input)
                        }
                        AppDialog.Builder(host)
                            .setTitle(copy("Добавить сайт", "Add website"))
                            .setMessage(copy("Укажите домен (например, notion.so или *.example.com для поддоменов).", "Enter a domain (e.g. notion.so or *.example.com for subdomains)."))
                            .setView(container)
                            .setNegativeButton(host.uiCopy("Отмена"), null)
                            .setPositiveButton(host.uiCopy("Добавить")) { _, _ ->
                                val domain = input.text.toString()
                                val normalized = UserSplitTunnelStore.normalizeDomain(domain)
                                if (normalized != null) {
                                    store.addDomain(normalized)
                                    renderContent()
                                    onChanged()
                                } else {
                                    AppDialog.Builder(host)
                                        .setTitle(copy("Некорректный адрес", "Invalid address"))
                                        .setMessage(copy("Введите корректное имя домена, например domain.com или *.domain.com.", "Please enter a valid domain name, e.g. domain.com or *.domain.com."))
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                            .show()
                    }
                }
                addView(addBtn)
            }
            content.addView(sitesHeader)

            val customDomains = store.readDomains()
            if (customDomains.isEmpty()) {
                val empty = host.text(copy("Нет добавленных сайтов. Нажмите «+ Добавить», чтобы открывать сайт без VPN.", "No custom sites added. Tap \"+ Add\" to bypass a site."), 12f, MUTED).apply {
                    setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                    background = host.rounded(SURFACE_2, 14f, LINE)
                }
                content.addView(empty)
            } else {
                val listCard = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    background = host.rounded(SURFACE_2, 14f, LINE)
                    setPadding(host.dp(10), host.dp(4), host.dp(10), host.dp(4))
                }
                customDomains.forEachIndexed { index, domain ->
                    if (index > 0) {
                        listCard.addView(View(host).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, host.dp(1)))
                    }
                    val row = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(host.dp(4), host.dp(8), host.dp(4), host.dp(8))
                        val textCol = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(host.text(domain, 14f, TEXT, android.graphics.Typeface.BOLD))
                            addView(host.text(if (vpnOnly) copy("Через VPN", "Through VPN") else copy("Прямое соединение без VPN", "Direct connection without VPN"), 11f, MUTED))
                        }
                        addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val delBtn = host.text("✕", 14f, DeyttUi.CORAL, android.graphics.Typeface.BOLD).apply {
                            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
                            setOnClickListener {
                                store.removeDomain(domain)
                                renderContent()
                                onChanged()
                            }
                        }
                        addView(delBtn)
                    }
                    listCard.addView(row)
                }
                content.addView(listCard)
            }

            content.addView(spacer(16, host))

            // 2. Свои приложения
            val appsHeader = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(host.sectionLabel(if (vpnOnly) copy("приложения через vpn", "apps through vpn") else copy("приложения без vpn", "bypass apps")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val addBtn = host.text("+ " + copy("Добавить", "Add"), 12f, SKY, android.graphics.Typeface.BOLD).apply {
                    setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
                    background = host.glassButtonBackground(0x303B82F6.toInt(), 0x181D4ED8.toInt(), 12f)
                    foreground = host.ripple(12f)
                    host.pressFeedback(this, .985f)
                    isClickable = true
                    setOnClickListener {
                        showAppPickerSheet(vpnOnly) { selectedPkg ->
                            store.addPackage(selectedPkg)
                            renderContent()
                            onChanged()
                        }
                    }
                }
                addView(addBtn)
            }
            content.addView(appsHeader)

            val customPackages = store.readPackages()
            if (customPackages.isEmpty()) {
                val empty = host.text(copy("Нет исключённых приложений. Нажмите «+ Добавить», чтобы выбрать приложение из списка.", "No excluded apps. Tap \"+ Add\" to select an application."), 12f, MUTED).apply {
                    setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                    background = host.rounded(SURFACE_2, 14f, LINE)
                }
                content.addView(empty)
            } else {
                val listCard = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    background = host.rounded(SURFACE_2, 14f, LINE)
                    setPadding(host.dp(10), host.dp(4), host.dp(10), host.dp(4))
                }
                val pm = host.packageManager
                customPackages.forEachIndexed { index, pkg ->
                    if (index > 0) {
                        listCard.addView(View(host).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, host.dp(1)))
                    }
                    val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    val appIcon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()

                    val row = LinearLayout(host).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(host.dp(4), host.dp(8), host.dp(4), host.dp(8))
                        if (appIcon != null) {
                            val iv = ImageView(host).apply {
                                setImageDrawable(appIcon)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }
                            addView(iv, LinearLayout.LayoutParams(host.dp(32), host.dp(32)).apply {
                                marginEnd = host.dp(10)
                            })
                        }
                        val textCol = LinearLayout(host).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(host.text(appLabel, 14f, TEXT, android.graphics.Typeface.BOLD))
                            addView(host.mono(pkg, 11f, MUTED))
                        }
                        addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val delBtn = host.text("✕", 14f, DeyttUi.CORAL, android.graphics.Typeface.BOLD).apply {
                            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
                            setOnClickListener {
                                store.removePackage(pkg)
                                renderContent()
                                onChanged()
                            }
                        }
                        addView(delBtn)
                    }
                    listCard.addView(row)
                }
                content.addView(listCard)
            }

            content.addView(spacer(16, host))

            // 3. Базовые правила из подписки
            content.addView(host.sectionLabel(copy("правила из подписки (всегда активны)", "subscription rules (always active)")))
            val subscriptionSites = SubscriptionStore(host).readCurrent()?.let { config ->
                runCatching { SplitTunnelProfile.bypassSites(config) }.getOrNull()
            } ?: emptyList()

            val subCard = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background = host.rounded(SURFACE_2, 14f, LINE)
                setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
                val ruleHeader = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(host.text("🔒", 12f, MUTED))
                    addView(host.text(copy("базовые правила подписки", "base subscription rules"), 12f, TEXT, android.graphics.Typeface.BOLD),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = host.dp(6) })
                    addView(host.text(copy("всегда без vpn", "always bypass VPN"), 10f, SKY, android.graphics.Typeface.BOLD).apply {
                        setPadding(host.dp(8), host.dp(5), host.dp(8), host.dp(5))
                        background = host.rounded(0x1F8494FF.toInt(), 10f, 0x3F8494FF.toInt())
                    })
                }
                addView(ruleHeader)
                val chips = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, host.dp(8), 0, host.dp(6))
                }
                if (subscriptionSites.isNotEmpty()) {
                    subscriptionSites.forEachIndexed { index, site ->
                        chips.addView(host.text(site, 11f, TEXT).apply {
                            typeface = android.graphics.Typeface.MONOSPACE
                            setPadding(host.dp(8), host.dp(5), host.dp(8), host.dp(5))
                            background = host.rounded(SURFACE, 8f, LINE)
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            if (index > 0) marginStart = host.dp(5)
                        })
                    }
                } else {
                    chips.addView(host.text(copy("Загрузка правил подписки…", "Loading subscription rules…"), 11f, MUTED))
                }
                addView(chips)
                addView(host.text(copy("Встроены в конфигурацию подписки для стабильной работы российских сервисов и госуслуг.", "Included in the subscription profile for reliable access to Russian services and public services."), 11f, MUTED))
            }
            content.addView(subCard)

            content.addView(spacer(10, host))
            content.addView(host.text(copy("Изменения вступают в силу при переподключении VPN.", "Changes take effect when VPN reconnects."), 11f, MUTED).apply {
                gravity = Gravity.CENTER
            })
        }

        renderContent()

        val doneBtn = host.button(copy("Готово", "Done")).apply {
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(doneBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(48)).apply {
            topMargin = host.dp(8)
        })

        BottomSheetHelper.show(host, dialog, sheet)
    }

    private fun showAppPickerSheet(vpnOnly: Boolean, onSelected: (String) -> Unit) {
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(20), host.dp(18), host.dp(20), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }

        sheet.addView(host.mono("./c · ВЫБОР ПРИЛОЖЕНИЯ", 9f, MUTED, 650))
        sheet.addView(host.text(copy("Выберите приложение", "Select application"), 20f, TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(6), 0, host.dp(4))
        })
        sheet.addView(host.text(
            copy(
                if (vpnOnly) "Трафик выбранного приложения будет идти через VPN." else "Трафик выбранного приложения будет идти напрямую в обход VPN.",
                if (vpnOnly) "Traffic for the selected application will use VPN." else "Traffic for the selected application will bypass VPN."
            ),
            12f,
            MUTED
        ))

        val searchInput = EditText(host).apply {
            hint = copy("Поиск приложения…", "Search application…")
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 14f
            background = host.rounded(SURFACE_2, 12f, LINE)
            setPadding(host.dp(14), host.dp(10), host.dp(14), host.dp(10))
        }
        sheet.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(10)
            bottomMargin = host.dp(8)
        })

        val progress = ProgressBar(host).apply {
            isIndeterminate = true
        }
        val progressContainer = FrameLayout(host).apply {
            addView(progress, FrameLayout.LayoutParams(host.dp(36), host.dp(36), Gravity.CENTER))
            setPadding(0, host.dp(24), 0, host.dp(24))
        }
        sheet.addView(progressContainer)

        val scroll = ScrollView(host).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }
        val listContainer = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(320)))

        val closeBtn = host.button(copy("Закрыть", "Close"), secondary = true).apply {
            setOnClickListener { dialog.dismiss() }
        }
        sheet.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(46)).apply {
            topMargin = host.dp(8)
        })

        val executor = Executors.newSingleThreadExecutor()
        class AppEntry(val name: String, val packageName: String, val icon: Drawable?)
        var allApps = listOf<AppEntry>()

        fun renderList(query: String = "") {
            listContainer.removeAllViews()
            val filtered = if (query.isBlank()) allApps else {
                val q = query.lowercase(Locale.ROOT)
                allApps.filter { it.name.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q) }
            }
            if (filtered.isEmpty()) {
                listContainer.addView(host.text(copy("Ничего не найдено", "No apps found"), 13f, MUTED).apply {
                    setPadding(0, host.dp(16), 0, host.dp(16))
                    gravity = Gravity.CENTER
                })
                return
            }
            filtered.forEachIndexed { index, app ->
                val row = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
                    background = host.glassRowBackground(false, 12f)
                    if (app.icon != null) {
                        val iv = ImageView(host).apply {
                            setImageDrawable(app.icon)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                        addView(iv, LinearLayout.LayoutParams(host.dp(36), host.dp(36)).apply { marginEnd = host.dp(12) })
                    }
                    val col = LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(host.text(app.name, 14f, TEXT, android.graphics.Typeface.BOLD))
                        addView(host.mono(app.packageName, 11f, MUTED))
                    }
                    addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(host.text(copy("+ добавить", "+ add"), 10f, SKY, android.graphics.Typeface.BOLD).apply {
                        gravity = Gravity.CENTER
                        setPadding(host.dp(8), host.dp(7), host.dp(8), host.dp(7))
                        background = host.rounded(DeyttUi.SKY_SURFACE, 10f, LINE)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = host.dp(8)
                    })
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        onSelected(app.packageName)
                        dialog.dismiss()
                    }
                }
                listContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = host.dp(6)
                })
            }
        }

        executor.execute {
            val pm = host.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != host.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { info ->
                    AppEntry(
                        name = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
                    )
                }
                .sortedBy { it.name.lowercase(Locale.ROOT) }

            host.runOnUiThread {
                if (host.isFinishing || host.isDestroyed || !dialog.isShowing) return@runOnUiThread
                allApps = installed
                progressContainer.visibility = View.GONE
                scroll.visibility = View.VISIBLE
                renderList(searchInput.text.toString())
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        BottomSheetHelper.show(host, dialog, sheet)
    }
}
