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
import android.widget.FrameLayout
import java.io.ByteArrayInputStream

/** Offline Android host for the same interactive network atlas used on deytt.space. */
class RouteGlobeView(context: Context) : FrameLayout(context) {
    private var selectedRoute = "auto"
    private var pageLoaded = false
    private var trafficEnabled = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val atlas: WebView

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
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse =
                    localResponse(request.url)

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                    applySelectedRoute()
                    applyTrafficState()
                }
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartX = event.x
                        touchStartY = event.y
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_MOVE -> {
                        val dx = kotlin.math.abs(event.x - touchStartX)
                        val dy = kotlin.math.abs(event.y - touchStartY)
                        if (dx > touchSlop && dx > dy) {
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (dy > touchSlop) {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
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
        val normalized = code.uppercase().substringAfterLast(':')
        selectedRoute = when {
            normalized in setOf("RU-DE", "RU_DE") -> "ru-de"
            normalized.contains("NL") -> "nl"
            normalized.contains("DE") -> "de"
            normalized.contains("FI") -> "fi"
            normalized.contains("RU") -> "ru"
            else -> "auto"
        }
        applySelectedRoute()
    }

    fun setTrafficEnabled(enabled: Boolean) {
        if (trafficEnabled == enabled) return
        trafficEnabled = enabled
        applyTrafficState()
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

    private fun applyTrafficState() {
        if (!pageLoaded) return
        atlas.evaluateJavascript(
            "window.deyttSetMapTraffic && window.deyttSetMapTraffic($trafficEnabled)",
            null,
        )
    }

    private fun mapDescription(route: String): String {
        val destination = when (route) {
            "nl" -> "выбрана точка Амстердам, Нидерланды"
            "de" -> "выбрана точка Франкфурт, Германия"
            "fi" -> "выбрана точка Хельсинки, Финляндия"
            "ru" -> "выбрана точка Санкт-Петербург, Россия"
            "ru-de" -> "показан двойной маршрут Санкт-Петербург — Франкфурт"
            else -> "показаны точки Амстердам, Франкфурт, Хельсинки и Санкт-Петербург"
        }
        return "Карта сети DEYTT; $destination. Поворот — свайпом, масштаб — жестом двумя пальцами."
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
    }
}
