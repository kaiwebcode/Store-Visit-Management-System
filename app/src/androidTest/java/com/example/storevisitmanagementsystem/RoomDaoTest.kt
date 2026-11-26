package com.example.storevisitmanagementsystem

import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.data.repository.TaskRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.IAuthRepository
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.ui.task.TaskDetailActivity
import com.example.storevisitmanagementsystem.ui.task.TaskViewModel
import com.example.storevisitmanagementsystem.util.Constants
import com.example.storevisitmanagementsystem.data.repository.PhotoRepositoryImpl
import com.example.storevisitmanagementsystem.util.ImageStorageManager
import com.example.storevisitmanagementsystem.util.Resource
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RoomDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun taskMutationsFromMainThreadUpdateActiveQueriesAndQueue() = runBlocking {
        val repository = TaskRepositoryImpl(db.taskDao(), db)
        val userId = "usr_001"
        val storeId = "str_101"
        val created = withContext(Dispatchers.Main) {
            repository.createTask(userId, storeId, "Audit Display", "Original", "SIGNAGE", "HIGH")
        }
        assertTrue(created is Resource.Success)
        val task = requireNotNull(created.data)
        assertEquals(task, repository.getTaskById(task.taskId, userId, storeId).first())
        assertEquals(listOf(task), repository.getTasksForStore(userId, storeId).first())
        assertNull(repository.getTaskById(task.taskId, "usr_002", storeId).first())
        assertNull(repository.getTaskById(task.taskId, userId, "str_102").first())

        val completion = withContext(Dispatchers.Main) {
            repository.toggleTaskCompletion(task.taskId, userId, storeId, isCompleted = true)
        }
        assertTrue(completion is Resource.Success)
        assertEquals("COMPLETED", repository.getTaskById(task.taskId, userId, storeId).first()?.status)
        assertEquals("COMPLETED", repository.getTasksForStore(userId, storeId).first().single().status)

        val edit = withContext(Dispatchers.Main) {
            repository.updateTask(userId, task.taskId, storeId, "Check Stock", "Revised", "STOCK", "LOW")
        }
        assertTrue(edit is Resource.Success)
        val edited = repository.getTasksForStore(userId, storeId).first().single()
        assertEquals(task.taskId, edited.taskId)
        assertEquals("Check Stock", edited.title)
        assertEquals("Revised", edited.description)
        assertEquals("STOCK", edited.category)
        assertEquals("LOW", edited.priority)
        assertEquals("COMPLETED", edited.status)

        val deletion = withContext(Dispatchers.Main) {
            repository.softDeleteTask(task.taskId, userId, storeId)
        }
        assertTrue(deletion is Resource.Success)
        assertNull(repository.getTaskById(task.taskId, userId, storeId).first())
        assertTrue(repository.getTasksForStore(userId, storeId).first().isEmpty())

        val pendingOps = db.syncQueueDao().getPendingOperations(userId).first()
        assertEquals(mapOf("CREATE" to 1, "UPDATE" to 2, "DELETE" to 1), pendingOps.groupingBy { it.operationType }.eachCount())
        assertTrue(pendingOps.all { it.entityId == task.taskId })
        val deleteOp = pendingOps.single { it.operationType == "DELETE" }
        val deleted = Gson().fromJson(deleteOp.payloadJson, TaskEntity::class.java)
        assertTrue(deleted.isDeleted)
        assertEquals("PENDING_DELETE", deleted.syncStatus)
        assertEquals(deleteOp.createdAt, deleted.updatedAt)
    }

    @Test
    fun failedQueueWriteRollsBackCompletion() = runBlocking {
        val repository = TaskRepositoryImpl(db.taskDao(), db)
        val created = repository.createTask("usr_001", "str_101", "Original", "", "GENERAL", "MEDIUM")
        val task = requireNotNull(created.data)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_queue_insert BEFORE INSERT ON sync_queue " +
                "BEGIN SELECT RAISE(ABORT, 'test queue failure'); END"
        )

        val result = withContext(Dispatchers.Main) {
            repository.toggleTaskCompletion(task.taskId, task.userId, task.storeId, isCompleted = true)
        }

        assertTrue(result is Resource.Error)
        assertEquals(task, repository.getTaskById(task.taskId, task.userId, task.storeId).first())
        assertEquals("CREATE", db.syncQueueDao().getPendingOperations(task.userId).first().single().operationType)
    }

    @Test
    fun photoPersistenceChecksScopeAndRemovesJpegsOnRollback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val userId = "photo_test_${UUID.randomUUID()}"
        val storeId = "str_101"
        val folder = File(context.filesDir, "task_photos/$userId")
        val storage = ImageStorageManager(context)
        val tasks = TaskRepositoryImpl(db.taskDao(), db)
        val photos = PhotoRepositoryImpl(db.photoEvidenceDao(), db, storage)
        val task = requireNotNull(tasks.createTask(userId, storeId, "Audit", "", "GENERAL", "MEDIUM").data)

        suspend fun attach(store: String) = withContext(Dispatchers.Main) {
            val file = storage.createTempImageFile(userId)
            try {
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                } finally {
                    bitmap.recycle()
                }
                photos.processAndSavePhoto(file, task.taskId, userId, store) { true }
            } finally {
                ImageStorageManager.releaseCapture(file)
            }
        }

        try {
            assertTrue(attach("wrong_store") is Resource.Error)
            assertTrue(folder.listFiles().orEmpty().none { it.extension == "jpg" })

            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_photo_queue BEFORE INSERT ON sync_queue " +
                    "WHEN NEW.entity_type = 'PHOTO' BEGIN SELECT RAISE(ABORT, 'test queue failure'); END"
            )
            assertTrue(attach(storeId) is Resource.Error)
            assertTrue(photos.getPhotosForTask(task.taskId, userId).first().isEmpty())
            assertTrue(folder.listFiles().orEmpty().none { it.extension == "jpg" })

            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_photo_queue")
            val photo = requireNotNull(attach(storeId).data)
            val savedFile = File(photo.localFilePath)
            assertTrue(savedFile.length() > 0)
            assertTrue(tasks.softDeleteTask(task.taskId, userId, storeId) is Resource.Success)
            assertTrue(attach(storeId) is Resource.Error)
            assertEquals(listOf(photo), photos.getPhotosForTask(task.taskId, userId).first())
            assertEquals(listOf(savedFile), folder.listFiles().orEmpty().filter { it.extension == "jpg" })
            assertEquals(1, db.syncQueueDao().getPendingOperations(userId).first().count { it.entityType == "PHOTO" })
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun cleanupKeepsActiveAndRecentCapturesAndFinalPhotos() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val userId = "cleanup_test_${UUID.randomUUID()}"
        val storage = ImageStorageManager(context)
        val active = storage.createTempImageFile(userId)
        val folder = requireNotNull(active.parentFile)
        val oldTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        try {
            val abandoned = File(folder, "old.tmp").apply { writeText("old") }
            val recent = File(folder, "recent.tmp").apply { writeText("recent") }
            val finalized = File(folder, "old.jpg").apply { writeText("saved") }
            assertTrue(active.setLastModified(oldTime))
            assertTrue(abandoned.setLastModified(oldTime))
            assertTrue(finalized.setLastModified(oldTime))

            withContext(Dispatchers.Main) {
                PhotoRepositoryImpl(db.photoEvidenceDao(), db, storage).reconcileOrphanedPhotos(userId)
            }

            assertFalse(abandoned.exists())
            assertTrue(active.exists())
            assertTrue(recent.exists())
            assertTrue(finalized.exists())
        } finally {
            ImageStorageManager.releaseCapture(active)
            folder.deleteRecursively()
        }
    }

    @Test
    fun insertPhotoWithSyncOpAndQueryByTask() = runBlocking {
        val photo = PhotoEvidenceEntity(
            photoId = "pho_001",
            taskId = "tsk_001",
            userId = "usr_001",
            localFilePath = "/data/user/0/com.example/files/task_photos/usr_001/pho_001.jpg",
            fileSizeBytes = 150000L,
            uploadStatus = "PENDING_UPLOAD",
            createdAt = "2026-03-31T10:05:00Z"
        )

        val syncOp = SyncQueueEntity(
            operationId = "op_002",
            entityType = "PHOTO",
            entityId = "pho_001",
            operationType = "UPLOAD_PHOTO",
            userId = "usr_001",
            payloadJson = "{}",
            status = "PENDING",
            createdAt = "2026-03-31T10:05:00Z"
        )

        db.withTransaction {
            db.photoEvidenceDao().insertPhoto(photo)
            db.photoEvidenceDao().insertSyncQueue(syncOp)
        }

        val photos = db.photoEvidenceDao().getPhotosForTask("tsk_001", "usr_001").first()
        assertEquals(1, photos.size)
        assertEquals("pho_001", photos[0].photoId)
        assertEquals("PENDING_UPLOAD", photos[0].uploadStatus)
    }

    @Test
    fun offlineTaskOpensFromPersistedListAfterRecreatingDatabaseAndDetailLookup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "detail_restart_test_${UUID.randomUUID()}.db"
        val userId = "usr_001"
        val storeId = "str_101"
        try {
            val original = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
            val taskId = try {
                val saved = TaskRepositoryImpl(original.taskDao(), original).createTask(
                    userId, storeId, "Offline detail", "", "GENERAL", "MEDIUM", null
                ) { true }
                assertNotNull(saved.data)
                requireNotNull(saved.data).taskId
            } finally {
                original.close()
            }

            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
            try {
                val tasks = TaskRepositoryImpl(reopened.taskDao(), reopened)
                val photos = PhotoRepositoryImpl(reopened.photoEvidenceDao(), reopened, ImageStorageManager(context))
                var activeUser = userId
                var hasSession = true
                val auth = object : IAuthRepository {
                    override suspend fun login(username: String, pass: String): Resource<LoginResponse> =
                        error("Offline lookup must not sign in")
                    override suspend fun logout(): Resource<Unit> = error("Not used")
                    override fun isLoggedIn() = hasSession
                    override fun getActiveUserId() = activeUser
                    override fun getActiveUserName() = null
                }
                val detail = TaskViewModel(tasks, photos, auth)
                val item = tasks.getTasksForStore(userId, storeId).first().single()
                val intent = TaskDetailActivity.createIntent(context, item, "Fixture store")
                val clickedId = requireNotNull(intent.getStringExtra(Constants.EXTRA_TASK_ID))
                val clickedStore = requireNotNull(intent.getStringExtra(Constants.EXTRA_STORE_ID))
                assertEquals(taskId, clickedId)
                assertEquals(storeId, clickedStore)
                assertEquals(item, detail.observeTask(clickedId, clickedStore)?.first())

                // A missing session is distinct from an authorized lookup with no matching row.
                hasSession = false
                assertNull(detail.observeTask(clickedId, clickedStore))
                hasSession = true
                activeUser = "usr_002"
                assertNotNull(detail.observeTask(clickedId, clickedStore))
                assertNull(detail.observeTask(clickedId, clickedStore)?.first())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun testMigration1To2PreservesPendingQueueOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dbName = "migration_test_${UUID.randomUUID()}.db"
        val schemaPath = "${AppDatabase::class.java.name}/1.json"
        val schema = instrumentation.context.assets.open(schemaPath).bufferedReader().use {
            org.json.JSONObject(it.readText()).getJSONObject("database")
        }

        try {
            context.openOrCreateDatabase(dbName, android.content.Context.MODE_PRIVATE, null).use { v1 ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    v1.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (indexId in 0 until indices.length()) {
                        v1.execSQL(indices.getJSONObject(indexId).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) v1.execSQL(setup.getString(index))
                v1.execSQL("INSERT INTO tasks VALUES ('tsk_001', 'str_101', 'usr_001', 'Offline task', '', 'GENERAL', 'MEDIUM', 'PENDING', NULL, '2026-11-25T10:00:00Z', '2026-11-25T10:00:00Z', 'PENDING_INSERT', NULL, 0)")
                v1.execSQL("INSERT INTO photo_evidence VALUES ('pho_001', 'tsk_001', 'usr_001', '/private/photo.jpg', NULL, 1024, 'image/jpeg', 'PENDING_UPLOAD', '2026-11-25T10:00:00Z')")
                v1.execSQL("INSERT INTO sync_queue VALUES ('op_009', 'TASK', 'tsk_001', 'CREATE', 'usr_001', '{}', 0, 'PENDING', NULL, '2026-11-25T10:00:00Z')")
                v1.execSQL("INSERT INTO sync_queue VALUES ('op_002', 'PHOTO', 'pho_001', 'UPLOAD_PHOTO', 'usr_001', '{}', 0, 'PENDING', NULL, '2026-11-25T10:00:00Z')")
                v1.version = schema.getInt("version")
            }

            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .build()
            try {
                val pending = migrated.syncQueueDao().getPendingOperationsSync("usr_001")
                assertEquals(2, migrated.openHelper.readableDatabase.version)
                assertEquals(listOf("op_009", "op_002"), pending.map { it.operationId })
                assertEquals(listOf(1L, 2L), pending.map { it.sequenceNumber })
                assertEquals("Offline task", migrated.taskDao().getTaskByIdSync("tsk_001", "usr_001", "str_101")?.title)
                assertEquals("/private/photo.jpg", migrated.photoEvidenceDao().getPhotoById("pho_001", "usr_001")?.localFilePath)
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}
