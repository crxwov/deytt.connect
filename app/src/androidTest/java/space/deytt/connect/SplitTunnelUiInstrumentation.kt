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
            // Dismiss the first-launch location disclosure when it covers Settings.
            uiAutomation.rootInActiveWindow?.let { root ->
                listOf("Не сейчас", "Not now").asSequence()
                    .flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
                    .firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            }
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
                    if (tv.isShown && (text.contains("раздельное туннелирование", ignoreCase = true) ||
                        text.contains("split tunneling", ignoreCase = true))) {
                        var target: View? = tv
                        while (target != null && !target.isClickable) target = target.parent as? View
                        splitRow = target
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
            val roots = (uiAutomation.windows.mapNotNull { it.root } +
                listOfNotNull(uiAutomation.rootInActiveWindow)).distinctBy { it.windowId }
            val sheetRoot = roots.firstOrNull { root ->
                listOf("Раздельное туннелирование", "Split tunneling").any {
                    root.findAccessibilityNodeInfosByText(it).isNotEmpty()
                }
            }
            check(sheetRoot != null) {
                val windowTexts = roots.map { root ->
                    val texts = mutableListOf<String>()
                    fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
                        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
                    }
                    collect(root)
                    texts.joinToString(" | ")
                }
                "Bottom sheet was not opened after click. Windows: ${windowTexts.joinToString(" || ")}"
            }
            val modeControl = listOf("Обход VPN для списка", "Bypass VPN for list")
                .asSequence().flatMap { sheetRoot.findAccessibilityNodeInfosByText(it).asSequence() }.firstOrNull()
            check(modeControl != null) { "Split tunnel mode control was not rendered" }
            modeControl.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            waitForIdleSync()
            Thread.sleep(250)
            val choiceRoot = uiAutomation.windows.mapNotNull { it.root }
                .firstOrNull { root -> listOf("Режим", "Mode").any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() } }
                ?: uiAutomation.rootInActiveWindow
            check(choiceRoot != null) { "Mode choices were not shown" }
            val vpnOnlyChoice = listOf("VPN только для списка", "VPN only for list")
                .asSequence().flatMap { choiceRoot.findAccessibilityNodeInfosByText(it).asSequence() }.firstOrNull()
            check(vpnOnlyChoice != null) { "VPN-only mode choice was not shown" }
            vpnOnlyChoice.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            waitForIdleSync()
            Thread.sleep(250)
            check(store.readMode() == UserSplitTunnelStore.MODE_VPN_ONLY) { "VPN-only mode selection was not saved" }

            // 7. Clean up
            store.clearAll()
            activity.finish()

            results.putString("stream", "PASS SplitTunnelUi verified on device: settings row rendered, bottom sheet opened, and VPN-only mode selection persisted\n")
            finish(-1, results)
        } catch (error: Throwable) {
            results.putString("stream", "FAIL ${error.javaClass.simpleName}: ${error.message}\n")
            finish(0, results)
        }
    }
}
