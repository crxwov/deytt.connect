package space.deytt.connect

import android.content.Context
import androidx.core.content.edit

enum class VpnPhase {
    IDLE,
    STARTING,
    CHECKING,
    CONNECTED,
    STOPPING,
    ERROR,
}

data class VpnSnapshot(
    val phase: VpnPhase,
    val title: String,
    val detail: String?,
) {
    val isActive: Boolean
        get() = phase == VpnPhase.STARTING || phase == VpnPhase.CHECKING || phase == VpnPhase.CONNECTED
}

enum class VpnControlAction { START, STOP }

object VpnControlDecision {
    fun decide(persisted: VpnPhase, libboxRunning: Boolean, awgRunning: Boolean): VpnControlAction =
        if (libboxRunning || awgRunning) VpnControlAction.STOP else VpnControlAction.START

    fun effectivePhase(persisted: VpnPhase, libboxRunning: Boolean, awgRunning: Boolean): VpnPhase =
        if (!libboxRunning && !awgRunning && persisted in setOf(VpnPhase.STARTING, VpnPhase.CHECKING, VpnPhase.CONNECTED, VpnPhase.STOPPING)) {
            VpnPhase.IDLE
        } else {
            persisted
        }
}

class VpnStateStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(ConnectVpnService.STATE_PREFS, Context.MODE_PRIVATE)

    fun read(): VpnSnapshot {
        val title = prefs.getString(ConnectVpnService.STATE_STATUS, IDLE_TITLE) ?: IDLE_TITLE
        val phase = prefs.getString(ConnectVpnService.STATE_PHASE, null)
            ?.let { runCatching { VpnPhase.valueOf(it) }.getOrNull() }
            ?: phaseFromLegacyTitle(title)
        return VpnSnapshot(phase, title, prefs.getString(ConnectVpnService.STATE_ERROR, null))
    }

    fun write(phase: VpnPhase, title: String, detail: String? = null) {
        prefs.edit {
            putString(ConnectVpnService.STATE_PHASE, phase.name)
            putString(ConnectVpnService.STATE_STATUS, title)
            if (detail.isNullOrBlank()) remove(ConnectVpnService.STATE_ERROR)
            else putString(ConnectVpnService.STATE_ERROR, detail)
        }
    }

    fun reconcile(libboxRunning: Boolean, awgRunning: Boolean): VpnSnapshot {
        val saved = read()
        val effective = VpnControlDecision.effectivePhase(saved.phase, libboxRunning, awgRunning)
        if (effective != saved.phase) {
            write(VpnPhase.IDLE, IDLE_TITLE)
            return VpnSnapshot(VpnPhase.IDLE, IDLE_TITLE, null)
        }
        return saved
    }

    companion object {
        const val IDLE_TITLE = "VPN отключён"

        private fun phaseFromLegacyTitle(title: String): VpnPhase = when {
            title == "VPN подключён" -> VpnPhase.CONNECTED
            title.contains("Запуск") -> VpnPhase.STARTING
            title.contains("Проверяем") -> VpnPhase.CHECKING
            title.contains("отключ", ignoreCase = true) && title != IDLE_TITLE -> VpnPhase.STOPPING
            title.startsWith("Ошибка") -> VpnPhase.ERROR
            else -> VpnPhase.IDLE
        }
    }
}
