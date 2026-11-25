package com.example.storevisitmanagementsystem

import com.example.storevisitmanagementsystem.data.local.db.dao.PhotoEvidenceDao
import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.data.repository.PhotoRepositoryImpl
import com.example.storevisitmanagementsystem.util.ImageStorageManager
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class PhotoRepositoryTest {

    private lateinit var fakePhotoDao: FakePhotoEvidenceDao
    private lateinit var fakeImageStorageManager: FakeImageStorageManager
    private lateinit var repository: PhotoRepositoryImpl

    @Before
    fun setUp() {
        fakePhotoDao = FakePhotoEvidenceDao()
        fakeImageStorageManager = FakeImageStorageManager()
        val task = TaskEntity(
            taskId = "tsk_1001", storeId = "str_101", userId = "usr_001",
            title = "Audit", description = "", createdAt = "2026-03-31T10:00:00Z",
            updatedAt = "2026-03-31T10:00:00Z"
        )
        repository = PhotoRepositoryImpl(fakePhotoDao, null, fakeImageStorageManager) { taskId, userId, storeId ->
            task.takeIf { taskId == it.taskId && userId == it.userId && storeId == it.storeId }
        }
    }

    @Test
    fun `processAndSavePhoto creates PhotoEvidenceEntity and SyncQueueEntity atomically`() = runTest {
        val tempFile = File.createTempFile("test_temp", ".tmp")
        tempFile.writeText("fake_image_content")

        val result = repository.processAndSavePhoto(
            tempFile = tempFile,
            taskId = "tsk_1001",
            userId = "usr_001",
            storeId = "str_101",
            isSessionCurrent = { true }
        )

        assertTrue(result is Resource.Success)
        val photo = (result as Resource.Success).data
        assertNotNull(photo)
        assertEquals("tsk_1001", photo!!.taskId)
        assertEquals("usr_001", photo.userId)
        assertEquals("PENDING_UPLOAD", photo.uploadStatus)

        assertEquals(1, fakePhotoDao.photos.size)
        assertEquals(1, fakePhotoDao.syncOps.size)

        val syncOp = fakePhotoDao.syncOps[0]
        assertEquals("UPLOAD_PHOTO", syncOp.operationType)
        assertEquals("PHOTO", syncOp.entityType)
        assertEquals(photo.photoId, syncOp.entityId)
        File(photo.localFilePath).delete()
    }

    @Test
    fun `photo save rejects a different store and removes the untracked JPEG`() = runTest {
        val capture = File.createTempFile("test_temp", ".tmp")
        val result = repository.processAndSavePhoto(capture, "tsk_1001", "usr_001", "str_102") { true }

        assertTrue(result is Resource.Error)
        assertTrue(fakePhotoDao.photos.isEmpty())
        assertTrue(fakePhotoDao.syncOps.isEmpty())
        assertFalse(requireNotNull(fakeImageStorageManager.lastCompressed).exists())
    }

    @Test
    fun `photo save rejects a session that changes during compression`() = runTest {
        val capture = File.createTempFile("test_temp", ".tmp")
        val result = repository.processAndSavePhoto(capture, "tsk_1001", "usr_001", "str_101") {
            fakeImageStorageManager.lastCompressed == null
        }

        assertTrue(result is Resource.Error)
        assertTrue(fakePhotoDao.photos.isEmpty())
        assertTrue(fakePhotoDao.syncOps.isEmpty())
        assertFalse(requireNotNull(fakeImageStorageManager.lastCompressed).exists())
    }

    private class FakePhotoEvidenceDao : PhotoEvidenceDao {
        val photos = mutableListOf<PhotoEvidenceEntity>()
        val syncOps = mutableListOf<SyncQueueEntity>()

        override fun getPhotosForTask(taskId: String, userId: String): Flow<List<PhotoEvidenceEntity>> {
            return flowOf(photos.filter { it.taskId == taskId && it.userId == userId })
        }

        override fun getPhotosForTaskSync(taskId: String, userId: String): List<PhotoEvidenceEntity> {
            return photos.filter { it.taskId == taskId && it.userId == userId }
        }

        override fun getPhotoById(photoId: String, userId: String): PhotoEvidenceEntity? {
            return photos.find { it.photoId == photoId && it.userId == userId }
        }

        override fun getAllLocalPhotoFilePaths(): List<String> {
            return photos.map { it.localFilePath }
        }

        override fun insertPhoto(photo: PhotoEvidenceEntity) {
            photos.add(photo)
        }

        override fun insertSyncQueue(syncOp: SyncQueueEntity) {
            syncOps.add(syncOp)
        }

        override fun deletePhoto(photo: PhotoEvidenceEntity) {
            photos.remove(photo)
        }
    }

    private class FakeImageStorageManager : ImageStorageManager(
        DummyContext()
    ) {
        var lastCompressed: File? = null
            private set

        override suspend fun compressAndSavePhoto(tempFile: File, userId: String): File {
            val finalFile = File.createTempFile("final_photo", ".jpg")
            finalFile.writeText("compressed_bytes")
            if (tempFile.exists()) tempFile.delete()
            lastCompressed = finalFile
            return finalFile
        }
    }

    private class DummyContext : android.content.ContextWrapper(null)
}
