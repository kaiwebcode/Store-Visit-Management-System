package com.example.storevisitmanagementsystem.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.data.repository.IAuthRepository
import com.example.storevisitmanagementsystem.data.repository.IPhotoRepository
import com.example.storevisitmanagementsystem.data.repository.ITaskRepository
import com.example.storevisitmanagementsystem.data.repository.StoreRepositoryImpl
import com.example.storevisitmanagementsystem.util.ImageStorageManager
import com.example.storevisitmanagementsystem.sync.InFlightSync
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TaskViewModel(
    private val taskRepository: ITaskRepository,
    private val photoRepository: IPhotoRepository,
    private val authRepository: IAuthRepository,
    private val scheduleSync: (String) -> Unit = {}
) : ViewModel() {

    private val _statusFilter = MutableStateFlow("ALL")
    val statusFilter: StateFlow<String> = _statusFilter.asStateFlow()

    private val _priorityFilter = MutableStateFlow("ALL")
    val priorityFilter: StateFlow<String> = _priorityFilter.asStateFlow()

    private val _allTasks = MutableStateFlow<List<TaskEntity>>(emptyList())

    val filteredTasks: StateFlow<List<TaskEntity>> = combine(
        _allTasks,
        _statusFilter,
        _priorityFilter
    ) { tasks, status, priority ->
        tasks.filter { task ->
            val matchesStatus = when (status.uppercase()) {
                "PENDING" -> task.status.uppercase() == "PENDING" || task.status.uppercase() == "IN_PROGRESS"
                "COMPLETED" -> task.status.uppercase() == "COMPLETED"
                else -> true
            }
            val matchesPriority = when (priority.uppercase()) {
                "HIGH" -> task.priority.uppercase() == "HIGH"
                "MEDIUM" -> task.priority.uppercase() == "MEDIUM"
                "LOW" -> task.priority.uppercase() == "LOW"
                else -> true
            }
            matchesStatus && matchesPriority
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalCount: StateFlow<Int> = _allTasks.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pendingCount: StateFlow<Int> = _allTasks.map { list ->
        list.count { it.status.uppercase() == "PENDING" || it.status.uppercase() == "IN_PROGRESS" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val completedCount: StateFlow<Int> = _allTasks.map { list ->
        list.count { it.status.uppercase() == "COMPLETED" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun observeTasksForStore(storeId: String) {
        val userId = getActiveUserId() ?: return
        viewModelScope.launch {
            // Reconcile orphaned temp photo files on task list initialization
            photoRepository.reconcileOrphanedPhotos(userId)

            combine(taskRepository.getTasksForStore(userId, storeId), InFlightSync.tasks) { tasks, active ->
                tasks.map { it.withLiveSyncState(active) }
            }.collect { tasks ->
                _allTasks.value = tasks
            }
        }
    }

    fun setStatusFilter(status: String) {
        _statusFilter.value = status
    }

    fun setPriorityFilter(priority: String) {
        _priorityFilter.value = priority
    }

    fun observeTask(taskId: String, storeId: String): Flow<TaskEntity?>? {
        val userId = getActiveUserId() ?: return null
        return combine(taskRepository.getTaskById(taskId, userId, storeId), InFlightSync.tasks) { task, active ->
            task?.withLiveSyncState(active)
        }
    }

    private fun TaskEntity.withLiveSyncState(active: Set<InFlightSync.Record>): TaskEntity =
        if (InFlightSync.Record(userId, taskId) in active) copy(syncStatus = "SYNCING") else this

    suspend fun toggleTaskCompletion(task: TaskEntity): Resource<Unit> {
        val userId = getActiveUserId()?.takeIf { it == task.userId }
            ?: return Resource.Error("The active account has changed.")
        val isCompleted = task.status.equals("COMPLETED", ignoreCase = true)
        return taskRepository.toggleTaskCompletion(task.taskId, userId, task.storeId, !isCompleted).also {
            if (it is Resource.Success) scheduleSync(userId)
        }
    }

    suspend fun deleteTask(task: TaskEntity): Resource<Unit> {
        val userId = getActiveUserId()?.takeIf { it == task.userId }
            ?: return Resource.Error("The active account has changed.")
        return taskRepository.softDeleteTask(task.taskId, userId, task.storeId).also {
            if (it is Resource.Success) scheduleSync(userId)
        }
    }

    fun loadPhotoCount(taskId: String, onCountLoaded: (Int) -> Unit) {
        val userId = getActiveUserId() ?: return
        viewModelScope.launch {
            photoRepository.getPhotosForTask(taskId, userId).firstOrNull()?.let { photos ->
                onCountLoaded(photos.size)
            }
        }
    }

    private val _photoSaveState = MutableStateFlow<Resource<PhotoEvidenceEntity>?>(null)
    val photoSaveState = _photoSaveState.asStateFlow()

    fun saveCapturedPhoto(file: File, taskId: String, userId: String, storeId: String, stores: StoreRepositoryImpl) {
        viewModelScope.launch {
            _photoSaveState.value = Resource.Loading()
            try {
                val authorization = stores.validateSelectedStoreLocally(userId, storeId)
                val token = authorization.data
                _photoSaveState.value = if (token == null) {
                    Resource.Error(authorization.message ?: "Could not verify the selected store.")
                } else {
                    photoRepository.processAndSavePhoto(file, taskId, userId, storeId) {
                        stores.isSelectedStoreSession(userId, storeId, token)
                    }
                }
            } finally {
                removeCapture(file)
            }
        }
    }

    fun clearPhotoSaveResult() {
        _photoSaveState.value = null
    }

    fun discardCapture(file: File) {
        viewModelScope.launch { removeCapture(file) }
    }

    private suspend fun removeCapture(file: File) {
        withContext(NonCancellable + Dispatchers.IO) {
            file.delete()
            ImageStorageManager.releaseCapture(file)
        }
    }

    fun getActiveUserId(): String? =
        authRepository.getActiveUserId()?.takeIf { authRepository.isLoggedIn() }

    class Factory(
        private val taskRepository: ITaskRepository,
        private val photoRepository: IPhotoRepository,
        private val authRepository: IAuthRepository,
        private val scheduleSync: (String) -> Unit = {}
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TaskViewModel(taskRepository, photoRepository, authRepository, scheduleSync) as T
        }
    }
}
