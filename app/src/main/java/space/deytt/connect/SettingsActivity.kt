package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class SettingsActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var updateButton: TextView
    private var latest: ReleaseInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = screen()
        root.addView(header("настройки", "deytt./connect", true))
        root.addView(spacer(10, this))
        root.addView(note("Версия ${BuildConfig.VERSION_NAME}\nОбновления загружаются только из официального репозитория."))
        root.addView(spacer(24, this))
        root.addView(sectionLabel("обновления"))
        updateButton = button("ПРОВЕРИТЬ ОБНОВЛЕНИЯ").apply {
            setOnClickListener { if (latest == null) checkForUpdate() else openLatest() }
        }
        root.addView(updateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = text("Готово проверить официальный релиз.", 13f, DeyttUi.MUTED).apply { setPadding(0, dp(14), 0, 0) }
        root.addView(status)
        root.addView(spacer(26, this))
        root.addView(sectionLabel("приватность"))
        root.addView(note("Ссылка на подписку и ключи хранятся только локально. Приложение принимает только ссылки DEYTTT."))
        present(root)
    }

    private fun checkForUpdate() {
        updateButton.isEnabled = false
        updateButton.alpha = .65f
        status.text = "Проверяем официальный релиз…"
        executor.execute {
            runCatching { UpdateChecker.latest() }
                .onSuccess { release -> runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.alpha = 1f
                    if (!ReleaseVersion.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                        latest = null
                        status.text = "Установлена последняя версия."
                    } else {
                        latest = release
                        status.text = "Доступна ${release.tag}. Откройте страницу релиза и скачайте APK."
                        updateButton.text = "ОТКРЫТЬ РЕЛИЗ"
                    }
                }}
                .onFailure { error -> runOnUiThread {
                    latest = null
                    updateButton.isEnabled = true
                    updateButton.alpha = 1f
                    updateButton.text = "ПРОВЕРИТЬ ОБНОВЛЕНИЯ"
                    status.text = "Не удалось проверить обновление: ${error.message ?: "нет соединения"}"
                }}
        }
    }

    private fun openLatest() {
        latest?.pageUrl?.takeIf(String::isNotBlank)?.let {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
