package space.deytt.connect

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Hourly silent subscription refresh so stored routes and keys stay current
 * even while the app is closed. The launch-time and manual refreshes remain
 * the user-visible paths; this worker only backfills between them.
 */
internal class SubscriptionRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val url = SubscriptionClient.getSavedSubscriptionUrl(context)?.trim().orEmpty()
        if (url.isEmpty()) return Result.success()
        return try {
            SubscriptionClient.import(context, url, beforeCommit = {
                // The user may have switched subscriptions while this import
                // was in flight; never replace a newer commit with this one.
                if (SubscriptionClient.getSavedSubscriptionUrl(context)?.trim() != url) {
                    throw SubscriptionCancelledException()
                }
            })
            android.util.Log.i("DeyttSubscription", "Hourly background refresh committed a fresh subscription")
            Result.success()
        } catch (error: SubscriptionCancelledException) {
            Result.success()
        } catch (error: Exception) {
            android.util.Log.w(
                "DeyttSubscription",
                "Hourly background refresh failed (${error.javaClass.simpleName})",
            )
            if (error is java.io.IOException) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "deytt-subscription-hourly-refresh"

        /** Idempotent scheduling; safe to call from every [android.app.Activity.onCreate]. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
