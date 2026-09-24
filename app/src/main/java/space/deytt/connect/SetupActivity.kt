package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import java.util.concurrent.Executors
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.actionLabel
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.note
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.sectionLabel
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
        root.addView(header("добавить источник", if (updating) "Обновить подписку" else "Подключить подписку", updating))
        root.addView(spacer(8, this))
        root.addView(note("Добавьте ссылку DEYTTT, чтобы загрузить доступные направления. Она хранится только на этом устройстве.", DeyttUi.MUTED))
        root.addView(spacer(19, this))
        root.addView(sectionLabel("ссылка на подписку"))
        val incomingUrl = intent.getStringExtra(EXTRA_SUBSCRIPTION_URL)
        input = EditText(this).apply {
            hint = "https://deytt.space/sub/token/…"
            setHintTextColor(DeyttUi.MUTED)
            setTextColor(DeyttUi.TEXT)
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            contentDescription = "Ссылка на подписку, скрытая"
            minHeight = dp(56)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(DeyttUi.SURFACE_2, 12f, DeyttUi.LINE)
            setText(incomingUrl ?: getSharedPreferences("profile_settings", MODE_PRIVATE).getString("subscription_url", ""))
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val revealLink = actionLabel("показать").apply {
            contentDescription = "Показать или скрыть ссылку на подписку"
            setOnClickListener {
                val cursor = input.selectionStart.coerceAtLeast(0)
                val hidden = input.transformationMethod is PasswordTransformationMethod
                input.transformationMethod = if (hidden) null else PasswordTransformationMethod.getInstance()
                contentDescription = if (hidden) "Скрыть ссылку на подписку" else "Показать ссылку на подписку"
                text = if (hidden) "скрыть" else "показать"
                input.setSelection(cursor.coerceAtMost(input.length()))
            }
        }
        root.addView(revealLink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
        })
        root.addView(spacer(14, this))
        importButton = button(if (updating) "Обновить подписку" else "Добавить подписку").apply { setOnClickListener { importProfile() } }
        state = note("", DeyttUi.MUTED).apply {
            visibility = View.GONE
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        root.addView(state)
        present(root, importButton)
    }

    private fun importProfile() {
        val raw = input.text.toString().trim()
        if (raw.isBlank()) {
            state.visibility = View.VISIBLE
            state.setTextColor(DeyttUi.CORAL)
            state.text = "Вставьте ссылку на подписку"
            return
        }
        input.isEnabled = false
        importButton.isEnabled = false
        importButton.alpha = .65f
        state.visibility = View.VISIBLE
        state.setTextColor(DeyttUi.BLUE)
        state.text = "Получаем маршруты…"
        state.announceForAccessibility(state.text)
        executor.execute {
            runCatching { SubscriptionClient.import(this, raw) }
                .onSuccess { imported -> runOnUiThread {
                    getSharedPreferences("profile_settings", MODE_PRIVATE).edit {
                        putString("subscription_url", imported.url)
                        if (imported.warnings.isEmpty()) remove("subscription_warning")
                        else putString("subscription_warning", imported.warnings.joinToString("\n"))
                    }
                    val config = SubscriptionStore(this).readCurrent().orEmpty()
                    val routes = RouteCatalog.from(config, AwgProfileStore(this@SetupActivity).profiles())
                    routes.firstOrNull()?.let { SelectedRouteStore(this).save(it) }
                    stopService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
                    AwgTunnelController.stop(this, publishStatus = false)
                    state.setTextColor(if (imported.warnings.isEmpty()) DeyttUi.MINT else DeyttUi.AMBER)
                    state.text = buildString {
                        append(getString(R.string.import_complete, routes.size))
                        if (imported.warnings.isNotEmpty()) {
                            append("\n\n")
                            append(imported.warnings.joinToString("\n"))
                        }
                    }
                    state.announceForAccessibility(state.text)
                    getSharedPreferences(ConnectVpnService.STATE_PREFS, MODE_PRIVATE).edit {
                        putString(ConnectVpnService.STATE_STATUS, VpnStateStore.IDLE_TITLE)
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
                    state.text = SubscriptionErrorText.userMessage(error)
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

    companion object {
        const val EXTRA_SUBSCRIPTION_URL = "subscription_url"
    }
}
