package space.deytt.connect

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.json.JSONArray

/** Offline Android host for the same interactive network atlas used on deytt.space. */
class RouteGlobeView(context: Context) : FrameLayout(context) {
    private var selectedRoute = "auto"
    private var networkLocation: IpNetworkLocation? = null
    private var pageLoaded = false
    private var trafficEnabled = false
    private var availableLocations = setOf("nl", "de", "fi", "ru")
    private val atlas: WebView
    var onMapNodeTapped: ((String) -> Unit)? = null

    private val nodeTapBridge = object {
        @JavascriptInterface
        fun onNodeTap(code: String) {
            val node = code.lowercase()
            if (node !in setOf("nl", "de", "fi", "ru", "user")) return
            post { onMapNodeTapped?.invoke(node) }
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        atlas = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = mapDescription(selectedRoute)
            settings.apply {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(nodeTapBridge, "DeyttAtlasBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                    request.url.scheme != "https" || request.url.host != ASSET_HOST

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse =
                    localResponse(request.url)

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                    applyMapLanguage()
                    applyAvailableLocations()
                    applySelectedRoute()
                    applyTrafficState()
                    applyUserLocation()
                }
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        addView(atlas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        atlas.loadUrl("https://appassets.androidplatform.net/map/index.html")
    }

    @Suppress("UNUSED_PARAMETER")
    fun focus(code: String, animate: Boolean = true) {
        selectedRoute = routeKeyFor(code)
        applySelectedRoute()
    }

    fun setTrafficEnabled(enabled: Boolean) {
        if (trafficEnabled == enabled) return
        trafficEnabled = enabled
        applyTrafficState()
    }

    internal fun setAvailableLocations(locations: Set<String>) {
        val allowed = setOf("nl", "de", "fi", "ru")
        availableLocations = locations.map(String::lowercase).filter(allowed::contains).toSet()
        if (pageLoaded) applyAvailableLocations()
    }

    internal fun setUserLocation(location: IpNetworkLocation?) {
        networkLocation = location
        atlas.contentDescription = mapDescription(selectedRoute)
        applyUserLocation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        atlas.onResume()
    }

    override fun onDetachedFromWindow() {
        atlas.onPause()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) atlas.onResume() else atlas.onPause()
    }

    private fun applySelectedRoute() {
        val route = selectedRoute
        atlas.contentDescription = mapDescription(route)
        if (!pageLoaded) return
        atlas.evaluateJavascript(
            "window.deyttSetMapRoute && window.deyttSetMapRoute('$route')",
            null,
        )
    }

    private fun applyMapLanguage() {
        if (!pageLoaded) return
        val language = AppLanguage.current(context)
        atlas.evaluateJavascript("window.deyttSetMapLanguage && window.deyttSetMapLanguage('$language')", null)
    }

    private fun applyAvailableLocations() {
        if (!pageLoaded) return
        val payload = JSONArray(availableLocations.toList()).toString()
        atlas.evaluateJavascript("window.deyttSetMapLocations && window.deyttSetMapLocations($payload)", null)
    }

    private fun applyTrafficState() {
        if (!pageLoaded) return
        atlas.evaluateJavascript(
            "window.deyttSetMapTraffic && window.deyttSetMapTraffic($trafficEnabled)",
            null,
        )
    }

    private fun applyUserLocation() {
        if (!pageLoaded) return
        val location = networkLocation
        if (location == null) {
            atlas.evaluateJavascript("window.deyttClearMapUserLocation && window.deyttClearMapUserLocation()", null)
            return
        }
        val details = JSONObject()
            .put("city", AppLanguage.locationLabel(context, location.city.take(80)))
            .put("country", location.countryCode.take(3))
            .toString()
        atlas.evaluateJavascript(
            "window.deyttSetMapUserLocation && window.deyttSetMapUserLocation(${location.latitude},${location.longitude},$details)",
            null,
        )
    }

    private fun mapDescription(route: String): String {
        if (AppLanguage.current(context) == AppLanguage.EN) {
            val destination = when (route) {
                "nl" -> "The selected exit is Amsterdam, Netherlands"
                "de" -> "The selected exit is Frankfurt, Germany"
                "fi" -> "The selected exit is Helsinki, Finland"
                "ru" -> "The selected exit is Saint Petersburg, Russia"
                "ru-de" -> "The selected route is a double hop from Saint Petersburg to Frankfurt"
                else -> "Available exits are Amsterdam, Frankfurt, Helsinki, and Saint Petersburg"
            }
            val origin = networkLocation?.let {
                " Entry point: ${it.placeLabel}, approximate by IP; the IP address is not stored."
            }.orEmpty()
            return "DEYTT network map. $destination.$origin Tap a point to choose an exit and protocol. Drag to rotate; pinch to zoom. Swipe horizontally to switch tabs."
        }
        val destination = when (route) {
            "nl" -> "выбрана точка Амстердам, Нидерланды"
            "de" -> "выбрана точка Франкфурт, Германия"
            "fi" -> "выбрана точка Хельсинки, Финляндия"
            "ru" -> "выбрана точка Санкт-Петербург, Россия"
            "ru-de" -> "показан двойной маршрут Санкт-Петербург — Франкфурт"
            else -> "показаны точки Амстердам, Франкфурт, Хельсинки и Санкт-Петербург"
        }
        val origin = networkLocation?.let { " Точка входа — ${it.placeLabel}, приблизительно по IP; адрес IP не сохраняется." }.orEmpty()
        return "Карта сети DEYTT; $destination.$origin Нажмите точку, чтобы выбрать выход и протокол. Поворот и масштаб — жестами двумя пальцами. Горизонтальный свайп переключает вкладку."
    }

    private fun localResponse(uri: Uri): WebResourceResponse {
        if (uri.scheme != "https" || uri.host != ASSET_HOST) return blockedResponse()
        val assetPath = when (uri.path) {
            "/map/index.html" -> "route-map/index.html"
            "/map/atlas-init.js" -> "route-map/atlas-init.js"
            "/map/network-atlas.js" -> "route-map/network-atlas.js"
            "/map/network-atlas.css" -> "route-map/network-atlas.css"
            "/map/world-land.json" -> "world-land.json"
            else -> return blockedResponse()
        }
        val assetName = assetPath.substringAfterLast('/')
        val mimeType = when {
            assetName.endsWith(".html") -> "text/html"
            assetName.endsWith(".js") -> "application/javascript"
            assetName.endsWith(".css") -> "text/css"
            else -> "application/json"
        }
        return runCatching {
            WebResourceResponse(
                mimeType,
                "UTF-8",
                200,
                "OK",
                mapOf("Cache-Control" to "public, max-age=31536000"),
                context.assets.open(assetPath),
            )
        }.getOrElse { blockedResponse() }
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"

        internal fun routeKeyFor(selectedRouteId: String): String {
            val routeTokens = selectedRouteId.uppercase().split(Regex("[^A-Z]+"))
                .filter(String::isNotBlank)
                .toSet()
            return when {
                routeTokens.containsAll(setOf("RU", "DE")) -> "ru-de"
                "NL" in routeTokens -> "nl"
                "DE" in routeTokens -> "de"
                "FI" in routeTokens -> "fi"
                "RU" in routeTokens -> "ru"
                else -> "auto"
            }
        }
    }
}
