package space.deytt.connect

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val VPN_PERMISSION_REQUEST = 1001
        private const val PREFS = "deytt-connect"
        private const val URL_KEY = "subscription_url"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var importButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildView()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildView() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            setBackgroundColor(Color.rgb(16, 19, 18))
        }

        val title = TextView(this).apply {
            text = "deytt./connect"
            textSize = 30f
            setTextColor(Color.WHITE)
        }
        content.addView(title, matchWidth(wrapContent()))

        val subtitle = TextView(this).apply {
            text = "Android MVP · системный VPN поверх общего sing-box core"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 10, 0, 28)
        }
        content.addView(subtitle, matchWidth(wrapContent()))

        urlInput = EditText(this).apply {
            hint = "HTTPS-ссылка на подписку DEYTTT"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setText(getPreferences(0).getString(URL_KEY, "").orEmpty())
        }
        content.addView(urlInput, matchWidth(wrapContent()))

        importButton = Button(this).apply {
            text = "Импортировать подписку"
            setOnClickListener { importSubscription() }
        }
        content.addView(importButton, matchWidth(wrapContent()))

        status = TextView(this).apply {
            text = "Подписка ещё не импортирована"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 22, 0, 22)
        }
        content.addView(status, matchWidth(wrapContent()))

        connectButton = Button(this).apply {
            text = "Подключить VPN"
            setOnClickListener { requestOrStartVpn() }
        }
        content.addView(connectButton, matchWidth(wrapContent()))

        val disconnectButton = Button(this).apply {
            text = "Отключить"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ConnectVpnService::class.java))
                status.text = "VPN отключён"
            }
        }
        content.addView(disconnectButton, matchWidth(wrapContent()))

        val note = TextView(this).apply {
            text = "Конфигурация сохраняется атомарно; предыдущая рабочая версия остаётся локально для отката."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 28, 0, 0)
        }
        content.addView(note, matchWidth(wrapContent()))

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun importSubscription() {
        val rawUrl = urlInput.text.toString().trim()
        if (rawUrl.isBlank()) {
            status.text = "Вставьте HTTPS-ссылку на подписку"
            return
        }
        importButton.isEnabled = false
        status.text = "Загружаю и проверяю конфигурацию…"
        getPreferences(0).edit().putString(URL_KEY, rawUrl).apply()
        executor.execute {
            try {
                val imported = SubscriptionClient.import(this, rawUrl)
                runOnUiThread {
                    importButton.isEnabled = true
                    status.text = "Импортировано: ${imported.summary.outboundCount} выходов; " +
                        "протоколы: ${imported.summary.protocols.ifEmpty { setOf("sing-box") }.joinToString()}"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    importButton.isEnabled = true
                    status.text = error.message ?: "Не удалось импортировать подписку"
                }
            }
        }
    }

    private fun requestOrStartVpn() {
        if (SubscriptionStore(this).readCurrent() == null) {
            status.text = "Сначала импортируйте подписку"
            return
        }
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST)
        } else {
            startVpnService()
        }
    }

    @Deprecated("Android activity result API is sufficient for the MVP")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, ConnectVpnService::class.java)
            .setAction(ConnectVpnService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        status.text = "Запускаю VPN…"
    }

    private fun matchWidth(height: Int): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    private fun wrapContent(): Int = ViewGroup.LayoutParams.WRAP_CONTENT
}
