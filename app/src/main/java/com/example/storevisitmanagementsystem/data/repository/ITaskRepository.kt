package com.example.storevisitmanagementsystem.data.repository

import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.flow.Flow

interface ITaskRepository {
    fun getTasksForStore(userId: String, storeId: String): Flow<List<TaskEntity>>
    fun getTaskById(taskId: String, userId: String, storeId: String): Flow<TaskEntity?>
    suspend fun createTask(
        userId: String,
        storeId: String,
        title: String,
        description: String,
        category: String,
        priority: String,
        dueDate: String? = null,
        isSessionCurrent: () -> Boolean = { true }
    ): Resource<TaskEntity>

    suspend fun updateTask(
        userId: String,
        taskId: String,
        storeId: String,
        title: String,
        description: String,
        category: String,
        priority: String,
        dueDate: String? = null
    ): Resource<Unit>

    suspend fun toggleTaskCompletion(taskId: String, userId: String, storeId: String, isCompleted: Boolean): Resource<Unit>
    suspend fun softDeleteTask(taskId: String, userId: String, storeId: String): Resource<Unit>
}
