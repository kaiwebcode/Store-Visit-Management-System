package com.example.storevisitmanagementsystem.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncManager {

    fun scheduleSync(context: Context, userId: String) =
        enqueue(context, userId, ExistingWorkPolicy.KEEP)

    fun triggerImmediateSync(context: Context, userId: String) =
        enqueue(context, userId, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueue(context: Context, userId: String, policy: ExistingWorkPolicy) {
        if (userId.isBlank()) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = Data.Builder()
            .putString(SyncWorker.KEY_USER_ID, userId)
            .build()

        val syncWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        val uniqueWorkName = "StoreVisitSyncWork_$userId"

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueWorkName,
            policy,
            syncWorkRequest
        )
    }
}
