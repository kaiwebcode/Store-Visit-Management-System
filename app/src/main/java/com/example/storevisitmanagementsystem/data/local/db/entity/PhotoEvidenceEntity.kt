package com.example.storevisitmanagementsystem.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photo_evidence",
    indices = [
        Index(value = ["task_id", "user_id"]),
        Index(value = ["upload_status"])
    ]
)
data class PhotoEvidenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "photo_id")
    val photoId: String,

    @ColumnInfo(name = "task_id")
    val taskId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "local_file_path")
    val localFilePath: String,

    @ColumnInfo(name = "remote_url")
    val remoteUrl: String? = null,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0L,

    @ColumnInfo(name = "mime_type")
    val mimeType: String = "image/jpeg",

    @ColumnInfo(name = "upload_status")
    val uploadStatus: String = "PENDING_UPLOAD",

    @ColumnInfo(name = "created_at")
    val createdAt: String
)
