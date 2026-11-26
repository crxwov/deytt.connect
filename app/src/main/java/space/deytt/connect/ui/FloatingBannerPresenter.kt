package space.deytt.connect

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.typeface

internal class FloatingBannerPresenter(
    private val host: MainActivity,
    private val globeStageProvider: () -> FrameLayout? = { null },
    private val statusInsetProvider: () -> View? = { null },
) {
    private var activeFloatingBanner: View? = null
    private var lastFloatingNotificationTs = 0L
    private val floatingBannerHandler = Handler(Looper.getMainLooper())
    private var floatingBannerDismissRunnable: Runnable? = null

    private fun dp(value: Int): Int = host.dp(value)
    private fun uiCopy(value: String): String = host.uiCopy(value)

    fun showFloatingBanner(title: String, subtitle: String? = null, isSuccess: Boolean = true) {
        if (host.isFinishing || host.isDestroyed) return
        val globeStage = globeStageProvider() ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFloatingNotificationTs < 800L) return
        lastFloatingNotificationTs = now

        floatingBannerDismissRunnable?.let { floatingBannerHandler.removeCallbacks(it) }
        activeFloatingBanner?.let { existing ->
            existing.animate().cancel()
            (existing.parent as? ViewGroup)?.removeView(existing)
            activeFloatingBanner = null
        }

        val statusInset = statusInsetProvider()
        val statusHeight = (statusInset?.let { (it.layoutParams as? ViewGroup.LayoutParams)?.height })
            ?.takeIf { it > 0 } ?: statusInset?.height ?: 0
        val topInset = if (statusHeight > 0) statusHeight else dp(28)
        val motion = !host.isReducedMotionEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
        val banner = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(6).toFloat()
            background = with(host) { rounded(DeyttUi.SHEET_SURFACE, DeyttUi.PANEL_RADIUS, DeyttUi.LINE) }
            tag = "subscription-update-banner"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(16), dp(10), dp(18), dp(10))
            isClickable = true
            isFocusable = true
        }

        val iconContainer = FrameLayout(host).apply {
            val iconBg = if (isSuccess) 0x2810B981.toInt() else 0x28F43F5E.toInt()
            background = with(host) { rounded(iconBg, 14f) }
            val iv = ImageView(host).apply {
                setImageResource(if (isSuccess) R.drawable.ic_shield_check else R.drawable.ic_sync)
                imageTintList = ColorStateList.valueOf(if (isSuccess) DeyttUi.MINT else DeyttUi.CORAL)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(iv, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }
        banner.addView(iconContainer, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })

        val textGroup = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            val titleTv = TextView(host).apply {
                text = uiCopy(title)
                textSize = 13.5f
                setTextColor(Color.WHITE)
                typeface = with(host) { typeface(DeyttUi.FontFamily.INTER_TIGHT, 650) }
            }
            addView(titleTv)
            if (!subtitle.isNullOrBlank()) {
                val subTv = TextView(host).apply {
                    text = uiCopy(subtitle)
                    textSize = 11f
                    setTextColor(DeyttUi.MUTED)
                    typeface = with(host) { typeface(DeyttUi.FontFamily.INTER_TIGHT, 400) }
                    setPadding(0, dp(2), 0, 0)
                }
                addView(subTv)
            }
        }
        banner.addView(textGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = topInset + dp(64) + dp(10)
            leftMargin = dp(16)
            rightMargin = dp(16)
        }

        banner.alpha = 0f
        activeFloatingBanner = banner
        globeStage.addView(banner, params)
        banner.bringToFront()

        var revealed = false
        fun reveal() {
            if (revealed || activeFloatingBanner !== banner) return
            revealed = true
            if (!motion) {
                banner.alpha = 1f
                return
            }
            banner.translationY = -dp(12).toFloat()
            banner.animate().translationY(0f).alpha(1f)
                .setDuration(180L)
                .setInterpolator(PathInterpolator(.2f, .85f, .28f, 1f))
                .start()
        }

        reveal()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                banner.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                banner.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }

        fun removeBanner() {
            (banner.parent as? ViewGroup)?.removeView(banner)
            if (activeFloatingBanner === banner) activeFloatingBanner = null
        }

        fun dismissBanner() {
            revealed = true
            if (!motion) {
                removeBanner()
                return
            }
            banner.animate()
                .translationY(-dp(10).toFloat())
                .alpha(0f)
                .setDuration(170L)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { removeBanner() }
                .start()
        }

        banner.setOnClickListener {
            floatingBannerDismissRunnable?.let { floatingBannerHandler.removeCallbacks(it) }
            dismissBanner()
        }

        val dismissTask = Runnable {
            if (!host.isFinishing && !host.isDestroyed && activeFloatingBanner === banner) {
                dismissBanner()
            }
        }
        floatingBannerDismissRunnable = dismissTask
        floatingBannerHandler.postDelayed(dismissTask, 4500L)
    }

    fun close() {
        floatingBannerDismissRunnable?.let { floatingBannerHandler.removeCallbacks(it) }
        floatingBannerDismissRunnable = null
        floatingBannerHandler.removeCallbacksAndMessages(null)
        activeFloatingBanner?.let { banner ->
            banner.animate().cancel()
            (banner.parent as? ViewGroup)?.removeView(banner)
            activeFloatingBanner = null
        }
    }
}
