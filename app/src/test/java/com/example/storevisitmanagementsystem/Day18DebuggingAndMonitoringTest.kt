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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.File
import java.io.IOException

/**
 * Day 18 Regression Tests:
 * Validates recoverable failures, diagnosis mechanisms, local data preservation,
 * session expiry handling, and idempotent replay across network and server faults.
 */
class Day18DebuggingAndMonitoringTest {

    private lateinit var fakeSyncQueueDao: TestSyncQueueDao
    private lateinit var fakeTaskDao: TestTaskDao
    private lateinit var fakePhotoDao: TestPhotoEvidenceDao
    private lateinit var fakeApiService: TestApiService
    private lateinit var fakeTokenManager: TestTokenManager
    private lateinit var syncRepository: SyncRepositoryImpl

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        fakeSyncQueueDao = TestSyncQueueDao()
        fakeTaskDao = TestTaskDao()
        fakePhotoDao = TestPhotoEvidenceDao()
        fakeApiService = TestApiService()
        fakeTokenManager = TestTokenManager()

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
    fun `Scenario A - Network interruption during sync preserves queue and permits recovery on network restoration`() = runTest {
        signIn("valid_token", "usr_001")
        createPendingTask("tsk_net_01", "usr_001")
        val photoFile = createPendingPhoto("pho_net_01", "tsk_net_01", "usr_001")

        // 1. Simulate network failure (e.g. Wi-Fi disconnected / IOException)
        fakeApiService.throwNetworkError = true

        val resultDisconnect = syncRepository.syncPendingOperations("usr_001")
        assertTrue("Network error must return Resource.Error", resultDisconnect is Resource.Error)
        assertTrue(
            "Error message should mention Network failure",
            (resultDisconnect as Resource.Error).message!!.contains("Network failure")
        )

        // Verify local unacknowledged data is completely preserved
        assertEquals("Task queue ops must remain pending", 2, fakeSyncQueueDao.ops.size)
        assertTrue("Queue items must be PENDING", fakeSyncQueueDao.ops.all { it.status == "PENDING" })
        assertEquals("Task status must remain PENDING_INSERT", "PENDING_INSERT", fakeTaskDao.tasks.single().syncStatus)
        assertEquals("Photo upload status must remain PENDING_UPLOAD", "PENDING_UPLOAD", fakePhotoDao.photos.single().uploadStatus)
        assertTrue("Photo file on disk must persist", photoFile.exists())

        // 2. Simulate network restoration (connectivity returns)
        fakeApiService.throwNetworkError = false

        val resultRestore = syncRepository.syncPendingOperations("usr_001")
        assertTrue("Recovery sync must return Resource.Success", resultRestore is Resource.Success)
        assertEquals("2 operations processed", 2, (resultRestore as Resource.Success).data)

        // Verify local queue is cleared and entities updated
        assertTrue("Sync queue must be empty after acknowledged recovery", fakeSyncQueueDao.ops.isEmpty())
        assertEquals("Task syncStatus must be SYNCED", "SYNCED", fakeTaskDao.tasks.single().syncStatus)
        assertEquals("Photo uploadStatus must be UPLOADED", "UPLOADED", fakePhotoDao.photos.single().uploadStatus)
        assertNotNull("Remote URL must be saved", fakePhotoDao.photos.single().remoteUrl)
    }

    @Test
    fun `Scenario B - HTTP 500 and 504 server failures maintain queue state and allow duplicate-free retry`() = runTest {
        signIn("valid_token", "usr_001")
        val task = createPendingTask("tsk_srv_500", "usr_001")

        // 1. Simulate HTTP 500 Server Error
        fakeApiService.httpErrorCode = 500

        val result500 = syncRepository.syncPendingOperations("usr_001")
        assertTrue("HTTP 500 must return Resource.Error", result500 is Resource.Error)
        assertEquals("PENDING", fakeSyncQueueDao.ops.single().status)
        assertEquals("PENDING_INSERT", fakeTaskDao.tasks.single().syncStatus)

        // 2. Simulate HTTP 504 Gateway Timeout
        fakeApiService.httpErrorCode = 504

        val result504 = syncRepository.syncPendingOperations("usr_001")
        assertTrue("HTTP 504 must return Resource.Error", result504 is Resource.Error)
        assertEquals("PENDING", fakeSyncQueueDao.ops.single().status)

        // 3. Server recovers (HTTP 200) -> Retry succeeds without duplicates
        fakeApiService.httpErrorCode = null

        val resultSuccess = syncRepository.syncPendingOperations("usr_001")
        assertTrue("Recovery sync must succeed", resultSuccess is Resource.Success)
        assertEquals(1, (resultSuccess as Resource.Success).data)
        assertEquals("SYNCED", fakeTaskDao.tasks.single().syncStatus)
        assertTrue(fakeSyncQueueDao.ops.isEmpty())

        // Confirm server received exactly the single operationId without duplication
        assertEquals(1, fakeApiService.syncTaskRequests.size)
        assertEquals("op_tsk_srv_500", fakeApiService.syncTaskRequests.single().operations.single().operationId)
    }

