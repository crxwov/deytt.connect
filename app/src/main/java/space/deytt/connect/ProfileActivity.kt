package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text
import space.deytt.connect.DeyttUi.note

class ProfileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metadata = SubscriptionMetadataStore(this).read()
        val root = screen(withBackdrop = true)
        root.addView(header("подписка", metadata.title, true))
        root.addView(spacer(10, this))
        root.addView(note("Данные обновляются вместе со ссылкой. Сам токен не показываем на экране."))
        root.addView(spacer(22, this))
        root.addView(sectionLabel("статус тарифа"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(15), dp(14), dp(15))
            background = rounded(DeyttUi.SURFACE, 15f, DeyttUi.SURFACE)
            addView(metric("использовано", formatBytes(metadata.usedBytes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(metric("лимит", if (metadata.totalBytes > 0) formatBytes(metadata.totalBytes) else "без ограничений"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(metric("до", metadata.expiresAtSeconds?.let { formatDate(it) } ?: "без срока"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        root.addView(spacer(28, this))
        root.addView(button("Обновить подписку").apply {
            setOnClickListener { startActivity(Intent(this@ProfileActivity, SetupActivity::class.java)) }
        })
        root.addView(spacer(18, this))
        root.addView(text("Профиль и токен хранятся только на этом устройстве.", 13f, DeyttUi.MUTED))
        present(root)
    }

    private fun metric(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(value, 15f, DeyttUi.TEXT, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(text(label, 11f, DeyttUi.MUTED).apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun formatBytes(value: Long): String {
        if (value <= 0) return "0 Б"
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return if (unit == 0) "${amount.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", amount, units[unit])
    }

    private fun formatDate(seconds: Long): String = DateFormat.getDateInstance(DateFormat.LONG, Locale("ru"))
        .format(Date(seconds * 1000))
}
