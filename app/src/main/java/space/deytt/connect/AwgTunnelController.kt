package space.deytt.connect

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.BufferedReader
import java.io.StringReader
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

object AwgTunnelController {
    private val executor = Executors.newSingleThreadExecutor()
    // GoBackend must stay alive for the whole active tunnel and stores only the
    // application context supplied below; it is cleared as soon as the tunnel stops.
    @SuppressLint("StaticFieldLeak")
    private var backend: GoBackend? = null
    private var tunnel: Tunnel? = null

    fun start(context: Context, rawConfig: String, name: String) {
        publish(context, "Запуск AmneziaWG…")
        executor.execute {
            try {
                stopInternal()
                val parsed = Config.parse(BufferedReader(StringReader(rawConfig)))
                val nextBackend = GoBackend(context.applicationContext)
                val nextTunnel = object : Tunnel {
                    override fun getName(): String = name.take(15)
                    override fun onStateChange(newState: Tunnel.State) = Unit
                }
                nextBackend.setStatusCallback { connected ->
                    if (connected) publish(context, "Проверяем туннель…")
                }
                backend = nextBackend
                tunnel = nextTunnel
                nextBackend.setState(nextTunnel, Tunnel.State.UP, parsed)
                verifyTraffic()
                publish(context, "VPN подключён")
            } catch (error: Throwable) {
                stopInternal()
                publish(
                    context,
                    "Ошибка запуска VPN",
                    error.message?.takeIf(String::isNotBlank) ?: "Не удалось запустить AmneziaWG",
                )
            }
        }
    }

    fun stop(context: Context, publishStatus: Boolean = true) {
        executor.execute {
            stopInternal()
            if (publishStatus) publish(context, "VPN отключён")
        }
    }

    private fun stopInternal() {
        val currentBackend = backend
        val currentTunnel = tunnel
        backend = null
        tunnel = null
        if (currentBackend != null && currentTunnel != null) {
            runCatching { currentBackend.setState(currentTunnel, Tunnel.State.DOWN, null) }
        }
    }

    private fun verifyTraffic() {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val connection = URL("https://www.gstatic.com/generate_204")
                    .openConnection() as HttpsURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                try {
                    check(TunnelCanary.acceptsHttpResponse(connection.responseCode, expectedStatus = null)) {
                        "проверочный сайт ответил HTTP ${connection.responseCode}"
                    }
                } finally {
                    connection.disconnect()
                }
                return
            } catch (error: Throwable) {
                lastError = error
                if (attempt < 2) Thread.sleep(1_500)
            }
        }
        throw IllegalStateException("Выбранный маршрут не передаёт трафик: ${lastError?.message.orEmpty()}")
    }

    private fun publish(context: Context, status: String, error: String? = null) {
        context.getSharedPreferences(ConnectVpnService.STATE_PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(ConnectVpnService.STATE_STATUS, status)
                putString(ConnectVpnService.STATE_ERROR, error)
            }
        val intent = Intent(ConnectVpnService.ACTION_STATUS)
            .setPackage(context.packageName)
            .putExtra(ConnectVpnService.EXTRA_STATUS, status)
        if (!error.isNullOrBlank()) intent.putExtra(ConnectVpnService.EXTRA_ERROR, error)
        context.sendBroadcast(intent)
    }
}
