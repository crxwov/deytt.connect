package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class ProfileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metadata = SubscriptionMetadataStore(this).read()
        val root = screen()
        root.addView(header("подписка", metadata.title, true))
        root.addView(spacer(22, this))
        root.addView(row("Использовано", formatBytes(metadata.usedBytes), "↗", "", interactive = false))
        root.addView(spacer(12, this))
        root.addView(row("Лимит", if (metadata.totalBytes > 0) formatBytes(metadata.totalBytes) else "Без ограничений", "∞", "", interactive = false))
        root.addView(spacer(12, this))
        root.addView(row("Действует до", metadata.expiresAtSeconds?.let { formatDate(it) } ?: "Без срока", "◷", "", interactive = false))
        root.addView(spacer(28, this))
        root.addView(button("ОБНОВИТЬ ПОДПИСКУ").apply {
            setOnClickListener { startActivity(Intent(this@ProfileActivity, SetupActivity::class.java)) }
        })
        root.addView(spacer(18, this))
        root.addView(text("Профиль и токен хранятся только на этом устройстве.", 13f, DeyttUi.MUTED))
        present(root)
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
