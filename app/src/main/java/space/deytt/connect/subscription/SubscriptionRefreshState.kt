package space.deytt.connect

import android.os.SystemClock

/**
 * Global persistent state for subscription refresh operations.
 * Preserves in-flight progress and phase descriptions across tab navigation,
 * so swiping away and back never resets progress.
 */
object SubscriptionRefreshState {
    enum class Phase {
        IDLE,
        CONNECTING,
        SYNCING,
        ASSEMBLING,
        SUCCESS,
        FAILURE
    }

    var inFlight: Boolean = false
        private set

    var currentPhase: Phase = Phase.IDLE
        private set

    var phaseDescription: String = ""
        private set

    var lastSuccessRoutesCount: Int = 0
        private set

    var lastSuccessTimestamp: Long = 0L
        private set

    var lastMessage: String = ""
        private set

    private val listeners = java.util.Collections.synchronizedSet(mutableSetOf<() -> Unit>())

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { runCatching { it.invoke() } }
    }

    fun stageDescription(stage: String, isEnglish: Boolean): String {
        return when (stage) {
            "download" -> if (isEnglish) "Connecting to subscription server…" else "Связываемся с сервером подписки…"
            "validate" -> if (isEnglish) "Validating protocols and profiles…" else "Проверка протоколов и профилей…"
            "awg" -> if (isEnglish) "Synchronizing sing-box, AmneziaWG, and OpenFlux…" else "Синхронизация sing-box, AmneziaWG и OpenFlux…"
            "save" -> if (isEnglish) "Saving and assembling routes…" else "Сохранение и сборка маршрутов…"
            else -> if (isEnglish) "Updating subscription…" else "Обновляем подписку…"
        }
    }

    fun start(initialDescription: String = "Обновляем подписку и маршруты…") {
        inFlight = true
        currentPhase = Phase.CONNECTING
        phaseDescription = initialDescription
        notifyChanged()
    }

    fun updateStage(stage: String, customDescription: String? = null) {
        if (!inFlight) return
        when (stage) {
            "download" -> {
                currentPhase = Phase.CONNECTING
                phaseDescription = customDescription ?: "Связываемся с сервером подписки…"
            }
            "validate" -> {
                currentPhase = Phase.SYNCING
                phaseDescription = customDescription ?: "Проверка протоколов и профилей…"
            }
            "awg" -> {
                currentPhase = Phase.SYNCING
                phaseDescription = customDescription ?: "Синхронизация sing-box, AmneziaWG и OpenFlux…"
            }
            "save" -> {
                currentPhase = Phase.ASSEMBLING
                phaseDescription = customDescription ?: "Сохранение и сборка маршрутов…"
            }
            else -> {
                if (customDescription != null) phaseDescription = customDescription
            }
        }
        notifyChanged()
    }

    fun finishSuccess(routesCount: Int, message: String) {
        inFlight = false
        currentPhase = Phase.SUCCESS
        lastSuccessRoutesCount = routesCount
        lastSuccessTimestamp = SystemClock.elapsedRealtime()
        lastMessage = message
        phaseDescription = message
        notifyChanged()
    }

    fun finishFailure(message: String) {
        inFlight = false
        currentPhase = Phase.FAILURE
        lastMessage = message
        phaseDescription = message
        notifyChanged()
    }
}
