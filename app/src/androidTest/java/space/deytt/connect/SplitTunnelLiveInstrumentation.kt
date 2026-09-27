package space.deytt.connect

import android.app.Instrumentation
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import androidx.core.content.ContextCompat
import io.nekohasekai.libbox.*
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/** Explicit manual device QA. Uses the installed subscription without exporting credentials. */
class SplitTunnelLiveInstrumentation : Instrumentation() {
    private var recoverFromProfile = false
    override fun onCreate(arguments: Bundle?) {
        recoverFromProfile = arguments?.getString("recover_from_profile") == "true"
        super.onCreate(arguments); start()
    }

    override fun onStart() {
        val result = Bundle()
        val passed = mutableListOf<String>()
        val context = targetContext
        val source = SubscriptionStore(context).readCurrent()
        val saved = SelectedRouteStore(context).read()
        val checkpoint = context.getSharedPreferences("split_live_qa", android.content.Context.MODE_PRIVATE)
        val wasConnected = if (checkpoint.contains("was_connected")) checkpoint.getBoolean("was_connected", false)
            else VpnStateStore(context).read().phase == VpnPhase.CONNECTED
        var original: DeyttRoute? = null
        var touched = false
        fun await(timeout: Long = 45_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeout * 1_000_000
            while (!condition()) { check(System.nanoTime() < deadline) { "Device state timeout" }; Thread.sleep(100) }
        }
        fun stop() {
            context.startService(Intent(context, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            AwgTunnelController.stop(context, publishStatus = false)
            await { !ConnectVpnService.isRunning() && !AwgTunnelController.isRunning() && !AwgTunnelController.isStopping() }
            Thread.sleep(500)
        }
        fun start(route: DeyttRoute) {
            SelectedRouteStore(context).save(route)
            VpnStateStore(context).write(VpnPhase.STARTING, "Проверка маршрутизации…")
            ContextCompat.startForegroundService(context, Intent(context, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_START))
            await {
                val phase = VpnStateStore(context).read().phase
                check(phase != VpnPhase.ERROR) { "Tunnel failed for ${route.protocol.name}" }
                phase == VpnPhase.CONNECTED && ConnectVpnService.isRunning()
            }
        }
        try {
            check(context.packageName == "space.deytt.connect" && source != null)
            check(VpnService.prepare(context) == null) { "VPN permission is required" }
            val routes = RouteCatalog.from(source, AwgProfileStore(context).profiles())
            original = if (recoverFromProfile) routes.firstOrNull { it.configTag == ProfileRoutes.selected(source) }
                else routes.firstOrNull { it.id == checkpoint.getString("route_id", saved.id) }
            check(original != null) { "Cannot safely restore selected route" }
            check(checkpoint.edit().putString("route_id", original!!.id).putBoolean("was_connected", wasConnected).commit())
            val sample = listOf(RouteProtocol.VLESS, RouteProtocol.TROJAN, RouteProtocol.HYSTERIA2, RouteProtocol.RU_DE, RouteProtocol.AWG31)
                .map { protocol -> routes.firstOrNull { it.protocol == protocol } ?: error("Missing protocol $protocol") }
            for (route in sample) {
                touched = true
                sendStatus(1, Bundle().apply { putString("stream", "Checking ${route.protocol.name}\n") })
                stop()
                start(route)
                val observed = ConcurrentHashMap<String, String>()
                val handler = object : CommandClientHandler {
                    override fun connected() = Unit
                    override fun disconnected(message: String) = Unit
                    override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
                    override fun setDefaultLogLevel(level: Int) = Unit
                    override fun updateClashMode(newMode: String) = Unit
                    override fun clearLogs() = Unit
                    override fun writeLogs(messages: LogIterator) = Unit
                    override fun writeOutbounds(message: OutboundGroupItemIterator) = Unit
                    override fun writeStatus(message: StatusMessage) = Unit
                    override fun writeGroups(message: OutboundGroupIterator) = Unit
                    override fun writeConnectionEvents(events: ConnectionEvents) {
                        val iterator = events.iterator()
                        while (iterator.hasNext()) {
                            val connection = iterator.next().connection ?: continue
                            val domain = connection.domain
                            if (domain in listOf("yandex.ru", "www.cloudflare.com")) observed[domain] = connection.outbound
                        }
                    }
                }
                val client = Libbox.newCommandClient(handler, CommandClientOptions().apply { addCommand(Libbox.CommandConnections) })
                try {
                    client.connect()
                    Thread.sleep(300)
                    val cm = context.getSystemService(ConnectivityManager::class.java)
                    val network = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
                    for (address in listOf("https://yandex.ru/", "https://www.cloudflare.com/cdn-cgi/trace")) {
                        val connection = network.openConnection(URL(address)) as HttpsURLConnection
                        connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Connection", "close")
                        try { check(connection.responseCode in 200..499) { "HTTPS probe failed" } }
                        finally { connection.disconnect() }
                    }
                    await(8_000) { observed.keys.containsAll(listOf("yandex.ru", "www.cloudflare.com")) }
                    check(observed["yandex.ru"] == "direct") { "Bypass was not direct" }
                    check(observed["www.cloudflare.com"] !in listOf(null, "direct")) { "Default traffic bypassed VPN" }
                    passed += "${route.protocol.name}: direct site + tunneled HTTPS"
                    sendStatus(1, Bundle().apply { putString("stream", "PASS ${passed.last()}\n") })
                } finally { client.disconnect() }
            }
            result.putString("stream", "PASS ${passed.joinToString("; ")}\n")
        } catch (error: Throwable) {
            result.putString("stream", "FAIL ${error.javaClass.simpleName}: ${error.message}; passed=${passed.joinToString()}\n")
        } finally {
            runCatching {
                if (touched) {
                    stop()
                    original?.let { route ->
                        SelectedRouteStore(context).save(route)
                        if (wasConnected) start(route)
                    }
                    check(checkpoint.edit().clear().commit())
                }
            }.onFailure { result.putString("restore", "FAILED ${it.javaClass.simpleName}") }
            finish(if (passed.size == 5 && !result.containsKey("restore")) -1 else 0, result)
        }
    }
}
