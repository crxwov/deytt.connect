package space.deytt.connect

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

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
        } catch (failure: Throwable) {
            results.putString("result", "FAIL:${failure.javaClass.simpleName}")
            results.putString("stage", stage)
        } finally {
            activity?.let { current -> runOnMainSync { current.finish() } }
            waitForIdleSync()
        }
        results.putStringArrayList("passed", passed)
        if (!results.containsKey("result")) results.putString("result", "PASS")
        finish(if (results.getString("result") == "PASS") -1 else 0, results)
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
