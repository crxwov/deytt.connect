package space.deytt.connect

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import libXray.LibXray
import org.json.JSONObject
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

internal class CdnLteXrayService : Service() {
    companion object {
        init {
            runCatching { System.loadLibrary("gojni") }
        }
    }
    private val worker = Executors.newSingleThreadExecutor()
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
        if (running) {
            runCatching {
                LibXray.invoke(JSONObject().put("apiVersion", 3).put("method", "stopXray").toString())
            }
        }
        running = false
    }

    override fun onDestroy() {
        runCatching { stopCore() }
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
