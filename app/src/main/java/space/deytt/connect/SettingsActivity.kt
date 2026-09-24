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
        root.addView(header("deytt. connect · ${BuildConfig.VERSION_NAME}", "Настройки"))
        root.addView(spacer(17, this))
        root.addView(sectionLabel("обновление приложения"))
        updateButton = button("Проверить обновления").apply {
            setOnClickListener { if (latest == null) checkForUpdate() else openLatest() }
        }
        status = text("Готово проверить официальный релиз.", 12f, DeyttUi.MUTED).apply { setPadding(dp(2), dp(10), 0, 0) }
        root.addView(status)
        root.addView(spacer(20, this))
        root.addView(sectionLabel("приватность"))
        root.addView(note("Ссылка и ключи хранятся локально. Принимаются только ссылки DEYTTT.\nГеография Natural Earth 1:110m работает офлайн.", DeyttUi.TEXT))
        root.addView(spacer(20, this))
        root.addView(sectionLabel("дальше"))
        root.addView(text("Раздельные маршруты и геонастройки появятся после готового контракта и тестирования.", 13f, DeyttUi.MUTED).apply {
            setPadding(dp(1), 0, dp(2), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        present(root, updateButton)
    }

    private fun checkForUpdate() {
        updateButton.isEnabled = false
        updateButton.alpha = .65f
        status.text = "Проверяем официальный релиз…"
        status.setTextColor(DeyttUi.SKY)
        executor.execute {
            runCatching { UpdateChecker.latest() }
                .onSuccess { release -> runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.alpha = 1f
                    if (!ReleaseVersion.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                        latest = null
                        status.setTextColor(DeyttUi.MINT)
                        status.text = "Установлена последняя версия."
                    } else {
                        latest = release
                        status.setTextColor(DeyttUi.MINT)
                        status.text = "Доступна ${release.tag}. Откройте официальный релиз и проверьте APK перед установкой."
                        updateButton.text = "Открыть официальный релиз"
                    }
                }}
                .onFailure { error -> runOnUiThread {
                    latest = null
                    updateButton.isEnabled = true
                    updateButton.alpha = 1f
                    updateButton.text = "Проверить обновления"
                    status.setTextColor(DeyttUi.CORAL)
                    status.text = "Не удалось проверить официальный релиз. Повторите попытку позже."
                }}
        }
    }

    private fun openLatest() {
        latest?.pageUrl?.takeIf(ReleaseUrlPolicy::isOfficialPage)?.let {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
        } ?: run {
            status.text = "Ссылка на релиз не прошла проверку."
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
