package space.deytt.connect

import android.app.Instrumentation
import android.os.Bundle
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject
import org.amnezia.awg.crypto.KeyPair
import space.deytt.awg.AwgProxyBackend
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic configurations only; does not activate a VPN or access the user's subscription. */
class SplitTunnelInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        try {
            check(targetContext.packageName.endsWith(".qa"))
            val source = """{
              "inbounds":[{"type":"tun","address":["172.19.0.1/30"],"auto_route":true}],
              "outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":12345}],
              "dns":{"servers":[{"type":"local","tag":"local-dns"},{"type":"https","tag":"remote-dns","server":"1.1.1.1","detour":"proxy"}],"final":"remote-dns"},
              "route":{"final":"proxy","rules":[{"action":"sniff"},{"protocol":"dns","action":"hijack-dns"},{"domain_suffix":["yandex.net","vk.com"],"outbound":"direct"}]}
            }"""
            fun runtime(config: String) = RuntimeProfile.withPrivateCacheFile(config, "${targetContext.cacheDir}/split-test.db")
            Libbox.checkConfig(runtime(SplitTunnelProfile.apply(source)))
            Libbox.checkConfig(runtime(SplitTunnelProfile.forAwg(source, 12345, "synthetic-test-user", "synthetic-test-password")))
            Libbox.checkConfig(runtime(SplitTunnelProfile.forOpenFlux(source, 12345, "synthetic-test-user", "synthetic-test-password")))
            val customDomains = setOf("*.custom-bypass.org", "myapp-direct.net")
            val customPackages = setOf("com.example.bypassed", "com.android.chrome")

            // Verify UserSplitTunnelStore on device
            val store = UserSplitTunnelStore(targetContext)
            store.clearAll()
            check(store.readDomains().isEmpty()) { "Store must start empty" }
            check(store.readPackages().isEmpty()) { "Store must start empty" }
            targetContext.getSharedPreferences("user_split_tunnel", android.content.Context.MODE_PRIVATE).edit()
                .putString("custom_domains", "[\"legacy.example\"]")
                .putString("custom_packages", "[\"com.example.legacy\"]").commit()
            check(store.readDomains() == listOf("legacy.example")) { "Legacy domains did not migrate to the default preset" }
            check(store.readPackages() == listOf("com.example.legacy")) { "Legacy packages did not migrate to the default preset" }
            store.clearAll()
            check(store.addDomain("https://*.custom-bypass.org/path")) { "Failed to add domain" }
            check(store.addDomain("myapp-direct.net")) { "Failed to add domain" }
            check(!store.addDomain("invalid..domain")) { "Must reject invalid domain" }
            check(store.addPackage("com.android.chrome")) { "Failed to add package" }
            check(!store.addPackage("bad package name!")) { "Must reject invalid package" }
            val readDomains = store.readDomains()
            check(readDomains.contains("*.custom-bypass.org")) { "Missing custom domain" }
            check(readDomains.contains("myapp-direct.net")) { "Missing custom domain" }
            val readPackages = store.readPackages()
            check(readPackages.contains("com.android.chrome")) { "Missing custom package" }
            store.removeDomain("myapp-direct.net")
            check(!store.readDomains().contains("myapp-direct.net")) { "Domain removal failed" }
            store.removePackage("com.android.chrome")
            check(!store.readPackages().contains("com.android.chrome")) { "Package removal failed" }

            check(store.createPreset("Игры")) { "Failed to create preset" }
            check(store.addDomain("vpn-only.example")) { "Failed to add preset domain" }
            check(store.addPackage("com.example.vpn")) { "Failed to add preset package" }
            store.setMode(UserSplitTunnelStore.MODE_VPN_ONLY)
            val gamePreset = store.activePreset()
            check(gamePreset.mode == UserSplitTunnelStore.MODE_VPN_ONLY) { "Preset mode was not saved" }
            check(gamePreset.domains == listOf("vpn-only.example")) { "Preset domains were not saved" }
            check(UserSplitTunnelStore(targetContext).activePreset() == gamePreset) { "Preset was not persisted" }
            check(store.createPreset("Работа")) { "Failed to create second preset" }
            check(store.readDomains().isEmpty() && store.readMode() == UserSplitTunnelStore.MODE_BYPASS) { "New preset inherited rules" }
            check(store.selectPreset(gamePreset.id)) { "Failed to switch preset" }
            check(store.readDomains() == listOf("vpn-only.example")) { "Preset switch lost its rules" }
            val vpnOnly = SplitTunnelProfile.apply(source, store.readDomains(), store.readPackages(), store.readMode())
            Libbox.checkConfig(runtime(vpnOnly))
            check(JSONObject(vpnOnly).getJSONObject("route").getString("final") == "direct") { "VPN-only fallback must be direct" }
            check(store.deleteActivePreset()) { "Failed to delete selected preset" }
            check(store.activePreset().id != gamePreset.id) { "Deleted preset remained active" }
            store.clearAll()

            // Verify libbox schemas with custom domains and packages
            Libbox.checkConfig(runtime(SplitTunnelProfile.apply(source, customDomains, customPackages)))
            Libbox.checkConfig(runtime(SplitTunnelProfile.forAwg(source, 12345, "synthetic-test-user", "synthetic-test-password", customDomains, customPackages)))
            Libbox.checkConfig(runtime(SplitTunnelProfile.forOpenFlux(source, 12345, "synthetic-test-user", "synthetic-test-password", customDomains, customPackages)))

            val protects = AtomicInteger()
            val key = KeyPair().privateKey.toHex()
            val failure = runCatching {
                AwgProxyBackend.start("private_key=$key\n", arrayOf("192.0.2.2"), arrayOf("192.0.2.1"),
                    1280, "synthetic-test-user", "synthetic-test-password") { protects.incrementAndGet(); false }
                    .use { error("A rejected socket must prevent startup") }
            }.exceptionOrNull()
            check(failure != null && protects.get() > 0) { "Native socket protection was not enforced" }
            // A loopback-only synthetic device proves JNI cleanup and listener lifecycle.
            repeat(3) {
                AwgProxyBackend.start("private_key=$key\n", arrayOf("192.0.2.2"), arrayOf("192.0.2.1"),
                    1280, "synthetic-test-user", "synthetic-test-password") { true }.use { proxy ->
                    check(proxy.port in 1..65535)
                    // Two Go shared libraries must never reuse each other's
                    // thread-local runtime state, even while both are active.
                    Libbox.checkConfig(runtime(SplitTunnelProfile.forAwg(source, proxy.port, "synthetic-test-user", "synthetic-test-password", customDomains, customPackages)))
                }
                Libbox.checkConfig(runtime(SplitTunnelProfile.apply(source, customDomains, customPackages)))
            }
            results.putString("stream", "PASS libbox split/AWG/OpenFlux schemas with custom rules; store validation; native protect rejection; three native start/close cycles\n")
            finish(-1, results)
        } catch (error: Throwable) {
            results.putString("stream", "FAIL ${error.javaClass.simpleName}: ${error.message}\n")
            finish(0, results)
        }
    }
}
