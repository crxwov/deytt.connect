package space.deytt.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborEntryIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.util.concurrent.Executors

class ConnectVpnService : VpnService(), CommandServerHandler, PlatformInterface {
    companion object {
        const val ACTION_START = "space.deytt.connect.action.START"
        const val ACTION_STOP = "space.deytt.connect.action.STOP"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "deytt-connect"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var commandServer: CommandServer? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startForegroundCompat("Запуск deytt./connect")
            executor.execute { startTunnel() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        stopTunnel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel() {
        val config = SubscriptionStore(this).readCurrent()
        if (config == null) {
            fail("Нет сохранённой подписки")
            return
        }
        try {
            val server = CommandServer(this, this)
            commandServer = server
            server.start()
            server.startOrReloadService(config, OverrideOptions().apply { autoRedirect = false })
            updateNotification("VPN подключён")
            Log.i(TAG, "VPN service started")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start VPN", error)
            fail(error.message ?: "Не удалось запустить VPN")
        }
    }

    private fun fail(message: String) {
        updateNotification(message)
        tunnel?.close()
        tunnel = null
        commandServer?.close()
        commandServer = null
        started = false
        stopSelf()
    }

    private fun stopTunnel() {
        if (!started && commandServer == null && tunnel == null) return
        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "Failed to close core service", it) }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunnel?.close() }
        tunnel = null
        started = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startForegroundCompat(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = notificationBuilder
            .setContentTitle("deytt./connect")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (!started) return
        startForegroundCompat(text)
    }

    // CommandServerHandler
    override fun connectSSHAgent(): Int = -1
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun serviceReload() {
        val config = SubscriptionStore(this).readCurrent() ?: return
        commandServer?.startOrReloadService(config, OverrideOptions().apply { autoRedirect = false })
    }
    override fun serviceStop() = stopTunnel()
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String) {
        Log.d(TAG, message)
    }

    // PlatformInterface
    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) error("Не удалось исключить core-соединение из VPN")
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun checkPlatformShell() = Unit
    override fun clearDNSCache() = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun createBridge(options: BridgeOptions): BridgeSession = error("Android bridge не поддержан в MVP")
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner = error("Поиск владельца соединения ещё не включён")

    override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): io.nekohasekai.libbox.NetworkInterface =
            throw NoSuchElementException("No interface snapshot")
    }

    override fun includeAllNetworks(): Boolean = false
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun lookupSFTPServer(): String = error("SFTP не поддержан")
    override fun lookupUser(username: String): PlatformUser = error("Системные пользователи не поддержаны")
    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession = error("Shell не поддержан")

    override fun openTun(options: TunOptions): Int {
        check(prepare(this) == null) { "Нет разрешения Android VPN" }
        val builder = Builder().setSession("deytt./connect").setMtu(options.getMTU())

        prefixIterator(options.getInet4Address()).forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
        }
        prefixIterator(options.getInet6Address()).forEach { (address, prefix) ->
            builder.addAddress(address, prefix)
        }

        if (options.getAutoRoute()) {
            val ipv4Routes = prefixIterator(options.getInet4RouteAddress())
            if (ipv4Routes.isEmpty()) {
                builder.addRoute("0.0.0.0", 0)
            } else {
                ipv4Routes.forEach { (address, prefix) -> builder.addRoute(address, prefix) }
            }
            val ipv6Routes = prefixIterator(options.getInet6RouteAddress())
            if (ipv6Routes.isEmpty()) {
                builder.addRoute("::", 0)
            } else {
                ipv6Routes.forEach { (address, prefix) -> builder.addRoute(address, prefix) }
            }

            stringIterator(options.getDNSServerAddress()).forEach(builder::addDnsServer)
            stringIterator(options.getIncludePackage()).forEach { packageName ->
                builder.addAllowedApplication(packageName)
            }
            stringIterator(options.getExcludePackage()).forEach { packageName ->
                builder.addDisallowedApplication(packageName)
            }
        }

        tunnel?.close()
        tunnel = builder.establish() ?: error("Android не выдал TUN-интерфейс")
        return tunnel!!.fd
    }

    override fun readSystemSSHHostKey(): String = error("SSH host key не поддержан")
    override fun readWIFIState(): WIFIState? = null
    override fun registerMyInterface(name: String) = Unit
    override fun sendNotification(notification: LibboxNotification) = Unit
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun tailscaleHostname(): String = ""
    override fun underNetworkExtension(): Boolean = false
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false
    override fun useProcFS(): Boolean = true

    private fun prefixIterator(iterator: RoutePrefixIterator): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        while (iterator.hasNext()) {
            val prefix = iterator.next()
            result += prefix.address() to prefix.prefix()
        }
        return result
    }

    private fun stringIterator(iterator: StringIterator): List<String> {
        val result = mutableListOf<String>()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }
}
