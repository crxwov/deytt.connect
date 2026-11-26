package space.deytt.connect

import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the rendered country queue and cancellation handoff with synthetic routes only. */
class CountryProbeQueueInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var registered = false
        val firstId = AtomicReference<String?>()
        val secondId = AtomicReference<String?>()
        val firstAccepted = CountDownLatch(1)
        val firstCancelled = CountDownLatch(1)
        val secondAccepted = CountDownLatch(1)
        val secondCancelled = CountDownLatch(1)
        val acceptedAt = mutableMapOf<String, Long>()
        val cancelledAt = mutableMapOf<String, Long>()
        val busyIds = mutableListOf<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val event = intent ?: return
                val id = event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty()
                val stage = event.getStringExtra(RouteProbeClient.EXTRA_STAGE).orEmpty()
                val complete = event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)
                synchronized(acceptedAt) {
                    when {
                        stage == "accepted" -> {
                            acceptedAt[id] = SystemClock.elapsedRealtime()
                            if (firstId.compareAndSet(null, id)) firstAccepted.countDown()
                            else if (id != firstId.get() && secondId.compareAndSet(null, id)) secondAccepted.countDown()
                        }
                        stage == "busy" -> busyIds += id
                    }
                    if (complete && stage == "cancelled") {
                        cancelledAt[id] = SystemClock.elapsedRealtime()
                        if (id == firstId.get()) firstCancelled.countDown()
                        if (id == secondId.get()) secondCancelled.countDown()
                    }
                }
            }
        }
        val stallServer = StallingRouteServer()
        val config = stallServer.configure(TEST_CONFIG)
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated QA package required" }
            targetContext.getSharedPreferences("profile_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("network_location_enabled", false).commit()
            check(VpnService.prepare(targetContext) == null) { "VPN permission must already be granted to the isolated QA package" }
            ProfileValidator.validate(config)
            AppLanguage.set(targetContext, AppLanguage.RU)
            SubscriptionStore(targetContext).saveValidated(config)
            SelectedRouteStore(targetContext).save(RouteCatalog.from(config, emptyList()).first { it.id == "route:NL:VLESS" })
            VpnStateStore(targetContext).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
            ContextCompat.registerReceiver(targetContext, receiver, IntentFilter(RouteProbeClient.ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

            lateinit var nlHeader: View
            lateinit var deHeader: View
            lateinit var nlDetails: View
            lateinit var deDetails: View
            runOnMainSync { (activity as MainActivity).selectTab(1) }
            waitForIdleSync()
            runOnMainSync {
                val home = activity as MainActivity
                nlHeader = findCountryHeader(home, "нидерланды")
                deHeader = findCountryHeader(home, "германия")
                nlDetails = (nlHeader.parent as ViewGroup).getChildAt(1)
                deDetails = (deHeader.parent as ViewGroup).getChildAt(1)
                nlHeader.performClick()
            }
            check(firstAccepted.await(8, TimeUnit.SECONDS)) { "Netherlands route probe was not accepted" }
            check(stallServer.awaitConnection()) { "Netherlands probe did not reach its blocking handshake" }
            runOnMainSync { deHeader.performClick() }
            waitForIdleSync()
            check(awaitText(deDetails, "В очереди", 2_000L)) { "Germany should render as queued while Netherlands is active" }
            runOnMainSync { nlHeader.performClick() }
            check(firstCancelled.await(8, TimeUnit.SECONDS)) { "Collapsed Netherlands probe did not acknowledge cancellation" }
            check(secondAccepted.await(8, TimeUnit.SECONDS)) { "Queued Germany probe did not start after cancellation" }
            val first = firstId.get() ?: error("Missing first request id")
            val second = secondId.get() ?: error("Missing second request id")
            synchronized(acceptedAt) {
                check((cancelledAt[first] ?: Long.MAX_VALUE) <= (acceptedAt[second] ?: 0L)) {
                    "Germany was accepted before Netherlands cancellation completed"
                }
                check(second !in busyIds) { "Germany request was rejected as overlapping the Netherlands core" }
            }
            SystemClock.sleep(350L)
            check(awaitText(nlDetails, "Проверка отменена", 1_000L)) { "A late Netherlands event overwrote its canceled row" }

            runOnMainSync { deHeader.performClick() }
            check(secondCancelled.await(8, TimeUnit.SECONDS)) { "Collapsed Germany probe did not acknowledge cancellation" }
            val home = activity as MainActivity
            val primaryField = MainActivity::class.java.getDeclaredField("primaryPages").apply { isAccessible = true }
            val primaryPages = primaryField.get(home) as PrimaryPages
            runOnMainSync {
                primaryPages.stopForegroundUpdates()
                RouteQualityStore.write(targetContext, RouteProbeSample("route:NL:VLESS", 42L, 1_000_000L))
                RouteQualityStore.write(targetContext, RouteProbeSample("route:DE:VLESS", 56L, 1_000_000L))
                nlHeader = findCountryHeader(home, "нидерланды")
                deHeader = findCountryHeader(home, "германия")
                nlHeader.performClick()
                deHeader.performClick()
            }
            val adapterField = MainActivity::class.java.getDeclaredField("pageAdapter").apply { isAccessible = true }
            val adapter = adapterField.get(home) as TopLevelViewPagerAdapter
            runOnMainSync { adapter.refresh(1) }
            waitForIdleSync()
            runOnMainSync {
                nlHeader = findCountryHeader(home, "нидерланды")
                deHeader = findCountryHeader(home, "германия")
                nlDetails = (nlHeader.parent as ViewGroup).getChildAt(1)
                deDetails = (deHeader.parent as ViewGroup).getChildAt(1)
                check(nlDetails.visibility == View.VISIBLE && deDetails.visibility == View.VISIBLE) {
                    "Manual route refresh collapsed an expanded country"
                }
            }
            check(awaitText(nlDetails, "42 мс", 1_000L) && awaitText(deDetails, "56 мс", 1_000L)) {
                "Cached route results were not visible after route-page refresh"
            }
            synchronized(acceptedAt) {
                check(acceptedAt.keys == setOf(first, second)) { "Route-page refresh started an unexpected probe" }
            }
            result.putString("result", "PASS: queued probes canceled on explicit collapse; a manual route refresh preserved both expanded countries and cached results without restarting probes")
            result.putLong("queue_delay_ms", (acceptedAt[second] ?: 0L) - (cancelledAt[first] ?: 0L))
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            stallServer.close()
            if (RouteProbeClient.isRunning(targetContext)) runCatching { RouteProbeClient.cancel(targetContext) }
            activity?.let { runCatching { runOnMainSync { it.finish() } } }
            if (registered) runCatching { targetContext.unregisterReceiver(receiver) }
            java.io.File(targetContext.filesDir, "subscription").deleteRecursively()
            targetContext.getSharedPreferences("selected_route", Context.MODE_PRIVATE).edit().clear().commit()
            targetContext.getSharedPreferences(ConnectVpnService.STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            targetContext.getSharedPreferences("profile_settings", Context.MODE_PRIVATE).edit().remove("app_language").commit()
            targetContext.getSharedPreferences("route_quality", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun findCountryHeader(activity: Activity, code: String): View {
        fun search(view: View): View? {
            if (view.contentDescription?.toString()?.contains("выходы $code", ignoreCase = true) == true) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) search(view.getChildAt(index))?.let { return it }
            return null
        }
        return search(activity.window.decorView) ?: error("Rendered country header not found: $code")
    }

    private fun awaitText(view: View, text: String, timeoutMillis: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < end) {
            var found = false
            runOnMainSync {
                fun search(node: View) {
                    if (node is TextView && node.text.toString().contains(text, ignoreCase = true)) found = true
                    if (node is ViewGroup) for (index in 0 until node.childCount) search(node.getChildAt(index))
                }
                search(view)
            }
            if (found) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private companion object {
        const val TEST_CONFIG = """{"inbounds":[{"type":"tun","address":["192.0.2.1/30"]}],"outbounds":[{"type":"urltest","tag":"Автоподбор","outbounds":["route:NL:VLESS","route:DE:VLESS"]},{"type":"vless","tag":"route:NL:VLESS","server":"203.0.113.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"},{"type":"vless","tag":"route:DE:VLESS","server":"203.0.113.2","server_port":443,"uuid":"00000000-0000-4000-8000-000000000002"}],"route":{"final":"Автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}}"""
    }
}
