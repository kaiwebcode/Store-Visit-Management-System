package com.example.storevisitmanagementsystem.data.repository

import androidx.room.withTransaction
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.db.dao.PhotoEvidenceDao
import com.example.storevisitmanagementsystem.data.local.db.dao.SyncQueueDao
import com.example.storevisitmanagementsystem.data.local.db.dao.TaskDao
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity
import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.SyncBatchRequest
import com.example.storevisitmanagementsystem.data.remote.model.SyncOperationDto
import com.example.storevisitmanagementsystem.util.Resource
import com.example.storevisitmanagementsystem.util.Constants
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import java.io.File
import java.util.UUID

class SyncRepositoryImpl(
    private val syncQueueDao: SyncQueueDao,
    private val taskDao: TaskDao,
    private val photoEvidenceDao: PhotoEvidenceDao,
    private val apiService: ApiService,
    private val tokenManager: ITokenManager,
    private val appDatabase: AppDatabase? = null
) : ISyncRepository {

    private companion object {
        private const val TAG = "StoreVisitSync"
    }

    private suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return if (appDatabase != null) {
            appDatabase.withTransaction { block() }
        } else {
            block()
        }
    }

    override suspend fun syncPendingOperations(userId: String): Resource<Int> = withContext(Dispatchers.IO) {
        val token = tokenManager.getAuthTokenForUser(userId)
            ?: run {
                Log.w(TAG, "Sync aborted: No active session for userId=$userId")
                return@withContext Resource.Error("No active session for this account.")
            }
        if (token.startsWith(Constants.LOCAL_MOCK_TOKEN_PREFIX)) {
            Log.d(TAG, "Sync skipped: Local-only mock session for userId=$userId")
            return@withContext Resource.Error("Local-only session. Backend sign-in required.")
        }

        // WorkManager serializes runs for this account. Queue entries remain pending
        // until the acknowledgement transaction commits, even if the process dies.
        val sortedOps = try {
            runInTransaction {
                val ops = syncQueueDao.getPendingOperationsSync(userId)
                ops.filter { it.status != "PENDING" }.forEach(::restorePending)
                ops
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sync queue for userId=$userId: ${e.localizedMessage}")
            return@withContext Resource.Error("Could not read the sync queue: ${e.localizedMessage}")
        }

        Log.i(TAG, "Starting sync for userId=$userId with ${sortedOps.size} pending operation(s)")

        var totalProcessed = 0
        val taskOps = mutableListOf<SyncOperationDto>()
        val taskQueueEntities = mutableListOf<SyncQueueEntity>()

        for (op in sortedOps) {
            if (op.entityType == "TASK") {
                val payloadObj = try {
                    JsonParser.parseString(op.payloadJson)
                } catch (e: Exception) {
                    op.payloadJson
                }
                taskOps.add(
                    SyncOperationDto(
                        operationId = op.operationId,
                        entityType = op.entityType,
                        entityId = op.entityId,
                        operationType = op.operationType,
                        userId = op.userId,
                        payload = payloadObj,
                        sequenceNumber = op.sequenceNumber
                    )
                )
                taskQueueEntities.add(op)
            } else if (op.entityType == "PHOTO") {
                // If we accumulated task ops prior to this photo, execute them first to preserve dependency order
                if (taskOps.isNotEmpty()) {
                    val batchResult = executeTaskBatch(token, userId, taskOps, taskQueueEntities)
                    if (batchResult is Resource.Error) return@withContext batchResult
                    totalProcessed += (batchResult as Resource.Success).data ?: 0
                    taskOps.clear()
                    taskQueueEntities.clear()
                }

                // Execute Photo Upload
                val photoResult = executePhotoUpload(token, op, userId)
                if (photoResult is Resource.Error) return@withContext Resource.Error(photoResult.message ?: "Photo upload failed")
                totalProcessed += 1
            }
        }

        // Flush remaining task operations
        if (taskOps.isNotEmpty()) {
            val batchResult = executeTaskBatch(token, userId, taskOps, taskQueueEntities)
            if (batchResult is Resource.Error) return@withContext batchResult
            totalProcessed += (batchResult as Resource.Success).data ?: 0
        }

        Resource.Success(totalProcessed)
    }

    private suspend fun executeTaskBatch(
        token: String,
        userId: String,
        taskOps: List<SyncOperationDto>,
        queueEntities: List<SyncQueueEntity>
    ): Resource<Int> {
        return try {
            val batchRequest = SyncBatchRequest(
                batchId = "batch_${UUID.randomUUID().toString().take(8)}",
                operations = taskOps
            )
            if (tokenManager.getAuthTokenForUser(userId) != token) {
                Log.w(TAG, "Task batch aborted: Token mismatch for userId=$userId")
                return Resource.Error("No active session for this account.")
            }
            Log.d(TAG, "Posting task batch ${batchRequest.batchId} with ${taskOps.size} operation(s)")
            val response = apiService.syncTasks(bearer(token), batchRequest)

            if (response.code() == 401) {
                Log.w(TAG, "HTTP 401 Unauthorized during task sync. Clearing session token while preserving local data.")
                tokenManager.handle401(token)
                return Resource.Error("HTTP 401 Unauthorized: Session token expired. Local data preserved.")
            }

            if (!response.isSuccessful || response.body()?.success != true) {
                val errorMsg = response.body()?.error?.message ?: response.message() ?: "HTTP ${response.code()}"
                if (response.code() in 400..499 && response.code() !in listOf(408, 429)) {
                    Log.e(TAG, "Permanent client error (HTTP ${response.code()}): $errorMsg. Marking tasks failed.")
                    markPermanentFailure(queueEntities, errorMsg)
                    return Resource.Error("Permanent error (HTTP ${response.code()}): $errorMsg")
                }
                Log.w(TAG, "Server error (HTTP ${response.code()}): $errorMsg. Retrying via WorkManager.")
                return Resource.Error("Server error (${response.code()}): $errorMsg")
            }

            val succeededIds = response.body()?.data?.succeededOperationIds.orEmpty().toSet()
            val acknowledged = queueEntities.filter { it.operationId in succeededIds }
            runInTransaction {
                acknowledged.forEach { syncQueueDao.deleteOperation(it.operationId) }
                val pendingTaskIds = syncQueueDao.getPendingOperationsSync(userId)
                    .filter { it.entityType == "TASK" }.map { it.entityId }.toSet()
                acknowledged.forEach { op ->
                    if (op.entityId !in pendingTaskIds) taskDao.updateTaskSyncStatus(op.entityId, "SYNCED", null)
                }
            }
            if (acknowledged.size != queueEntities.size) {
                Log.w(TAG, "Incomplete batch acknowledgment: acknowledged ${acknowledged.size} of ${queueEntities.size}")
                return Resource.Error("Task batch acknowledgement was incomplete.")
            }
            Log.i(TAG, "Task batch sync succeeded: ${acknowledged.size} operation(s) acknowledged")
            Resource.Success(acknowledged.size)
        } catch (e: CancellationException) {
            Log.d(TAG, "Task batch sync cancelled")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Network failure during task sync: ${e.localizedMessage}")
            Resource.Error("Network failure during task sync: ${e.localizedMessage}")
        }
    }

    private suspend fun executePhotoUpload(
        token: String,
        op: SyncQueueEntity,
        userId: String
    ): Resource<Unit> {
        return try {
            val photoEntity = photoEvidenceDao.getPhotoById(op.entityId, userId)
                ?: run {
                    Log.e(TAG, "Permanent error: Photo record ${op.entityId} missing in local DB")
                    return Resource.Error("Permanent error: photo record ${op.entityId} not found.")
                }

            val photoFile = File(photoEntity.localFilePath)
            if (!photoFile.exists() || photoFile.length() == 0L) {
                Log.e(TAG, "Permanent error: Photo file missing or empty at ${photoEntity.localFilePath}")
                markPermanentFailure(listOf(op), "Photo file missing locally: ${photoEntity.localFilePath}")
                return Resource.Error("Permanent error: photo file missing locally.")
            }

            val textMediaType = "text/plain".toMediaTypeOrNull()
            val imageMediaType = "image/jpeg".toMediaTypeOrNull()

            val opIdBody = op.operationId.toRequestBody(textMediaType)
            val photoIdBody = photoEntity.photoId.toRequestBody(textMediaType)
            val taskIdBody = photoEntity.taskId.toRequestBody(textMediaType)
            val filePart = MultipartBody.Part.createFormData(
                "file",
                photoFile.name,
                photoFile.asRequestBody(imageMediaType)
            )

            if (tokenManager.getAuthTokenForUser(userId) != token) {
                Log.w(TAG, "Photo upload aborted: Token mismatch for userId=$userId")
                return Resource.Error("No active session for this account.")
            }
            Log.d(TAG, "Uploading photo photoId=${photoEntity.photoId}, opId=${op.operationId}, fileSizeBytes=${photoFile.length()}")
            val response = apiService.uploadPhoto(bearer(token), opIdBody, photoIdBody, taskIdBody, filePart)

            if (response.code() == 401) {
                Log.w(TAG, "HTTP 401 Unauthorized during photo upload. Clearing session token while preserving local photo.")
                tokenManager.handle401(token)
                return Resource.Error("HTTP 401 Unauthorized: Session token expired. Local photo preserved.")
            }

            if (!response.isSuccessful || response.body()?.success != true) {
                val errorMsg = response.body()?.error?.message ?: response.message() ?: "HTTP ${response.code()}"
                if (response.code() in 400..499 && response.code() !in listOf(408, 429)) {
                    Log.e(TAG, "Permanent error uploading photo (HTTP ${response.code()}): $errorMsg")
                    markPermanentFailure(listOf(op), errorMsg)
                    return Resource.Error("Permanent error uploading photo (HTTP ${response.code()}): $errorMsg")
                }
                Log.w(TAG, "Server error uploading photo (HTTP ${response.code()}): $errorMsg")
                return Resource.Error("Server error (${response.code()}): $errorMsg")
            }

            val uploadData = response.body()?.data
            val remoteUrl = uploadData?.remoteUrl
            if (uploadData?.operationId != op.operationId || uploadData.photoId != photoEntity.photoId ||
                uploadData.taskId != photoEntity.taskId || uploadData.status != "UPLOADED" || remoteUrl.isNullOrBlank()
            ) {
                Log.w(TAG, "Photo acknowledgment mismatch: received opId=${uploadData?.operationId}")
                return Resource.Error("Photo acknowledgement did not match the queued upload.")
            }

            runInTransaction {
                photoEvidenceDao.updatePhotoUploadStatus(photoEntity.photoId, "UPLOADED", remoteUrl)
                syncQueueDao.deleteOperation(op.operationId)
            }
            Log.i(TAG, "Photo upload succeeded: photoId=${photoEntity.photoId}, remoteUrl=$remoteUrl")
            Resource.Success(Unit)
        } catch (e: CancellationException) {
            Log.d(TAG, "Photo upload cancelled")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Network failure during photo upload: ${e.localizedMessage}")
            Resource.Error("Network failure during photo upload: ${e.localizedMessage}")
        }
    }

    private fun bearer(token: String): String = if (token.startsWith("Bearer ")) token else "Bearer $token"

    private fun restorePending(op: SyncQueueEntity) {
        Log.d(TAG, "Restoring opId=${op.operationId} (type=${op.entityType}) status to PENDING")
        syncQueueDao.updateOperationStatus(op.operationId, "PENDING", null)
        when (op.entityType) {
            "TASK" -> {
                val status = when (op.operationType) {
                    "CREATE" -> "PENDING_INSERT"
                    "DELETE" -> "PENDING_DELETE"
                    else -> "PENDING_UPDATE"
                }
                taskDao.updateTaskSyncStatus(op.entityId, status, null)
            }
            "PHOTO" -> photoEvidenceDao.updatePhotoUploadStatus(op.entityId, "PENDING_UPLOAD", null)
        }
    }

    private suspend fun markPermanentFailure(ops: List<SyncQueueEntity>, errorMsg: String) {
        Log.w(TAG, "Marking ${ops.size} operation(s) as permanent failure: $errorMsg")
        runInTransaction {
            for (op in ops) {
                if (op.entityType == "TASK") {
                    taskDao.updateTaskSyncStatus(op.entityId, "SYNC_FAILED", errorMsg)
                } else if (op.entityType == "PHOTO") {
                    photoEvidenceDao.updatePhotoUploadStatus(op.entityId, "UPLOAD_FAILED", null)
                }
            }
        }
    }
}
