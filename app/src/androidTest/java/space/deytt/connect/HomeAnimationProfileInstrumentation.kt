package space.deytt.connect

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Holds a synthetic Home animation state for one bounded Perfetto capture. */
class HomeAnimationProfileInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        val atlasSnapshots = linkedMapOf<String, String>()
        try {
            check(targetContext.packageName == QA_PACKAGE) { "Expected isolated QA target $QA_PACKAGE" }
            ProfileValidator.validate(TEST_CONFIG)
            AppLanguage.set(targetContext, AppLanguage.RU)
            TelegramSessionStore.clear(targetContext)
            SubscriptionStore(targetContext).saveValidated(TEST_CONFIG)
            val route = RouteCatalog.from(TEST_CONFIG, emptyList(), emptyList()).first { it.id == "route:NL:VLESS" }
            SelectedRouteStore(targetContext).save(route)
            VpnStateStore(targetContext).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
            check(targetContext.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("network_location_enabled", false).commit()) { "Could not suppress the isolated QA location consent prompt" }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

            lateinit var globe: RouteGlobeView
            lateinit var atlas: WebView
            runOnMainSync {
                val home = activity as MainActivity
                (field(home, "selectedRouteLatencyHandler") as android.os.Handler).removeCallbacksAndMessages(null)
                (field(home, "mapTrafficHandler") as android.os.Handler).removeCallbacksAndMessages(null)
                set(home, "homeSpeedForeground", true)
                set(home, "homeSpeedOnHome", false)
                set(home, "renderedPhase", VpnPhase.CONNECTED)
                call(home, "updateQualityStrip")
                val latency = field(home, "latencyText") as TextView
                val download = field(home, "downloadText") as TextView
                latency.text = "—"
                download.text = "—"
                val ping = field(home, "pingValueProgress") as InlineMetricProgress
                val speed = field(home, "speedValueProgress") as InlineMetricProgress
                ping.setLoading(true)
                speed.setLoading(true)
                check(hasLoadingSpan(latency) && hasLoadingSpan(download)) { "Both Home metric values must display inline loading indicators" }
                set(home, "mapTrafficActive", true)
                globe = field(home, "globe") as RouteGlobeView
                globe.focus("nl", animate = false)
                globe.setUserLocation(IpNetworkLocation(54.7388, 55.9721, "Уфа", "Республика Башкортостан", "RU"))
                globe.setConnected(true)
                globe.setEgressCountry("NL")
                globe.setTrafficActive(true)
                atlas = field(globe, "atlas") as WebView
            }
            waitForIdleSync()
            val readyBy = SystemClock.elapsedRealtime() + 8_000L
            var bridgeReady = false
            while (!bridgeReady && SystemClock.elapsedRealtime() < readyBy) {
                val latch = java.util.concurrent.CountDownLatch(1)
                val available = java.util.concurrent.atomic.AtomicBoolean()
                runOnMainSync {
                    atlas.evaluateJavascript("typeof window.DeyttAtlasBridge !== 'undefined' && typeof window.DeyttAtlasBridge.onTrafficTap === 'function'") {
                        available.set(it == "true")
                        latch.countDown()
                    }
                }
                latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
                bridgeReady = available.get()
                if (!bridgeReady) SystemClock.sleep(100L)
            }
            check(bridgeReady) { "Local atlas traffic bridge did not become ready" }
            val home = activity as MainActivity
            check(awaitWindowFocus(home, 5_000L)) { "Home did not regain window focus after launch" }

            mark("READY; waiting 5s for recorder")
            SystemClock.sleep(5_000L)
            atlasSnapshots["home_loading_before"] = atlasSnapshot(atlas)
            tracedPhase("HOME_LOADING_GLOBE") { SystemClock.sleep(6_000L) }
            atlasSnapshots["home_loading_after"] = atlasSnapshot(atlas)
            tracedPhase("TRAFFIC_EXPLANATION") {
                runOnMainSync { atlas.evaluateJavascript("window.DeyttAtlasBridge.onTrafficTap('download')", null) }
                check(awaitDialogHas("ответный трафик", 5_000L)) { "Traffic explanation did not open" }
                SystemClock.sleep(4_000L)
                val root = uiAutomation.rootInActiveWindow ?: error("Traffic explanation dialog missing")
                try {
                    val close = root.findAccessibilityNodeInfosByText("показать все линии").firstOrNull()
                    check(close?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot dismiss traffic explanation" }
                } finally { root.recycle() }
                waitForIdleSync()
            }
            runOnMainSync {
                val home = activity as MainActivity
                set(home, "mapTrafficActive", true)
                (field(home, "pingValueProgress") as InlineMetricProgress).setLoading(true)
                (field(home, "speedValueProgress") as InlineMetricProgress).setLoading(true)
                globe.setTrafficActive(true)
            }
            check(awaitWindowFocus(home, 5_000L)) { "Home did not regain window focus after dismissing the traffic explanation" }
            atlasSnapshots["home_resumed_before"] = atlasSnapshot(atlas)
            tracedPhase("HOME_RESUMED") { SystemClock.sleep(3_000L) }
            atlasSnapshots["home_resumed_after"] = atlasSnapshot(atlas)
            mark("DONE")
            result.putString("result", "PASS: synthetic Home spinners/globe traffic, opened and dismissed traffic explanation")
            atlasSnapshots.forEach { (key, value) -> result.putString("atlas_$key", value) }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            mark("ERROR: ${error.javaClass.simpleName}: ${error.message}")
            result.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            File(targetContext.filesDir, "subscription").deleteRecursively()
            TelegramSessionStore.clear(targetContext)
            targetContext.getSharedPreferences("selected_route", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            targetContext.getSharedPreferences(ConnectVpnService.STATE_PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit()
            targetContext.getSharedPreferences("profile_settings", android.content.Context.MODE_PRIVATE).edit().remove("network_location_enabled").commit()
        }
    }

    private fun mark(message: String) = Log.i(TAG, "${SystemClock.elapsedRealtime()}: $message")
    private inline fun tracedPhase(name: String, action: () -> Unit) {
        mark("$name BEGIN")
        Trace.beginSection("HomeProfile:$name")
        try { action() } finally { Trace.endSection(); mark("$name END") }
    }
    private fun hasLoadingSpan(view: TextView): Boolean =
        (view.text as? Spanned)?.getSpans(0, view.length(), ReplacementSpan::class.java)?.isNotEmpty() == true
    private fun awaitWindowFocus(activity: Activity, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val focused = AtomicReference(false)
            runOnMainSync { focused.set(activity.hasWindowFocus()) }
            if (focused.get()) return true
            SystemClock.sleep(50L)
        }
        return false
    }
    private fun atlasSnapshot(atlas: WebView): String {
        val value = AtomicReference<String?>()
        val ready = CountDownLatch(1)
        runOnMainSync {
            atlas.evaluateJavascript("(function(){var a=window.deyttMapAtlas;if(!a)return null;return {renderCount:a.renderCount,staticRenderCount:a.staticRenderCount,overlayCacheBuildCount:a.overlayCacheBuildCount,now:performance.now(),trafficActive:a.trafficActive,reducedMotion:a.reducedMotion.matches};})()") {
                value.set(it)
                ready.countDown()
            }
        }
        check(ready.await(2, TimeUnit.SECONDS)) { "Atlas snapshot evaluation timed out" }
        return value.get()?.takeIf { it != "null" } ?: error("Atlas instance unavailable for snapshot")
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.first { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }.invoke(target, *args)

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
        const val TAG = "HomeAnimationProfile"
        const val QA_PACKAGE = "space.deytt.connect.qa.measurements.qa"
        const val TEST_CONFIG = """{"inbounds":[{"type":"tun","address":["192.0.2.1/30"]}],"outbounds":[{"type":"urltest","tag":"Автоподбор","outbounds":["route:NL:VLESS","route:DE:VLESS"]},{"type":"vless","tag":"route:NL:VLESS","server":"203.0.113.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"},{"type":"vless","tag":"route:DE:VLESS","server":"203.0.113.2","server_port":443,"uuid":"00000000-0000-4000-8000-000000000002"}],"route":{"final":"Автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}}"""
    }
}
