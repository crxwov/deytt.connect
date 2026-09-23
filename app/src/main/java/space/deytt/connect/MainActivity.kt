package space.deytt.connect

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import space.deytt.connect.DeyttUi.button
import space.deytt.connect.DeyttUi.dp
import space.deytt.connect.DeyttUi.header
import space.deytt.connect.DeyttUi.present
import space.deytt.connect.DeyttUi.row
import space.deytt.connect.DeyttUi.rounded
import space.deytt.connect.DeyttUi.screen
import space.deytt.connect.DeyttUi.spacer
import space.deytt.connect.DeyttUi.text

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var power: TextView
    private lateinit var action: TextView
    private lateinit var routeRow: LinearLayout
    private var pendingRoute: SelectedRoute? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = renderStatus(
            intent?.getStringExtra(ConnectVpnService.EXTRA_STATUS),
            intent?.getStringExtra(ConnectVpnService.EXTRA_ERROR),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SubscriptionStore(this).readCurrent() == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        buildScreen()
    }

    override fun onStart() {
        super.onStart()
        if (!::statusText.isInitialized) return
        val filter = IntentFilter(ConnectVpnService.ACTION_STATUS)
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        renderStoredState()
        rebuildRouteRow()
    }

    override fun onStop() {
        if (::statusText.isInitialized) runCatching { unregisterReceiver(statusReceiver) }
        super.onStop()
    }

    private fun buildScreen() {
        val root = screen()
        root.addView(header("connect / android", "deytt."))
        root.addView(spacer(42, this))

        power = text("●", 74f, DeyttUi.BLUE).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF151529.toInt(), 68f, DeyttUi.LINE)
        }
        root.addView(power, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)))
        root.addView(spacer(26, this))
        statusText = text("VPN отключён", 30f, DeyttUi.TEXT, android.graphics.Typeface.BOLD).apply { gravity = Gravity.CENTER }
        detailText = text("Можно подключаться", 15f, DeyttUi.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        root.addView(statusText)
        root.addView(detailText)
        root.addView(spacer(32, this))

        routeRow = LinearLayout(this)
        root.addView(routeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(spacer(14, this))
        action = button("ПОДКЛЮЧИТЬ").apply { setOnClickListener { toggleTunnel() } }
        root.addView(action)
        root.addView(spacer(28, this))
        root.addView(row("Подписка", "Трафик, срок и обновление", "◎").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }
        })
        present(root)
        rebuildRouteRow()
        renderStoredState()
    }

    private fun rebuildRouteRow() {
        if (!::routeRow.isInitialized) return
        val selected = SelectedRouteStore(this).read()
        routeRow.removeAllViews()
        routeRow.addView(row(selected.title, selected.subtitle, if (selected.engine == TunnelEngine.AMNEZIAWG) "◈" else "↗").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, RoutesActivity::class.java)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toggleTunnel() {
        val current = currentStatus()
        if (current == "VPN подключён" || current.contains("Запуск") || current.contains("Проверяем")) {
            stopService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            AwgTunnelController.stop(this)
            return
        }
        pendingRoute = SelectedRouteStore(this).read()
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST) else startSelectedTunnel()
    }

    @Deprecated("Android VPN permission API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) startSelectedTunnel()
    }

    private fun startSelectedTunnel() {
        val route = pendingRoute ?: SelectedRouteStore(this).read()
        if (route.engine == TunnelEngine.AMNEZIAWG) {
            stopService(Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_STOP))
            val store = AwgProfileStore(this)
            val config = if (route.id == "awg31") store.read31() else store.read15()
            if (config == null) {
                renderStatus("Ошибка запуска VPN", "Обновите подписку: профиль ${route.subtitle} отсутствует")
                return
            }
            AwgTunnelController.start(this, config, route.id)
        } else {
            AwgTunnelController.stop(this, publishStatus = false)
            val intent = Intent(this, ConnectVpnService::class.java).setAction(ConnectVpnService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private fun renderStoredState() {
        val prefs = getSharedPreferences(ConnectVpnService.STATE_PREFS, MODE_PRIVATE)
        renderStatus(prefs.getString(ConnectVpnService.STATE_STATUS, "VPN отключён"), prefs.getString(ConnectVpnService.STATE_ERROR, null))
    }

    private fun currentStatus(): String = getSharedPreferences(ConnectVpnService.STATE_PREFS, MODE_PRIVATE)
        .getString(ConnectVpnService.STATE_STATUS, "VPN отключён") ?: "VPN отключён"

    private fun renderStatus(status: String?, error: String?) {
        if (!::statusText.isInitialized) return
        val value = status ?: "VPN отключён"
        statusText.text = value
        detailText.text = error ?: when (value) {
            "VPN подключён" -> "Трафик защищён"
            "VPN отключён" -> "Можно подключаться"
            else -> "Проверяем доступ к интернету"
        }
        val failed = value.startsWith("Ошибка")
        val connected = value == "VPN подключён"
        power.setTextColor(if (failed) DeyttUi.CORAL else if (connected) DeyttUi.MINT else DeyttUi.BLUE)
        action.text = if (connected || value.contains("Запуск") || value.contains("Проверяем")) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ"
    }

    companion object { private const val VPN_PERMISSION_REQUEST = 701 }
}
