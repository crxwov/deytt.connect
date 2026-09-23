package space.deytt.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AwgDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_DISCONNECT) {
            AwgTunnelController.stop(context.applicationContext)
        }
    }

    companion object {
        const val ACTION_DISCONNECT = "space.deytt.connect.action.DISCONNECT_AWG"
    }
}
