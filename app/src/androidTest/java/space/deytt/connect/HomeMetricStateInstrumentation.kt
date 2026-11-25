package space.deytt.connect

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.TextView

/** Exercises Home metric presentation against synthetic state in the isolated QA app. */
class HomeMetricStateInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated QA package required" }
            targetContext.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE).edit()
                .putBoolean("network_location_enabled", false).commit()
            ProfileValidator.validate(TEST_CONFIG)
            AppLanguage.set(targetContext, AppLanguage.RU)
            TelegramSessionStore.clear(targetContext)
            SubscriptionStore(targetContext).saveValidated(TEST_CONFIG)
            val routes = RouteCatalog.from(TEST_CONFIG, emptyList(), emptyList())
            val route = routes.first { it.id == "route:NL:VLESS" }
            val otherRoute = routes.first { it.id == "route:DE:VLESS" }
            SelectedRouteStore(targetContext).save(route)
            VpnStateStore(targetContext).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

            var failure: Throwable? = null
            runOnMainSync {
                try {
                    val home = activity as MainActivity
                    // Keep the test offline and deterministic; presentation is driven by injected sample state.
                    set(home, "homeSpeedForeground", false)
                    set(home, "homeSpeedOnHome", false)
                    (field(home, "selectedRouteLatencyHandler") as android.os.Handler).removeCallbacksAndMessages(null)
                    TelegramSessionStore.save(targetContext, "synthetic-original-account-12345678901234567890")
                    set(home, "renderedPhase", VpnPhase.CONNECTED)
                    val key = call(home, "currentHomeRouteMetricKey", route.id)
                    set(home, "lastRouteLatency", key to 42L)
                    set(home, "homeSpeedSample", key to 250_000L)
                    set(home, "homePingErrorKey", key)
                    set(home, "homeSpeedErrorKey", key)
                    set(home, "homeSpeedError", "synthetic timeout")
                    call(home, "updateQualityStrip")
                    val latency = field(home, "latencyText") as TextView
                    val speed = field(home, "downloadText") as TextView
                    check(latency.text.toString() == "42 мс") { "A successful ping must survive a later timeout" }
                    check(speed.text.toString().contains("2.0 Мбит/с")) { "A successful speed sample must survive a later error: ${speed.text}" }

                    set(home, "homeConnectionGeneration", (field(home, "homeConnectionGeneration") as Long) + 1L)
                    call(home, "updateQualityStrip")
                    check(latency.text.toString() == "—" && speed.text.toString() == "—") {
                        "Samples from an earlier connection generation must be hidden"
                    }
                    set(home, "homeConnectionGeneration", 0L)
                    set(home, "lastRouteLatency", key to 42L)
                    set(home, "homeSpeedSample", key to 250_000L)
                    SelectedRouteStore(targetContext).save(otherRoute)
                    call(home, "updateQualityStrip")
                    check(latency.text.toString() == "—" && speed.text.toString() == "—") { "Changing routes must hide previous-route samples" }

                    SelectedRouteStore(targetContext).save(route)
                    call(home, "updateQualityStrip")
                    TelegramSessionStore.save(targetContext, "synthetic-account-token-12345678901234567890")
                    call(home, "updateQualityStrip")
                    check(latency.text.toString() == "—" && speed.text.toString() == "—") { "Changing accounts must hide previous-account samples" }

                    val metric = home.window.decorView.findViewWithTag<View>("home-latency")
                        ?: error("Home latency metric view missing")
                    check(metric.performClick()) { "Tapping the ping metric did not open its explanation" }
                    check(!home.isFinishing) { "Metric explanation unexpectedly navigated away from Home" }
                } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
            waitForIdleSync()
            check(awaitDialogHas("Сколько миллисекунд", 5_000L)) { "Tapping ping must show its short explanation" }
            val dialog = uiAutomation.rootInActiveWindow ?: error("Ping explanation dialog missing")
            val dismiss = dialog.findAccessibilityNodeInfosByText("понятно").firstOrNull()
            check(dismiss?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) {
                "Ping explanation could not be dismissed"
            }
            dialog.recycle()
            waitForIdleSync()
            runOnMainSync {
                val speedMetric = (activity as MainActivity).window.decorView.findViewWithTag<View>("home-speed")
                    ?: error("Home speed metric view missing")
                check(speedMetric.performClick()) { "Tapping the speed metric did not open its explanation" }
            }
            waitForIdleSync()
            check(awaitDialogHas("Сколько данных", 5_000L)) { "Tapping speed must show its short explanation" }
            val speedDialog = uiAutomation.rootInActiveWindow ?: error("Speed explanation dialog missing")
            val speedDismiss = speedDialog.findAccessibilityNodeInfosByText("понятно").firstOrNull()
            check(speedDismiss?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) {
                "Speed explanation could not be dismissed"
            }
            speedDialog.recycle()
            waitForIdleSync()

            val main = activity as MainActivity
            val globe = field(main, "globe") as RouteGlobeView
            val atlas = field(globe, "atlas") as WebView
            val bridgeReady = SystemClock.elapsedRealtime() + 8_000L
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < bridgeReady) {
                val response = java.util.concurrent.CountDownLatch(1)
                val available = java.util.concurrent.atomic.AtomicBoolean()
                runOnMainSync {
                    atlas.evaluateJavascript("typeof window.DeyttAtlasBridge !== 'undefined' && typeof window.DeyttAtlasBridge.onTrafficTap === 'function'") {
                        available.set(it == "true")
                        response.countDown()
                    }
                }
                response.await(1, java.util.concurrent.TimeUnit.SECONDS)
                ready = available.get()
                if (!ready) SystemClock.sleep(100L)
            }
            check(ready) { "Local atlas WebView did not expose its traffic callback" }
            val trafficCases = listOf(
                Triple("upload", "исходящие данные", "Запросы и данные идут"),
                Triple("download", "ответный трафик", "Ответы возвращаются"),
                Triple("control", "служебный трафик", "Небольшие служебные обмены"),
            )
            trafficCases.forEach { (flow, title, explanation) ->
                runOnMainSync { atlas.evaluateJavascript("window.DeyttAtlasBridge.onTrafficTap('$flow')", null) }
                check(awaitDialogHas(title, 5_000L)) { "WebView $flow tap did not open its native Russian title" }
                check(awaitDialogHas(explanation, 1_000L)) { "WebView $flow tap did not open its native explanation" }
                runOnMainSync {
                    val sheet = field(main, "currentTrafficExplanation") as android.app.Dialog
                    check((sheet.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0) {
                        "Traffic explanation must not blur a live animated window"
                    }
                    check(field(field(main, "pingValueProgress")!!, "paused") == true &&
                        field(field(main, "speedValueProgress")!!, "paused") == true) { "Covered metrics must pause their animation" }
                }
                val image = checkNotNull(uiAutomation.takeScreenshot())
                val pictures = java.io.File(targetContext.externalMediaDirs.first(), "additional_test_output/traffic-sheets").apply { mkdirs() }
                java.io.File(pictures, "$flow.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                val trafficDialog = uiAutomation.rootInActiveWindow ?: error("$flow explanation dialog missing")
                val dismiss = trafficDialog.findAccessibilityNodeInfosByText("показать все линии").firstOrNull()
                check(dismiss?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    "$flow explanation could not be dismissed"
                }
                trafficDialog.recycle()
                waitForIdleSync()
                runOnMainSync {
                    check(field(main, "currentTrafficExplanation") == null) { "Dismissed sheet must release its animation pause" }
                    check(field(field(main, "pingValueProgress")!!, "paused") == false &&
                        field(field(main, "speedValueProgress")!!, "paused") == false)
                }
            }
            runOnMainSync {
                val key = call(main, "currentHomeRouteMetricKey", route.id)
                val pending = java.util.concurrent.FutureTask(java.util.concurrent.Callable { Unit })
                set(main, "selectedRouteLatencyInFlight", true)
                set(main, "selectedRouteLatencyFuture", pending)
                set(main, "lastRouteLatency", key to 42L)
                set(main, "homeSpeedSample", key to 250_000L)
                set(main, "homeSpeedInFlight", true)
                set(main, "homeSpeedAttemptAt", SystemClock.elapsedRealtime())
                call(main, "onStop")
                check(field(main, "homeSpeedAttemptAt") == 0L) { "Canceled downloads must not leave a minute-long retry delay" }
                check(field(main, "selectedRouteLatencyInFlight") == false && pending.isCancelled) {
                    "Stopping Home must cancel and release its selected-route ping"
                }
                check(field(main, "lastRouteLatency") == (key to 42L) && field(main, "homeSpeedSample") == (key to 250_000L)) {
                    "Stopping Home must preserve successful samples for the same connection"
                }
            }
            result.putString("result", "PASS: retained successful values over errors and stop, canceled in-flight ping, hid stale generation/route/account samples, opened both metric explanations, and verified all three WebView traffic taps")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            java.io.File(targetContext.filesDir, "subscription").deleteRecursively()
            TelegramSessionStore.clear(targetContext)
            targetContext.getSharedPreferences("selected_route", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            targetContext.getSharedPreferences(ConnectVpnService.STATE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
        return method.apply { isAccessible = true }.invoke(target, *args)
    }

    private fun awaitDialogHas(text: String, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            if (root != null) {
                val found = try { root.findAccessibilityNodeInfosByText(text).isNotEmpty() } finally { root.recycle() }
                if (found) return true
            }
            SystemClock.sleep(50L)
        }
        return false
    }

    private companion object {
        const val TEST_CONFIG = """{"inbounds":[{"type":"tun","address":["192.0.2.1/30"]}],"outbounds":[{"type":"urltest","tag":"Автоподбор","outbounds":["route:NL:VLESS","route:DE:VLESS"]},{"type":"vless","tag":"route:NL:VLESS","server":"203.0.113.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"},{"type":"vless","tag":"route:DE:VLESS","server":"203.0.113.2","server_port":443,"uuid":"00000000-0000-4000-8000-000000000002"}],"route":{"final":"Автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}}"""
    }
}