    @Test
    fun `Scenario C - Session expiry (HTTP 401) clears auth token but preserves pending local data`() = runTest {
        signIn("expired_session_token", "usr_401")
        val task = createPendingTask("tsk_sec_401", "usr_401")
        val photoFile = createPendingPhoto("pho_sec_401", "tsk_sec_401", "usr_401")

        // 1. Simulate HTTP 401 Unauthorized
        fakeApiService.httpErrorCode = 401

        val result401 = syncRepository.syncPendingOperations("usr_401")
        assertTrue("HTTP 401 must return Resource.Error", result401 is Resource.Error)
        assertTrue((result401 as Resource.Error).message!!.contains("401"))

        // Verify active token is invalidated
        assertNull("Auth token must be cleared on 401", fakeTokenManager.getAuthToken())
        assertEquals("Active User ID remains preserved in prefs", "usr_401", fakeTokenManager.getUserId())

        // Verify local queued task, photo entity, and physical file remain 100% intact
        assertEquals("Queue operations preserved", 2, fakeSyncQueueDao.ops.size)
        assertEquals("PENDING_INSERT", fakeTaskDao.tasks.single().syncStatus)
        assertEquals("PENDING_UPLOAD", fakePhotoDao.photos.single().uploadStatus)
        assertTrue("Local JPEG file preserved on disk", photoFile.exists())

        // 2. Simulate User Re-login -> Save new token -> Sync succeeds seamlessly
        signIn("new_valid_token_2026", "usr_401")
        fakeApiService.httpErrorCode = null

        val resultReLoginSync = syncRepository.syncPendingOperations("usr_401")
        assertTrue("Sync post re-login must succeed", resultReLoginSync is Resource.Success)
        assertEquals(2, (resultReLoginSync as Resource.Success).data)
        assertEquals("SYNCED", fakeTaskDao.tasks.single().syncStatus)
        assertEquals("UPLOADED", fakePhotoDao.photos.single().uploadStatus)
    }

    @Test
    fun `Scenario D - App restart simulation preserves queued offline records`() = runTest {
        signIn("token_restart", "usr_restart")
        createPendingTask("tsk_restart_01", "usr_restart")
        val photoFile = createPendingPhoto("pho_restart_01", "tsk_restart_01", "usr_restart")

        // Verify initial state
        assertEquals(2, fakeSyncQueueDao.ops.size)
        assertEquals(1, fakeTaskDao.tasks.size)
        assertEquals(1, fakePhotoDao.photos.size)

        // Simulate app kill / repository recreation (new instance reading existing DAO tables)
        val freshSyncRepo = SyncRepositoryImpl(
            syncQueueDao = fakeSyncQueueDao,
            taskDao = fakeTaskDao,
            photoEvidenceDao = fakePhotoDao,
            apiService = fakeApiService,
            tokenManager = fakeTokenManager,
            appDatabase = null
        )

        // Perform sync using fresh repository instance
        val syncResult = freshSyncRepo.syncPendingOperations("usr_restart")
        assertTrue("Post-restart sync must succeed", syncResult is Resource.Success)
        assertEquals(2, (syncResult as Resource.Success).data)
        assertTrue("Sync queue cleared post-acknowledgement", fakeSyncQueueDao.ops.isEmpty())
        assertEquals("SYNCED", fakeTaskDao.tasks.single().syncStatus)
        assertEquals("UPLOADED", fakePhotoDao.photos.single().uploadStatus)
        assertTrue("Photo file preserved", photoFile.exists())
    }

    @Test
    fun `Idempotent replay - duplicate server acknowledgements do not double-process tasks or photos`() = runTest {
        signIn("token_idempotent", "usr_idempotent")
        val task = createPendingTask("tsk_idem_01", "usr_idempotent")

        val result1 = syncRepository.syncPendingOperations("usr_idempotent")
        assertTrue(result1 is Resource.Success)
        assertEquals(1, (result1 as Resource.Success).data)
        assertEquals("SYNCED", fakeTaskDao.tasks.single().syncStatus)
        assertTrue(fakeSyncQueueDao.ops.isEmpty())

        // Triggering sync again when queue is empty does not call server or cause errors
        val result2 = syncRepository.syncPendingOperations("usr_idempotent")
        assertTrue(result2 is Resource.Success)
        assertEquals(0, (result2 as Resource.Success).data)
        assertEquals(1, fakeApiService.syncTaskRequests.size) // No extra call
    }

