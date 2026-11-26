package space.deytt.connect

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout

/** Exercises real Android span/layout behavior without account or VPN data. */
class InlineMetricProgressInstrumentation : Instrumentation() {
    private var captureNormalStartup = false
    override fun onCreate(arguments: Bundle?) {
        captureNormalStartup = arguments?.getString("captureNormalStartup") == "true"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".qa")) { "Isolated QA package required" }
            var checkFailure: Throwable? = null
            runOnMainSync {
                try {
                    val label = TextView(targetContext).apply {
                        textSize = 11f
                        text = "Отклик: — · Скорость: —"
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val progress = InlineMetricProgress(label)
                    fun slots(): Int = (label.text as Spanned).getSpans(0, label.length(), ReplacementSpan::class.java).size
                    progress.setLoading(true)
                    check(slots() == 2) { "Both pending values need their own inline indicator" }
                    val width = (110 * targetContext.resources.displayMetrics.density).toInt()
                    label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    label.layout(0, 0, width, label.measuredHeight)
                    check(label.lineCount > 1) { "Narrow metric row should wrap without moving indicators out of text" }
                    val image = Bitmap.createBitmap(width, label.height, Bitmap.Config.ARGB_8888)
                    label.draw(Canvas(image))
                    image.recycle()
                    label.text = "Отклик: 45 мс · Скорость: —"
                    check(slots() == 1) { "A measured response must immediately release its loading slot" }
                    label.append(" · скорость в очереди")
                    check(slots() == 1) { "Appending a status must retain exactly one speed indicator" }
                    progress.setLoading(false)
                    check(slots() == 0) { "Completion must remove all animation spans" }
                    check(label.text.toString() == "Отклик: 45 мс · Скорость: — · скорость в очереди")
                    progress.setLoading(true)
                    check(slots() == 1)
                    label.text = "Отклик: 45 мс · Скорость: 12 Мбит/с"
                    check(slots() == 0) { "Live values must not keep a detached spinner" }
                    progress.setLoading(false)
                } catch (error: Throwable) { checkFailure = error }
            }
            checkFailure?.let { throw it }
            val originalScale = Settings.Global.getString(targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
            check(originalScale == null || originalScale.matches(Regex("[0-9.]+")))
            fun scale(value: String?) {
                val command = if (value == null) "settings delete global animator_duration_scale"
                    else "settings put global animator_duration_scale $value"
                uiAutomation.executeShellCommand(command).use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
                Thread.sleep(100)
                check(Settings.Global.getString(targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) == value) {
                    "Device must apply the requested animation scale before checking visible frames"
                }
            }
            try {
                scale("1")
                checkVisibleAnimation()
                scale("0")
                checkVisibleAnimation()
            } finally { scale(originalScale) }
            // Legacy startup snapshots target the normal app; span checks stay isolated by default.
            if (captureNormalStartup) captureColdMainStartupFrames()
            result.putString("result", "PASS: inline slots, updates, wrapping, visible frames with animations ON/OFF, modal pause, hidden/visible resume, completion")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkVisibleAnimation() {
        val activity = startActivitySync(Intent(targetContext, SetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var label: TextView
        lateinit var progress: InlineMetricProgress
        try {
            runOnMainSync {
                label = with(DeyttUi) {
                    activity.text("Response: — · Speed: —", 11f, android.graphics.Color.WHITE)
                }.apply {
                    maxLines = 2
                }
                activity.setContentView(FrameLayout(activity).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setPadding(24, 180, 24, 24)
                    addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120))
                })
                progress = InlineMetricProgress(label)
                // Route callbacks replace the text after the watcher has been registered.
                label.text = "Response: — · Speed: —"
                check(label.text is android.text.Editable) { "Exercise the same late text-update layout as route callbacks" }
                progress.setLoading(true)
            }
            waitForIdleSync()
            Thread.sleep(2_000)
            val position = IntArray(2)
            runOnMainSync { label.getLocationOnScreen(position) }
            fun capture(): Bitmap {
                val screen = checkNotNull(uiAutomation.takeScreenshot())
                val crop = Bitmap.createBitmap(screen, position[0], position[1], label.width, label.height)
                if (crop !== screen) screen.recycle()
                return crop
            }
            val first = capture()
            var changed = false
            repeat(4) {
                Thread.sleep(110)
                val next = capture()
                changed = changed || !first.sameAs(next)
                next.recycle()
            }
            first.recycle()
            check(changed) { "Attached loading indicators must change pixels on a hardware-rendered window" }
            runOnMainSync { progress.setPaused(true) }
            Thread.sleep(100)
            val paused = capture()
            Thread.sleep(180)
            val stillPaused = capture()
            check(paused.sameAs(stillPaused)) { "Modal panels must pause the covered loading animation" }
            paused.recycle()
            stillPaused.recycle()
            runOnMainSync { progress.setPaused(false); label.visibility = View.GONE }
            Thread.sleep(100)
            var queuedWhileHidden = true
            runOnMainSync {
                queuedWhileHidden = InlineMetricProgress::class.java.getDeclaredField("framePosted").apply { isAccessible = true }
                    .getBoolean(progress)
                label.visibility = View.VISIBLE
            }
            check(!queuedWhileHidden) { "Hidden metric rows must not keep posting frame callbacks" }
            Thread.sleep(150)
            val resumed = capture()
            Thread.sleep(110)
            val nextResumed = capture()
            check(!resumed.sameAs(nextResumed)) { "Visible loading animation must resume after a collapsed parent reopens" }
            resumed.recycle()
            nextResumed.recycle()
            runOnMainSync { progress.setLoading(false) }
            waitForIdleSync()
            Thread.sleep(100)
            val stopped = capture()
            Thread.sleep(180)
            val later = capture()
            check(stopped.sameAs(later)) { "Completed indicators must stop redrawing" }
            stopped.recycle()
            later.recycle()
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun captureColdMainStartupFrames() {
        fun shell(command: String) {
            uiAutomation.executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        val mediaRoot = targetContext.externalMediaDirs.firstOrNull()
            ?: error("Instrumentation media output is unavailable")
        val output = java.io.File(mediaRoot, "additional_test_output/main-startup").apply {
            check(mkdirs() || isDirectory)
        }
        shell("am force-stop space.deytt.connect")
        val startedAt = SystemClock.uptimeMillis()
        shell("am start -n space.deytt.connect/.MainActivity")
        listOf(150L, 350L, 600L, 850L, 1_100L, 1_350L, 1_600L, 1_900L, 2_300L, 2_800L, 3_400L).forEach { target ->
            val remaining = startedAt + target - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining)
            val screenshot = checkNotNull(uiAutomation.takeScreenshot())
            java.io.File(output, "frame-$target.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
        }
    }
}
