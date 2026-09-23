package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class SetupActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var input: EditText
    private lateinit var state: TextView
    private lateinit var importButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val updating = SubscriptionStore(this).readCurrent() != null
        val root = screen()
        root.addView(header("шаг 1 из 1", if (updating) "Обновить подписку" else "Добавить подписку", updating))
        root.addView(spacer(24, this))
        root.addView(text("Одна ссылка добавит доступные страны и все протоколы. Токен хранится только на устройстве.", 16f, DeyttUi.MUTED))
        root.addView(spacer(28, this))
        root.addView(text("Ссылка на подписку", 13f, DeyttUi.MUTED, android.graphics.Typeface.BOLD))
        root.addView(spacer(8, this))
        input = EditText(this).apply {
            hint = "https://deytt.space/sub/token/…"
            setHintTextColor(DeyttUi.MUTED)
            setTextColor(DeyttUi.TEXT)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            contentDescription = "Ссылка на подписку"
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(DeyttUi.SURFACE, 16f, DeyttUi.LINE)
            setText(getSharedPreferences("profile_settings", MODE_PRIVATE).getString("subscription_url", ""))
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(spacer(14, this))
        importButton = button(if (updating) "ОБНОВИТЬ" else "ДОБАВИТЬ").apply { setOnClickListener { importProfile() } }
        root.addView(importButton)
        state = text("", 15f, DeyttUi.MUTED).apply { setPadding(0, dp(22), 0, 0) }
        root.addView(state)
        present(root)
    }

    private fun importProfile() {
        val raw = input.text.toString().trim()
        if (raw.isBlank()) { state.text = "Вставьте ссылку на подписку"; return }
        input.isEnabled = false
        importButton.isEnabled = false
        importButton.alpha = .65f
        state.setTextColor(DeyttUi.BLUE)
        state.text = "↻  Получаем маршруты…"
        state.announceForAccessibility(state.text)
        executor.execute {
            runCatching { SubscriptionClient.import(this, raw) }
                .onSuccess { imported -> runOnUiThread {
                    getSharedPreferences("profile_settings", MODE_PRIVATE).edit { putString("subscription_url", raw) }
                    val config = SubscriptionStore(this).readCurrent().orEmpty()
                    val routes = RouteCatalog.from(config, imported.awg15Available, imported.awg31Available)
                    routes.firstOrNull()?.let { SelectedRouteStore(this).save(it) }
                    stopService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
                    AwgTunnelController.stop(this, publishStatus = false)
                    state.setTextColor(DeyttUi.MINT)
                    state.text = getString(R.string.import_complete, routes.size)
                    state.announceForAccessibility(state.text)
                    getSharedPreferences(ConnectVpnService.STATE_PREFS, MODE_PRIVATE).edit {
                        putString(ConnectVpnService.STATE_STATUS, "VPN отключён")
                        remove(ConnectVpnService.STATE_ERROR)
                    }
                    state.animate().alpha(1f).setDuration(220).withEndAction {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                        applyFadeTransition()
                    }.start()
                }}
                .onFailure { error -> runOnUiThread {
                    state.setTextColor(DeyttUi.CORAL)
                    state.text = error.message ?: "Не удалось обновить подписку"
                    state.announceForAccessibility(state.text)
                    input.isEnabled = true; importButton.isEnabled = true; importButton.alpha = 1f
                }}
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun applyFadeTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
