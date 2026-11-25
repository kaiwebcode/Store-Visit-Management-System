package com.example.storevisitmanagementsystem.data.repository

import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.flow.Flow
import java.io.File

interface IPhotoRepository {
    fun getPhotosForTask(taskId: String, userId: String): Flow<List<PhotoEvidenceEntity>>
    fun createTempCaptureFile(userId: String): File
    suspend fun processAndSavePhoto(
        tempFile: File,
        taskId: String,
        userId: String,
        storeId: String,
        isSessionCurrent: () -> Boolean
    ): Resource<PhotoEvidenceEntity>
    suspend fun reconcileOrphanedPhotos(userId: String)
}
