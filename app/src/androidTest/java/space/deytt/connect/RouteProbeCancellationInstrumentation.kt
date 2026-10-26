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
import androidx.core.content.ContextCompat
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
        var overlappingRequestId: String? = null
        var followupRequestId: String? = null
        val completion = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val preLibboxStage = CountDownLatch(1)
        val overlappingCompletion = CountDownLatch(1)
        val followupAccepted = CountDownLatch(1)
        val followupCompletion = CountDownLatch(1)
        val completionStage = AtomicReference<String?>()
        val preLibboxElapsed = AtomicReference<Long?>()
        val overlappingStage = AtomicReference<String?>()
        val followupStage = AtomicReference<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val eventId = intent.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID)
                if (eventId == requestId) {
                    if (intent.getStringExtra(RouteProbeClient.EXTRA_STAGE) == "accepted") accepted.countDown()
                    if (intent.getStringExtra(RouteProbeClient.EXTRA_STAGE) == "startup_profile_validation") {
                        preLibboxElapsed.set(intent.getLongExtra(RouteProbeClient.EXTRA_ELAPSED_MILLIS, -1L))
                        preLibboxStage.countDown()
                    }
                    if (intent.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                        completionStage.set(intent.getStringExtra(RouteProbeClient.EXTRA_STAGE))
                        completion.countDown()
                    }
                } else if (eventId == overlappingRequestId && intent.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                    overlappingStage.set(intent.getStringExtra(RouteProbeClient.EXTRA_STAGE))
                    overlappingCompletion.countDown()
                } else if (eventId == followupRequestId) {
                    if (intent.getStringExtra(RouteProbeClient.EXTRA_STAGE) == "accepted") followupAccepted.countDown()
                    if (intent.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                        followupStage.set(intent.getStringExtra(RouteProbeClient.EXTRA_STAGE))
                        followupCompletion.countDown()
                    }
                }
            }
        }
        val stallServer = StallingRouteServer()
        val config = stallServer.configure(TEST_CONFIG)
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
            ProfileValidator.validate(config)
            ContextCompat.registerReceiver(targetContext, receiver,
                IntentFilter(RouteProbeClient.ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true

            requestId = RouteProbeClient.start(
                targetContext,
                config,
                listOf("route:NL:VLESS"),
                RouteProbeMethod.HEAD,
                token = null,
                latencyOnly = true,
            )
            check(accepted.await(5, TimeUnit.SECONDS)) { "Route probe service did not accept the first request" }
            check(stallServer.awaitConnection()) { "Synthetic route never reached its blocking handshake" }
            targetContext.startService(Intent(targetContext, RouteProbeVpnService::class.java)
                .setAction(ConnectVpnService.ACTION_CANCEL_ROUTE_PROBE)
                .putExtra(RouteProbeClient.EXTRA_REQUEST_ID, java.util.UUID.randomUUID().toString()))
            check(!completion.await(250, TimeUnit.MILLISECONDS)) { "A stale cancel stopped the active request" }
            check(RouteProbeClient.isRunning(targetContext)) { "A stale cancel cleared the active client request" }
            overlappingRequestId = java.util.UUID.randomUUID().toString()
            val overlappingIntent = Intent(targetContext, RouteProbeVpnService::class.java)
                .setAction(ConnectVpnService.ACTION_ROUTE_PROBE)
                .putExtra(RouteProbeClient.EXTRA_REQUEST_ID, overlappingRequestId)
                .putExtra("config", config)
                .putStringArrayListExtra("route_tags", arrayListOf("route:NL:VLESS"))
                .putExtra("method", RouteProbeMethod.HEAD.name)
                .putExtra(RouteProbeClient.EXTRA_TOKEN, "")
                .putExtra(RouteProbeClient.EXTRA_REQUEST_STARTED_AT, SystemClock.elapsedRealtime())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) targetContext.startForegroundService(overlappingIntent)
            else targetContext.startService(overlappingIntent)
            check(overlappingCompletion.await(5, TimeUnit.SECONDS)) { "Overlapping request was not rejected" }
            check(overlappingStage.get() == "busy") { "Overlapping request changed the active probe session" }
            check(preLibboxStage.await(5, TimeUnit.SECONDS)) { "Startup stage before libbox setup was not reported" }
            check((preLibboxElapsed.get() ?: -1L) >= 0L) { "Startup stage omitted monotonic elapsed time" }
            RouteProbeClient.cancel(targetContext)
            check(completion.await(5, TimeUnit.SECONDS)) { "Canceled route probe did not acknowledge completion" }
            check(completionStage.get() == "cancelled") { "Route probe completed without the cancellation stage" }
            val clearDeadline = SystemClock.elapsedRealtime() + 2_000L
            while (RouteProbeClient.isRunning(targetContext) && SystemClock.elapsedRealtime() < clearDeadline) Thread.sleep(50)
            check(!RouteProbeClient.isRunning(targetContext)) { "Canceled route probe remained active in the client" }

            followupRequestId = RouteProbeClient.start(
                targetContext,
                config,
                listOf("route:NL:VLESS"),
                RouteProbeMethod.HEAD,
                token = null,
                latencyOnly = true,
            )
            check(followupAccepted.await(5, TimeUnit.SECONDS)) { "A new request was rejected before teardown completed" }
            RouteProbeClient.cancel(targetContext, followupRequestId)
            check(followupCompletion.await(5, TimeUnit.SECONDS)) { "Follow-up cancellation was not acknowledged" }
            check(followupStage.get() == "cancelled") { "Follow-up cancellation was not the final service acknowledgement" }
            targetContext.startService(Intent(targetContext, RouteProbeVpnService::class.java)
                .setAction(ConnectVpnService.ACTION_CANCEL_ROUTE_PROBE)
                .putExtra(RouteProbeClient.EXTRA_REQUEST_ID, requestId))
            Thread.sleep(150)
            val manager = targetContext.getSystemService(android.app.ActivityManager::class.java)
            val idleDeadline = SystemClock.elapsedRealtime() + 3_000L
            fun serviceExists(): Boolean = manager.getRunningServices(100).any {
                it.service.packageName == targetContext.packageName && it.service.className == RouteProbeVpnService::class.java.name
            }
            while (serviceExists() && SystemClock.elapsedRealtime() < idleDeadline) Thread.sleep(50)
            check(!serviceExists()) { "An idle stale cancel left an unnecessary diagnostic service alive" }
            results.putString("result", "PASS")
        } catch (failure: Throwable) {
            results.putString("result", "FAIL:${failure.javaClass.simpleName}")
            results.putString("message", failure.message?.take(160))
        } finally {
            stallServer.close()
            if (requestId != null && RouteProbeClient.isRunning(targetContext)) {
                runCatching { RouteProbeClient.cancel(targetContext) }
            }
            if (receiverRegistered) runCatching { targetContext.unregisterReceiver(receiver) }
            finish(if (results.getString("result") == "PASS") -1 else 0, results)
        }
    }

    private companion object {
        const val TEST_CONFIG = """{"inbounds":[{"type":"tun","address":["192.0.2.1/30"]}],"outbounds":[{"type":"vless","tag":"route:NL:VLESS","server":"203.0.113.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"}],"route":{"final":"route:NL:VLESS","rules":[{"protocol":"dns","action":"hijack-dns"}]}}"""
    }
}
