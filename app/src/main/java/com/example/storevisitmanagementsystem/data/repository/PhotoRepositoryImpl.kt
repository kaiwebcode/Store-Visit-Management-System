package com.example.storevisitmanagementsystem.data.repository

import androidx.room.withTransaction
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.db.dao.PhotoEvidenceDao
import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity
import com.example.storevisitmanagementsystem.util.ImageStorageManager
import com.example.storevisitmanagementsystem.util.Resource
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class PhotoRepositoryImpl(
    private val photoDao: PhotoEvidenceDao,
    private val appDatabase: AppDatabase? = null,
    private val imageStorageManager: ImageStorageManager,
    private val gson: Gson = Gson(),
    private val findActiveTask: (String, String, String) -> TaskEntity? =
        requireNotNull(appDatabase).taskDao()::getTaskByIdSync
) : IPhotoRepository {

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    private fun currentTimestamp(): String {
        return isoDateFormat.format(Date())
    }

    override fun getPhotosForTask(taskId: String, userId: String): Flow<List<PhotoEvidenceEntity>> {
        return photoDao.getPhotosForTask(taskId, userId)
    }

    override fun createTempCaptureFile(userId: String): File {
        return imageStorageManager.createTempImageFile(userId)
    }

    private suspend fun runInTransaction(block: suspend () -> Unit) {
        if (appDatabase != null) {
            appDatabase.withTransaction { block() }
        } else {
            block()
        }
    }

    override suspend fun processAndSavePhoto(
        tempFile: File,
        taskId: String,
        userId: String,
        storeId: String,
        isSessionCurrent: () -> Boolean
    ): Resource<PhotoEvidenceEntity> = withContext(Dispatchers.IO) {
        if (!isSessionCurrent()) return@withContext Resource.Error("The session or selected store has changed.")

        val photoId = "pho_${UUID.randomUUID().toString().take(12)}"
        var finalFile: File? = null
        var persisted = false
        try {
            val compressedFile = imageStorageManager.compressAndSavePhoto(tempFile, userId)
            finalFile = compressedFile
            val timestamp = currentTimestamp()
            val photo = PhotoEvidenceEntity(
                photoId = photoId,
                taskId = taskId,
                userId = userId,
                localFilePath = compressedFile.absolutePath,
                fileSizeBytes = compressedFile.length(),
                uploadStatus = "PENDING_UPLOAD",
                createdAt = timestamp
            )
            val syncOp = SyncQueueEntity(
                operationId = "op_${UUID.randomUUID().toString().take(12)}",
                entityType = "PHOTO",
                entityId = photoId,
                operationType = "UPLOAD_PHOTO",
                userId = userId,
                payloadJson = gson.toJson(photo),
                status = "PENDING",
                createdAt = timestamp
            )

            runInTransaction {
                check(isSessionCurrent()) { "The session or selected store has changed." }
                check(findActiveTask(taskId, userId, storeId) != null) { "Task is unavailable for this account and store." }
                photoDao.insertPhoto(photo)
                photoDao.insertSyncQueue(syncOp)
            }
            persisted = true
            Resource.Success(photo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("Failed to save photo: ${e.localizedMessage}")
        } finally {
            val file = finalFile
            if (!persisted && file != null) {
                withContext(NonCancellable) {
                    // Cancellation can arrive after commit. Keep any file referenced by Room.
                    runCatching { photoDao.getPhotoById(photoId, userId) }.onSuccess { saved ->
                        if (saved == null) file.delete()
                    }
                }
            }
        }
    }

    override suspend fun reconcileOrphanedPhotos(userId: String) {
        imageStorageManager.reconcileOrphanedTempFiles(userId)
    }
}
