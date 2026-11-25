package com.example.storevisitmanagementsystem

import com.example.storevisitmanagementsystem.data.local.db.dao.TaskDao
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.data.repository.TaskRepositoryImpl
import com.example.storevisitmanagementsystem.util.Resource
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TaskRepositoryTest {

    private lateinit var fakeTaskDao: FakeTaskDao
    private lateinit var repository: TaskRepositoryImpl

    @Before
    fun setUp() {
        fakeTaskDao = FakeTaskDao()
        repository = TaskRepositoryImpl(fakeTaskDao)
    }

    @Test
    fun `createTask with blank title returns error`() = runTest {
        val result = repository.createTask(
            userId = "usr_001",
            storeId = "str_101",
            title = "   ",
            description = "Test description",
            category = "SIGNAGE",
            priority = "HIGH"
        )

        assertTrue(result is Resource.Error)
        assertEquals("Task title cannot be blank.", (result as Resource.Error).message)
        assertEquals(0, fakeTaskDao.tasks.size)
        assertEquals(0, fakeTaskDao.syncOps.size)
    }

    @Test
    fun `createTask with valid input persists TaskEntity and SyncQueueEntity atomically`() = runTest {
        val result = repository.createTask(
            userId = "usr_001",
            storeId = "str_101",
            title = "Audit Entrance Display",
            description = "Check promotional posters.",
            category = "SIGNAGE",
            priority = "HIGH"
        )

        assertTrue(result is Resource.Success)
        val createdTask = (result as Resource.Success).data
        assertNotNull(createdTask)
        assertEquals("Audit Entrance Display", createdTask!!.title)
        assertEquals("PENDING", createdTask.status)
        assertEquals("SIGNAGE", createdTask.category)
        assertEquals("HIGH", createdTask.priority)

        assertEquals(1, fakeTaskDao.tasks.size)
        assertEquals(1, fakeTaskDao.syncOps.size)

        val syncOp = fakeTaskDao.syncOps[0]
        assertEquals("CREATE", syncOp.operationType)
        assertEquals("TASK", syncOp.entityType)
        assertEquals(createdTask.taskId, syncOp.entityId)
        assertEquals("usr_001", syncOp.userId)
    }

    @Test
    fun `toggleTaskCompletion updates task status and inserts sync operation`() = runTest {
        repository.createTask("usr_001", "str_101", "Initial Task", "Desc", "GENERAL", "MEDIUM")
        val task = fakeTaskDao.tasks.single().copy(
            updatedAt = "2026-03-31T10:00:00Z",
            syncStatus = "SYNCED"
        )
        fakeTaskDao.updateTask(task)

        val result = repository.toggleTaskCompletion(task.taskId, task.userId, task.storeId, isCompleted = true)

        assertTrue(result is Resource.Success)
        val updatedTask = fakeTaskDao.tasks.single()
        assertEquals("COMPLETED", updatedTask.status)
        assertEquals("PENDING_UPDATE", updatedTask.syncStatus)
        assertNotEquals(task.updatedAt, updatedTask.updatedAt)

        val syncOp = fakeTaskDao.syncOps.last()
        assertEquals(2, fakeTaskDao.syncOps.size)
        assertEquals("UPDATE", syncOp.operationType)
        assertEquals("PENDING", syncOp.status)
        assertEquals(updatedTask.updatedAt, syncOp.createdAt)
        assertEquals(updatedTask, Gson().fromJson(syncOp.payloadJson, TaskEntity::class.java))

        repository.toggleTaskCompletion(task.taskId, task.userId, task.storeId, isCompleted = false)
        assertEquals("PENDING", fakeTaskDao.tasks.single().status)
    }

    @Test
    fun `softDeleteTask marks isDeleted true and inserts DELETE sync operation`() = runTest {
        repository.createTask("usr_001", "str_101", "Task to Delete", "Desc", "GENERAL", "LOW")
        val task = fakeTaskDao.tasks[0]

        val deleteResult = repository.softDeleteTask(task.taskId, task.userId, task.storeId)

        assertTrue(deleteResult is Resource.Success)
        val deletedTask = fakeTaskDao.tasks[0]
        assertTrue(deletedTask.isDeleted)
        assertEquals("PENDING_DELETE", deletedTask.syncStatus)
        assertTrue(fakeTaskDao.getTasksForStoreSync(task.userId, task.storeId).isEmpty())
        assertTrue(repository.toggleTaskCompletion(task.taskId, task.userId, task.storeId, true) is Resource.Error)

        assertEquals(2, fakeTaskDao.syncOps.size)
        assertEquals("DELETE", fakeTaskDao.syncOps[1].operationType)
    }

    @Test
    fun `edit validates input and updates category and priority on the same task`() = runTest {
        repository.createTask("usr_001", "str_101", "Original", "Desc", "GENERAL", "MEDIUM")
        val task = fakeTaskDao.tasks.single()

        val invalid = repository.updateTask(task.userId, task.taskId, task.storeId, " ", "", "STOCK", "HIGH")
        assertTrue(invalid is Resource.Error)
        assertEquals(task, fakeTaskDao.tasks.single())
        assertEquals(1, fakeTaskDao.syncOps.size)

        val result = repository.updateTask(task.userId, task.taskId, task.storeId, "New title", "Revised", "STOCK", "HIGH")
        assertTrue(result is Resource.Success)
        val updated = fakeTaskDao.tasks.single()
        assertEquals(task.taskId, updated.taskId)
        assertEquals(task.createdAt, updated.createdAt)
        assertEquals(task.status, updated.status)
        assertEquals("New title", updated.title)
        assertEquals("Revised", updated.description)
        assertEquals("STOCK", updated.category)
        assertEquals("HIGH", updated.priority)
        assertEquals("PENDING_INSERT", updated.syncStatus)
        assertEquals(updated, Gson().fromJson(fakeTaskDao.syncOps.last().payloadJson, TaskEntity::class.java))
    }

    @Test
    fun `mutations reject another user or store without queuing changes`() = runTest {
        repository.createTask("usr_001", "str_101", "Original", "", "GENERAL", "MEDIUM")
        val task = fakeTaskDao.tasks.single()

        assertTrue(repository.softDeleteTask(task.taskId, "usr_002", task.storeId) is Resource.Error)
        assertTrue(repository.toggleTaskCompletion(task.taskId, task.userId, "str_102", true) is Resource.Error)
        assertEquals(task, fakeTaskDao.tasks.single())
        assertEquals(1, fakeTaskDao.syncOps.size)
    }

    private class FakeTaskDao : TaskDao {
        val tasks = mutableListOf<TaskEntity>()
        val syncOps = mutableListOf<SyncQueueEntity>()

        override fun getTasksForStore(userId: String, storeId: String): Flow<List<TaskEntity>> {
            return flowOf(tasks.filter { it.userId == userId && it.storeId == storeId && !it.isDeleted })
        }

        override fun getTasksForStoreSync(userId: String, storeId: String): List<TaskEntity> {
            return tasks.filter { it.userId == userId && it.storeId == storeId && !it.isDeleted }
        }

        override fun getTaskById(taskId: String, userId: String, storeId: String): Flow<TaskEntity?> {
            return flowOf(getTaskByIdSync(taskId, userId, storeId))
        }

        override fun getTaskByIdSync(taskId: String, userId: String, storeId: String): TaskEntity? {
            return tasks.find {
                it.taskId == taskId && it.userId == userId && it.storeId == storeId && !it.isDeleted
            }
        }

        override fun insertTask(task: TaskEntity) {
            tasks.removeAll { it.taskId == task.taskId }
            tasks.add(task)
        }

        override fun updateTask(task: TaskEntity) {
            tasks.removeAll { it.taskId == task.taskId }
            tasks.add(task)
        }

        override fun insertSyncQueue(syncOp: SyncQueueEntity) {
            syncOps.add(syncOp)
        }
    }
}
