package space.deytt.connect

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.io.File
import java.util.UUID

/** Dependency-free device regression suite; only run against -PisolatedQa=true. No live credentials. */
class SubscriptionReliabilityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        val passed = mutableListOf<String>()
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated QA application required" }
            val names = mutableSetOf<String>()
            val prefix = "test-${UUID.randomUUID()}"
            val directory = File(targetContext.cacheDir, prefix).apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = directory
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("$prefix-$name".also(names::add), mode)
            }
            fun test(name: String, block: () -> Unit) { block(); passed += name }
            fun transport(core: String = CORE, coreStatus: Int = 200, optionalStatus: Int = 404) =
                SubscriptionClient.SubscriptionHttpTransport { _, accept ->
                    if (accept == "application/json") SubscriptionClient.SubscriptionHttpResponse(coreStatus, core)
                    else SubscriptionClient.SubscriptionHttpResponse(optionalStatus)
                }
            try {
                test("valid_import_commits_source_and_stages") {
                    val stages = mutableListOf<String>()
                    SubscriptionClient.import(isolated, SOURCE, transport(), stages::add)
                    check(SubscriptionStore(isolated).readCurrent() == CORE)
                    check(isolated.getSharedPreferences("profile_settings", Context.MODE_PRIVATE).getString("subscription_url", null) == SOURCE)
                    check(stages == listOf("download", "validate", "awg", "save"))
                }
                test("invalid_profile_preserves_last_good") {
                    val failure = runCatching { SubscriptionClient.import(isolated, SOURCE, transport(core = "{}")) }.exceptionOrNull()
                    check(failure is SubscriptionPayloadException)
                    check(SubscriptionStore(isolated).readCurrent() == CORE)
                }
                test("access_denial_preserves_last_good") {
                    val failure = runCatching { SubscriptionClient.import(isolated, SOURCE, transport(coreStatus = 409)) }.exceptionOrNull()
                    check(failure is SubscriptionHttpFailure && failure.statusCode == 409)
                    check(SubscriptionStore(isolated).readCurrent() == CORE)
                }
                test("optional_outage_does_not_block_core") {
                    val imported = SubscriptionClient.import(isolated, SOURCE, transport(optionalStatus = 503))
                    check(imported.warnings.isNotEmpty())
                    check(SubscriptionStore(isolated).readCurrent() == CORE)
                }
                test("stale_session_guard_prevents_commit") {
                    val failure = runCatching {
                        SubscriptionClient.import(isolated, SOURCE, transport(CORE.replace("test-route", "replacement")),
                            onBeforeCommit = { throw SubscriptionCancelledException() })
                    }.exceptionOrNull()
                    check(failure is SubscriptionCancelledException)
                    check(SubscriptionStore(isolated).readCurrent() == CORE)
                }
                test("error_screen_survives_activity_recreation") {
                    val monitor = addMonitor(SetupActivity::class.java.name, null, false)
                    var activity = startActivitySync(Intent(targetContext, SetupActivity::class.java)
                        .putExtra(SetupActivity.EXTRA_SUBSCRIPTION_URL, "http://deytt.space/sub/token/synthetic-test")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    fun text(): String {
                        var value = ""
                        runOnMainSync {
                            fun collect(view: View): String = when (view) {
                                is TextView -> view.text.toString()
                                is ViewGroup -> (0 until view.childCount).joinToString(" ") { collect(view.getChildAt(it)) }
                                else -> ""
                            }
                            value = collect(activity.findViewById(android.R.id.content))
                        }
                        return value
                    }
                    fun awaitCondition(condition: () -> Boolean) {
                        val deadline = System.nanoTime() + 10_000_000_000L
                        while (!condition()) {
                            check(System.nanoTime() < deadline) { "UI did not reach expected state" }
                            Thread.sleep(100)
                        }
                    }
                    try {
                        awaitCondition { text().contains("Код:") }
                        check(text().contains("HTTPS"))
                        val previous = activity
                        runOnMainSync { activity.recreate() }
                        awaitCondition { monitor.lastActivity != null && monitor.lastActivity !== previous }
                        activity = monitor.lastActivity
                        awaitCondition { text().contains("Код:") }
                        check(text().contains("HTTPS"))
                    } finally {
                        runOnMainSync { activity.finish() }
                        removeMonitor(monitor)
                    }
                }
                test("stale_unauthorized_cannot_clear_new_session") {
                    val old = "synthetic-old-session-" + "x".repeat(32)
                    val newer = "synthetic-new-session-" + "y".repeat(32)
                    TelegramSessionStore.save(isolated, old)
                    TelegramSessionStore.save(isolated, newer)
                    check(!TelegramSessionStore.clearIfMatches(isolated, old))
                    check(TelegramSessionStore.read(isolated) == newer)
                    check(TelegramSessionStore.clearIfMatches(isolated, newer))
                    check(TelegramSessionStore.read(isolated) == null)
                }
            } finally {
                names.forEach { targetContext.deleteSharedPreferences(it) }
                directory.deleteRecursively()
            }
            results.putString("result", "PASS")
            results.putStringArrayList("passed", ArrayList(passed))
            finish(-1, results)
        } catch (failure: Throwable) {
            results.putString("result", "FAIL:${failure.javaClass.simpleName}")
            results.putStringArrayList("passed", ArrayList(passed))
            finish(0, results)
        }
    }

    private companion object {
        const val SOURCE = "https://deytt.space/sub/token/synthetic-test"
        const val CORE = """{"inbounds":[{"type":"tun","address":["192.0.2.1/30"]}],"outbounds":[{"type":"urltest","tag":"Автоподбор","outbounds":["test-route"]},{"type":"vless","tag":"test-route"}],"route":{"final":"Автоподбор","rules":[{"protocol":"dns","action":"hijack-dns"}]}}"""
    }
}
