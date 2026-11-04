package space.deytt.connect

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.text

internal class AwgMeasurementCoordinator(
    private val host: MainActivity,
    private val cancelHomeRouteDownload: (() -> Unit)? = null,
    private val onClearPendingRouteProbe: (() -> Unit)? = null,
    private val onClearPendingRoute: (() -> Unit)? = null,
    private val onRestored: (() -> Unit)? = null,
    private val onRouteRowRebuild: (() -> Unit)? = null,
    private val onStateChanged: (() -> Unit)? = null,
    private val isSystemTunnelActive: (() -> Boolean)? = null,
    private val awaitEnginesStopped: ((waitForDiagnostics: Boolean, onFailure: () -> Unit, action: () -> Unit) -> Unit)? = null,
) {
    companion object {
        const val ROUTE_PROBE_PERMISSION_REQUEST = 703
    }

    var temporaryAwgMeasurement = false
    var restoreAwgMeasurement: (() -> Unit)? = null
    var cancelAwgMeasurementForSelection: (() -> Boolean)? = null
    var pendingRouteProbeAuthorization: (() -> Unit)? = null

    fun cancelAwgMeasurement() {
        restoreAwgMeasurement?.invoke()
    }

    fun requestAwgMeasurement(
        route: DeyttRoute,
        onComplete: (Boolean) -> Unit = {},
        measure: ((Boolean) -> Unit) -> Unit,
    ) = requestAwgMeasurements(listOf(route), onComplete, {}, measure = { _, finished -> measure(finished) })

    /** One temporary app-only VPN sequence, followed by restoration of the actual previous VPN. */
    fun requestAwgMeasurements(
        routes: List<DeyttRoute>,
        onComplete: (Boolean) -> Unit,
        onRouteFailure: (DeyttRoute) -> Unit,
        onRouteStarting: (DeyttRoute) -> Unit = {},
        measure: (DeyttRoute, (Boolean) -> Unit) -> Unit,
    ) {
        if (temporaryAwgMeasurement || routes.isEmpty() || host.isFinishing || host.isDestroyed) {
            onComplete(false)
            return
        }
        val previous = SelectedRouteStore(host).read()
        val config = SubscriptionStore(host).readCurrent()
        val previousRoute = config?.let { RouteCatalog.from(it, AwgProfileStore(host).profiles(), OpenFluxProfileStore(host).profiles(host)) }
            ?.firstOrNull { it.id == previous.id }
            ?: if (previous.id == "auto") DeyttRoute(id = "auto", countryCode = "", country = "автоподбор", flag = "", protocol = RouteProtocol.AUTO, engine = TunnelEngine.LIBBOX) else null
        val wasConnected = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        android.util.Log.i("DeyttRouteProbe", "requestAwgMeasurements temporary=$temporaryAwgMeasurement routes=${routes.size} previous=${previous.id} previousRoute=${previousRoute?.id} wasConnected=$wasConnected")
        if (config == null || (wasConnected && previousRoute == null)) {
            android.util.Log.w("DeyttRouteProbe", "requestAwgMeasurements aborted: config=$config previousRoute=$previousRoute wasConnected=$wasConnected")
            routes.forEach(onRouteFailure)
            onComplete(false)
            return
        }
        if (VpnService.prepare(host) != null) {
            // Country LIBBOX diagnostics normally acquired this already. Avoid
            // automatically replacing another app's VPN permission in a batch.
            routes.forEach(onRouteFailure)
            onComplete(false)
            return
        }
        cancelHomeRouteDownload?.invoke()
        temporaryAwgMeasurement = true
        runCatching { RouteProbeClient.cancel(host) }
        onClearPendingRouteProbe?.invoke()
        val app = host.applicationContext
        val handler = Handler(Looper.getMainLooper())
        var restorationRequested = false
        var restorationReconnectRequested = false
        var completionSent = false
        var switchedTunnel = false
        var allSuccessful = true
        var activeStep = 0

        fun complete(restored: Boolean) {
            if (completionSent) return
            completionSent = true
            temporaryAwgMeasurement = false
            restoreAwgMeasurement = null
            cancelAwgMeasurementForSelection = null
            if (restored && wasConnected) onRestored?.invoke()
            if (!host.isFinishing && !host.isDestroyed) {
                onRouteRowRebuild?.invoke()
                onStateChanged?.invoke()
            }
            onComplete(restored && allSuccessful)
        }

        fun restorationFailed() {
            VpnStateStore(app).write(VpnPhase.ERROR, "Не удалось восстановить соединение", "Подключитесь к прежнему маршруту вручную")
            complete(false)
        }

        fun restore() {
            if (restorationRequested) return
            restorationRequested = true
            activeStep++
            if (!switchedTunnel) { complete(true); return }
            AwgTunnelController.stop(app, publishStatus = false)
            if (previousRoute != null) {
                SelectedRouteStore(app).save(previousRoute)
            }
            SubscriptionStore(app).saveValidated(config)
            onClearPendingRoute?.invoke()
            var waitStarted = SystemClock.elapsedRealtime()
            var reconnectStarted = false
            var reconnectAt = 0L
            var retries = 0
            var transportGoneAt = 0L
            val finish = object : Runnable {
                override fun run() {
                    if (completionSent) return
                    if (SelectedRouteStore(app).read().id != previous.id) { complete(false); return }
                    val state = VpnStateStore(app).read()
                    if (reconnectStarted) {
                        val originalRunning = ConnectVpnService.isRunning()
                        when {
                            state.phase == VpnPhase.CONNECTED && originalRunning -> complete(true)
                            state.phase == VpnPhase.ERROR && !originalRunning && retries == 0 -> {
                                retries++
                                reconnectStarted = false
                                transportGoneAt = 0L
                                waitStarted = SystemClock.elapsedRealtime()
                                handler.postDelayed(this, 100L)
                            }
                            (state.phase == VpnPhase.ERROR && !originalRunning) || SystemClock.elapsedRealtime() - reconnectAt > 45_000L -> restorationFailed()
                            else -> handler.postDelayed(this, 100L)
                        }
                        return
                    }
                    if (AwgTunnelController.isRunning() || AwgTunnelController.isStopping() ||
                        ConnectVpnService.isRunning() || RouteProbeClient.isRunning(app) || isTunnelActive(app)) {
                        transportGoneAt = 0L
                        if (SystemClock.elapsedRealtime() - waitStarted < 15_000L) handler.postDelayed(this, 30L)
                        else restorationFailed()
                        return
                    }
                    if (transportGoneAt == 0L) transportGoneAt = SystemClock.elapsedRealtime()
                    if (SystemClock.elapsedRealtime() - transportGoneAt < 150L) {
                        handler.postDelayed(this, 30L)
                        return
                    }
                    if (!wasConnected) {
                        VpnStateStore(app).write(VpnPhase.IDLE, VpnStateStore.IDLE_TITLE)
                        complete(true)
                        return
                    }
                    reconnectStarted = true
                    reconnectAt = SystemClock.elapsedRealtime()
                    VpnStateStore(app).write(VpnPhase.STARTING, "Восстанавливаем соединение…")
                    restorationReconnectRequested = true
                    runCatching {
                        ContextCompat.startForegroundService(
                            app,
                            Intent(app, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_START),
                        )
                    }.onFailure { restorationFailed() }
                    if (!completionSent) handler.postDelayed(this, 30L)
                }
            }
            handler.post(finish)
        }

        restoreAwgMeasurement = ::restore
        cancelAwgMeasurementForSelection = {
            restorationRequested = true
            activeStep++
            if (restorationReconnectRequested) {
                host.startService(Intent(app, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            }
            AwgTunnelController.stop(app, publishStatus = false)
            complete(false)
            wasConnected
        }

        lateinit var advance: (Int) -> Unit
        advance = next@ { index ->
            if (restorationRequested || completionSent) return@next
            if (index >= routes.size || host.isFinishing || host.isDestroyed) { restore(); return@next }
            val route = routes[index]
            onRouteStarting(route)
            val step = ++activeStep

            fun failed() {
                if (restorationRequested || step != activeStep) return
                allSuccessful = false
                onRouteFailure(route)
                advance(index + 1)
            }

            fun measureReady() {
                var reported = false
                runCatching {
                    measure(route) { success ->
                        host.runOnUiThread {
                            if (!reported && !restorationRequested && step == activeStep) {
                                reported = true
                                if (!success) allSuccessful = false
                                advance(index + 1)
                            }
                        }
                    }
                }.onFailure { failed() }
            }

            if (!switchedTunnel && previous.id == route.id && AwgTunnelController.isRunning() &&
                VpnStateStore(app).read().phase == VpnPhase.CONNECTED) {
                measureReady()
                return@next
            }
            switchedTunnel = true
            if (ConnectVpnService.isRunning()) {
                host.startService(Intent(host, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            }
            AwgTunnelController.stop(app, publishStatus = false)
            SelectedRouteStore(app).save(route)
            waitForEnginesStopped(waitForDiagnostics = true, onFailure = { allSuccessful = false; restore() }, action = {
                if (!wasConnected && isTunnelActive(app)) {
                    failed()
                    return@waitForEnginesStopped
                }
                if (!restorationRequested && step == activeStep) {
                    val raw = AwgProfileStore(app).read(route.id)
                    if (raw == null) {
                        failed()
                    } else {
                        AwgTunnelController.start(app, raw, route.id, diagnosticOnly = true)
                        val began = SystemClock.elapsedRealtime()
                        val check = object : Runnable {
                            override fun run() {
                                if (restorationRequested || step != activeStep) return
                                val state = VpnStateStore(app).read()
                                when {
                                    host.isFinishing || host.isDestroyed -> restore()
                                    state.phase == VpnPhase.ERROR || SystemClock.elapsedRealtime() - began > 5_000L -> failed()
                                    state.phase == VpnPhase.CONNECTED && AwgTunnelController.isRunning() -> measureReady()
                                    else -> handler.postDelayed(this, 30L)
                                }
                            }
                        }
                        handler.postDelayed(check, 30L)
                    }
                }
            })
        }
        advance(0)
    }

    fun showRouteProbePermissionSheet(permission: Intent) {
        val localized: (String, String) -> String = { ru, en ->
            if (AppLanguage.current(host) == AppLanguage.EN) en else ru
        }
        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(20), host.dp(20), host.dp(20), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        sheet.addView(host.mono(localized("ДИАГНОСТИКА · РАЗОВОЕ РАЗРЕШЕНИЕ", "DIAGNOSTICS · ONE-TIME PERMISSION"), 9f, DeyttUi.MUTED, 650))
        sheet.addView(host.text(localized("Разрешить проверку маршрутов?", "Allow route diagnostics?"), 19f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(7))
        })
        sheet.addView(host.text(
            localized(
                "Android запросит системное разрешение для замера. Для AmneziaWG тестовый туннель временно подключится, а после проверки приложение вернёт прежний маршрут.",
                "Android will request system permission for route diagnostics. AmneziaWG briefly connects a test tunnel, then restores your previous route.",
            ),
            13f,
            DeyttUi.MUTED,
        ).apply { setLineSpacing(host.dp(3).toFloat(), 1f) })
        sheet.addView(host.button(localized("Продолжить", "Continue")).apply {
            setOnClickListener {
                dialog.dismiss()
                @Suppress("DEPRECATION")
                host.startActivityForResult(permission, ROUTE_PROBE_PERMISSION_REQUEST)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(50)).apply { topMargin = host.dp(16) })
        sheet.addView(host.text(localized("отмена", "cancel"), 13f, DeyttUi.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, host.dp(13), 0, host.dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                pendingRouteProbeAuthorization = null
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val container = FrameLayout(host).apply {
            setPadding(host.dp(14), 0, host.dp(14), host.dp(20))
            addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener {
                pendingRouteProbeAuthorization = null
                dialog.dismiss()
            }
        }
        sheet.setOnClickListener { /* prevent dismiss */ }
        dialog.setContentView(container)
        dialog.setOnCancelListener { pendingRouteProbeAuthorization = null }
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            DeyttUi.blurBehind(this, host.dp(24))
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun requestRouteProbePermission(afterGrant: () -> Unit): Boolean {
        val permission = VpnService.prepare(host) ?: return false
        pendingRouteProbeAuthorization = afterGrant
        showRouteProbePermissionSheet(permission)
        return true
    }

    @Suppress("DEPRECATION")
    private fun isTunnelActive(context: Context): Boolean {
        if (isSystemTunnelActive != null) return isSystemTunnelActive.invoke()
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun waitForEnginesStopped(
        waitForDiagnostics: Boolean,
        onFailure: () -> Unit,
        action: () -> Unit,
        attempt: Int = 0,
    ) {
        if (awaitEnginesStopped != null) {
            awaitEnginesStopped.invoke(waitForDiagnostics, onFailure, action)
            return
        }
        val app = host.applicationContext
        val handler = Handler(Looper.getMainLooper())
        if (host.isFinishing || host.isDestroyed) {
            onFailure()
            return
        }
        if (!ConnectVpnService.isRunning() &&
            !AwgTunnelController.isRunning() &&
            !AwgTunnelController.isStopping() &&
            (!waitForDiagnostics || (!RouteProbeClient.isRunning(app) && !isTunnelActive(app)))
        ) {
            action()
            return
        }
        if (attempt >= if (waitForDiagnostics) 300 else 100) {
            onFailure()
            return
        }
        handler.postDelayed({
            waitForEnginesStopped(waitForDiagnostics, onFailure, action, attempt + 1)
        }, 30L)
    }
}
