package com.example.storevisitmanagementsystem.data.repository

import androidx.room.withTransaction
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.db.dao.PhotoEvidenceDao
import com.example.storevisitmanagementsystem.data.local.db.dao.SyncQueueDao
import com.example.storevisitmanagementsystem.data.local.db.dao.TaskDao
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity
import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.ApiResponse
import com.example.storevisitmanagementsystem.data.remote.model.SyncBatchRequest
import com.example.storevisitmanagementsystem.data.remote.model.SyncOperationDto
import com.example.storevisitmanagementsystem.util.Resource
import com.example.storevisitmanagementsystem.sync.InFlightSync
import com.example.storevisitmanagementsystem.util.Constants
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
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
                ops.filter { it.status == "SYNCING" || it.status == "FAILED" }.forEach(::restorePending)
                ops
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sync queue for userId=$userId: ${e.localizedMessage}")
            return@withContext Resource.Error("Could not read the sync queue: ${e.localizedMessage}")
        }

        val pendingCount = sortedOps.count { it.status != "REJECTED" }
        Log.i(TAG, "Starting sync for userId=$userId with $pendingCount pending operation(s)")
        try {
            drainQueue(token, userId, sortedOps)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Could not finish the local sync queue", failure)
            Resource.Error("Could not finish the local sync queue.")
        }
    }

    private suspend fun drainQueue(
        token: String,
        userId: String,
        sortedOps: List<SyncQueueEntity>
    ): Resource<Int> {
        var totalProcessed = 0
        val taskQueue = mutableListOf<SyncQueueEntity>()
        val blockedTasks = mutableSetOf<String>()

        suspend fun flushTasks(): Resource<Int> {
            val result = executeTaskBatch(token, userId, taskQueue, blockedTasks)
            taskQueue.clear()
            if (result is Resource.Success) totalProcessed += result.data ?: 0
            return result
        }

        for (op in sortedOps) {
            if (op.entityType == "TASK" && op.status != "REJECTED") {
                taskQueue.add(op)
                continue
            }

            val taskResult = flushTasks()
            if (taskResult is Resource.Error) return taskResult

            val taskId = parentTaskId(op)
            if (op.status == "REJECTED") {
                taskId?.let(blockedTasks::add)
                continue
            }
            if (op.entityType != "PHOTO" || taskId in blockedTasks) continue

            val photoResult = executePhotoUpload(token, op, userId)
            if (photoResult is Resource.Error) {
                if (!isPermanent(photoResult)) {
                    return Resource.Error(photoResult.message ?: "Photo upload failed")
                }
                markPermanentFailure(op, photoResult.message.orEmpty())
                taskId?.let(blockedTasks::add)
            } else {
                totalProcessed += 1
            }
        }

        val taskResult = flushTasks()
        if (taskResult is Resource.Error) return taskResult
        return Resource.Success(totalProcessed)
    }

    private fun isPermanent(result: Resource.Error<*>): Boolean =
        result.message?.startsWith("Permanent error") == true

    private fun parentTaskId(op: SyncQueueEntity): String? {
        if (op.entityType == "TASK") return op.entityId
        return runCatching { JsonParser.parseString(op.payloadJson).asJsonObject.get("taskId")?.asString }
            .getOrNull() ?: photoEvidenceDao.getPhotoById(op.entityId, op.userId)?.taskId
    }

    private suspend fun executeTaskBatch(
        token: String,
        userId: String,
        queue: List<SyncQueueEntity>,
        blockedTasks: MutableSet<String>
    ): Resource<Int> {
        val eligible = queue.filter { it.entityId !in blockedTasks }
        if (eligible.isEmpty()) return Resource.Success(0)

        val operations = eligible.map { op ->
            SyncOperationDto(
                operationId = op.operationId,
                entityType = op.entityType,
                entityId = op.entityId,
                operationType = op.operationType,
                userId = op.userId,
                payload = runCatching<Any> { JsonParser.parseString(op.payloadJson) }.getOrElse { op.payloadJson },
                sequenceNumber = op.sequenceNumber
            )
        }
        val result = sendTaskBatch(token, userId, operations, eligible)
        if (result !is Resource.Error || !isPermanent(result)) return result

        // The server rolls back the entire batch on rejection. Split in queue order
        // to isolate the rejected operation without acknowledging the other entries.
        if (eligible.size == 1) {
            val rejected = eligible.single()
            markPermanentFailure(rejected, result.message.orEmpty())
            blockedTasks.add(rejected.entityId)
            return Resource.Success(0)
        }

        val middle = eligible.size / 2
        val first = executeTaskBatch(token, userId, eligible.subList(0, middle), blockedTasks)
        if (first is Resource.Error) return first
        val second = executeTaskBatch(token, userId, eligible.subList(middle, eligible.size), blockedTasks)
        if (second is Resource.Error) return second
        return Resource.Success((first.data ?: 0) + (second.data ?: 0))
    }

    private suspend fun sendTaskBatch(
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
            val records = queueEntities.map { InFlightSync.Record(userId, it.entityId) }.toSet()
            val response = InFlightSync.during(records) { apiService.syncTasks(bearer(token), batchRequest) }

            if (response.code() == 401) {
                val invalidated = tokenManager.handle401(token)
                Log.w(TAG, "HTTP 401 during task sync (sessionInvalidated=$invalidated)")
                return Resource.Error("HTTP 401 Unauthorized: Session token expired. Local data preserved.")
            }

            if (!response.isSuccessful || response.body()?.success != true) {
                val errorMsg = failureMessage(response)
                if (response.code() in 400..499 && response.code() !in listOf(408, 429)) {
                    Log.e(TAG, "Permanent client error (HTTP ${response.code()}): $errorMsg.")
                    return Resource.Error("Permanent error (HTTP ${response.code()}): $errorMsg")
                }
                Log.w(TAG, "Server error (HTTP ${response.code()}): $errorMsg. Retrying via WorkManager.")
                return Resource.Error("Server error (${response.code()}): $errorMsg")
            }

            val succeededIds = response.body()?.data?.succeededOperationIds.orEmpty().toSet()
            val acknowledged = queueEntities.filter { it.operationId in succeededIds }
            runInTransaction {
                acknowledged.forEach { syncQueueDao.deleteOperation(it.operationId) }
                val remainingByTask = syncQueueDao.getPendingOperationsSync(userId).groupBy(::parentTaskId)
                acknowledged.map { it.entityId }.distinct().forEach { taskId ->
                    refreshTaskStatus(userId, taskId, remainingByTask[taskId].orEmpty())
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
                Log.e(TAG, "Permanent error: Photo file missing or empty")
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
            val response = InFlightSync.during(
                setOf(InFlightSync.Record(userId, photoEntity.taskId)),
                InFlightSync.Record(userId, photoEntity.photoId)
            ) {
                apiService.uploadPhoto(bearer(token), op.operationId, opIdBody, photoIdBody, taskIdBody, filePart)
            }

            if (response.code() == 401) {
                val invalidated = tokenManager.handle401(token)
                Log.w(TAG, "HTTP 401 during photo upload (sessionInvalidated=$invalidated)")
                return Resource.Error("HTTP 401 Unauthorized: Session token expired. Local photo preserved.")
            }

            if (!response.isSuccessful || response.body()?.success != true) {
                val errorMsg = failureMessage(response)
                if (response.code() in 400..499 && response.code() !in listOf(408, 429)) {
                    Log.e(TAG, "Permanent error uploading photo (HTTP ${response.code()}): $errorMsg")
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
                val remaining = syncQueueDao.getPendingOperationsSync(userId)
                    .filter { parentTaskId(it) == photoEntity.taskId }
                refreshTaskStatus(userId, photoEntity.taskId, remaining)
            }
            Log.i(TAG, "Photo upload succeeded: photoId=${photoEntity.photoId}")
            Resource.Success(Unit)
        } catch (e: CancellationException) {
            Log.d(TAG, "Photo upload cancelled")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Network failure during photo upload: ${e.localizedMessage}")
            Resource.Error("Network failure during photo upload: ${e.localizedMessage}")
        }
    }

    private fun failureMessage(response: Response<out ApiResponse<*>>): String {
        val serverMessage = runCatching {
            response.errorBody()?.use { body ->
                JsonParser.parseString(body.string()).asJsonObject.getAsJsonObject("error").get("message").asString
            }
        }.getOrNull()
        return response.body()?.error?.message ?: serverMessage
            ?: response.message().ifBlank { "HTTP ${response.code()}" }
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

    private fun refreshTaskStatus(userId: String, taskId: String, queue: List<SyncQueueEntity>) {
        val taskOps = queue.filter { it.entityType == "TASK" }
        val photos = photoEvidenceDao.getPhotosForTaskSync(taskId, userId)
        val rejection = queue.firstOrNull { it.status == "REJECTED" }
        val status = when {
            rejection != null || photos.any { it.uploadStatus == "UPLOAD_FAILED" } -> "SYNC_FAILED"
            taskOps.any { it.operationType == "DELETE" } -> "PENDING_DELETE"
            taskOps.any { it.operationType == "CREATE" } -> "PENDING_INSERT"
            queue.isNotEmpty() || photos.any { it.uploadStatus != "UPLOADED" } -> "PENDING_UPDATE"
            else -> "SYNCED"
        }
        taskDao.updateTaskSyncStatus(taskId, status, rejection?.errorMessage)
    }

    private suspend fun markPermanentFailure(op: SyncQueueEntity, errorMsg: String) {
        Log.w(TAG, "Rejecting operation ${op.operationId}: $errorMsg")
        runInTransaction {
            syncQueueDao.updateOperationStatus(op.operationId, "REJECTED", errorMsg)
            if (op.entityType == "PHOTO") {
                photoEvidenceDao.updatePhotoUploadStatus(op.entityId, "UPLOAD_FAILED", null)
            }
            parentTaskId(op)?.let { taskId ->
                taskDao.updateTaskSyncStatus(taskId, "SYNC_FAILED", errorMsg)
            }
        }
    }
}
