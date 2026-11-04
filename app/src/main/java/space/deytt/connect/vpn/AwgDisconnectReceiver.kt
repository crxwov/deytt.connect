package space.deytt.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.amnezia.awg.backend.GoBackend

class AwgDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_DISCONNECT) {
            AwgTunnelController.stop(context.applicationContext)
        } else if (intent?.action == ACTION_RESTORE) {
            if (!AwgTunnelController.isRunning()) return
            val serviceIntent = Intent(context, GoBackend.VpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    companion object {
        const val ACTION_DISCONNECT = "space.deytt.connect.action.DISCONNECT_AWG"
        const val ACTION_RESTORE = "space.deytt.connect.action.RESTORE_AWG_NOTIFICATION"
    }
}
