package com.tfassbender.shoppinglist.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tfassbender.shoppinglist.ShoppingListApp
import java.util.concurrent.TimeUnit

/**
 * Sends changes made offline as soon as there is a network - also when the app is in the background or
 * closed. It only *pushes* what is queued (and pulls as part of the sync protocol); nothing is fetched
 * in the background otherwise (decided: no push notifications, the app pulls when opened).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ShoppingListApp).container
        if (container.auth.currentSession() == null) return Result.success()
        val engine = container.syncEngine
        engine.syncAll()
        val stillPending = engine.hasPendingChanges() && engine.status.value != SyncStatus.IDLE
        return if (stillPending) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "send-queued-changes"

        /** Runs once when a network is available; scheduling again while one is waiting changes nothing. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
