package com.example.storevisitmanagementsystem.data.repository

import androidx.room.withTransaction
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.db.dao.TaskDao
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.util.Resource
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val appDatabase: AppDatabase? = null,
    private val gson: Gson = Gson()
) : ITaskRepository {

    private fun currentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
    }

    override fun getTasksForStore(userId: String, storeId: String): Flow<List<TaskEntity>> {
        return taskDao.getTasksForStore(userId, storeId)
    }

    override fun getTaskById(taskId: String, userId: String, storeId: String): Flow<TaskEntity?> {
        return taskDao.getTaskById(taskId, userId, storeId)
    }

    private suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return if (appDatabase != null) {
            appDatabase.withTransaction { block() }
        } else {
            block()
        }
    }

    override suspend fun createTask(
        userId: String,
        storeId: String,
        title: String,
        description: String,
        category: String,
        priority: String,
        dueDate: String?,
        isSessionCurrent: () -> Boolean
    ): Resource<TaskEntity> {
        if (title.isBlank()) {
            return Resource.Error("Task title cannot be blank.")
        }
        if (storeId.isBlank() || userId.isBlank()) {
            return Resource.Error("Invalid store or user identity.")
        }

        val taskId = "tsk_${UUID.randomUUID().toString().take(12)}"
        val timestamp = currentTimestamp()

        val taskEntity = TaskEntity(
            taskId = taskId,
            storeId = storeId,
            userId = userId,
            title = title.trim(),
            description = description.trim(),
            category = category.ifBlank { "GENERAL" },
            priority = priority.ifBlank { "MEDIUM" },
            status = "PENDING",
            dueDate = dueDate,
            createdAt = timestamp,
            updatedAt = timestamp,
            syncStatus = "PENDING_INSERT",
            isDeleted = false
        )

        return try {
            runInTransaction {
                check(isSessionCurrent()) { "The session or selected store has changed." }
                val nextSeq = (appDatabase?.syncQueueDao()?.getMaxSequenceNumber() ?: 0L) + 1L
                val syncOp = SyncQueueEntity(
                    operationId = "op_${UUID.randomUUID().toString().take(12)}",
                    entityType = "TASK",
                    entityId = taskId,
                    operationType = "CREATE",
                    userId = userId,
                    payloadJson = gson.toJson(taskEntity),
                    status = "PENDING",
                    createdAt = timestamp,
                    sequenceNumber = nextSeq
                )
                taskDao.insertTask(taskEntity)
                taskDao.insertSyncQueue(syncOp)
            }
            Resource.Success(taskEntity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("Failed to save task: ${e.localizedMessage}")
        }
    }

    override suspend fun updateTask(
        userId: String,
        taskId: String,
        storeId: String,
        title: String,
        description: String,
        category: String,
        priority: String,
        dueDate: String?
    ): Resource<Unit> {
        if (title.isBlank()) {
            return Resource.Error("Task title cannot be blank.")
        }

        return mutateTask(taskId, userId, storeId, "UPDATE", "Failed to update task") { task, timestamp ->
            task.copy(
                title = title.trim(),
                description = description.trim(),
                category = category.ifBlank { task.category },
                priority = priority.ifBlank { task.priority },
                dueDate = dueDate ?: task.dueDate,
                updatedAt = timestamp,
                syncStatus = when (task.syncStatus) {
                    "SYNC_FAILED", "PENDING_INSERT" -> task.syncStatus
                    else -> "PENDING_UPDATE"
                }
            )
        }
    }

    override suspend fun toggleTaskCompletion(
        taskId: String,
        userId: String,
        storeId: String,
        isCompleted: Boolean
    ): Resource<Unit> {
        val status = if (isCompleted) "COMPLETED" else "PENDING"
        return mutateTask(taskId, userId, storeId, "UPDATE", "Failed to change task status") { task, timestamp ->
            if (task.status == status) {
                task
            } else {
                task.copy(
                    status = status,
                    updatedAt = timestamp,
                    syncStatus = when (task.syncStatus) {
                        "SYNC_FAILED", "PENDING_INSERT" -> task.syncStatus
                        else -> "PENDING_UPDATE"
                    }
                )
            }
        }
    }

    override suspend fun softDeleteTask(taskId: String, userId: String, storeId: String): Resource<Unit> {
        return mutateTask(taskId, userId, storeId, "DELETE", "Failed to delete task") { task, timestamp ->
            task.copy(
                isDeleted = true,
                updatedAt = timestamp,
                syncStatus = if (task.syncStatus == "SYNC_FAILED") task.syncStatus else "PENDING_DELETE"
            )
        }
    }

    private suspend fun mutateTask(
        taskId: String,
        userId: String,
        storeId: String,
        operationType: String,
        errorMessage: String,
        transform: (TaskEntity, String) -> TaskEntity
    ): Resource<Unit> {
        return try {
            runInTransaction {
                val task = taskDao.getTaskByIdSync(taskId, userId, storeId)
                    ?: return@runInTransaction Resource.Error("Task not found.")

                val timestamp = currentTimestamp()
                val updatedTask = transform(task, timestamp)
                if (updatedTask != task) {
                    taskDao.updateTask(updatedTask)
                    val nextSeq = (appDatabase?.syncQueueDao()?.getMaxSequenceNumber() ?: 0L) + 1L
                    taskDao.insertSyncQueue(
                        SyncQueueEntity(
                            operationId = "op_${UUID.randomUUID().toString().take(12)}",
                            entityType = "TASK",
                            entityId = taskId,
                            operationType = operationType,
                            userId = userId,
                            payloadJson = gson.toJson(updatedTask),
                            status = "PENDING",
                            createdAt = timestamp,
                            sequenceNumber = nextSeq
                        )
                    )
                }
                Resource.Success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Resource.Error("$errorMessage: ${e.localizedMessage}")
        }
    }
}
