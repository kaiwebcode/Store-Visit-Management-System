package com.example.storevisitmanagementsystem.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.storevisitmanagementsystem.BuildConfig
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.preferences.TokenManagerImpl
import com.example.storevisitmanagementsystem.data.remote.api.ApiClient
import com.example.storevisitmanagementsystem.data.repository.SyncRepositoryImpl
import com.example.storevisitmanagementsystem.util.Resource

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_USER_ID = "key_user_id"
        private const val TAG = "StoreVisitSync"
    }

    override suspend fun doWork(): Result {
        if (!BuildConfig.DEBUG) return Result.success()
        val userId = inputData.getString(KEY_USER_ID)
            ?: return Result.failure()

        val context = applicationContext
        val appDb = AppDatabase.getInstance(context)
        val tokenManager = TokenManagerImpl(context)
        val apiService = ApiClient.getApiService(context)

        val syncRepository = SyncRepositoryImpl(
            syncQueueDao = appDb.syncQueueDao(),
            taskDao = appDb.taskDao(),
            photoEvidenceDao = appDb.photoEvidenceDao(),
            apiService = apiService,
            tokenManager = tokenManager,
            appDatabase = appDb
        )

        return when (val result = syncRepository.syncPendingOperations(userId)) {
            is Resource.Success -> {
                Log.i(TAG, "Work $id acknowledged ${result.data ?: 0} operations")
                Result.success()
            }
            is Resource.Error -> {
                val msg = result.message ?: ""
                when {
                    msg.startsWith("HTTP 401") || msg.startsWith("No active session") ||
                        msg.startsWith("Local-only session") -> {
                        Log.i(TAG, "Work $id paused for backend sign-in")
                        Result.success()
                    }
                    msg.startsWith("Permanent error") -> {
                        Log.w(TAG, "Work $id paused after a request rejection")
                        Result.success()
                    }
                    else -> {
                        Log.i(TAG, "Work $id will retry after attempt ${runAttemptCount + 1}")
                        Result.retry()
                    }
                }
            }
            else -> Result.failure()
        }
    }
}
