package com.example.storevisitmanagementsystem

import com.example.storevisitmanagementsystem.data.local.db.dao.PhotoEvidenceDao
import com.example.storevisitmanagementsystem.data.local.db.dao.SyncQueueDao
import com.example.storevisitmanagementsystem.data.local.db.dao.TaskDao
import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.*
import com.example.storevisitmanagementsystem.data.repository.SyncRepositoryImpl
import com.example.storevisitmanagementsystem.util.Resource
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import retrofit2.Response

class SyncRepositoryTest {

    private lateinit var fakeSyncQueueDao: FakeSyncQueueDao
    private lateinit var fakeTaskDao: FakeTaskDao
    private lateinit var fakePhotoDao: FakePhotoEvidenceDao
    private lateinit var fakeApiService: FakeApiService
    private lateinit var fakeTokenManager: FakeTokenManager
    private lateinit var syncRepository: SyncRepositoryImpl

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        fakeSyncQueueDao = FakeSyncQueueDao()
        fakeTaskDao = FakeTaskDao()
        fakePhotoDao = FakePhotoEvidenceDao()
        fakeApiService = FakeApiService()
        fakeTokenManager = FakeTokenManager()

        syncRepository = SyncRepositoryImpl(
            syncQueueDao = fakeSyncQueueDao,
            taskDao = fakeTaskDao,
            photoEvidenceDao = fakePhotoDao,
            apiService = fakeApiService,
            tokenManager = fakeTokenManager,
            appDatabase = null
        )
    }

    @Test
    fun `syncPendingOperations with empty queue returns Success(0)`() = runTest {
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")
        val result = syncRepository.syncPendingOperations("usr_001")
        assertTrue(result is Resource.Success)
        assertEquals(0, (result as Resource.Success).data)
    }

    @Test
    fun `syncPendingOperations processes pending task and updates Room status to SYNCED`() = runTest {
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")

        val task = TaskEntity(
            taskId = "tsk_101",
            storeId = "str_101",
            userId = "usr_001",
            title = "Audit Display",
            description = "Check signage",
            createdAt = "2026-03-31T10:00:00Z",
            updatedAt = "2026-03-31T10:00:00Z",
            syncStatus = "PENDING_INSERT"
        )
        fakeTaskDao.tasks.add(task)

        val syncOp = SyncQueueEntity(
            operationId = "op_101",
            entityType = "TASK",
            entityId = "tsk_101",
            operationType = "CREATE",
            userId = "usr_001",
            payloadJson = Gson().toJson(task),
            status = "PENDING",
            createdAt = "2026-03-31T10:00:00Z",
            sequenceNumber = 1L
        )
        fakeSyncQueueDao.ops.add(syncOp)

        val result = syncRepository.syncPendingOperations("usr_001")

        assertTrue(result is Resource.Success)
        assertEquals(1, (result as Resource.Success).data)
        assertEquals("SYNCED", fakeTaskDao.tasks.single().syncStatus)
        assertTrue(fakeSyncQueueDao.ops.isEmpty())
    }

    @Test
    fun `syncPendingOperations on HTTP 401 clears token but preserves local tasks and photos`() = runTest {
        fakeTokenManager.saveSession("expired_token", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")
        fakeApiService.force401 = true

        val task = TaskEntity(
            taskId = "tsk_102", storeId = "str_101", userId = "usr_001",
            title = "Task", description = "", createdAt = "2026-03-31T10:00:00Z",
            updatedAt = "2026-03-31T10:00:00Z", syncStatus = "PENDING_INSERT"
        )
        fakeTaskDao.tasks.add(task)

        val syncOp = SyncQueueEntity(
            operationId = "op_102", entityType = "TASK", entityId = "tsk_102",
            operationType = "CREATE", userId = "usr_001", payloadJson = Gson().toJson(task),
            status = "PENDING", createdAt = "2026-03-31T10:00:00Z", sequenceNumber = 1L
        )
        fakeSyncQueueDao.ops.add(syncOp)
        val jpeg = queuePhoto(task.taskId, 2)

        val result = syncRepository.syncPendingOperations("usr_001")

        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message!!.contains("401"))
        assertNull(fakeTokenManager.getAuthToken()) // Token cleared
        assertEquals("usr_001", fakeTokenManager.getUserId()) // User ID preserved
        assertEquals(1, fakeTaskDao.tasks.size) // Task preserved
        assertEquals(2, fakeSyncQueueDao.ops.size)
        assertEquals("PENDING_UPLOAD", fakePhotoDao.photos.single().uploadStatus)
        assertTrue(jpeg.exists())
        assertEquals("PENDING_INSERT", fakeTaskDao.tasks.single().syncStatus)
    }

    @Test
    fun `syncPendingOperations on HTTP 500 error reverts status to PENDING for WorkManager retry`() = runTest {
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")
        fakeApiService.force500 = true

        val task = TaskEntity(
            taskId = "tsk_103", storeId = "str_101", userId = "usr_001",
            title = "Task", description = "", createdAt = "2026-03-31T10:00:00Z",
            updatedAt = "2026-03-31T10:00:00Z", syncStatus = "PENDING_INSERT"
        )
        fakeTaskDao.tasks.add(task)

        val syncOp = SyncQueueEntity(
            operationId = "op_103", entityType = "TASK", entityId = "tsk_103",
            operationType = "CREATE", userId = "usr_001", payloadJson = Gson().toJson(task),
            status = "PENDING", createdAt = "2026-03-31T10:00:00Z", sequenceNumber = 1L
        )
        fakeSyncQueueDao.ops.add(syncOp)

        val result = syncRepository.syncPendingOperations("usr_001")

        assertTrue(result is Resource.Error)
        assertEquals("PENDING", fakeSyncQueueDao.ops.single().status)
        assertEquals("PENDING_INSERT", fakeTaskDao.tasks.single().syncStatus)
    }

    @Test
    fun partialTaskAcknowledgementKeepsUnacknowledgedTaskAndStopsLaterWork() = runTest {
        signIn()
        queueTask("first", 1)
        queueTask("second", 2)
        val jpeg = queuePhoto("second", 3)
        queueTask("later", 4)
        fakeApiService.taskAcknowledgements = listOf("op_first")

        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Error)
        assertEquals(listOf("op_second", "op_photo", "op_later"), fakeSyncQueueDao.ops.map { it.operationId })
        assertTrue(fakeSyncQueueDao.ops.all { it.status == "PENDING" })
        assertEquals("SYNCED", fakeTaskDao.tasks.first().syncStatus)
        assertEquals(0, fakeApiService.photoRequests)
        assertTrue(jpeg.exists())

        fakeApiService.taskAcknowledgements = null
        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Success)
        assertEquals(listOf("op_second"), fakeApiService.taskRequests[1])
        assertEquals(listOf("op_later"), fakeApiService.taskRequests[2])
        assertTrue(fakeSyncQueueDao.ops.isEmpty())
    }

    @Test
    fun photoServerFailureKeepsLaterOperationsPendingAndPreservesTheJpeg() = runTest {
        signIn()
        queueTask("first", 1)
        val jpeg = queuePhoto("first", 2)
        queueTask("later", 3)
        fakeApiService.beforePhoto = { fakeApiService.force500 = true }

        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Error)
        assertEquals(listOf("op_photo", "op_later"), fakeSyncQueueDao.ops.map { it.operationId })
        assertTrue(fakeSyncQueueDao.ops.all { it.status == "PENDING" })
        assertTrue(jpeg.exists())

        fakeApiService.beforePhoto = {}
        fakeApiService.force500 = false
        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Success)
        assertEquals(listOf("op_photo", "op_photo"), fakeApiService.sentPhotoIds)
        assertEquals(listOf("first" to "photo", "first" to "photo"), fakeApiService.photoEntityRequests)
        assertTrue(fakeSyncQueueDao.ops.isEmpty())
    }

    @Test
    fun cancellationLeavesCurrentAndLaterOperationsRetryable() = runTest {
        signIn()
        queueTask("first", 1)
        val jpeg = queuePhoto("first", 2)
        queueTask("later", 3)
        val uploadStarted = CompletableDeferred<Unit>()
        val holdUpload = CompletableDeferred<Unit>()
        fakeApiService.beforePhoto = {
            uploadStarted.complete(Unit)
            holdUpload.await()
        }

        val job = launch { syncRepository.syncPendingOperations("usr_001") }
        uploadStarted.await()
        job.cancelAndJoin()
        assertEquals(listOf("op_photo", "op_later"), fakeSyncQueueDao.ops.map { it.operationId })
        assertTrue(fakeSyncQueueDao.ops.all { it.status == "PENDING" })
        assertEquals("PENDING_UPLOAD", fakePhotoDao.photos.single().uploadStatus)
        assertTrue(jpeg.exists())

        fakeApiService.beforePhoto = {}
        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Success)
        assertTrue(fakeSyncQueueDao.ops.isEmpty())
    }

    @Test
    fun staleClaimsAreRestoredInQueueOrderForOnlyTheActiveAccount() = runTest {
        signIn()
        queueTask("second", 2, queueStatus = "SYNCING")
        queueTask("first", 1, queueStatus = "SYNCING")
        queueTask("other", 3, userId = "usr_002", queueStatus = "SYNCING")
        fakeApiService.force500 = true

        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Error)
        assertEquals(listOf("op_first", "op_second"), fakeApiService.taskRequests.single())
        assertEquals(listOf("PENDING", "PENDING", "SYNCING"), fakeSyncQueueDao.ops.map { it.status })
        assertEquals(listOf("PENDING_INSERT", "PENDING_INSERT", "SYNCING"), fakeTaskDao.tasks.map { it.syncStatus })
    }

    @Test
    fun workerForAnotherAccountDoesNotSendQueuedRecords() = runTest {
        signIn()
        queueTask("other", 1, userId = "usr_002")

        assertTrue(syncRepository.syncPendingOperations("usr_002") is Resource.Error)
        assertTrue(fakeApiService.taskRequests.isEmpty())
        assertEquals("PENDING", fakeSyncQueueDao.ops.single().status)
    }

    @Test
    fun localMockSessionKeepsOfflineDataAndDoesNotAttemptBackendSync() = runTest {
        val localToken = "mock_token_fixture"
        signIn(token = localToken)
        queueTask("local", 1)

        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Error)
        assertEquals(localToken, fakeTokenManager.getAuthToken())
        assertEquals(emptyList<List<String>>(), fakeApiService.taskRequests)
        assertEquals("PENDING", fakeSyncQueueDao.ops.single().status)

        signIn()
        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Success)
        assertEquals(listOf(listOf("op_local")), fakeApiService.taskRequests)
        assertEquals(listOf(listOf("local")), fakeApiService.taskEntityRequests)
    }

    @Test
    fun delayedUnauthorizedResponseCannotClearNewLogin() = runTest {
        signIn()
        queueTask("first", 1)
        fakeApiService.force401 = true
        fakeApiService.beforeTasks = { signIn(token = "new-session", userId = "usr_002") }

        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Error)
        assertEquals("new-session", fakeTokenManager.getAuthToken())
        assertEquals("usr_002", fakeTokenManager.getUserId())
        assertEquals("PENDING", fakeSyncQueueDao.ops.single().status)
    }

    @Test
    fun photoRequiresMatchingAcknowledgementAndCanRetryUsingSameId() = runTest {
        signIn()
        val jpeg = queuePhoto("first", 1)
        fakeApiService.photoAckId = "wrong-id"

        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Error)
        assertEquals("op_photo", fakeSyncQueueDao.ops.single().operationId)
        assertEquals("PENDING_UPLOAD", fakePhotoDao.photos.single().uploadStatus)
        assertTrue(jpeg.exists())

        fakeApiService.photoAckId = null
        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Success)
        assertEquals(listOf("op_photo", "op_photo"), fakeApiService.sentPhotoIds)
        assertTrue(fakeSyncQueueDao.ops.isEmpty())
        assertEquals("UPLOADED", fakePhotoDao.photos.single().uploadStatus)
        assertTrue(jpeg.exists())
    }

    @Test
    fun missingLocalJpegDoesNotAcknowledgeOrDiscardUpload() = runTest {
        signIn()
        val jpeg = queuePhoto("first", 1)
        assertTrue(jpeg.delete())

        assertTrue(syncRepository.syncPendingOperations("usr_001") is Resource.Error)
        assertEquals(0, fakeApiService.photoRequests)
        assertEquals("op_photo", fakeSyncQueueDao.ops.single().operationId)
        assertEquals("UPLOAD_FAILED", fakePhotoDao.photos.single().uploadStatus)
    }

    private fun signIn(token: String = "current-session", userId: String = "usr_001") {
        fakeTokenManager.saveSession(token, userId, "agent", "Test Agent", "FIELD_AGENT")
    }

    private fun queueTask(id: String, sequence: Long, userId: String = "usr_001", queueStatus: String = "PENDING") {
        val task = TaskEntity(
            taskId = id, storeId = "str_101", userId = userId, title = id, description = "",
            createdAt = "2026-11-25T10:00:00Z", updatedAt = "2026-11-25T10:00:00Z",
            syncStatus = if (queueStatus == "SYNCING") "SYNCING" else "PENDING_INSERT"
        )
        fakeTaskDao.tasks.add(task)
        fakeSyncQueueDao.ops.add(SyncQueueEntity(
            operationId = "op_" + id, entityType = "TASK", entityId = id, operationType = "CREATE",
            userId = userId, payloadJson = Gson().toJson(task), status = queueStatus,
            createdAt = task.createdAt, sequenceNumber = sequence
        ))
    }

    private fun queuePhoto(taskId: String, sequence: Long): File {
        val jpeg = temp.newFile("photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val createdAt = "2026-11-25T10:00:00Z"
        val photo = PhotoEvidenceEntity(
            photoId = "photo", taskId = taskId, userId = "usr_001", localFilePath = jpeg.path,
            fileSizeBytes = jpeg.length(), uploadStatus = "PENDING_UPLOAD", createdAt = createdAt
        )
        fakePhotoDao.photos.add(photo)
        fakeSyncQueueDao.ops.add(SyncQueueEntity(
            operationId = "op_photo", entityType = "PHOTO", entityId = photo.photoId,
            operationType = "UPLOAD_PHOTO", userId = photo.userId, payloadJson = Gson().toJson(photo),
            status = "PENDING", createdAt = createdAt, sequenceNumber = sequence
        ))
        return jpeg
    }

    // --- Fakes ---
    private class FakeSyncQueueDao : SyncQueueDao {
        val ops = mutableListOf<SyncQueueEntity>()

        override fun getPendingOperations(userId: String): Flow<List<SyncQueueEntity>> = flowOf(ops.filter { it.userId == userId && it.status == "PENDING" })
        override fun getPendingOperationsSync(userId: String): List<SyncQueueEntity> = ops.filter { it.userId == userId && it.status in setOf("PENDING", "SYNCING", "FAILED") }.sortedBy { it.sequenceNumber }
        override fun getMaxSequenceNumber(): Long? = ops.maxOfOrNull { it.sequenceNumber }
        override fun insertOperation(operation: SyncQueueEntity) { ops.removeAll { it.operationId == operation.operationId }; ops.add(operation) }
        override fun updateOperationStatus(operationId: String, status: String, errorMessage: String?) {
            val idx = ops.indexOfFirst { it.operationId == operationId }
            if (idx != -1) ops[idx] = ops[idx].copy(status = status, errorMessage = errorMessage)
        }
        override fun deleteOperation(operationId: String) { ops.removeAll { it.operationId == operationId } }
        override fun deleteCompletedOperations(operationIds: List<String>) { ops.removeAll { operationIds.contains(it.operationId) } }
    }

    private class FakeTaskDao : TaskDao {
        val tasks = mutableListOf<TaskEntity>()
        override fun getTasksForStore(userId: String, storeId: String): Flow<List<TaskEntity>> = flowOf(tasks)
        override fun getTasksForStoreSync(userId: String, storeId: String): List<TaskEntity> = tasks
        override fun getTaskById(taskId: String, userId: String, storeId: String): Flow<TaskEntity?> = flowOf(tasks.find { it.taskId == taskId })
        override fun getTaskByIdSync(taskId: String, userId: String, storeId: String): TaskEntity? = tasks.find { it.taskId == taskId }
        override fun insertTask(task: TaskEntity) { tasks.removeAll { it.taskId == task.taskId }; tasks.add(task) }
        override fun updateTask(task: TaskEntity) { tasks.removeAll { it.taskId == task.taskId }; tasks.add(task) }
        override fun updateTaskSyncStatus(taskId: String, syncStatus: String, syncError: String?) {
            val idx = tasks.indexOfFirst { it.taskId == taskId }
            if (idx != -1) tasks[idx] = tasks[idx].copy(syncStatus = syncStatus, syncError = syncError)
        }
        override fun insertSyncQueue(syncOp: SyncQueueEntity) {}
    }

    private class FakePhotoEvidenceDao : PhotoEvidenceDao {
        val photos = mutableListOf<PhotoEvidenceEntity>()
        override fun getPhotosForTask(taskId: String, userId: String): Flow<List<PhotoEvidenceEntity>> = flowOf(photos)
        override fun getPhotosForTaskSync(taskId: String, userId: String): List<PhotoEvidenceEntity> = photos
        override fun getPhotoById(photoId: String, userId: String): PhotoEvidenceEntity? = photos.find { it.photoId == photoId }
        override fun getAllLocalPhotoFilePaths(): List<String> = photos.map { it.localFilePath }
        override fun insertPhoto(photo: PhotoEvidenceEntity) { photos.add(photo) }
        override fun updatePhotoUploadStatus(photoId: String, uploadStatus: String, remoteUrl: String?) {
            val idx = photos.indexOfFirst { it.photoId == photoId }
            if (idx != -1) photos[idx] = photos[idx].copy(uploadStatus = uploadStatus, remoteUrl = remoteUrl)
        }
        override fun insertSyncQueue(syncOp: SyncQueueEntity) {}
        override fun deletePhoto(photo: PhotoEvidenceEntity) { photos.remove(photo) }
    }

    private class FakeTokenManager : ITokenManager {
        private var token: String? = null
        private var userId: String? = null
        override fun saveSession(token: String, userId: String, username: String, name: String, role: String) { this.token = token; this.userId = userId }
        override fun getAuthToken(): String? = token
        override fun getUserId(): String? = userId
        override fun getUsername(): String? = "agent1"
        override fun getUserName(): String? = "Alex Mercer"
        override fun getUserRole(): String? = "FIELD_AGENT"
        override fun saveSelectedStoreId(storeId: String?) {}
        override fun getSelectedStoreId(): String? = "str_101"
        override fun isLoggedIn(): Boolean = !token.isNullOrBlank()
        override fun clearSessionTokenOnly() { this.token = null }
        override fun handle401(requestToken: String?): Boolean {
            if (requestToken == null || requestToken != token) return false
            clearSessionTokenOnly()
            return true
        }
    }

    private class FakeApiService : ApiService {
        var force401 = false
        var force500 = false
        var taskAcknowledgements: List<String>? = null
        var beforeTasks: suspend () -> Unit = {}
        var beforePhoto: suspend () -> Unit = {}
        var photoAckId: String? = null
        val taskRequests = mutableListOf<List<String>>()
        val sentPhotoIds = mutableListOf<String>()
        val photoEntityRequests = mutableListOf<Pair<String, String>>()
        val taskEntityRequests = mutableListOf<List<String>>()
        val photoRequests get() = sentPhotoIds.size

        override suspend fun login(request: LoginRequest): Response<ApiResponse<LoginResponse>> = Response.success(ApiResponse(success = true, data = null))
        override suspend fun logout(token: String): Response<ApiResponse<Map<String, String>>> = Response.success(ApiResponse(success = true, data = emptyMap()))
        override suspend fun getAssignedStores(token: String, query: String?): Response<ApiResponse<List<StoreDto>>> = Response.success(ApiResponse(success = true, data = emptyList()))

        override suspend fun syncTasks(token: String, request: SyncBatchRequest): Response<ApiResponse<SyncBatchResponse>> {
            taskRequests.add(request.operations.map { it.operationId })
            taskEntityRequests.add(request.operations.map { it.entityId })
            beforeTasks()
            if (force401) return Response.error(401, "Unauthorized".toResponseBody(null))
            if (force500) return Response.error(500, "Server Error".toResponseBody(null))

            val succeededIds = taskAcknowledgements ?: request.operations.map { it.operationId }
            return Response.success(
                ApiResponse(
                    success = true,
                    data = SyncBatchResponse(
                        processedCount = succeededIds.size,
                        succeededOperationIds = succeededIds,
                        failedOperations = emptyList()
                    )
                )
            )
        }

        override suspend fun uploadPhoto(token: String, operationId: RequestBody, photoId: RequestBody, taskId: RequestBody, file: MultipartBody.Part): Response<ApiResponse<PhotoUploadResponse>> {
            val sentOperationId = operationId.asText()
            sentPhotoIds.add(sentOperationId)
            photoEntityRequests.add(taskId.asText() to photoId.asText())
            beforePhoto()
            if (force401) return Response.error(401, "Unauthorized".toResponseBody(null))
            if (force500) return Response.error(500, "Server Error".toResponseBody(null))

            return Response.success(
                ApiResponse(
                    success = true,
                    data = PhotoUploadResponse(
                        photoId = photoId.asText(),
                        taskId = taskId.asText(),
                        operationId = photoAckId ?: sentOperationId,
                        remoteUrl = "http://localhost:3000/v1/uploads/pho_001.jpg",
                        status = "UPLOADED"
                    )
                )
            )
        }
        private fun RequestBody.asText(): String = okio.Buffer().also { writeTo(it) }.readUtf8()
    }
}
