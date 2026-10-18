package space.deytt.connect

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Verifies that canceling an isolated route probe releases the queued connection request. */
class RouteProbeCancellationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        var receiverRegistered = false
        var requestId: String? = null
        val completion = CountDownLatch(1)
        val completionStage = AtomicReference<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID) != requestId) return
                if (!intent.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) return
                completionStage.set(intent.getStringExtra(RouteProbeClient.EXTRA_STAGE))
                completion.countDown()
            }
        }
        try {
            check(targetContext.packageName.startsWith("space.deytt.connect.qa")) { "Isolated QA application required" }
            VpnService.prepare(targetContext)?.let { permission ->
                runCatching { startActivitySync(permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                val deadline = SystemClock.elapsedRealtime() + 60_000L
                while (VpnService.prepare(targetContext) != null && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(200)
                }
                check(VpnService.prepare(targetContext) == null) { "VPN permission wasn't granted to the isolated QA package" }
            }
            ProfileValidator.validate(TEST_CONFIG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetContext.registerReceiver(receiver, IntentFilter(RouteProbeClient.ACTION_RESULT), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                targetContext.registerReceiver(receiver, IntentFilter(RouteProbeClient.ACTION_RESULT))
            }
            receiverRegistered = true

            requestId = RouteProbeClient.start(
                targetContext,
                TEST_CONFIG,
                listOf("cancel-test"),
                RouteProbeMethod.HEAD,
                token = null,
                latencyOnly = true,
            )
            Thread.sleep(200)
            RouteProbeClient.cancel(targetContext)
            check(completion.await(5, TimeUnit.SECONDS)) { "Canceled route probe did not acknowledge completion" }
            check(completionStage.get() == "cancelled") { "Route probe completed without the cancellation stage" }
            val clearDeadline = SystemClock.elapsedRealtime() + 2_000L
            while (RouteProbeClient.isRunning(targetContext) && SystemClock.elapsedRealtime() < clearDeadline) Thread.sleep(50)
            check(!RouteProbeClient.isRunning(targetContext)) { "Canceled route probe remained active in the client" }
            results.putString("result", "PASS")
        } catch (failure: Throwable) {
            results.putString("result", "FAIL:${failure.javaClass.simpleName}")
            results.putString("message", failure.message?.take(160))
        } finally {
            if (requestId != null && RouteProbeClient.isRunning(targetContext)) {
                runCatching { RouteProbeClient.cancel(targetContext) }
            }
            if (receiverRegistered) runCatching { targetContext.unregisterReceiver(receiver) }
            finish(if (results.getString("result") == "PASS") -1 else 0, results)
        }
    }

    private companion object {
        const val TEST_CONFIG = """{"inbounds":[{"type":"tun","address":["192.0.2.1/30"]}],"outbounds":[{"type":"vless","tag":"cancel-test","server":"203.0.113.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"}],"route":{"final":"cancel-test","rules":[{"protocol":"dns","action":"hijack-dns"}]}}"""
    }
}
