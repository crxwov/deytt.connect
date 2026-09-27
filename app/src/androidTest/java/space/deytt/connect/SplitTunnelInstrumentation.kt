package space.deytt.connect

import android.app.Instrumentation
import android.os.Bundle
import io.nekohasekai.libbox.Libbox
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
                    Libbox.checkConfig(runtime(SplitTunnelProfile.forAwg(source, proxy.port, "synthetic-test-user", "synthetic-test-password")))
                }
                Libbox.checkConfig(runtime(SplitTunnelProfile.apply(source)))
            }
            results.putString("stream", "PASS libbox split/AWG schemas; native protect rejection; three native start/close cycles\n")
            finish(-1, results)
        } catch (error: Throwable) {
            results.putString("stream", "FAIL ${error.javaClass.simpleName}: ${error.message}\n")
            finish(0, results)
        }
    }
}