    // --- Helpers ---

    private fun signIn(token: String, userId: String) {
        fakeTokenManager.saveSession(token, userId, "agent_test", "Test Agent", "FIELD_AGENT")
    }

    private fun createPendingTask(taskId: String, userId: String): TaskEntity {
        val task = TaskEntity(
            taskId = taskId,
            storeId = "str_101",
            userId = userId,
            title = "Test Task $taskId",
            description = "Description",
            category = "GENERAL",
            priority = "HIGH",
            status = "PENDING",
            createdAt = "2026-11-26T10:00:00Z",
            updatedAt = "2026-11-26T10:00:00Z",
            syncStatus = "PENDING_INSERT"
        )
        fakeTaskDao.tasks.add(task)
        fakeSyncQueueDao.ops.add(
            SyncQueueEntity(
                operationId = "op_$taskId",
                entityType = "TASK",
                entityId = taskId,
                operationType = "CREATE",
                userId = userId,
                payloadJson = Gson().toJson(task),
                status = "PENDING",
                createdAt = task.createdAt,
                sequenceNumber = (fakeSyncQueueDao.ops.size + 1).toLong()
            )
        )
        return task
    }

    private fun createPendingPhoto(photoId: String, taskId: String, userId: String): File {
        val jpegFile = tempFolder.newFile("$photoId.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        val photo = PhotoEvidenceEntity(
            photoId = photoId,
            taskId = taskId,
            userId = userId,
            localFilePath = jpegFile.absolutePath,
            fileSizeBytes = jpegFile.length(),
            uploadStatus = "PENDING_UPLOAD",
            createdAt = "2026-11-26T10:05:00Z"
        )
        fakePhotoDao.photos.add(photo)
        fakeSyncQueueDao.ops.add(
            SyncQueueEntity(
                operationId = "op_$photoId",
                entityType = "PHOTO",
                entityId = photoId,
                operationType = "UPLOAD_PHOTO",
                userId = userId,
                payloadJson = Gson().toJson(photo),
                status = "PENDING",
                createdAt = photo.createdAt,
                sequenceNumber = (fakeSyncQueueDao.ops.size + 1).toLong()
            )
        )
        return jpegFile
    }

    // --- Fakes ---

    private class TestSyncQueueDao : SyncQueueDao {
        val ops = mutableListOf<SyncQueueEntity>()

        override fun getPendingOperations(userId: String): Flow<List<SyncQueueEntity>> =
            flowOf(ops.filter { it.userId == userId && it.status == "PENDING" })

        override fun getPendingOperationsSync(userId: String): List<SyncQueueEntity> =
            ops.filter { it.userId == userId && it.status in setOf("PENDING", "SYNCING", "FAILED") }
                .sortedBy { it.sequenceNumber }

        override fun getMaxSequenceNumber(): Long? = ops.maxOfOrNull { it.sequenceNumber }

        override fun insertOperation(operation: SyncQueueEntity) {
            ops.removeAll { it.operationId == operation.operationId }
            ops.add(operation)
        }

        override fun updateOperationStatus(operationId: String, status: String, errorMessage: String?) {
            val idx = ops.indexOfFirst { it.operationId == operationId }
            if (idx != -1) ops[idx] = ops[idx].copy(status = status, errorMessage = errorMessage)
        }

        override fun deleteOperation(operationId: String) {
            ops.removeAll { it.operationId == operationId }
        }

        override fun deleteCompletedOperations(operationIds: List<String>) {
            ops.removeAll { operationIds.contains(it.operationId) }
        }
    }

    private class TestTaskDao : TaskDao {
        val tasks = mutableListOf<TaskEntity>()

        override fun getTasksForStore(userId: String, storeId: String): Flow<List<TaskEntity>> = flowOf(tasks)
        override fun getTasksForStoreSync(userId: String, storeId: String): List<TaskEntity> = tasks
        override fun getTaskById(taskId: String, userId: String, storeId: String): Flow<TaskEntity?> =
            flowOf(tasks.find { it.taskId == taskId })
        override fun getTaskByIdSync(taskId: String, userId: String, storeId: String): TaskEntity? =
            tasks.find { it.taskId == taskId }
        override fun insertTask(task: TaskEntity) { tasks.removeAll { it.taskId == task.taskId }; tasks.add(task) }
        override fun updateTask(task: TaskEntity) { tasks.removeAll { it.taskId == task.taskId }; tasks.add(task) }
        override fun updateTaskSyncStatus(taskId: String, syncStatus: String, syncError: String?) {
            val idx = tasks.indexOfFirst { it.taskId == taskId }
            if (idx != -1) tasks[idx] = tasks[idx].copy(syncStatus = syncStatus, syncError = syncError)
        }
        override fun insertSyncQueue(syncOp: SyncQueueEntity) {}
    }

