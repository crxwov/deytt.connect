package space.deytt.connect

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.edit
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.button

internal class NetworkLocationCoordinator(
    private val host: MainActivity,
    private val isHomeApplicationRoutedByTunnel: (String) -> Boolean,
    private val onPrimaryPageMotionChanged: () -> Unit,
    private val isGlobeInitialized: () -> Boolean = { false },
    private val getGlobe: () -> RouteGlobeView? = { null },
    private val getCurrentAutoRouteKey: () -> String? = { null },
) {
    companion object {
        private const val PROFILE_SETTINGS = "profile_settings"
        private const val KEY_NETWORK_LOCATION_ENABLED = "network_location_enabled"
    }

    var currentNetworkLocation: IpNetworkLocation? = OriginLocationMemory.location
        private set
    var currentEgressLocation: IpNetworkLocation? = null
        private set
    var locationRequestInFlight = false
        private set
    var locationLookupFailed = false
        private set
    private var locationRequestGeneration = 0
    var egressRequestInFlight = false
        private set
    var egressLookupFailed = false
        private set
    private var egressRequestGeneration = 0

    private val locationExecutor = Executors.newSingleThreadExecutor()

    fun isNetworkLocationEnabled(): Boolean =
        host.getSharedPreferences(PROFILE_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NETWORK_LOCATION_ENABLED, false)

    fun isReducedMotionEnabled(): Boolean =
        host.getSharedPreferences(PROFILE_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("reduced_motion", false)

    fun setNetworkLocationEnabled(enabled: Boolean) {
        host.getSharedPreferences(PROFILE_SETTINGS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_NETWORK_LOCATION_ENABLED, enabled)
        }
        if (!enabled) {
            locationRequestGeneration++
            locationRequestInFlight = false
            locationLookupFailed = false
            egressRequestGeneration++
            egressRequestInFlight = false
            egressLookupFailed = false
            currentNetworkLocation = null
            currentEgressLocation = null
            IpNetworkLocationStore(host).clear()
            OriginLocationMemory.location = null
            if (isGlobeInitialized()) getGlobe()?.setUserLocation(null)
        }
        updateNetworkLocationViews()
        if (enabled) refreshNetworkLocation(force = true)
    }

    fun setReducedMotionEnabled(enabled: Boolean) {
        host.getSharedPreferences(PROFILE_SETTINGS, Context.MODE_PRIVATE).edit {
            putBoolean("reduced_motion", enabled)
        }
        onPrimaryPageMotionChanged()
    }

    fun updateNetworkLocationViews() {
        val enabled = isNetworkLocationEnabled()
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (isGlobeInitialized()) {
            val globe = getGlobe() ?: return
            globe.setConnected(tunnelActive)
            globe.setEgressCountry(if (enabled && tunnelActive) currentEgressLocation?.countryCode else null)
            val isAutoRoute = SelectedRouteStore(host).read().id == "auto"
            val currentAutoRouteKey = getCurrentAutoRouteKey()
            if (!tunnelActive || !isAutoRoute || currentAutoRouteKey != null) {
                globe.setActiveAutoRoute(currentAutoRouteKey.takeIf { tunnelActive && isAutoRoute })
            }
        }
    }

    fun showNetworkLocationConsentIfNeeded() {
        val preferences = host.getSharedPreferences(PROFILE_SETTINGS, Context.MODE_PRIVATE)
        if (preferences.contains(KEY_NETWORK_LOCATION_ENABLED) || host.isFinishing || host.isDestroyed) return

        val dialog = Dialog(host)
        val sheet = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(22), host.dp(20), host.dp(22), host.dp(18))
            background = host.rounded(DeyttUi.SHEET_SURFACE, 24f)
        }
        sheet.addView(host.mono("КАРТА · ПРИВАТНОСТЬ", 9f, DeyttUi.MUTED, 700))
        sheet.addView(host.text("Показывать ваш примерный регион?", 20f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
            setPadding(0, host.dp(10), 0, host.dp(8))
        })
        sheet.addView(host.text(
            "Точка определяется по публичному IP через ipinfo.io — это не GPS и на выбор маршрута не влияет. Запрос видит внешний сервис; приложение не сохраняет адрес или координаты, а держит точку только в памяти до закрытия.",
            13f,
            DeyttUi.MUTED,
        ).apply { setLineSpacing(host.dp(3).toFloat(), 1f) })
        sheet.addView(host.button("Показывать на карте").apply {
            setOnClickListener {
                dialog.dismiss()
                setNetworkLocationEnabled(true)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, host.dp(52)).apply { topMargin = host.dp(18) })
        sheet.addView(host.text("не сейчас", 13f, DeyttUi.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, host.dp(14), 0, host.dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                setNetworkLocationEnabled(false)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val container = FrameLayout(host).apply {
            setPadding(host.dp(14), 0, host.dp(14), host.dp(20))
            addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener {
                setNetworkLocationEnabled(false)
                dialog.dismiss()
            }
        }
        sheet.setOnClickListener { /* prevent dismiss */ }
        dialog.setContentView(container)
        dialog.setOnCancelListener { setNetworkLocationEnabled(false) }
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            DeyttUi.blurBehind(this, host.dp(24))
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun availableMapLocations(): Set<String> {
        val config = SubscriptionStore(host).readCurrent().orEmpty()
        val routes = runCatching {
            RouteCatalog.from(config, AwgProfileStore(host).profiles(), OpenFluxProfileStore(host).profiles(host))
        }.getOrDefault(emptyList())
        return routes.flatMap { route ->
            when (route.countryCode.uppercase()) {
                "NL" -> listOf("nl")
                "DE" -> listOf("de")
                "FI" -> listOf("fi")
                "RU" -> listOf("ru")
                "IT" -> listOf("it")
                else -> emptyList()
            }
        }.toSet()
    }

    fun refreshNetworkLocation(force: Boolean = false) {
        if (!isNetworkLocationEnabled()) {
            updateNetworkLocationViews()
            return
        }
        val tunnelActive = ConnectVpnService.isRunning() || AwgTunnelController.isRunning()
        if (tunnelActive) {
            if (currentNetworkLocation == null) refreshOriginOutsideVpn()
            if (ConnectVpnService.isRunning()) {
                val config = SubscriptionStore(host).readCurrent()
                if (config != null && !isHomeApplicationRoutedByTunnel(config)) {
                    egressLookupFailed = true
                    egressRequestInFlight = false
                    updateNetworkLocationViews()
                    return
                }
            }
            if (currentEgressLocation != null || egressRequestInFlight) {
                updateNetworkLocationViews()
                return
            }
            val generation = ++egressRequestGeneration
            egressRequestInFlight = true
            egressLookupFailed = false
            updateNetworkLocationViews()
            locationExecutor.execute {
                val result = runCatching { IpNetworkLocationClient.fetch() }
                host.runOnUiThread {
                    if (generation != egressRequestGeneration || host.isFinishing || host.isDestroyed) return@runOnUiThread
                    egressRequestInFlight = false
                    if (isNetworkLocationEnabled() &&
                        (ConnectVpnService.isRunning() || AwgTunnelController.isRunning())
                    ) {
                        result.onSuccess { location ->
                            currentEgressLocation = location
                            egressLookupFailed = false
                            android.util.Log.i("DeyttIpLocation", "Approximate tunnel egress region is available in memory")
                        }
                        result.onFailure { failure ->
                            egressLookupFailed = true
                            android.util.Log.w("DeyttIpLocation", "Tunnel egress lookup failed (${failure.javaClass.simpleName})")
                        }
                    }
                    updateNetworkLocationViews()
                }
            }
            updateNetworkLocationViews()
            return
        }
        if (egressRequestInFlight || currentEgressLocation != null) {
            egressRequestGeneration++
            egressRequestInFlight = false
            egressLookupFailed = false
            currentEgressLocation = null
        }
        if (currentNetworkLocation != null && !force) {
            updateNetworkLocationViews()
            return
        }
        if (locationRequestInFlight) {
            updateNetworkLocationViews()
            return
        }

        val generation = ++locationRequestGeneration
        locationRequestInFlight = true
        locationLookupFailed = false
        android.util.Log.i("DeyttIpLocation", "Lookup started")
        updateNetworkLocationViews()
        locationExecutor.execute {
            val result = runCatching { IpNetworkLocationClient.fetch() }
            host.runOnUiThread {
                if (generation != locationRequestGeneration || host.isFinishing || host.isDestroyed) return@runOnUiThread
                locationRequestInFlight = false
                if (isNetworkLocationEnabled() && !ConnectVpnService.isRunning() && !AwgTunnelController.isRunning()) {
                    result.onSuccess { location ->
                        locationLookupFailed = false
                        currentNetworkLocation = location
                        OriginLocationMemory.location = location
                        android.util.Log.i("DeyttIpLocation", "Approximate location is available in memory")
                        if (isGlobeInitialized()) getGlobe()?.setUserLocation(location)
                    }
                    result.onFailure { failure ->
                        locationLookupFailed = true
                        android.util.Log.w("DeyttIpLocation", "Lookup failed (${failure.javaClass.simpleName})")
                    }
                }
                updateNetworkLocationViews()
            }
        }
    }

    fun refreshOriginOutsideVpn() {
        if (locationRequestInFlight || !isNetworkLocationEnabled()) return
        val manager = host.getSystemService(ConnectivityManager::class.java) ?: return
        val network = manager.allNetworks.firstOrNull { candidate ->
            val capabilities = manager.getNetworkCapabilities(candidate)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return
        val generation = ++locationRequestGeneration
        locationRequestInFlight = true
        locationExecutor.execute {
            val result = runCatching { IpNetworkLocationClient.fetch(network) }
            host.runOnUiThread {
                if (host.isDestroyed || host.isFinishing || generation != locationRequestGeneration) return@runOnUiThread
                locationRequestInFlight = false
                if (isNetworkLocationEnabled()) {
                    result.onSuccess {
                        currentNetworkLocation = it
                        OriginLocationMemory.location = it
                        if (isGlobeInitialized()) getGlobe()?.setUserLocation(it)
                    }
                    locationLookupFailed = result.isFailure
                }
                updateNetworkLocationViews()
            }
        }
    }

    fun close() {
        locationExecutor.shutdownNow()
    }
}
