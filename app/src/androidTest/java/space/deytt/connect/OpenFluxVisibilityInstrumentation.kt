package space.deytt.connect

import android.Manifest
import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.InputDevice
import android.view.Choreographer
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.webkit.WebView
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Empty-cache UI regression. This runner must never target the installed production application. */
class OpenFluxVisibilityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        val passed = arrayListOf<String>()
        var stage = "isolated_qa_preflight"
        var activity: MainActivity? = null
        var setupActivity: SetupActivity? = null
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated QA application required" }
            assertNoNativeConnection()
            check(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                "Grant the isolated QA notification permission before this UI test"
            }

            stage = "shared_backdrop_respects_drawable_bounds"
            val backdropBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            backdropBitmap.eraseColor(android.graphics.Color.MAGENTA)
            DeyttUi.starfieldBackground(targetContext).apply {
                bounds = Rect(25, 25, 75, 75)
                draw(android.graphics.Canvas(backdropBitmap))
            }
            check(backdropBitmap.getPixel(10, 10) == android.graphics.Color.MAGENTA &&
                backdropBitmap.getPixel(90, 90) == android.graphics.Color.MAGENTA) {
                "Shared backdrop must not erase the header or dock outside its bounds"
            }
            check(backdropBitmap.getPixel(50, 50) != android.graphics.Color.MAGENTA)
            backdropBitmap.recycle()
            passed += stage

            stage = "seed_empty_credential_cache"
            ProfileValidator.validate(CORE)
            SubscriptionStore(targetContext).saveValidated(CORE)
            AwgProfileStore(targetContext).save(emptyList())
            TelegramSessionStore.clear(targetContext)
            check(targetContext.getSharedPreferences("profile_settings", Context.MODE_PRIVATE).edit()
                .remove("subscription_url")
                .putString("app_language", "ru")
                .putBoolean("network_location_enabled", false)
                // MainActivity starts the updater on foreground; suppress its network request.
                .putLong("last_official_update_check_ms", System.currentTimeMillis())
                .commit())
            check(targetContext.getSharedPreferences(ConnectVpnService.STATE_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit())
            SelectedRouteStore(targetContext).save(RouteCatalog.from(CORE, emptyList()).first())
            assertEmptyCredentials()
            passed += stage

            stage = "open_routes_through_visible_navigation"
            // Home can start automatic latency diagnostics when VPN permission was granted.
            // Start on Settings, then use the actual navigation view to open Routes.
            val launched = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_TAB, 3)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = launched
            waitForIdleSync()
            awaitCondition {
                onMainCondition {
                    findView(launched.findViewById(android.R.id.content)) {
                        it.isShown && it.isClickable && it.contentDescription?.toString() == "маршруты"
                    }?.let(::isVisible) == true
                }
            }
            runOnMainSync {
                val navigation = checkNotNull(findView(launched.findViewById(android.R.id.content)) {
                    it.isShown && it.isClickable && it.contentDescription?.toString() == "маршруты"
                })
                check(navigation.performClick())
            }
            passed += stage

            stage = "empty_cache_routes_remain_visible"
            awaitVisibleText(launched, "lte + белые списки")
            awaitVisibleText(launched, "обход cups · германия")
            check(SelectedRouteStore(targetContext).read().id == "auto")
            assertEmptyCredentials()
            assertNoNativeConnection()
            passed += stage

            stage = "single_click_selects_cups_without_connecting"
            runOnMainSync {
                val title = checkNotNull(findText(launched.findViewById(android.R.id.content), "обход cups · германия"))
                check(isVisible(title))
                var clickTarget: View? = title
                while (clickTarget != null && !clickTarget.isClickable) clickTarget = clickTarget.parent as? View
                val row = checkNotNull(clickTarget)
                check(row.isEnabled && row.performClick())
            }
            awaitCondition {
                val selected = SelectedRouteStore(targetContext).read()
                selected.id == "openflux:DE" && selected.engine == TunnelEngine.OPENFLUX
            }
            waitForIdleSync()
            check(SelectedRouteStore(targetContext).read().configTag.isEmpty())
            assertEmptyCredentials()
            assertNoNativeConnection()
            passed += stage

            stage = "offline_main_pager_visual_and_interaction_qa"
            val screenshots = arrayListOf<String>()
            val headerGeometryLogs = arrayListOf<String>()
            val sharedGlobe = onMainValue {
                val content = launched.findViewById<View>(android.R.id.content)
                val globes = findViews(content) { it is RouteGlobeView }
                check(globes.size == 1) { "Expected one persistent globe, found ${globes.size}" }
                val wordmark = findView(content) { it.tag == "deytt-wordmark" }
                check(wordmark is TextView && wordmark.text.toString() == "./connect")
                check(findView(content) { it.tag == "telegram-profile-control" }?.isShown == true)
                val accountName = findView(content) { it.tag == "telegram-account-name" }
                check(accountName is TextView && accountName.visibility == View.GONE)
                globes.single() as RouteGlobeView
            }
            runOnMainSync {
                headerGeometryLogs += "initial:${readHeaderGeometry(launched)}"
                results.putStringArrayList("header_geometry", ArrayList(headerGeometryLogs))
            }

            fun capturePage(
                name: String,
                expectedText: String,
                pageIndex: Int,
                pageActivity: MainActivity = launched,
            ) {
                val pageGlobe = onMainValue {
                    findViews(pageActivity.findViewById(android.R.id.content)) { it is RouteGlobeView }
                        .single() as RouteGlobeView
                }
                awaitPagerSettled(pageActivity, pageIndex, expectedText)
                awaitVisibleText(pageActivity, expectedText)
                scrollCurrentPageToTop(pageActivity, expectedText)
                awaitPagerSettled(pageActivity, pageIndex, expectedText)
                awaitAtlasReady(pageGlobe)
                runOnMainSync {
                    val title = checkNotNull(findText(pageActivity.findViewById(android.R.id.content), expectedText))
                    check(isVisible(title)) { "$name title should be visible at the viewport top" }
                    check(isTransparentScreenRoot(title)) { "$name screen must be transparent over the shared globe" }
                    check(findViews(pageActivity.findViewById(android.R.id.content)) { it is RouteGlobeView }.single() === pageGlobe)
                    if (pageIndex == 0) {
                        val content = pageActivity.findViewById<View>(android.R.id.content)
                        val pager = checkNotNull(findView(content) { it.javaClass.name == "androidx.viewpager2.widget.ViewPager2" })
                            as androidx.viewpager2.widget.ViewPager2
                        val homePage = checkNotNull(activePagerPage(pager, expectedText))
                        check(findView(homePage) { it is ScrollView } == null) {
                            "Home should fit its controls in the pager without vertical scrolling"
                        }
                        val shell = checkNotNull(findView(content) { it.tag == "shell-starfield" })
                        val shellBounds = Rect()
                        check(shell.isShown && shell.getGlobalVisibleRect(shellBounds) && shellBounds.width() >= content.width * .95f) {
                            "Home shell starfield should span the full content width: bounds=$shellBounds content=${content.width}x${content.height}"
                        }
                        val refresh = checkNotNull(findView(content) { it.tag == "home-refresh" })
                        val refreshBounds = Rect()
                        check(refresh.getGlobalVisibleRect(refreshBounds) && refreshBounds.height() == refresh.height &&
                            refreshBounds.width() >= content.width * .85f) {
                            "Home refresh action should fit above the dock and span the content width: bounds=$refreshBounds content=${content.width}x${content.height}"
                        }
                        val latency = checkNotNull(findView(content) { it.tag == "home-latency" })
                        val speed = checkNotNull(findView(content) { it.tag == "home-speed" })
                        check(findView(content) { it is DiagnosticDotsView }?.isShown == false) {
                            "Disconnected Home must not animate a measurement that is not running"
                        }
                        val latencyBounds = Rect()
                        val speedBounds = Rect()
                        check(latency.isShown && speed.isShown && latency.getGlobalVisibleRect(latencyBounds) && speed.getGlobalVisibleRect(speedBounds)) {
                            "Home latency and speed metrics should both be visible"
                        }
                        val widthRatio = latencyBounds.width().toFloat() / speedBounds.width().coerceAtLeast(1)
                        check(widthRatio in .8f..1.25f) {
                            "Home latency and speed metric columns should remain balanced: latency=$latencyBounds speed=$speedBounds ratio=$widthRatio"
                        }
                        check(findText(content, "Сведения о маршруте") == null) {
                            "Home should not show a separate route-details control"
                        }
                    }
                    headerGeometryLogs += "$name:${readHeaderGeometry(pageActivity)}"
                    results.putStringArrayList("header_geometry", ArrayList(headerGeometryLogs))
                }
                awaitPagerSettled(pageActivity, pageIndex, expectedText)
                val bitmap = checkNotNull(uiAutomation.takeScreenshot()) { "Could not capture $name page" }
                val directory = File(targetContext.cacheDir, "main-pager-visual-qa")
                check(directory.exists() || directory.mkdirs()) { "Cannot create screenshot directory" }
                val image = File(directory, "$name.png")
                FileOutputStream(image).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not save $name screenshot" }
                }
                bitmap.recycle()
                screenshots += image.absolutePath
                results.putStringArrayList("screenshots", ArrayList(screenshots))
            }

            // The actual Settings page is opened through the visible bottom dock.
            clickNavigation(launched, "настройки")
            capturePage("settings", "Настройки", 3)

            // The shell profile control must still reach the real Profile page without a Telegram session.
            runOnMainSync {
                val profileControl = checkNotNull(findView(launched.findViewById(android.R.id.content)) {
                    it.tag == "telegram-profile-control" && it.isShown
                })
                var clickTarget: View? = profileControl
                while (clickTarget != null && !clickTarget.isClickable) clickTarget = clickTarget.parent as? View
                check(clickTarget?.performClick() == true)
            }
            capturePage("profile", "Профиль", 2)
            assertEmptyCredentials()

            stage = "profile_accordion_interruptible_expansion"
            assertProfileAccordionExpansion(launched)
            passed += stage

            // Selecting the synthetic OpenFlux route avoids diagnostic network calls on Home.
            clickNavigation(launched, "главная")
            awaitVisibleText(launched, "Подключить")
            check(SelectedRouteStore(targetContext).read().id == "openflux:DE")
            assertNoNativeConnection()
            capturePage("home", "Подключить", 0)
            stage = "reduced_motion_keeps_home_page_identity"
            assertReducedMotionPageIdentity(launched)
            passed += stage
            stage = "long_home_diagnostic_keeps_metrics_compact"
            assertHomeLongDiagnosticFits(launched)
            passed += stage
            val homeGlobeHost = onMainValue { sharedGlobe.parent }

            stage = "two_pointer_map_pinch_zoom_does_not_page"
            android.os.Trace.beginSection("GlobePinchQA")
            try {
                pinchMapWithoutPaging(launched, sharedGlobe, screenshots, results)
            } finally {
                android.os.Trace.endSection()
            }
            passed += stage

            // Exercise the requested leftward swipe from Home to Routes using the real ViewPager2.
            stage = "leftward_home_swipe_opens_routes"
            swipePageLeft(launched)
            awaitVisibleText(launched, "lte + белые списки")
            awaitCondition {
                onMainCondition {
                    findView(launched.findViewById(android.R.id.content)) {
                        it.isShown && it.contentDescription?.toString()?.startsWith("маршруты", ignoreCase = true) == true
                    } != null
                }
            }
            capturePage("routes", "lte + белые списки", 1)
            check(onMainValue { sharedGlobe.parent } !== homeGlobeHost) {
                "The same globe should move between the Home map slot and shared secondary-page backdrop"
            }
            check(SelectedRouteStore(targetContext).read().id == "openflux:DE")
            assertEmptyCredentials()
            assertNoNativeConnection()

            stage = "setup_entry_and_pairing_form_stay_offline"
            setupActivity = startActivitySync(Intent(targetContext, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SetupActivity
            waitForIdleSync()
            fun assertSetupLayout(expectedHeader: String, stateName: String): TextView {
                val current = checkNotNull(setupActivity)
                val setupRoot = current.findViewById<View>(android.R.id.content)
                val page = checkNotNull(findView(setupRoot) { it.tag == "setup-content" })
                val header = checkNotNull(findView(page) { it.tag == "setup-header" })
                val topBack = findView(page) { it.tag == "setup-back" }
                val headerTitle = checkNotNull(findText(header, expectedHeader))
                val introCard = checkNotNull(findView(page) { it.tag == "setup-intro-card" && it.isShown })
                val introCopy = checkNotNull(findView(introCard) { it.tag == "setup-intro-copy" })
                val setupAction = checkNotNull(findView(introCard) { it.tag == "setup-primary" && it.isShown }) as TextView
                val status = checkNotNull(findView(introCard) { it.tag == "setup-state" })
                check(isVisible(introCard) && isVisible(headerTitle)) { "$stateName setup card and title should be visible" }
                awaitCondition {
                    onMainCondition {
                        current.window.decorView.hasWindowFocus() && isVisible(introCard) && isVisible(headerTitle)
                    }
                }
                check(setupAction.isEnabled && setupAction.parent === introCard && status.parent === introCard) {
                    "$stateName action and status should stay in the explanatory card"
                }
                val copyBounds = Rect()
                val actionBounds = Rect()
                val headerBounds = Rect()
                val cardBounds = Rect()
                val pageBounds = Rect()
                check(introCopy.getGlobalVisibleRect(copyBounds) && setupAction.getGlobalVisibleRect(actionBounds) &&
                    header.getGlobalVisibleRect(headerBounds) && introCard.getGlobalVisibleRect(cardBounds) &&
                    page.getGlobalVisibleRect(pageBounds)) { "$stateName setup geometry should be measurable" }
                val density = current.resources.displayMetrics.density
                val copyActionGap = actionBounds.top - copyBounds.bottom
                val headerCardGap = cardBounds.top - headerBounds.bottom
                check(copyActionGap in 0..(density * 34).toInt()) {
                    "$stateName action should sit beside its explanation: gap=${copyActionGap / density}dp"
                }
                check(headerCardGap in 0..(density * 16).toInt()) {
                    "$stateName card should stay close to its heading: gap=${headerCardGap / density}dp"
                }
                val centerTop = topBack?.let { back ->
                    val backBounds = Rect()
                    check(back.getGlobalVisibleRect(backBounds)) { "Update back action should be visible" }
                    check(backBounds.top <= pageBounds.top + pageBounds.height() * .15f) {
                        "Update back action should remain at the top: page=$pageBounds back=$backBounds"
                    }
                    backBounds.bottom
                } ?: pageBounds.top
                val availableCenter = (centerTop + pageBounds.bottom) / 2f
                check(kotlin.math.abs((headerBounds.top + cardBounds.bottom) / 2f - availableCenter) <= pageBounds.height() * .20f) {
                    "$stateName title and card should be centered in their available region: page=$pageBounds header=$headerBounds card=$cardBounds"
                }
                results.putString("setup_layout_$stateName", "headerCardGapDp=${headerCardGap / density};copyActionGapDp=${copyActionGap / density};cardHeightDp=${cardBounds.height() / density}")
                awaitNextFrame(current.window.decorView)
                return setupAction
            }

            check(SubscriptionStore(targetContext).readCurrent() != null) { "The update-stage screenshot needs the isolated synthetic subscription fixture" }
            val updateAction = assertSetupLayout("Обновить подписку", "update")
            check(updateAction.isEnabled)
            waitForIdleSync()
            saveQaScreenshot("setup-update", screenshots, results)
            runOnMainSync { setupActivity!!.finish() }
            setupActivity = null
            waitForIdleSync()
            val syntheticSubscriptionFile = File(targetContext.filesDir, "subscription/current.json")
            check(syntheticSubscriptionFile.isFile && syntheticSubscriptionFile.delete()) {
                "Could not clear the isolated synthetic subscription before the entry-stage screenshot"
            }
            check(SubscriptionStore(targetContext).readCurrent() == null)
            setupActivity = startActivitySync(Intent(targetContext, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SetupActivity
            waitForIdleSync()
            val setupAction = assertSetupLayout("Подключить Telegram", "entry")
            check(setupAction.isEnabled)
            saveQaScreenshot("setup-entry", screenshots, results)
            runOnMainSync { check(setupAction.performClick()) }
            waitForIdleSync()
            val pairingDialog = setupActivity!!.javaClass.getDeclaredField("pairingDialog").run {
                isAccessible = true
                get(setupActivity)
            } as? Dialog
            val activePairingDialog = checkNotNull(pairingDialog) { "Pairing form did not open" }
            val pairingRoot = checkNotNull(activePairingDialog.window?.decorView) { "Pairing form window did not open" }
            check(findView(pairingRoot) { it is ScrollView } != null) {
                "Pairing form should allow scrolling when the keyboard reduces available height"
            }
            check((activePairingDialog.window!!.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) ==
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                "Pairing dialog should resize for the on-screen keyboard"
            }
            val usernameInput = checkNotNull(findView(pairingRoot) {
                it is EditText && it.contentDescription?.toString() == AppLanguage.text(targetContext, "Имя пользователя Telegram")
            }) as EditText
            val codeInput = checkNotNull(findView(pairingRoot) {
                it is EditText && it.contentDescription?.toString() == AppLanguage.text(targetContext, "Код подтверждения Telegram")
            }) as EditText
            val pairingPrimary = checkNotNull(findView(pairingRoot) { it.tag == "pairing-primary" && it.isShown })
            check(usernameInput.isShown && (usernameInput.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_GO) {
                "Username field should be visible and submit from the keyboard"
            }
            check((codeInput.imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_DONE) {
                "Verification-code field should submit from the keyboard"
            }
            assertEmptyCredentials()
            assertNoNativeConnection()
            awaitCondition {
                onMainCondition {
                    activePairingDialog.isShowing && pairingRoot.hasWindowFocus() && usernameInput.hasWindowFocus() && usernameInput.isShown
                }
            }
            runOnMainSync {
                usernameInput.requestFocus()
                val inputMethod = targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                check(inputMethod.showSoftInput(usernameInput, InputMethodManager.SHOW_IMPLICIT)) {
                    "Input method did not accept the visible username field"
                }
                WindowCompat.getInsetsController(activePairingDialog.window!!, pairingRoot)
                    .show(WindowInsetsCompat.Type.ime())
                pairingRoot.postInvalidateOnAnimation()
            }
            awaitStableImeAndPrimary(activePairingDialog, pairingRoot, pairingPrimary)
            awaitNextFrame(pairingRoot)
            saveQaScreenshot("setup-pairing", screenshots, results)
            runOnMainSync {
                WindowCompat.getInsetsController(activePairingDialog.window!!, pairingRoot)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            runOnMainSync { activePairingDialog.dismiss() }
            runOnMainSync { setupActivity!!.finish() }
            setupActivity = null
            waitForIdleSync()
            SubscriptionStore(targetContext).saveValidated(CORE)

            stage = "english_home_renders_without_account_data"
            runOnMainSync { launched.finish() }
            waitForIdleSync()
            AppLanguage.set(targetContext, AppLanguage.EN)
            try {
                val englishHome = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_START_TAB, 0)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                activity = englishHome
                awaitVisibleText(englishHome, "connect")
                val englishPager = checkNotNull(findView(englishHome.findViewById(android.R.id.content)) {
                    it.javaClass.name == "androidx.viewpager2.widget.ViewPager2"
                }) as androidx.viewpager2.widget.ViewPager2
                check(activePagerPage(englishPager, "connect") !is ScrollView) {
                    "English Home should keep the compact non-scrolling layout"
                }
                val cyrillicText = onMainValue {
                    findViews(englishHome.findViewById(android.R.id.content)) {
                        it is TextView && it.isShown && Regex("[А-Яа-яЁё]").containsMatchIn(it.text.toString())
                    }.map { (it as TextView).text.toString() }
                }
                check(cyrillicText.isEmpty()) { "English Home still shows Russian copy: ${cyrillicText.take(4)}" }
                capturePage("home-english", "connect", 0, englishHome)
                fun assertEnglishPage(name: String, expectedTitle: String, pageIndex: Int) {
                    val cyrillic = onMainValue {
                        findViews(englishHome.findViewById(android.R.id.content)) {
                            it is TextView && it.isShown && Regex("[А-Яа-яЁё]").containsMatchIn(it.text.toString())
                        }.map { (it as TextView).text.toString() }
                    }
                    check(cyrillic.isEmpty()) { "English $name still shows Russian copy: ${cyrillic.take(4)}" }
                    capturePage("$name-english", expectedTitle, pageIndex, englishHome)
                }
                clickNavigation(englishHome, "routes")
                awaitVisibleText(englishHome, "routes")
                assertEnglishPage("routes", "routes", 1)
                clickNavigation(englishHome, "profile")
                awaitVisibleText(englishHome, "profile")
                assertEnglishPage("profile", "profile", 2)
                clickNavigation(englishHome, "settings")
                awaitVisibleText(englishHome, "settings")
                assertEnglishPage("settings", "settings", 3)
                assertEmptyCredentials()
                assertNoNativeConnection()
            } finally {
                activity?.let { current -> runOnMainSync { current.finish() } }
                waitForIdleSync()
                AppLanguage.set(targetContext, AppLanguage.RU)
            }
            results.putStringArrayList("screenshots", screenshots)
            results.putStringArrayList("header_geometry", headerGeometryLogs)
            passed += stage
        } catch (failure: Throwable) {
            captureFailureScreenshot(stage, results)
            results.putString("result", "FAIL:${failure.javaClass.simpleName}")
            results.putString("stage", stage)
            results.putString("failure_message", failure.message?.take(512))
        } finally {
            setupActivity?.let { current -> runOnMainSync { current.finish() } }
            activity?.let { current -> runOnMainSync { current.finish() } }
            waitForIdleSync()
            runCatching { SubscriptionStore(targetContext).saveValidated(CORE) }
        }
        results.putStringArrayList("passed", passed)
        if (!results.containsKey("result")) results.putString("result", "PASS")
        finish(if (results.getString("result") == "PASS") -1 else 0, results)
    }

    private fun clickNavigation(activity: MainActivity, label: String) {
        awaitCondition {
            onMainCondition {
                findView(activity.findViewById(android.R.id.content)) {
                    it.isShown && it.isClickable && it.contentDescription?.toString()
                        ?.startsWith(label, ignoreCase = true) == true
                }?.let(::isVisible) == true
            }
        }
        runOnMainSync {
            val tab = checkNotNull(findView(activity.findViewById(android.R.id.content)) {
                it.isShown && it.isClickable && it.contentDescription?.toString()
                    ?.startsWith(label, ignoreCase = true) == true
            })
            check(tab.performClick())
        }
        waitForIdleSync()
    }

    private fun swipePageLeft(activity: MainActivity) {
        val pager = onMainValue {
            checkNotNull(findView(activity.findViewById(android.R.id.content)) {
                it.javaClass.name == "androidx.viewpager2.widget.ViewPager2"
            })
        }
        val bounds = Rect()
        runOnMainSync { check(pager.getGlobalVisibleRect(bounds)) }
        val y = bounds.centerY().toFloat()
        val startX = (bounds.right - bounds.width() / 8).toFloat()
        val endX = (bounds.left + bounds.width() / 8).toFloat()
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, x: Float, eventTime: Long) {
            val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                check(uiAutomation.injectInputEvent(event, true)) { "Could not inject pager swipe event" }
            } finally {
                event.recycle()
            }
        }
        inject(MotionEvent.ACTION_DOWN, startX, downTime)
        for (step in 1..6) {
            val fraction = step / 7f
            inject(MotionEvent.ACTION_MOVE, startX + (endX - startX) * fraction, downTime + step * 45L)
        }
        inject(MotionEvent.ACTION_UP, endX, downTime + 350L)
        waitForIdleSync()
        awaitCondition {
            onMainCondition { (pager as? androidx.viewpager2.widget.ViewPager2)?.currentItem == 1 }
        }
    }

    private fun assertProfileAccordionExpansion(activity: MainActivity) {
        val pair = onMainValue {
            val content = activity.findViewById<View>(android.R.id.content)
            val toggle = checkNotNull(findView(content) {
                it.isClickable && it.contentDescription?.toString()?.startsWith("Устройства и сессии") == true
            }) { "Profile devices accordion toggle is missing" }
            val group = toggle.parent as? ViewGroup
                ?: error("Profile devices accordion has no content group")
            check(group.childCount >= 2)
            toggle to group.getChildAt(group.childCount - 1)
        }
        val (toggle, accordionContent) = pair
        onMainValue {
            check(accordionContent.visibility == View.GONE) { "Profile accordion should start collapsed" }
            repeat(3) { index ->
                check(toggle.performClick()) { "Profile accordion tap ${index + 1} was not handled" }
                SystemClock.sleep(28)
            }
        }
        awaitCondition {
            onMainCondition {
                accordionContent.visibility == View.VISIBLE && accordionContent.height > 0 &&
                    accordionContent.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        check(onMainValue {
            findText(accordionContent, "Подключите аккаунт, чтобы увидеть устройства.") != null
        }) { "Expanded profile accordion should show its offline empty-account summary" }
        onMainValue { check(toggle.performClick()) }
        awaitCondition { onMainCondition { accordionContent.visibility == View.GONE } }
    }

    private fun assertReducedMotionPageIdentity(activity: MainActivity) {
        val preferences = targetContext.getSharedPreferences("profile_settings", Context.MODE_PRIVATE)
        val original = preferences.getBoolean("reduced_motion", false)
        try {
            runOnMainSync { activity.setReducedMotionEnabled(true) }
            waitForIdleSync()
            val webView = onMainValue {
                val globe = checkNotNull(findViews(activity.findViewById(android.R.id.content)) { it is RouteGlobeView }.singleOrNull())
                checkNotNull(findViews(globe) { it is WebView }.singleOrNull()) as WebView
            }
            check(runAtlasScript(webView, "String(window.deyttMapReducedMotion)") == "true") {
                "Reduced-motion preference should reach the shared map renderer"
            }
            val pager = onMainValue {
                checkNotNull(findView(activity.findViewById(android.R.id.content)) {
                    it.javaClass.name == "androidx.viewpager2.widget.ViewPager2"
                }) as androidx.viewpager2.widget.ViewPager2
            }
            onMainValue {
                check(pager.currentItem == 0 && pager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE)
                val motionField = MainActivity::class.java.getDeclaredField("pageMotionEnabled").apply { isAccessible = true }
                check(motionField.getBoolean(activity).not()) { "Reduced motion should disable page transforms" }
                for (index in 0 until pager.childCount) {
                    val page = pager.getChildAt(index)
                    if (page.isShown) {
                        check(kotlin.math.abs(page.alpha - 1f) < .001f &&
                            kotlin.math.abs(page.scaleX - 1f) < .001f &&
                            kotlin.math.abs(page.scaleY - 1f) < .001f &&
                            kotlin.math.abs(page.translationX) < .5f) {
                            "Reduced-motion pager page should retain identity transforms: alpha=${page.alpha}, scale=${page.scaleX}/${page.scaleY}, x=${page.translationX}"
                        }
                    }
                }
            }
            runOnMainSync { activity.setReducedMotionEnabled(false) }
            waitForIdleSync()
            check(runAtlasScript(webView, "String(window.deyttMapReducedMotion)") == "false") {
                "Restoring motion should reach the shared map renderer"
            }
            check(onMainValue { pager.currentItem == 0 && pager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE })
        } finally {
            runOnMainSync { activity.setReducedMotionEnabled(original) }
        }
    }

    private fun assertHomeLongDiagnosticFits(activity: MainActivity) {
        val views = onMainValue {
            val content = activity.findViewById<View>(android.R.id.content)
            val latency = checkNotNull(findView(content) { it.tag == "home-latency" })
            val speed = checkNotNull(findView(content) { it.tag == "home-speed" })
            fun value(metric: View): TextView = checkNotNull(findViews(metric) { it is TextView }
                .filterIsInstance<TextView>().lastOrNull()) { "Metric value is missing" }
            Triple(latency, value(latency), value(speed))
        }
        val (_, latency, speed) = views
        val originalLatency = latency.text
        val originalSpeed = speed.text
        try {
            onMainValue {
                latency.text = "после подключения"
                speed.text = "недоступно"
            }
            waitForIdleSync()
            val density = targetContext.resources.displayMetrics.scaledDensity
            check(latency.textSize <= 13.1f * density && speed.textSize <= 13.1f * density) {
                "Long offline metric labels should compact to readable 13sp text: latency=${latency.textSize / density}sp speed=${speed.textSize / density}sp"
            }
            val content = activity.findViewById<View>(android.R.id.content)
            val refresh = checkNotNull(findView(content) { it.tag == "home-refresh" })
            val bounds = Rect()
            check(onMainValue { refresh.getGlobalVisibleRect(bounds) && bounds.height() == refresh.height && bounds.width() >= content.width * .85f }) {
                "Home refresh action must remain full-width and visible with long offline diagnostics: $bounds"
            }
        } finally {
            onMainValue {
                latency.text = originalLatency
                speed.text = originalSpeed
            }
            waitForIdleSync()
        }
    }

    private fun pinchMapWithoutPaging(
        activity: MainActivity,
        globe: RouteGlobeView,
        screenshots: ArrayList<String>,
        results: Bundle,
    ) {
        val webView = onMainValue {
            checkNotNull(findViews(globe) { it is WebView }.singleOrNull()) {
                "Home globe should contain one local WebView for pinch QA"
            } as WebView
        }
        val mapBounds = Rect()
        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        runOnMainSync {
            check(webView.getGlobalVisibleRect(mapBounds)) { "Home map is not visible for pinch QA" }
            webView.getLocationOnScreen(screenLocation)
            webView.getLocationInWindow(windowLocation)
            check(mapBounds.width() >= 180 && mapBounds.height() >= 180) {
                "Home map is too small for a reliable two-pointer gesture: $mapBounds"
            }
        }
        val initialZoom = readAtlasTargetZoom(webView)
        check(initialZoom in 1.08..1.12) {
            "Map must start at overview zoom before gesture assertions: $initialZoom"
        }
        val wheelProbe = runAtlasScript(webView, """
            (function(){
              var a=window.deyttMapAtlas,c=document.querySelector('.network-atlas canvas');
              if(!a||!c)return 'missing-atlas-or-canvas';
              var b=c.getBoundingClientRect(),cx=b.left+b.width/2,cy=b.top+b.height/2,r=a.radius*a.zoom-.5;
              var ox=b.left+4,oy=b.top+4,outsideCircle=Math.hypot(ox-cx,oy-cy)>r;
              var outside=new WheelEvent('wheel',{deltaY:-120,clientX:ox,clientY:oy,bubbles:true,cancelable:true});
              var before=a.targetZoom;c.dispatchEvent(outside);var afterOutside=a.targetZoom;
              var inside=new WheelEvent('wheel',{deltaY:-120,clientX:cx,clientY:cy,bubbles:true,cancelable:true});
              c.dispatchEvent(inside);
              var result='outsideCircle='+outsideCircle+';cornerInsideRect='+(ox>=b.left&&oy>=b.top&&ox<b.right&&oy<b.bottom)+';cornerPrevented='+outside.defaultPrevented+';cornerDelta='+(afterOutside-before)+';insidePrevented='+inside.defaultPrevented+';insideZoom='+a.targetZoom;
              var previous={connected:a.connected,route:a.route,routeKey:a.autoExitRouteKey,country:a.autoExitCountry};
              a.connected=true;a.route='auto';a.autoExitRouteKey=null;a.autoExitCountry=null;
              var unresolved=(a.activeRouteDefinition()===null&&a.activeRouteNodeKeys().length===0);
              a.autoExitRouteKey='de';
              var german=(a.activeRouteDefinition()===window.DeyttAtlas.routes.de&&JSON.stringify(a.activeRouteNodeKeys())===JSON.stringify(window.DeyttAtlas.routes.de.nodes));
              a.connected=previous.connected;a.route=previous.route;a.autoExitRouteKey=previous.routeKey;a.autoExitCountry=previous.country;
              return result+';unresolvedAutoSuppressed='+unresolved+';activeDeUsesRealPath='+german+';manualZoomMax='+a.clampZoom(64);
            })()
        """.trimIndent())
        android.util.Log.i("OpenFluxVisibilityQA", "globe_rectangular_interaction=$wheelProbe")
        check(wheelProbe.contains("outsideCircle=true") &&
            wheelProbe.contains("cornerInsideRect=true") &&
            wheelProbe.contains("cornerPrevented=true") &&
            wheelProbe.substringAfter("cornerDelta=").substringBefore(';').toDoubleOrNull()?.let { it > 0.1 } == true &&
            wheelProbe.contains("insidePrevented=true") &&
            wheelProbe.substringAfter("insideZoom=").substringBefore(';').toDoubleOrNull()?.let { it > 1.2 } == true &&
            wheelProbe.contains("unresolvedAutoSuppressed=true") &&
            wheelProbe.contains("activeDeUsesRealPath=true") &&
            wheelProbe.contains("manualZoomMax=32")) {
            "Atlas rectangular viewport/connected-route behavior failed: $wheelProbe"
        }
        runOnMainSync { webView.evaluateJavascript("window.deyttResetMapView && window.deyttResetMapView()", null) }
        val wheelResetDeadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < wheelResetDeadline &&
            (kotlin.math.abs(readAtlasTargetZoom(webView) - 1.1) >= .01 ||
                kotlin.math.abs(readAtlasCurrentZoom(webView) - 1.1) >= .03)) {
            Thread.sleep(50)
        }
        check(kotlin.math.abs(readAtlasTargetZoom(webView) - 1.1) < .01 &&
            kotlin.math.abs(readAtlasCurrentZoom(webView) - 1.1) < .03) {
            "Wheel contract probe did not restore the map to default zoom: ${readAtlasPinchDiagnostics(webView)}"
        }
        val zoomBefore = readAtlasTargetZoom(webView)
        val centerX = mapBounds.centerX().toFloat()
        val centerY = mapBounds.centerY().toFloat()
        val density = activity.resources.displayMetrics.density
        val startHalfSpan = (mapBounds.width() * .075f).coerceAtLeast(20f * density)
        val endHalfSpan = (mapBounds.width() * .19f).coerceAtMost(88f * density)
        check(endHalfSpan > startHalfSpan * 1.6f) {
            "Map bounds do not allow a measurable pinch: $mapBounds, spans=$startHalfSpan..$endHalfSpan"
        }
        val left = (centerX - startHalfSpan).coerceAtLeast(mapBounds.left + 2f * density)
        val right = (centerX + startHalfSpan).coerceAtMost(mapBounds.right - 2f * density)
        val leftEnd = (centerX - endHalfSpan).coerceAtLeast(mapBounds.left + 2f * density)
        val rightEnd = (centerX + endHalfSpan).coerceAtMost(mapBounds.right - 2f * density)
        val cssCenterX = (centerX - screenLocation[0]) / density
        val cssCenterY = (centerY - screenLocation[1]) / density
        val pointerTraceScript = """
            (function(){
              var a=window.deyttMapAtlas,c=document.querySelector('.network-atlas canvas'),r=document.querySelector('.network-atlas');
              window.__qaNativePinchEvents=[];window.__qaDocumentPinchEvents=[];
              if(!a||!c||!r)return 'atlas='+!!a+';canvas='+!!c+';root='+!!r;
              var t=function(e){var x=window.deyttMapAtlas;window.__qaNativePinchEvents.push([e.type,e.pointerId,e.pointerType,x.activePointers?x.activePointers.size:-1,x.targetZoom]);};
              var d=function(e){window.__qaDocumentPinchEvents.push([e.type,e.pointerId,e.pointerType,e.target&&e.target.tagName,e.clientX,e.clientY]);};
              ['pointerdown','pointermove','pointerup','pointercancel'].forEach(function(n){c.addEventListener(n,t,false);});
              ['pointerdown','pointermove','pointerup','pointercancel','touchstart','touchmove','touchend','mousedown','mousemove','mouseup'].forEach(function(n){document.addEventListener(n,d,true);});
              var cr=c.getBoundingClientRect(),rr=r.getBoundingClientRect(),el=document.elementFromPoint($cssCenterX,$cssCenterY),cs=getComputedStyle(c),rs=getComputedStyle(r);
              return 'url='+location.href+';search='+location.search+';wheelZoomOnly='+a.wheelZoomOnly+';targetZoom='+a.targetZoom+';active='+a.activePointers.size+';rootClass='+r.className+';rootPointerEvents='+rs.pointerEvents+';canvasPointerEvents='+cs.pointerEvents+';canvasRect='+[cr.left,cr.top,cr.right,cr.bottom].join(',')+';rootRect='+[rr.left,rr.top,rr.right,rr.bottom].join(',')+';centerCss=$cssCenterX,$cssCenterY;centerHit='+(el?(el.tagName+'#'+el.id+'.'+el.className):'null')+';viewport='+[innerWidth,innerHeight,devicePixelRatio].join(',');
            })()
        """.trimIndent()
        val pointerTraceSetup = runAtlasScript(webView, pointerTraceScript)
        android.util.Log.i("OpenFluxVisibilityQA", "pinch_native_geometry=global=$mapBounds;screen=${screenLocation.contentToString()};window=${windowLocation.contentToString()};view=${webView.width}x${webView.height};density=$density;points=$left,$centerY,$right->$leftEnd,$centerY,$rightEnd")
        android.util.Log.i("OpenFluxVisibilityQA", "pinch_trace_setup=$pointerTraceSetup")
        val downTime = SystemClock.uptimeMillis()

        fun pointer(id: Int, x: Float): MotionEvent.PointerProperties = MotionEvent.PointerProperties().apply {
            this.id = id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }

        fun coordinates(x: Float): MotionEvent.PointerCoords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = centerY
            pressure = 1f
            size = 1f
        }

        fun inject(action: Int, x1: Float, x2: Float? = null) {
            val props = if (x2 == null) arrayOf(pointer(0, x1)) else arrayOf(pointer(0, x1), pointer(1, x2))
            val coords = if (x2 == null) arrayOf(coordinates(x1)) else arrayOf(coordinates(x1), coordinates(x2))
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, props.size, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try {
                check(uiAutomation.injectInputEvent(event, true)) { "Could not inject map pinch event action=$action" }
            } finally {
                event.recycle()
            }
            // Let Android dispatch this pointer event through the WebView before the next one.
            SystemClock.sleep(45)
            android.util.Log.i("OpenFluxVisibilityQA", "pinch_event_action=$action ${readAtlasPinchDiagnostics(webView)}")
        }

        inject(MotionEvent.ACTION_DOWN, left)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), left, right)
        for (step in 1..6) {
            val fraction = step / 6f
            val x1 = left + (leftEnd - left) * fraction
            val x2 = right + (rightEnd - right) * fraction
            inject(MotionEvent.ACTION_MOVE, x1, x2)
        }
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), leftEnd, rightEnd)
        inject(MotionEvent.ACTION_UP, leftEnd)

        val zoomAfter = readAtlasTargetZoom(webView)
        val pinchDiagnostics = readAtlasPinchDiagnostics(webView)
        check(zoomBefore in 1.08..1.12 && zoomAfter > 1.2 && zoomAfter > zoomBefore + .1 && zoomAfter <= 32.0 + .005) {
            "Native pinch should zoom beyond 1.2 while respecting the 32x manual ceiling: before=$zoomBefore after=$zoomAfter; $pinchDiagnostics"
        }
        val pager = onMainValue {
            checkNotNull(findView(activity.findViewById(android.R.id.content)) {
                it.javaClass.name == "androidx.viewpager2.widget.ViewPager2"
            }) as androidx.viewpager2.widget.ViewPager2
        }
        onMainValue {
            check(pager.currentItem == 0 && pager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                "Map pinch changed pager state: item=${pager.currentItem}, scroll=${pager.scrollState}"
            }
        }
        awaitPagerSettled(activity, 0, "Подключить")

        runOnMainSync { webView.evaluateJavascript("window.deyttResetMapView && window.deyttResetMapView()", null) }
        awaitAtlasSettled(webView, 1.1, expanded = false)
        val normalRadius = readAtlasRadius(webView)
        val labelsBeforeExpand = readAtlasLabelLayout(webView)
        val placementsBeforeExpand = labelPlacementIndices(labelsBeforeExpand)
        check(placementsBeforeExpand.isNotEmpty()) {
            "Normal Home viewport did not produce label placements for resize comparison: $labelsBeforeExpand"
        }
        runOnMainSync { webView.evaluateJavascript("window.deyttMapAtlas.targetZoom=2.05;window.deyttMapAtlas.start()", null) }
        val expandedDiagnostics = awaitAtlasSettled(webView, 2.05, expanded = true)
        val expandedBounds = awaitNativeMapFillsPager(activity, webView)
        val expandedRadius = readAtlasRadius(webView)
        val expectedExpandedRadius = normalRadius * 2.05
        val actualExpandedRadius = expandedRadius * readAtlasCurrentZoom(webView)
        val scaleError = kotlin.math.abs(actualExpandedRadius - expectedExpandedRadius) / expectedExpandedRadius
        check(scaleError <= .02) {
            "Native viewport expansion changed globe scale: normalRadius=$normalRadius expandedRadius=$expandedRadius " +
                "zoom=${readAtlasCurrentZoom(webView)} expectedRadiusZoom=$expectedExpandedRadius actualRadiusZoom=$actualExpandedRadius " +
                "relativeError=$scaleError; $expandedBounds; $expandedDiagnostics"
        }
        val canvasResolution = runAtlasScript(webView, """
            (function(){var a=window.deyttMapAtlas,c=a.canvas,s=a.staticCanvas,cap=Math.min(a.pixelRatio,2);
              var sw=Math.round(a.width*cap),sh=Math.round(a.height*cap),lw=Math.round(a.width*a.pixelRatio),lh=Math.round(a.height*a.pixelRatio);
              return 'static='+s.width+'x'+s.height+';expectedStatic='+sw+'x'+sh+';staticMatch='+(s.width===sw&&s.height===sh)+
                ';live='+c.width+'x'+c.height+';expectedLive='+lw+'x'+lh+';liveMatch='+(c.width===lw&&c.height===lh);})()
        """.trimIndent())
        check(canvasResolution.contains("staticMatch=true") && canvasResolution.contains("liveMatch=true")) {
            "Static/live canvas resolutions do not match their pixel-ratio contracts: $canvasResolution"
        }
        android.util.Log.i("OpenFluxVisibilityQA", "globe_canvas_resolution=$canvasResolution")
        val labelsAfterExpand = readAtlasLabelLayout(webView)
        val placementsAfterExpand = labelPlacementIndices(labelsAfterExpand)
        val commonLabels = placementsBeforeExpand.keys.intersect(placementsAfterExpand.keys)
        check(commonLabels.isNotEmpty()) {
            "No label keys survived native viewport expansion for placement comparison: before=$labelsBeforeExpand after=$labelsAfterExpand"
        }
        val changedPlacements = commonLabels.filter { placementsBeforeExpand[it] != placementsAfterExpand[it] }
        check(changedPlacements.isEmpty()) {
            "Size-only expansion changed placement indices for common labels $changedPlacements: before=$labelsBeforeExpand after=$labelsAfterExpand"
        }
        val pathCacheSetup = runAtlasScript(webView, "(function(){var a=window.deyttMapAtlas;window.__qaLandPath=a.landPathCache&&a.landPathCache.path;return String(!!window.__qaLandPath);})()")
        check(pathCacheSetup == "true") { "Expanded atlas did not retain a coastline path cache: $pathCacheSetup" }
        android.util.Log.i("OpenFluxVisibilityQA", "globe_expanded_viewport=$expandedBounds;scaleError=$scaleError;labels=$labelsAfterExpand;$expandedDiagnostics")
        saveQaScreenshot("globe-expanded", screenshots, results)

        val labelBaseline = labelsAfterExpand
        check(labelBaseline.substringAfter("placements=").substringBefore(';').isNotBlank()) {
            "Expanded atlas did not produce any label placements: $labelBaseline"
        }
        for (target in listOf(2.2, 2.8, 2.05, 2.5)) {
            runOnMainSync { webView.evaluateJavascript("window.deyttMapAtlas.targetZoom=$target;window.deyttMapAtlas.start()", null) }
            val settled = awaitAtlasSettled(webView, target, expanded = true)
            val labels = readAtlasLabelLayout(webView)
            check(labels == labelBaseline) {
                "Label placement/scale changed after zoom settled at $target: before=$labelBaseline after=$labels; $settled"
            }
            val pathReused = runAtlasScript(webView, "String(window.deyttMapAtlas.landPathCache.path===window.__qaLandPath)")
            check(pathReused == "true") {
                "Same-orientation zoom rebuilt the coastline path cache at $target: reused=$pathReused; $settled"
            }
        }
        val idleBefore = readAtlasRenderCount(webView)
        Thread.sleep(750)
        val idleAfter = readAtlasRenderCount(webView)
        check(idleAfter == idleBefore) {
            "Settled atlas continued redrawing while idle: renderCount=$idleBefore->$idleAfter; ${readAtlasPinchDiagnostics(webView)}"
        }
        android.util.Log.i("OpenFluxVisibilityQA", "globe_zoom_labels=$labelBaseline;idle_render_count=$idleBefore->$idleAfter")
        val crowdedLabelProbe = runAtlasScript(webView, """
            (function(){
              var a=window.deyttMapAtlas;
              if(!a)return 'missing-atlas';
              var snapshot={context:a.context,width:a.width,height:a.height,radius:a.radius,route:a.route,connected:a.connected,
                autoExitCountry:a.autoExitCountry,autoExitRouteKey:a.autoExitRouteKey,availableLocationKeys:a.availableLocationKeys,
                annotationFilter:a.annotationFilter,userLocation:a.userLocation,hovered:a.hovered,centerLon:a.centerLon,centerLat:a.centerLat,
                targetLon:a.targetLon,targetLat:a.targetLat,zoom:a.zoom,targetZoom:a.targetZoom,currentBasis:a.currentBasis,
                projectedNodes:a.projectedNodes,labelPlacements:a.labelPlacements,drawLabel:a.drawLabel};
              var drawn=[];
              try{
                var probe=document.createElement('canvas');probe.width=320;probe.height=220;
                a.context=probe.getContext('2d');a.width=320;a.height=220;a.radius=220*.405;
                a.route='auto';a.connected=false;a.autoExitCountry=null;a.autoExitRouteKey=null;
                a.availableLocationKeys=new Set(['nl','de','fi','ru','it']);a.annotationFilter=null;a.userLocation=null;a.hovered='de';
                a.centerLon=a.targetLon=15;a.centerLat=a.targetLat=50;a.zoom=a.targetZoom=.82;a.currentBasis=a.basis();
                a.projectedNodes={};a.labelPlacements=new Map();
                a.drawLabel=function(key,colors,occupied){var before=occupied.length;snapshot.drawLabel.call(this,key,colors,occupied);if(occupied.length>before){
                  var r=occupied[occupied.length-1];drawn.push({key:key,left:r.left,top:r.top,width:r.width,height:r.height});}};
                var colors=a.colors();a.drawNodes(colors);a.drawLabels(colors);
                var overlapCount=0;for(var i=0;i<drawn.length;i++)for(var j=i+1;j<drawn.length;j++){
                  var x=drawn[i],y=drawn[j];if(x.left<y.left+y.width&&x.left+x.width>y.left&&x.top<y.top+y.height&&x.top+x.height>y.top)overlapCount++;}
                var visible=Object.keys(a.projectedNodes).filter(function(k){return a.projectedNodes[k].z>.03;}).length;
                return 'visible='+visible+';drawn='+drawn.map(function(x){return x.key;}).join(',')+';overlaps='+overlapCount+';hoverFirst='+(drawn.length?drawn[0].key:'none');
              }finally{Object.assign(a,snapshot);}
            })()
        """.trimIndent())
        android.util.Log.i("OpenFluxVisibilityQA", "globe_crowded_auto_labels=$crowdedLabelProbe")
        check(crowdedLabelProbe.contains("visible=5") &&
            crowdedLabelProbe.substringAfter("drawn=").substringBefore(';').isNotBlank() &&
            crowdedLabelProbe.contains("overlaps=0") && crowdedLabelProbe.contains("hoverFirst=de")) {
            "Crowded all-location Auto labels overlapped or ignored hover priority: $crowdedLabelProbe"
        }
        runAtlasScript(webView, "(function(){var a=window.deyttMapAtlas;a.centerLon+=9;a.targetLon=a.centerLon;a.staticDirty=true;a.start();return 'scheduled';})()")
        val cacheDeadline = SystemClock.elapsedRealtime() + 10_000L
        var invalidation = "pending"
        while (SystemClock.elapsedRealtime() < cacheDeadline && invalidation != "rebuilt=true;orientation=true") {
            invalidation = runAtlasScript(webView, "(function(){var a=window.deyttMapAtlas;return 'rebuilt='+(a.landPathCache.path!==window.__qaLandPath)+';orientation='+(a.landPathCache.lon===a.centerLon);})()")
            if (invalidation != "rebuilt=true;orientation=true") Thread.sleep(50)
        }
        check(invalidation == "rebuilt=true;orientation=true") {
            "Orientation change did not invalidate and rebuild the coastline cache: $invalidation; ${readAtlasPinchDiagnostics(webView)}"
        }
        android.util.Log.i("OpenFluxVisibilityQA", "globe_orientation_cache=$invalidation")
        saveQaScreenshot("globe-zoom3", screenshots, results)

        runOnMainSync { webView.evaluateJavascript("window.deyttMapAtlas.targetZoom=32;window.deyttMapAtlas.start()", null) }
        val maxZoomDeadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < maxZoomDeadline && readAtlasCurrentZoom(webView) < 31.95) {
            Thread.sleep(50)
        }
        check(readAtlasTargetZoom(webView) == 32.0 && readAtlasCurrentZoom(webView) >= 31.95) {
            "Maximum zoom did not settle before rectangular-viewport QA: ${readAtlasPinchDiagnostics(webView)}"
        }
        val offscreenLabel = runAtlasScript(webView, """
            (function(){var a=window.deyttMapAtlas,p=a.projectedNodes.de||a.projectLocation(window.DeyttAtlas.locations.de,1.008);
              return 'deAnchorVisible='+a.isAndroidViewportAnchorVisible(p);})()
        """.trimIndent())
        check(offscreenLabel == "deAnchorVisible=false") {
            "Offscreen DE label anchor should be hidden as a whole at 32x, got $offscreenLabel"
        }
        val cornerAlpha = runAtlasScript(webView, """
            (function(){var c=document.querySelector('.network-atlas canvas'),x=c.getContext('2d'),w=c.width,h=c.height;
              return [[0,0],[w-1,0],[0,h-1],[w-1,h-1]].map(function(p){return x.getImageData(p[0],p[1],1,1).data[3]}).join(',');})()
        """.trimIndent())
        val cornerValues = cornerAlpha.split(',').mapNotNull(String::toIntOrNull)
        check(cornerValues.size == 4 && cornerValues.all { it > 248 }) {
            "Zoom 32 geography should cover all rectangular viewport corners, got alpha=$cornerAlpha"
        }
        runOnMainSync { android.util.Log.i("OpenFluxVisibilityQA", "globe_zoom32_rect_corner_alpha=$cornerAlpha") }
        saveQaScreenshot("globe-zoom32", screenshots, results)

        runOnMainSync {
            webView.evaluateJavascript("window.deyttResetMapView && window.deyttResetMapView()", null)
        }
        val collapsedDiagnostics = awaitAtlasSettled(webView, 1.1, expanded = false)
        val collapsedBounds = onMainValue {
            val bounds = Rect()
            check(webView.getGlobalVisibleRect(bounds)) { "Collapsed Home map is not visible" }
            bounds.toShortString()
        }
        val homeControls = onMainValue {
            val content = activity.findViewById<View>(android.R.id.content)
            listOf("Подключить", "home-refresh", "home-latency", "home-speed").map { key ->
                val view = if (key.startsWith("home-")) findView(content) { it.tag == key } else findText(content, key)
                val bounds = Rect()
                check(view != null && view.isShown && view.getGlobalVisibleRect(bounds) && bounds.height() == view.height) {
                    "Home control $key was not restored after globe collapse: view=$view bounds=$bounds map=$collapsedBounds"
                }
                "$key=${bounds.toShortString()}"
            }.joinToString(";")
        }
        android.util.Log.i("OpenFluxVisibilityQA", "globe_collapsed_viewport=$collapsedBounds;$collapsedDiagnostics;controls=$homeControls")
        saveQaScreenshot("globe-collapsed", screenshots, results)
        runOnMainSync { webView.evaluateJavascript("window.deyttMapAtlas.targetZoom=2.05;window.deyttMapAtlas.start()", null) }
        awaitAtlasSettled(webView, 2.05, expanded = true)
        awaitNativeMapFillsPager(activity, webView)
        runOnMainSync { activity.onBackPressed() }
        val backDiagnostics = awaitAtlasSettled(webView, 1.1, expanded = false)
        val backControlsVisible = onMainValue {
            val content = activity.findViewById<View>(android.R.id.content)
            listOf("Подключить", "home-refresh", "home-latency", "home-speed").all { key ->
                val view = if (key.startsWith("home-")) findView(content) { it.tag == key } else findText(content, key)
                val bounds = Rect()
                view != null && view.isShown && view.getGlobalVisibleRect(bounds) && bounds.height() == view.height
            }
        }
        check(!activity.isFinishing && backControlsVisible) {
            "Back from expanded Home did not collapse the viewport and retain Home controls: finishing=${activity.isFinishing}; " +
                "controlsVisible=$backControlsVisible; $backDiagnostics"
        }
        onMainValue {
            check(pager.currentItem == 0 && pager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                "Resetting the map changed pager state: item=${pager.currentItem}, scroll=${pager.scrollState}"
            }
        }
        awaitPagerSettled(activity, 0, "Подключить")
        runOnMainSync {
            android.util.Log.i("OpenFluxVisibilityQA", "pinch_zoom=$zoomBefore->$zoomAfter; manual_max=32; pager=0; map_reset=1.1")
        }
    }

    private fun awaitAtlasSettled(webView: WebView, targetZoom: Double, expanded: Boolean): String {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var last = "unavailable"
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runAtlasScript(webView, """
                (function(){var a=window.deyttMapAtlas;return [a.targetZoom,a.zoom,a.viewportExpanded,a.renderCount].join('|');})()
            """.trimIndent())
            val values = last.split('|')
            val target = values.getOrNull(0)?.toDoubleOrNull()
            val current = values.getOrNull(1)?.toDoubleOrNull()
            val isExpanded = values.getOrNull(2) == "true"
            val renders = values.getOrNull(3)?.toIntOrNull()
            if (target != null && current != null && kotlin.math.abs(target - targetZoom) < .01 &&
                kotlin.math.abs(current - targetZoom) < .03 && isExpanded == expanded && renders != null) {
                Thread.sleep(150)
                val settled = runAtlasScript(webView, """
                    (function(){var a=window.deyttMapAtlas;return [a.targetZoom,a.zoom,a.viewportExpanded,a.renderCount].join('|');})()
                """.trimIndent())
                val settledParts = settled.split('|')
                if (settledParts.getOrNull(0)?.toDoubleOrNull()?.let { kotlin.math.abs(it - targetZoom) < .01 } == true &&
                    settledParts.getOrNull(1)?.toDoubleOrNull()?.let { kotlin.math.abs(it - targetZoom) < .03 } == true &&
                    settledParts.getOrNull(2) == expanded.toString() && settledParts.getOrNull(3)?.toIntOrNull() == renders) return settled
            }
            Thread.sleep(50)
        }
        error("Atlas did not settle at zoom=$targetZoom expanded=$expanded: $last; ${readAtlasPinchDiagnostics(webView)}")
    }

    private fun awaitNativeMapFillsPager(activity: MainActivity, webView: WebView): String {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var last = "unavailable"
        var fillsPager = false
        while (SystemClock.elapsedRealtime() < deadline) {
            onMainValue {
                val content = activity.findViewById<View>(android.R.id.content)
                val pager = checkNotNull(findView(content) {
                    it.javaClass.name == "androidx.viewpager2.widget.ViewPager2"
                }) as androidx.viewpager2.widget.ViewPager2
                val pagerBounds = Rect()
                val mapBounds = Rect()
                fillsPager = pager.getGlobalVisibleRect(pagerBounds) && webView.getGlobalVisibleRect(mapBounds) &&
                    kotlin.math.abs(pagerBounds.left - mapBounds.left) <= 2 &&
                    kotlin.math.abs(pagerBounds.top - mapBounds.top) <= 2 &&
                    kotlin.math.abs(pagerBounds.right - mapBounds.right) <= 2 &&
                    kotlin.math.abs(pagerBounds.bottom - mapBounds.bottom) <= 2
                last = "pager=${pagerBounds.toShortString()};map=${mapBounds.toShortString()};view=${webView.width}x${webView.height}"
            }
            if (fillsPager) return last
            Thread.sleep(50)
        }
        error("Expanded native map does not fill the pager area: $last; ${readAtlasPinchDiagnostics(webView)}")
    }

    private fun readAtlasLabelLayout(webView: WebView): String = runAtlasScript(webView, """
        (function(){var a=window.deyttMapAtlas;return 'placements='+Array.from(a.labelPlacements.entries()).sort(function(x,y){return x[0].localeCompare(y[0]);}).map(function(x){return x[0]+':'+x[1];}).join(',')+';scale='+(a.options.labelScale||1)+';compact='+(a.width<520)+';size='+a.width+'x'+a.height;})()
    """.trimIndent())

    private fun labelPlacementIndices(layout: String): Map<String, String> =
        layout.substringAfter("placements=").substringBefore(';').split(',')
            .mapNotNull { entry -> entry.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()

    private fun readAtlasRadius(webView: WebView): Double =
        runAtlasScript(webView, "window.deyttMapAtlas ? window.deyttMapAtlas.radius : null")
            .toDoubleOrNull() ?: error("Atlas radius is unavailable")

    private fun readAtlasRenderCount(webView: WebView): Int =
        runAtlasScript(webView, "window.deyttMapAtlas ? window.deyttMapAtlas.renderCount : -1")
            .toIntOrNull() ?: error("Atlas render count is unavailable")

    private fun readAtlasTargetZoom(webView: WebView): Double {
        return readAtlasZoom(webView, "targetZoom")
    }

    private fun readAtlasCurrentZoom(webView: WebView): Double {
        return readAtlasZoom(webView, "zoom")
    }

    private fun readAtlasPinchDiagnostics(webView: WebView): String = runAtlasScript(
        webView,
        "(function(){var a=window.deyttMapAtlas;var t=window.__qaNativePinchEvents||[];var d=window.__qaDocumentPinchEvents||[];return 'url='+location.href+';search='+location.search+';wheelZoomOnly='+(a&&a.wheelZoomOnly)+';targetZoom='+(a&&a.targetZoom)+';currentZoom='+(a&&a.zoom)+';active='+(a&&a.activePointers?a.activePointers.size:-1)+';canvasEvents='+t.map(function(e){return e[0]+':'+e[1]+':'+e[2]+':'+e[3]+':'+e[4];}).join(',')+';documentEvents='+d.map(function(e){return e[0]+':'+e[1]+':'+e[2]+':'+e[3]+':'+e[4]+':'+e[5];}).join(',');})()"
    )

    private fun runAtlasScript(webView: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var value: String? = null
        runOnMainSync {
            webView.evaluateJavascript(script) { result -> value = result; completed.countDown() }
        }
        check(completed.await(3, TimeUnit.SECONDS)) { "Atlas diagnostic query timed out" }
        return value.orEmpty().trim().removeSurrounding("\"")
    }

    private fun readAtlasZoom(webView: WebView, property: String): Double {
        val value = runAtlasScript(webView, "window.deyttMapAtlas ? window.deyttMapAtlas.$property : null")
        return value.orEmpty().trim().removeSurrounding("\"").toDoubleOrNull()
            ?: error("Atlas $property is unavailable: $value")
    }

    private fun awaitPagerSettled(activity: MainActivity, expectedPage: Int, expectedText: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var stableSamples = 0
        var lastState = "unavailable"
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = onMainValue {
                val content = activity.findViewById<View>(android.R.id.content)
                val pager = findView(content) { it.javaClass.name == "androidx.viewpager2.widget.ViewPager2" }
                    as? androidx.viewpager2.widget.ViewPager2
                if (pager == null) return@onMainValue "missing-pager"
                val activePage = activePagerPage(pager, expectedText)
                val pagerBounds = Rect()
                val pageBounds = Rect()
                val pagerVisible = pager.getGlobalVisibleRect(pagerBounds)
                val pageVisible = activePage?.getGlobalVisibleRect(pageBounds) == true
                val tolerance = (2 * activity.resources.displayMetrics.density).toInt()
                val aligned = pagerVisible && pageVisible && pageBounds.width() > 0 &&
                    kotlin.math.abs(pageBounds.left - pagerBounds.left) <= tolerance &&
                    kotlin.math.abs(pageBounds.right - pagerBounds.right) <= tolerance
                "item=${pager.currentItem},scroll=${pager.scrollState},aligned=$aligned,page=${pageBounds.left},${pageBounds.right},pager=${pagerBounds.left},${pagerBounds.right}"
                    .also {
                        if (pager.currentItem == expectedPage && pager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE && aligned) {
                            stableSamples++
                        } else stableSamples = 0
                    }
            }
            lastState = state
            if (stableSamples >= 3) return
            SystemClock.sleep(80)
        }
        error("Pager did not settle on page $expectedPage for '$expectedText': $lastState")
    }

    private fun readHeaderGeometry(activity: MainActivity): String {
        val content = activity.findViewById<View>(android.R.id.content)
        val wordmark = checkNotNull(findView(content) { it.tag == "deytt-wordmark" }) { "Missing wordmark" }
        val profile = checkNotNull(findView(content) { it.tag == "telegram-profile-control" }) { "Missing profile control" }
        check(wordmark.parent === profile.parent) { "Wordmark and profile control should share the brand header" }

        fun describe(view: View): String {
            val bounds = Rect()
            val parent = checkNotNull(view.parent as? View) { "${view.tag} has no View parent" }
            val parentBounds = Rect()
            check(view.getGlobalVisibleRect(bounds) && bounds.width() > 0 && bounds.height() > 0) {
                "${view.tag} has empty/invisible bounds: $bounds"
            }
            check(view.isShown && view.alpha > 0f && parent.alpha > 0f &&
                parent.getGlobalVisibleRect(parentBounds) && parentBounds.contains(bounds)) {
                "${view.tag} is clipped or transparent: view=$bounds parent=$parentBounds"
            }
            return "${view.tag}[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom};alpha=${view.alpha};parent=${parentBounds.left},${parentBounds.top},${parentBounds.right},${parentBounds.bottom};parentAlpha=${parent.alpha}]"
        }

        return "${describe(wordmark)} ${describe(profile)}"
    }

    private fun scrollCurrentPageToTop(activity: MainActivity, expectedText: String) {
        val scroll = onMainValue {
            val content = activity.findViewById<View>(android.R.id.content)
            val pager = checkNotNull(findView(content) { it.javaClass.name == "androidx.viewpager2.widget.ViewPager2" })
                as androidx.viewpager2.widget.ViewPager2
            val activePage = checkNotNull(activePagerPage(pager, expectedText)) { "Could not find the active pager page for $expectedText" }
            findView(activePage) { it is ScrollView && it.isShown } as? ScrollView
        }
        scroll?.let {
            runOnMainSync { it.scrollTo(0, 0) }
            waitForIdleSync()
            awaitCondition { onMainCondition { it.scrollY == 0 } }
        }
    }

    private fun activePagerPage(pager: androidx.viewpager2.widget.ViewPager2, expectedText: String): View? {
        val pageHost = pager.getChildAt(0) as? ViewGroup ?: return null
        return (0 until pageHost.childCount)
            .asSequence()
            .map(pageHost::getChildAt)
            .firstOrNull { it.isShown && findText(it, expectedText) != null }
    }

    private fun saveQaScreenshot(name: String, screenshots: ArrayList<String>, results: Bundle) {
        val bitmap = checkNotNull(uiAutomation.takeScreenshot()) { "Could not capture $name" }
        val directory = File(targetContext.cacheDir, "main-pager-visual-qa")
        check(directory.exists() || directory.mkdirs()) { "Cannot create QA screenshot directory" }
        val image = File(directory, "$name.png")
        FileOutputStream(image).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not save $name" }
        }
        bitmap.recycle()
        screenshots += image.absolutePath
        results.putStringArrayList("screenshots", ArrayList(screenshots))
    }

    private fun awaitAtlasReady(globe: RouteGlobeView) {
        val webView = onMainValue {
            checkNotNull(findViews(globe) { it is WebView }.singleOrNull()) { "RouteGlobeView has no local WebView" } as WebView
        }
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var lastDomState = "pending"
        val expression = "(function(){var r=document.querySelector('.network-atlas');var c=r&&r.querySelector('canvas');var s=r&&r.querySelector('[data-atlas-status][role=status]');var a=window.deyttMapAtlas;return (r?r.dataset.atlasStatus:'missing')+'|'+(c?c.width:0)+'x'+(c?c.height:0)+'|'+(a&&a.landPolygons?a.landPolygons.length:0)+'|'+(s?s.textContent.trim():'')})()"
        while (SystemClock.elapsedRealtime() < deadline) {
            val completed = CountDownLatch(1)
            var value: String? = null
            runOnMainSync { webView.evaluateJavascript(expression) { result -> value = result; completed.countDown() } }
            check(completed.await(3, TimeUnit.SECONDS)) { "Atlas DOM status query timed out" }
            lastDomState = value.orEmpty().trim().removeSurrounding("\"")
            val parts = lastDomState.split('|', limit = 4)
            val size = parts.getOrNull(1).orEmpty().split('x')
            if (parts.firstOrNull() == "ready" && size.getOrNull(0)?.toIntOrNull()?.let { it > 0 } == true &&
                size.getOrNull(1)?.toIntOrNull()?.let { it > 0 } == true &&
                parts.getOrNull(2)?.toIntOrNull()?.let { it > 0 } == true
            ) return
            val statusText = parts.getOrNull(3).orEmpty()
            check(!statusText.contains("temporarily unavailable", ignoreCase = true) &&
                !statusText.contains("временно недоступна", ignoreCase = true)) {
                "Atlas reported a local map error: $statusText"
            }
            Thread.sleep(200)
        }
        error("Atlas did not reach DOM ready state within 15 seconds: $lastDomState")
    }

    private fun isTransparentScreenRoot(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current is android.widget.LinearLayout && current.orientation == android.widget.LinearLayout.VERTICAL) {
                val background = current.background as? android.graphics.drawable.ColorDrawable
                if (background?.color == android.graphics.Color.TRANSPARENT) return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun <T> onMainValue(block: () -> T): T {
        var value: T? = null
        runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun findViews(root: View, predicate: (View) -> Boolean): List<View> {
        val result = mutableListOf<View>()
        if (predicate(root)) result += root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            result += findViews(root.getChildAt(index), predicate)
        }
        return result
    }

    private fun assertEmptyCredentials() {
        check(SubscriptionClient.getSavedSubscriptionUrl(targetContext) == null)
        check(TelegramSessionStore.read(targetContext) == null)
        check(!File(targetContext.filesDir, "subscription/${OpenFluxProfileStore.FILE_NAME}").exists())
        check(OpenFluxProfileStore(targetContext).profiles(targetContext).isEmpty())
    }

    private fun assertNoNativeConnection() {
        check(!ConnectVpnService.isRunning())
        check(!ConnectVpnService.isRouteProbeRunning())
        check(!AwgTunnelController.isRunning())
        check(!RouteProbeClient.isRunning(targetContext))
    }

    private fun awaitVisibleText(activity: MainActivity, expected: String) {
        awaitCondition {
            onMainCondition {
                val view = findText(activity.findViewById(android.R.id.content), expected)
                if (view == null || view.width == 0 || view.height == 0) false else {
                    view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
                    isVisible(view)
                }
            }
        }
    }

    private fun onMainCondition(condition: () -> Boolean): Boolean {
        var result = false
        runOnMainSync { result = condition() }
        return result
    }

    private fun findText(root: View, expected: String): View? = findView(root) {
        it is TextView && it.isShown && it.text.toString().equals(expected, ignoreCase = true)
    }

    private fun findView(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findView(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun isVisible(view: View): Boolean = view.isShown && view.getGlobalVisibleRect(Rect())

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "UI did not reach the expected state" }
            Thread.sleep(50)
        }
    }

    private fun awaitNextFrame(view: View) {
        val frameDrawn = CountDownLatch(1)
        runOnMainSync {
            view.postInvalidateOnAnimation()
            Choreographer.getInstance().postFrameCallback { frameDrawn.countDown() }
        }
        check(frameDrawn.await(2, TimeUnit.SECONDS)) { "Window did not reach the next rendered frame" }
        waitForIdleSync()
    }

    private fun awaitStableImeAndPrimary(dialog: Dialog, root: View, primary: View) {
        var previous: List<Int>? = null
        var stableSamples = 0
        var lastDiagnostic = "unavailable"
        val deadline = SystemClock.elapsedRealtime() + 1_500L
        while (SystemClock.elapsedRealtime() < deadline && stableSamples < 3) {
            val sample = onMainValue {
                val insets = ViewCompat.getRootWindowInsets(root)
                val visibleFrame = Rect()
                val primaryBounds = Rect()
                val decor = dialog.window?.decorView
                val imeHeight = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                val imeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                val primaryVisible = primary.getGlobalVisibleRect(primaryBounds)
                val location = IntArray(2)
                primary.getLocationOnScreen(location)
                val actualPrimary = Rect(location[0], location[1], location[0] + primary.width, location[1] + primary.height)
                val viewportVisible = root.getWindowVisibleDisplayFrame(visibleFrame).let { visibleFrame.height() > 0 }
                val rootBounds = Rect()
                val rootVisible = root.getGlobalVisibleRect(rootBounds)
                lastDiagnostic = "showing=${dialog.isShowing};windowFocus=${decor?.hasWindowFocus()};rootFocus=${root.hasWindowFocus()};" +
                    "imeVisible=$imeVisible;imeInset=$imeHeight;decorHeight=${decor?.height};rootRect=$rootBounds;rootVisible=$rootVisible;" +
                    "visibleFrame=$visibleFrame;viewportVisible=$viewportVisible;primaryShown=${primary.isShown};primaryVisibility=${primary.visibility};" +
                    "primaryGlobal=$primaryBounds;primaryActual=$actualPrimary;primarySize=${primary.width}x${primary.height}"
                val primaryAboveIme = primaryVisible && primaryBounds.height() == primary.height &&
                    actualPrimary.top >= visibleFrame.top && actualPrimary.bottom <= visibleFrame.bottom
                if (!dialog.isShowing || decor?.hasWindowFocus() != true || !imeVisible ||
                    !viewportVisible || !primary.isShown || !primaryAboveIme) {
                    null
                } else {
                    check(actualPrimary.bottom <= visibleFrame.bottom) {
                        "Pairing CTA must remain above the visible keyboard: CTA=$actualPrimary viewport=$visibleFrame; $lastDiagnostic"
                    }
                    listOf(decor.height, visibleFrame.bottom, actualPrimary.top, actualPrimary.bottom, primaryBounds.height())
                }
            }
            stableSamples = if (sample != null && sample == previous) stableSamples + 1 else 0
            previous = sample
            if (stableSamples < 3) Thread.sleep(50)
        }
        check(stableSamples >= 3) {
            "IME geometry and pairing CTA did not settle within 1.5s: samples=$stableSamples previous=$previous; $lastDiagnostic"
        }
        waitForIdleSync()
    }

    private fun captureFailureScreenshot(stage: String, results: Bundle) {
        runCatching {
            val bitmap = uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            val directory = File(targetContext.cacheDir, "main-pager-visual-qa")
            check(directory.exists() || directory.mkdirs())
            val file = File(directory, "failure-${stage.replace(Regex("[^A-Za-z0-9_-]"), "_")}.png")
            FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            results.putString("failure_screenshot", file.absolutePath)
        }.onFailure { results.putString("failure_screenshot_error", it.message?.take(200)) }
    }

    private companion object {
        const val CORE = """{"inbounds":[{"type":"tun","address":["192.0.2.1/30"]}],"outbounds":[{"type":"selector","tag":"Автоподбор","outbounds":["route:RU-DE:CHAIN"]},{"type":"trojan","tag":"route:RU-DE:CHAIN","server":"192.0.2.2","server_port":443,"password":"synthetic-ui-only"}],"route":{"final":"Автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}}"""
    }
}
