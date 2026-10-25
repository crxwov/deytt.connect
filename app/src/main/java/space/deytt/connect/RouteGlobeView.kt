package space.deytt.connect

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
    private var egressCountryCode: String? = null
    private var activeAutoRouteKey: String? = null
    private var pageLoaded = false
    private var backdropStageActive = false
    private var backdropProgress = 0f
    private var lastBackdropProgressSentAt = 0L
    private var overviewModeActive = false
    private var connected = false
    private var gestureStartedInsideGlobe = false
    private var reducedMotionActive = false
    private var availableLocations = setOf("nl", "de", "fi", "ru")
    private var trafficActive = false
    private val atlas: WebView
    private var viewportAnchor: Rect? = null
    var onMapNodeTapped: ((String) -> Unit)? = null

    private val nodeTapBridge = object {
        @JavascriptInterface
        fun onNodeTap(code: String) {
            val node = code.lowercase()
            if (node !in setOf("nl", "de", "fi", "ru", "it", "user")) return
            post { onMapNodeTapped?.invoke(node) }
        }

    }

    init {
        if (BuildConfig.DEBUG && context.packageName.endsWith(".qa")) WebView.setWebContentsDebuggingEnabled(true)
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
                    applyViewportAnchor()
                    applyReducedMotion()
                    applyOverviewMode()
                    applyMapLanguage()
                    applyAvailableLocations()
                    applySelectedRoute()
                    applyUserLocation()
                    applyEgressCountry()
                    applyActiveAutoRoute()
                    applyConnected()
                    applyTrafficActive()
                    applyBackdropStage()
                    applyBackdropProgress()
                }
            }
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    gestureStartedInsideGlobe = !backdropStageActive && isTouchInsideViewport(
                        event.x, event.y, view.width, view.height,
                    )
                }
                val allowGlobeGesture = gestureStartedInsideGlobe && !backdropStageActive
                parentInterceptPolicy(event.actionMasked, allowGlobeGesture)?.let { disallow ->
                    view.parent?.requestDisallowInterceptTouchEvent(disallow)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    gestureStartedInsideGlobe = false
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

    internal fun setViewportAnchor(left: Int, top: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val next = Rect(left, top, left + width, top + height)
        if (viewportAnchor == next) return
        viewportAnchor = next
        applyViewportAnchor()
    }

    private fun applyViewportAnchor() {
        val anchor = viewportAnchor ?: return
        if (!pageLoaded) return
        val density = resources.displayMetrics.density
        atlas.evaluateJavascript(
            "window.deyttSetMapViewportAnchor && window.deyttSetMapViewportAnchor(" +
                "${anchor.left / density},${anchor.top / density},${anchor.width() / density},${anchor.height() / density})",
            null,
        )
    }

    internal fun setAvailableLocations(locations: Set<String>) {
        val allowed = setOf("nl", "de", "fi", "ru", "it")
        availableLocations = locations.map(String::lowercase).filter(allowed::contains).toSet()
        if (pageLoaded) applyAvailableLocations()
    }

    internal fun setUserLocation(location: IpNetworkLocation?) {
        networkLocation = location
        atlas.contentDescription = mapDescription(selectedRoute)
        applyUserLocation()
    }

    internal fun setEgressCountry(countryCode: String?) {
        val normalized = countryCode?.uppercase()?.takeIf { connected && it in setOf("NL", "DE", "FI", "RU", "IT") }
        if (egressCountryCode == normalized) return
        egressCountryCode = normalized
        atlas.contentDescription = mapDescription(selectedRoute)
        applyEgressCountry()
    }

    internal fun setActiveAutoRoute(routeKey: String?) {
        val allowed = setOf("nl", "de", "fi", "ru", "it", "ru-de")
        val normalized = routeKey?.lowercase()?.takeIf { connected && it in allowed }
        if (activeAutoRouteKey == normalized) return
        activeAutoRouteKey = normalized
        applyActiveAutoRoute()
    }

    internal fun setConnected(active: Boolean) {
        if (connected == active) return
        connected = active
        if (!active) {
            egressCountryCode = null
            activeAutoRouteKey = null
            atlas.contentDescription = mapDescription(selectedRoute)
            applyEgressCountry()
            applyActiveAutoRoute()
        }
        applyConnected()
    }

    internal fun setTrafficActive(active: Boolean) {
        if (trafficActive == active) return
        trafficActive = active
        applyTrafficActive()
    }

    internal fun setBackdropStage(active: Boolean) {
        if (backdropStageActive == active) return
        backdropStageActive = active
        applyBackdropStage()
    }

    internal fun setBackdropProgress(progress: Float) {
        val next = progress.coerceIn(0f, 1f)
        if (kotlin.math.abs(next - backdropProgress) < .008f) return
        backdropProgress = next
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alpha = 1f - .6f * next
            val radius = resources.displayMetrics.density * 18f * next
            setRenderEffect(if (next <= 0f) null else RenderEffect.createBlurEffect(
                radius, radius, Shader.TileMode.CLAMP,
            ))
        } else if (pageLoaded) {
            val now = SystemClock.uptimeMillis()
            if (next == 0f || next == 1f || now - lastBackdropProgressSentAt >= 32L) {
                lastBackdropProgressSentAt = now
                applyBackdropProgress()
            }
        }
    }

    internal fun setOverviewMode(active: Boolean) {
        if (overviewModeActive == active) return
        overviewModeActive = active
        applyOverviewMode()
    }

    internal fun setReducedMotion(enabled: Boolean) {
        if (reducedMotionActive == enabled) return
        reducedMotionActive = enabled
        applyReducedMotion()
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

    private fun applyEgressCountry() {
        if (!pageLoaded) return
        val country = egressCountryCode?.let(JSONObject::quote) ?: "null"
        atlas.evaluateJavascript(
            "window.deyttSetMapEgressCountry && window.deyttSetMapEgressCountry($country)",
            null,
        )
    }

    private fun applyActiveAutoRoute() {
        if (!pageLoaded) return
        val route = activeAutoRouteKey?.let(JSONObject::quote) ?: "null"
        atlas.evaluateJavascript(
            "window.deyttSetMapActiveAutoRoute && window.deyttSetMapActiveAutoRoute($route)",
            null,
        )
    }

    private fun applyConnected() {
        if (!pageLoaded) return
        atlas.evaluateJavascript(
            "window.deyttSetMapConnected && window.deyttSetMapConnected($connected)",
            null,
        )
    }

    private fun applyTrafficActive() {
        if (!pageLoaded) return
        atlas.evaluateJavascript(
            "window.deyttSetMapTrafficActive && window.deyttSetMapTrafficActive($trafficActive)",
            null,
        )
    }

    private fun applyBackdropStage() {
        if (!pageLoaded) return
        atlas.evaluateJavascript(
            "window.deyttSetMapBackdropStage && window.deyttSetMapBackdropStage($backdropStageActive)",
            null,
        )
    }

    private fun applyBackdropProgress() {
        if (!pageLoaded || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        atlas.evaluateJavascript(
            "window.deyttSetMapBackdropProgress && window.deyttSetMapBackdropProgress($backdropProgress)",
            null,
        )
    }

    private fun applyReducedMotion() {
        if (!pageLoaded) return
        atlas.evaluateJavascript(
            "window.deyttSetMapReducedMotion && window.deyttSetMapReducedMotion($reducedMotionActive)",
            null,
        )
    }

    private fun applyOverviewMode() {
        if (!pageLoaded) return
        atlas.evaluateJavascript(
            "window.deyttSetMapOverviewMode && window.deyttSetMapOverviewMode($overviewModeActive)",
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
            val egress = if (route == "auto" && egressCountryCode != null) " Approximate active exit: $egressCountryCode." else ""
            return "DEYTT network map. $destination.$origin$egress Tap a point to choose an exit and protocol. Drag to rotate; pinch to zoom. Swipe horizontally to switch tabs."
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
        val egress = if (route == "auto" && egressCountryCode != null) " Примерно по IP выбран выход в ${countryName(egressCountryCode!!)}." else ""
        return "Карта сети DEYTT; $destination.$origin$egress Нажмите точку, чтобы выбрать выход и протокол. Поворот и масштаб — жестами двумя пальцами. Горизонтальный свайп переключает вкладку."
    }

    private fun countryName(code: String): String = when (code) {
        "NL" -> "Нидерландах"
        "DE" -> "Германии"
        "FI" -> "Финляндии"
        "RU" -> "России"
        else -> code
    }

    private fun localResponse(uri: Uri): WebResourceResponse {
        if (uri.scheme != "https" || uri.host != ASSET_HOST) return blockedResponse()
        val assetPath = when (uri.path) {
            "/map/index.html" -> "route-map/index.html"
            "/map/atlas-init.js" -> "route-map/atlas-init.js"
            "/map/network-atlas.js" -> "route-map/network-atlas.js"
            "/map/network-atlas.css" -> "route-map/network-atlas.css"
            "/map/world-land.json" -> "world-land.json"
            "/map/fonts/InterTight-Regular.ttf" -> "route-map/fonts/InterTight-Regular.ttf"
            "/map/fonts/InterTight-SemiBold.ttf" -> "route-map/fonts/InterTight-SemiBold.ttf"
            "/map/fonts/InterTight-Bold.ttf" -> "route-map/fonts/InterTight-Bold.ttf"
            else -> return blockedResponse()
        }
        val assetName = assetPath.substringAfterLast('/')
        val mimeType = when {
            assetName.endsWith(".html") -> "text/html"
            assetName.endsWith(".js") -> "application/javascript"
            assetName.endsWith(".css") -> "text/css"
            assetName.endsWith(".ttf") -> "font/ttf"
            else -> "application/json"
        }
        return runCatching {
            WebResourceResponse(
                mimeType,
                "UTF-8",
                200,
                "OK",
                mapOf(
                    "Cache-Control" to if (assetName == "world-land.json") "public, max-age=31536000" else "no-store",
                ),
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

        internal fun parentInterceptPolicy(actionMasked: Int, gestureStartedInsideGlobe: Boolean): Boolean? = when {
            actionMasked == MotionEvent.ACTION_DOWN ||
                actionMasked == MotionEvent.ACTION_MOVE ||
                actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                actionMasked == MotionEvent.ACTION_POINTER_UP -> gestureStartedInsideGlobe
            actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL -> false
            else -> null
        }

        internal fun isTouchInsideViewport(
            x: Float,
            y: Float,
            width: Int,
            height: Int,
        ): Boolean {
            return width > 0 && height > 0 && x >= 0f && x < width && y >= 0f && y < height
        }

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
                "IT" in routeTokens -> "it"
                else -> "auto"
            }
        }
    }
}
