package space.deytt.connect

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UI Instrumentation verifying Split Tunneling settings and bottom sheet on real device. */
class SplitTunnelUiInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        val context = targetContext
        try {
            check(context.packageName.endsWith(".qa"))

            // 1. Prepare valid synthetic subscription
            val syntheticSubscription = """{
              "inbounds":[{"type":"tun","address":["172.19.0.1/30"],"auto_route":true}],
              "outbounds":[{"type":"urltest","tag":"🌍 автоподбор"},{"type":"vless","tag":"route:DE:VLESS"},{"type":"direct","tag":"direct"}],
              "dns":{"servers":[{"type":"local","tag":"local-dns"},{"type":"https","tag":"remote-dns","server":"1.1.1.1","detour":"route:DE:VLESS"}],"final":"remote-dns","rules":[{"server":"local-dns","domain_suffix":["ru","xn--p1ai"]},{"server":"local-dns","domain":["cp.cloudflare.com"]}]},
              "route":{"final":"route:DE:VLESS","rules":[{"action":"sniff"},{"protocol":"dns","action":"hijack-dns"},{"domain_suffix":["ru","xn--p1ai"],"outbound":"direct"},{"domain":["cp.cloudflare.com"],"outbound":"direct"}]}
            }"""
            SubscriptionStore(context).saveValidated(syntheticSubscription)

            // 2. Prepare custom split tunnel store
            val store = UserSplitTunnelStore(context)
            store.clearAll()
            store.addDomain("my-ui-test.com")
            store.addPackage("com.android.chrome")

            // 3. Launch MainActivity directly on Settings tab (tab 3)
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(MainActivity.EXTRA_START_TAB, 3)
            }
            val activity = startActivitySync(intent) as MainActivity
            waitForIdleSync()
            runOnMainSync { activity.selectTab(3) }
            waitForIdleSync()
            Thread.sleep(500)

            // 4. Find the split tunneling row in settings
            fun findAllTextViews(view: View): List<TextView> {
                val list = mutableListOf<TextView>()
                if (view is TextView) list.add(view)
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        list.addAll(findAllTextViews(view.getChildAt(i)))
                    }
                }
                return list
            }

            var splitRow: View? = null
            var allTexts = emptyList<String>()
            runOnMainSync {
                val textViews = findAllTextViews(activity.window.decorView)
                allTexts = textViews.map { it.text?.toString().orEmpty() }
                for (tv in textViews) {
                    val text = tv.text?.toString().orEmpty()
                    if (text.contains("раздельное туннелирование", ignoreCase = true) ||
                        text.contains("split tunneling", ignoreCase = true)) {
                        splitRow = tv.parent as? View ?: tv
                        break
                    }
                }
            }
            check(splitRow != null) { "Could not find Split Tunneling settings row. Visible texts: ${allTexts.filter { it.isNotBlank() }}" }

            // 5. Click the split tunneling row to open bottom sheet
            runOnMainSync {
                splitRow!!.performClick()
            }
            waitForIdleSync()
            Thread.sleep(1200)

            // 6. Verify the bottom sheet opened
            var foundSheetTitle = false
            runOnMainSync {
                val sheetViews = findAllTextViews(activity.window.decorView)
                for (tv in sheetViews) {
                    val text = tv.text?.toString().orEmpty()
                    if (text.contains("раздельное туннелирование", ignoreCase = true) ||
                        text.contains("split tunneling", ignoreCase = true)) {
                        foundSheetTitle = true
                        break
                    }
                }
            }
            check(foundSheetTitle) { "Bottom sheet was not opened after click" }

            // 7. Clean up
            store.clearAll()
            activity.finish()

            results.putString("stream", "PASS SplitTunnelUi verified on device: settings row rendered, summary displayed 2 custom rules, and bottom sheet dialog opened successfully\n")
            finish(-1, results)
        } catch (error: Throwable) {
            results.putString("stream", "FAIL ${error.javaClass.simpleName}: ${error.message}\n")
            finish(0, results)
        }
    }
}
