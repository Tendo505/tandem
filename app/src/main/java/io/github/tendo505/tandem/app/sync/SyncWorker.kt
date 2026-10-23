package io.github.tendo505.tandem.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tendo505.tandem.app.TandemApp
import java.util.concurrent.TimeUnit

/** Background sync: runs after local edits (once there's a network) and every 15 minutes. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = (applicationContext as TandemApp).container.sync
        return when (engine.syncAll()) {
            null, SyncProblem.NOT_CONFIGURED -> Result.success()
            SyncProblem.OFFLINE -> Result.retry()
            SyncProblem.REJECTED -> Result.failure()
        }
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Replacing the pending request debounces bursts of edits. Replacing a
         * running one is also fine: a cancelled sync loses nothing.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setInitialDelay(1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
