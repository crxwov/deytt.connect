package space.deytt.connect

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.util.Log
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object CdnLteXrayController {
    private const val ACTION_START = "space.deytt.connect.action.CDN_LTE_START"
    private const val ACTION_STOP = "space.deytt.connect.action.CDN_LTE_STOP"
    private const val EXTRA_CONFIG = "xray_config"
    private const val EXTRA_RESULT = "result"

    fun start(context: Context, config: String) {
        val latch = CountDownLatch(1)
        var failure: String? = null
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode != 1) failure = resultData?.getString("error") ?: "Не удалось запустить Xray"
                latch.countDown()
            }
        }
        context.startService(
            Intent(context, CdnLteXrayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_RESULT, receiver),
        ) ?: throw IllegalStateException("Не удалось запустить процесс Xray")
        if (!latch.await(20, TimeUnit.SECONDS)) {
            stop(context)
            throw IllegalStateException("Запуск LTE Xray не завершился вовремя")
        }
        failure?.let { throw IllegalStateException(it) }
    }

    fun stop(context: Context) {
        runCatching {
            context.startService(Intent(context, CdnLteXrayService::class.java).setAction(ACTION_STOP))
        }
    }

    internal fun actions() = ACTION_START to ACTION_STOP
    internal fun extras() = EXTRA_CONFIG to EXTRA_RESULT
}

internal class CdnLteXrayService : Service(), DialerController {
    companion object {
        init {
            runCatching { System.loadLibrary("gojni") }
        }
    }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var dialNetwork: Network? = null
    @Volatile private var dnsConfigured = false
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (startAction, stopAction) = CdnLteXrayController.actions()
        val (configExtra, resultExtra) = CdnLteXrayController.extras()
        when (intent?.action) {
            startAction -> {
                val config = intent.getStringExtra(configExtra)
                val receiver = readReceiver(intent, resultExtra)
                worker.execute {
                    val result = runCatching {
                        stopCore()
                        startCore(config ?: error("Пустой профиль Xray"))
                    }
                    if (result.isSuccess) {
                        receiver?.send(1, Bundle())
                    } else {
                        stopCore()
                        receiver?.send(0, Bundle().apply { putString("error", result.exceptionOrNull()?.message) })
                    }
                }
            }
            stopAction -> worker.execute {
                stopCore()
                stopSelf(startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun readReceiver(intent: Intent, key: String): ResultReceiver? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, ResultReceiver::class.java)
        else intent.getParcelableExtra(key)
    }

    private fun startCore(config: String) {
        val (network, dnsServer) = selectDialNetwork()
        dialNetwork = network
        LibXray.registerDialerController(this)
        LibXray.setDNS(this, dnsServer)
        dnsConfigured = true
        val request = JSONObject()
            .put("apiVersion", 3)
            .put("method", "runXray")
            .put("payload", JSONObject().put("xrayJson", config))
        val response = JSONObject(LibXray.invoke(request.toString()))
        check(response.optBoolean("success")) { response.optString("error", "Xray отклонил профиль") }
        val state = JSONObject(LibXray.invoke(
            JSONObject().put("apiVersion", 3).put("method", "getXrayState").toString(),
        ))
        check(state.optBoolean("success") && state.optJSONObject("data")?.optBoolean("running") == true) {
            "Xray не подтвердил запуск"
        }
        running = true
    }

    @Synchronized
    private fun stopCore() {
        if (running || dnsConfigured) {
            runCatching {
                LibXray.invoke(JSONObject().put("apiVersion", 3).put("method", "stopXray").toString())
            }
            if (dnsConfigured) runCatching { LibXray.resetDNS() }
        }
        running = false
        dnsConfigured = false
        dialNetwork = null
    }

    private fun selectDialNetwork(): Pair<Network, String> {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val networks = connectivity.allNetworks.mapNotNull { network ->
            connectivity.getNetworkCapabilities(network)?.let { network to it }
        }
        val active = connectivity.activeNetwork
        val upstreamVpn = networks.firstOrNull { (network, capabilities) ->
            network == active && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                Build.VERSION.SDK_INT >= 30 && capabilities.ownerUid != applicationInfo.uid
        } ?: networks.firstOrNull { (_, capabilities) ->
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                Build.VERSION.SDK_INT >= 30 && capabilities.ownerUid != applicationInfo.uid
        }
        val selected = upstreamVpn?.first ?: networks.firstOrNull { (network, capabilities) ->
            network == active && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }?.first ?: networks.firstOrNull { (_, capabilities) ->
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }?.first ?: error("Не найдена сеть для LTE Xray")
        val dns = connectivity.getLinkProperties(selected)?.dnsServers?.firstOrNull {
            !it.isAnyLocalAddress && !it.isLoopbackAddress
        } ?: error("У сети LTE Xray не найден DNS-сервер")
        val host = dns.hostAddress?.substringBefore('%') ?: error("DNS host address is null")
        val endpoint = if (dns is Inet6Address) "[$host]:53" else "$host:53"
        return selected to endpoint
    }

    override fun protectFd(fd: Long): Boolean {
        val network = dialNetwork ?: return false
        if (fd !in 0..Int.MAX_VALUE.toLong()) return false
        val descriptor = runCatching { ParcelFileDescriptor.fromFd(fd.toInt()) }.getOrNull() ?: return false
        return try {
            network.bindSocket(descriptor.fileDescriptor)
            true
        } catch (error: Exception) {
            Log.w("deytt-connect", "Xray socket could not bind to the upstream network", error)
            false
        } finally {
            runCatching { descriptor.close() }
        }
    }

    override fun onDestroy() {
        runCatching { stopCore() }
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
