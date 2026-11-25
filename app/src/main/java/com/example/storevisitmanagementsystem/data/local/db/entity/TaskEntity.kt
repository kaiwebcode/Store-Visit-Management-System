package com.example.storevisitmanagementsystem.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["user_id", "store_id"]),
        Index(value = ["sync_status"])
    ]
)
data class TaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,

    @ColumnInfo(name = "store_id")
    val storeId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "category")
    val category: String = "GENERAL",

    @ColumnInfo(name = "priority")
    val priority: String = "MEDIUM",

    @ColumnInfo(name = "status")
    val status: String = "PENDING",

    @ColumnInfo(name = "due_date")
    val dueDate: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "PENDING_INSERT",

    @ColumnInfo(name = "sync_error")
    val syncError: String? = null,

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
)
