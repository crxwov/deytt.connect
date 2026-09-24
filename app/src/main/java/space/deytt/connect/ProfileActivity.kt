package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.mono
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class ProfileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metadata = SubscriptionMetadataStore(this).read()
        val root = screen()
        root.addView(header("аккаунт", "Профиль"))
        root.addView(spacer(10, this))

        root.addView(sectionLabel("подписка"))
        val fraction = if (metadata.totalBytes > 0) {
            (metadata.usedBytes.toDouble() / metadata.totalBytes).toFloat().coerceIn(0f, 1f)
        } else 0f
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(19), dp(20), dp(18))
            background = rounded(DeyttUi.SURFACE, 17f, DeyttUi.LINE)
            addView(text(metadata.title, 14f, DeyttUi.MUTED).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(text(formatBytes(metadata.usedBytes), 36f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply {
                setPadding(0, dp(15), 0, 0)
            })
            addView(text("трафика использовано", 12f, DeyttUi.MUTED).apply { setPadding(0, dp(1), 0, 0) })
            if (metadata.totalBytes > 0) {
                addView(UsageMeterView(this@ProfileActivity, fraction),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(15) })
            }
            addView(View(this@ProfileActivity).apply { setBackgroundColor(DeyttUi.LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(17) })
            addView(LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(15), 0, 0)
                addView(LinearLayout(this@ProfileActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(mono("ЛИМИТ", 9f, DeyttUi.MUTED, 560))
                    addView(text(if (metadata.totalBytes > 0) formatBytes(metadata.totalBytes) else "Без лимита", 14f, DeyttUi.TEXT, android.graphics.Typeface.BOLD)
                        .apply { setPadding(0, dp(5), 0, 0); maxLines = 1 })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(LinearLayout(this@ProfileActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(mono("ДЕЙСТВУЕТ ДО", 9f, DeyttUi.MUTED, 560))
                    addView(text(metadata.expiresAtSeconds?.let { formatDate(it) } ?: "Без срока", 14f, DeyttUi.TEXT, android.graphics.Typeface.BOLD)
                        .apply { setPadding(0, dp(5), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        })
        root.addView(spacer(17, this))
        val updateAction = button("Обновить подписку").apply {
            setOnClickListener { startActivity(Intent(this@ProfileActivity, SetupActivity::class.java)) }
        }
        root.addView(spacer(16, this))
        root.addView(text("Ссылка и ключи остаются на этом устройстве.", 12f, DeyttUi.MUTED))
        present(root, updateAction)
    }

    private fun formatBytes(value: Long): String {
        if (value <= 0) return "0 Б"
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    private fun formatDate(seconds: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale("ru"))
        .format(Date(seconds * 1000))
}

private class UsageMeterView(context: android.content.Context, private val fraction: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val height = height.toFloat()
        val track = RectF(0f, 0f, width.toFloat(), height)
        paint.color = DeyttUi.SURFACE_2
        canvas.drawRoundRect(track, height / 2, height / 2, paint)
        if (fraction > 0f) {
            track.right = (width * fraction).coerceAtLeast(height)
            paint.color = DeyttUi.BLUE
            canvas.drawRoundRect(track, height / 2, height / 2, paint)
        }
    }
}