    private class TestPhotoEvidenceDao : PhotoEvidenceDao {
        val photos = mutableListOf<PhotoEvidenceEntity>()

        override fun getPhotosForTask(taskId: String, userId: String): Flow<List<PhotoEvidenceEntity>> = flowOf(photos)
        override fun getPhotosForTaskSync(taskId: String, userId: String): List<PhotoEvidenceEntity> = photos
        override fun getPhotoById(photoId: String, userId: String): PhotoEvidenceEntity? =
            photos.find { it.photoId == photoId }
        override fun getAllLocalPhotoFilePaths(): List<String> = photos.map { it.localFilePath }
        override fun insertPhoto(photo: PhotoEvidenceEntity) { photos.add(photo) }
        override fun updatePhotoUploadStatus(photoId: String, uploadStatus: String, remoteUrl: String?) {
            val idx = photos.indexOfFirst { it.photoId == photoId }
            if (idx != -1) photos[idx] = photos[idx].copy(uploadStatus = uploadStatus, remoteUrl = remoteUrl)
        }
        override fun insertSyncQueue(syncOp: SyncQueueEntity) {}
        override fun deletePhoto(photo: PhotoEvidenceEntity) { photos.remove(photo) }
    }

    private class TestTokenManager : ITokenManager {
        private var token: String? = null
        private var userId: String? = null

        override fun saveSession(token: String, userId: String, username: String, name: String, role: String) {
            this.token = token
            this.userId = userId
        }

        override fun getAuthToken(): String? = token
        override fun getUserId(): String? = userId
        override fun getUsername(): String? = "agent_test"
        override fun getUserName(): String? = "Test Agent"
        override fun getUserRole(): String? = "FIELD_AGENT"
        override fun saveSelectedStoreId(storeId: String?) {}
        override fun getSelectedStoreId(): String? = "str_101"
        override fun isLoggedIn(): Boolean = !token.isNullOrBlank()

        override fun clearSessionTokenOnly() {
            this.token = null
        }

        override fun handle401(requestToken: String?): Boolean {
            if (requestToken == null || requestToken != token) return false
            clearSessionTokenOnly()
            return true
        }
    }

    private class TestApiService : ApiService {
        var throwNetworkError = false
        var httpErrorCode: Int? = null
        val syncTaskRequests = mutableListOf<SyncBatchRequest>()

        override suspend fun login(request: LoginRequest): Response<ApiResponse<LoginResponse>> =
            Response.success(ApiResponse(success = true, data = null))

        override suspend fun logout(token: String): Response<ApiResponse<Map<String, String>>> =
            Response.success(ApiResponse(success = true, data = emptyMap()))

        override suspend fun getAssignedStores(token: String, query: String?): Response<ApiResponse<List<StoreDto>>> =
            Response.success(ApiResponse(success = true, data = emptyList()))

        override suspend fun syncTasks(token: String, request: SyncBatchRequest): Response<ApiResponse<SyncBatchResponse>> {
            if (throwNetworkError) throw IOException("Network failure: Host unreachable")
            httpErrorCode?.let { code ->
                return Response.error(code, "Simulated HTTP $code Error".toResponseBody(null))
            }

            syncTaskRequests.add(request)
            val succeededIds = request.operations.map { it.operationId }
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

        override suspend fun uploadPhoto(
            token: String,
            operationId: RequestBody,
            photoId: RequestBody,
            taskId: RequestBody,
            file: MultipartBody.Part
        ): Response<ApiResponse<PhotoUploadResponse>> {
            if (throwNetworkError) throw IOException("Network failure: Host unreachable during photo upload")
            httpErrorCode?.let { code ->
                return Response.error(code, "Simulated HTTP $code Error".toResponseBody(null))
            }

            val opIdStr = operationId.asText()
            val photoIdStr = photoId.asText()
            val taskIdStr = taskId.asText()

            return Response.success(
                ApiResponse(
                    success = true,
                    data = PhotoUploadResponse(
                        photoId = photoIdStr,
                        taskId = taskIdStr,
                        operationId = opIdStr,
                        remoteUrl = "http://localhost:3000/v1/uploads/$photoIdStr.jpg",
                        status = "UPLOADED"
                    )
                )
            )
        }

        private fun RequestBody.asText(): String = okio.Buffer().also { writeTo(it) }.readUtf8()
    }
}
