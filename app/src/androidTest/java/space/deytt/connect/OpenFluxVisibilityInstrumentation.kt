package space.deytt.connect

import android.Manifest
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
import android.widget.TextView
import android.webkit.WebView
import android.widget.ScrollView
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

            fun capturePage(name: String, expectedText: String, pageIndex: Int) {
                awaitPagerSettled(launched, pageIndex, expectedText)
                awaitVisibleText(launched, expectedText)
                scrollCurrentPageToTop(launched, expectedText)
                awaitPagerSettled(launched, pageIndex, expectedText)
                awaitAtlasReady(sharedGlobe)
                runOnMainSync {
                    val title = checkNotNull(findText(launched.findViewById(android.R.id.content), expectedText))
                    check(isVisible(title)) { "$name title should be visible at the viewport top" }
                    check(isTransparentScreenRoot(title)) { "$name screen must be transparent over the shared globe" }
                    check(findViews(launched.findViewById(android.R.id.content)) { it is RouteGlobeView }.single() === sharedGlobe)
                    if (pageIndex == 0) {
                        val refresh = checkNotNull(findText(launched.findViewById(android.R.id.content), "Обновить замер"))
                        val refreshBounds = Rect()
                        check(refresh.getGlobalVisibleRect(refreshBounds) && refreshBounds.height() == refresh.height) {
                            "Home refresh action should fit above the dock without scrolling"
                        }
                    }
                    headerGeometryLogs += "$name:${readHeaderGeometry(launched)}"
                    results.putStringArrayList("header_geometry", ArrayList(headerGeometryLogs))
                }
                awaitPagerSettled(launched, pageIndex, expectedText)
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

            // Selecting the synthetic OpenFlux route avoids diagnostic network calls on Home.
            clickNavigation(launched, "главная")
            awaitVisibleText(launched, "Подключить")
            check(SelectedRouteStore(targetContext).read().id == "openflux:DE")
            assertNoNativeConnection()
            capturePage("home", "Подключить", 0)
            val homeGlobeHost = onMainValue { sharedGlobe.parent }

            stage = "two_pointer_map_pinch_zoom_does_not_page"
            pinchMapWithoutPaging(launched, sharedGlobe)
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
            results.putStringArrayList("screenshots", screenshots)
            results.putStringArrayList("header_geometry", headerGeometryLogs)
            passed += stage
        } catch (failure: Throwable) {
            results.putString("result", "FAIL:${failure.javaClass.simpleName}")
            results.putString("stage", stage)
            results.putString("failure_message", failure.message?.take(512))
        } finally {
            activity?.let { current -> runOnMainSync { current.finish() } }
            waitForIdleSync()
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

    private fun pinchMapWithoutPaging(activity: MainActivity, globe: RouteGlobeView) {
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
        check(zoomAfter > zoomBefore * 1.25) {
            "Native pinch did not increase atlas zoom by 25%: before=$zoomBefore after=$zoomAfter; $pinchDiagnostics"
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

        runOnMainSync {
            webView.evaluateJavascript("window.deyttResetMapView && window.deyttResetMapView()", null)
        }
        val resetDeadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < resetDeadline &&
            (kotlin.math.abs(readAtlasTargetZoom(webView) - 1.1) >= .01 ||
                kotlin.math.abs(readAtlasCurrentZoom(webView) - 1.1) >= .03)) {
            Thread.sleep(50)
        }
        check(kotlin.math.abs(readAtlasTargetZoom(webView) - 1.1) < .01 &&
            kotlin.math.abs(readAtlasCurrentZoom(webView) - 1.1) < .03) {
            "Map reset did not settle at default zoom: ${readAtlasPinchDiagnostics(webView)}"
        }
        onMainValue {
            check(pager.currentItem == 0 && pager.scrollState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                "Resetting the map changed pager state: item=${pager.currentItem}, scroll=${pager.scrollState}"
            }
        }
        awaitPagerSettled(activity, 0, "Подключить")
        runOnMainSync {
            android.util.Log.i("OpenFluxVisibilityQA", "pinch_zoom=$zoomBefore->$zoomAfter; pager=0; map_reset=1.1")
        }
    }

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
                val activePage = findViews(content) { it is ScrollView && it.isShown }
                    .firstOrNull { findText(it, expectedText) != null }
                val pagerBounds = Rect()
                val pageBounds = Rect()
                val pagerVisible = pager.getGlobalVisibleRect(pagerBounds)
                val pageVisible = (activePage as? ScrollView)?.getGlobalVisibleRect(pageBounds) == true
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
            checkNotNull(findViews(content) { it is ScrollView && it.isShown }.firstOrNull { page ->
                val visibleBounds = Rect()
                page.getGlobalVisibleRect(visibleBounds) && findText(page, expectedText) != null
            }) { "Could not find the active page scroll view for $expectedText" } as ScrollView
        }
        runOnMainSync { scroll.scrollTo(0, 0) }
        waitForIdleSync()
        awaitCondition { onMainCondition { scroll.scrollY == 0 } }
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

    private companion object {
        const val CORE = """{"inbounds":[{"type":"tun","address":["192.0.2.1/30"]}],"outbounds":[{"type":"selector","tag":"Автоподбор","outbounds":["route:RU-DE:CHAIN"]},{"type":"trojan","tag":"route:RU-DE:CHAIN","server":"192.0.2.2","server_port":443,"password":"synthetic-ui-only"}],"route":{"final":"Автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}}"""
    }
}
