package space.deytt.connect

import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.Executors

internal class SubscriptionRefreshCoordinator(
    private val host: MainActivity,
    private val onRoutesUpdated: (List<DeyttRoute>) -> Unit = {},
    private val onSetupRequested: () -> Unit = {},
    private val invalidateCache: () -> Unit = {},
    private val rebuildRouteRow: () -> Unit = {},
    private val refreshPages: () -> Unit = {},
) {
    var subscriptionRefreshInFlight = false
    var subscriptionRefreshLastStartedAt = 0L
    private val manualRefreshCallbacks = mutableListOf<(Boolean, String) -> Unit>()
    private val subscriptionExecutor = Executors.newSingleThreadExecutor()

    fun refreshSubscriptionIfNeeded() {
        if (host.isFinishing || host.isDestroyed || subscriptionRefreshInFlight) return
        val url = SubscriptionClient.getSavedSubscriptionUrl(host)?.trim()?.takeIf(String::isNotEmpty)
        if (url == null) {
            android.util.Log.i("DeyttSubscription", "Background refresh skipped: no readable saved subscription URL")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (subscriptionRefreshLastStartedAt > 0 && now - subscriptionRefreshLastStartedAt < 30_000) return

        subscriptionRefreshInFlight = true
        subscriptionRefreshLastStartedAt = now
        val isEn = AppLanguage.current(host) == AppLanguage.EN
        SubscriptionRefreshState.start(if (isEn) "Updating subscription and routes…" else "Обновляем подписку и маршруты…")
        subscriptionExecutor.execute {
            val result = runCatching {
                SubscriptionClient.import(host, url, beforeCommit = {
                    if (SubscriptionClient.getSavedSubscriptionUrl(host) != url) {
                        throw SubscriptionCancelledException()
                    }
                }, onStage = { stage ->
                    SubscriptionRefreshState.updateStage(stage, SubscriptionRefreshState.stageDescription(stage, isEn))
                })
            }
            result.onFailure { failure ->
                val status = (failure as? SubscriptionHttpFailure)?.statusCode
                android.util.Log.w(
                    "DeyttSubscription",
                    "Background refresh failed (${failure.javaClass.simpleName}, HTTP ${status ?: 0})"
                )
            }
            host.runOnUiThread {
                subscriptionRefreshInFlight = false
                val callbacks = synchronized(manualRefreshCallbacks) {
                    val copy = ArrayList(manualRefreshCallbacks)
                    manualRefreshCallbacks.clear()
                    copy
                }
                if (host.isFinishing || host.isDestroyed || result.isFailure) {
                    if (result.isFailure) {
                        val err = result.exceptionOrNull()
                        val msg = SubscriptionRetryPolicy.safeFailureSummary(err ?: IOException())
                        val failText = if (isEn) "Failed to update: $msg" else "Не удалось обновить: $msg"
                        SubscriptionRefreshState.finishFailure(failText)
                        callbacks.forEach { it.invoke(false, failText) }
                    }
                    return@runOnUiThread
                }

                invalidateCache()
                val routes = runCatching {
                    val config = SubscriptionStore(host).readCurrent() ?: return@runCatching emptyList()
                    RouteCatalog.from(config, AwgProfileStore(host).profiles(), OpenFluxProfileStore(host).profiles(host))
                }.getOrNull() ?: return@runOnUiThread
                if (routes.none { it.id == SelectedRouteStore(host).read().id }) {
                    routes.firstOrNull()?.let { SelectedRouteStore(host).save(it) }
                }
                onRoutesUpdated(routes)
                rebuildRouteRow()
                refreshPages()
                val successText = if (isEn) "Routes updated (${routes.size})" else "Маршруты обновлены (${routes.size})"
                SubscriptionRefreshState.finishSuccess(routes.size, successText)
                callbacks.forEach { it.invoke(true, successText) }
            }
        }
    }

    fun refreshSubscriptionManually(onComplete: ((Boolean, String) -> Unit)? = null) {
        if (host.isFinishing || host.isDestroyed) return
        onComplete?.let {
            synchronized(manualRefreshCallbacks) {
                manualRefreshCallbacks.add(it)
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (subscriptionRefreshInFlight) {
            // If already in flight and not stalled (< 8s), let it complete and notify all callbacks
            if (subscriptionRefreshLastStartedAt > 0 && now - subscriptionRefreshLastStartedAt < 8_000) {
                return
            }
            // Stalled background job: clear flag and force fresh start
            subscriptionRefreshInFlight = false
        }
        val isEnManual = AppLanguage.current(host) == AppLanguage.EN
        val url = SubscriptionClient.getSavedSubscriptionUrl(host)?.trim()?.takeIf(String::isNotEmpty)
        val token = TelegramSessionStore.read(host)
        if (url == null && token == null) {
            val callbacks = synchronized(manualRefreshCallbacks) {
                val copy = ArrayList(manualRefreshCallbacks)
                manualRefreshCallbacks.clear()
                copy
            }
            val noAccountText = if (isEnManual) "Connect profile or Telegram" else "Подключите профиль или Telegram"
            callbacks.forEach { it.invoke(false, noAccountText) }
            onSetupRequested()
            return
        }

        subscriptionRefreshInFlight = true
        subscriptionRefreshLastStartedAt = now
        SubscriptionRefreshState.start(if (isEnManual) "Updating subscription and routes…" else "Обновляем подписку и маршруты…")

        subscriptionExecutor.execute {
            val targetUrl = url ?: runCatching {
                TelegramPairingClient.subscriptionUrl(token!!)
            }.getOrNull()

            if (targetUrl.isNullOrBlank()) {
                host.runOnUiThread {
                    subscriptionRefreshInFlight = false
                    val callbacks = synchronized(manualRefreshCallbacks) {
                        val copy = ArrayList(manualRefreshCallbacks)
                        manualRefreshCallbacks.clear()
                        copy
                    }
                    val failText = if (isEnManual) "Active subscription not found" else "Активная подписка не найдена"
                    SubscriptionRefreshState.finishFailure(failText)
                    callbacks.forEach { it.invoke(false, failText) }
                }
                return@execute
            }

            if (url == null) {
                SubscriptionClient.saveSubscriptionUrl(host, targetUrl)
            }

            val result = runCatching {
                SubscriptionClient.import(host, targetUrl, beforeCommit = {
                    if (SubscriptionClient.getSavedSubscriptionUrl(host) != targetUrl) {
                        throw SubscriptionCancelledException()
                    }
                }, onStage = { stage ->
                    SubscriptionRefreshState.updateStage(stage, SubscriptionRefreshState.stageDescription(stage, isEnManual))
                })
            }

            host.runOnUiThread {
                subscriptionRefreshInFlight = false
                val callbacks = synchronized(manualRefreshCallbacks) {
                    val copy = ArrayList(manualRefreshCallbacks)
                    manualRefreshCallbacks.clear()
                    copy
                }
                if (host.isFinishing || host.isDestroyed) return@runOnUiThread

                if (result.isFailure) {
                    val err = result.exceptionOrNull()
                    val msg = SubscriptionRetryPolicy.safeFailureSummary(err ?: IOException())
                    val failText = if (isEnManual) "Failed to update: $msg" else "Не удалось обновить: $msg"
                    SubscriptionRefreshState.finishFailure(failText)
                    callbacks.forEach { it.invoke(false, failText) }
                    return@runOnUiThread
                }

                invalidateCache()
                val routes = runCatching {
                    val config = SubscriptionStore(host).readCurrent() ?: return@runCatching emptyList()
                    RouteCatalog.from(config, AwgProfileStore(host).profiles(), OpenFluxProfileStore(host).profiles(host))
                }.getOrNull() ?: emptyList()

                if (routes.none { it.id == SelectedRouteStore(host).read().id }) {
                    routes.firstOrNull()?.let { SelectedRouteStore(host).save(it) }
                }
                onRoutesUpdated(routes)
                rebuildRouteRow()
                refreshPages()
                val isEnManualFinish = AppLanguage.current(host) == AppLanguage.EN
                val successText = if (isEnManualFinish) "Routes updated (${routes.size})" else "Маршруты обновлены (${routes.size})"
                SubscriptionRefreshState.finishSuccess(routes.size, successText)
                callbacks.forEach { it.invoke(true, successText) }
            }
        }
    }

    fun close() {
        subscriptionExecutor.shutdownNow()
        subscriptionRefreshInFlight = false
        synchronized(manualRefreshCallbacks) {
            manualRefreshCallbacks.clear()
        }
    }
}
