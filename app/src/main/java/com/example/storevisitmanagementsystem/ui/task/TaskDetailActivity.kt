package com.example.storevisitmanagementsystem.ui.task

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.data.local.preferences.TokenManagerImpl
import com.example.storevisitmanagementsystem.data.remote.api.ApiClient
import com.example.storevisitmanagementsystem.data.repository.AuthRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.PhotoRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.TaskRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.StoreRepositoryImpl
import com.example.storevisitmanagementsystem.databinding.ActivityTaskDetailBinding
import com.example.storevisitmanagementsystem.util.Constants
import com.example.storevisitmanagementsystem.ui.auth.LoginActivity
import com.example.storevisitmanagementsystem.util.ImageStorageManager
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.launch
import java.io.File

class TaskDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskDetailBinding
    private lateinit var photoAdapter: PhotoAdapter

    private var storeId: String = ""
    private var taskId: String = ""
    private var currentTask: TaskEntity? = null
    private var isMutatingTask = false

    private var tempCaptureFile: File? = null
    private var captureUserId: String? = null

    private val storeRepository by lazy {
        StoreRepositoryImpl(
            ApiClient.getApiService(applicationContext),
            TokenManagerImpl(applicationContext)
        )
    }

    private val viewModel: TaskViewModel by viewModels {
        val appDb = AppDatabase.getInstance(applicationContext)
        val tokenManager = TokenManagerImpl(applicationContext)
        val apiService = ApiClient.getApiService(applicationContext)
        val authRepo = AuthRepositoryImpl(apiService, tokenManager)
        val taskRepo = TaskRepositoryImpl(appDb.taskDao(), appDb)
        val imageStorageManager = ImageStorageManager(applicationContext)
        val photoRepo = PhotoRepositoryImpl(appDb.photoEvidenceDao(), appDb, imageStorageManager)
        TaskViewModel.Factory(taskRepo, photoRepo, authRepo)
    }

    /**
     * Requirement 3 & Phase 3: Launch real camera capture flow using ActivityResultContracts.TakePicture with FileProvider.
     */
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempCaptureFile
        val userId = captureUserId
        tempCaptureFile = null
        captureUserId = null
        updateTaskActions()

        if (file != null) {
            if (success && userId != null) {
                viewModel.saveCapturedPhoto(file, taskId, userId, storeId, storeRepository)
            } else {
                viewModel.discardCapture(file)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTaskWindowInsets()

        taskId = intent.getStringExtra(Constants.EXTRA_TASK_ID) ?: ""
        storeId = intent.getStringExtra(Constants.EXTRA_STORE_ID) ?: ""
        tempCaptureFile = savedInstanceState?.getString(STATE_CAPTURE_PATH)?.let(::File)
        captureUserId = savedInstanceState?.getString(STATE_CAPTURE_USER)
        tempCaptureFile?.let(ImageStorageManager::protectCapture)

        setupUI()
        setupListeners()
        updateTaskActions()
        observeData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CAPTURE_PATH, tempCaptureFile?.absolutePath)
        outState.putString(STATE_CAPTURE_USER, captureUserId)
    }

    override fun onDestroy() {
        if (isFinishing) tempCaptureFile?.let(ImageStorageManager::releaseCapture)
        super.onDestroy()
    }

    private fun setupUI() {
        photoAdapter = PhotoAdapter { photo ->
            showPhotoDetailDialog(photo.localFilePath)
        }

        binding.rvPhotos.apply {
            layoutManager = GridLayoutManager(this@TaskDetailActivity, 3)
            adapter = photoAdapter
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnEditTask.setOnClickListener {
            val task = currentTask ?: return@setOnClickListener
            val intent = Intent(this, TaskCreateEditActivity::class.java).apply {
                putExtra(Constants.EXTRA_TASK_ID, task.taskId)
                putExtra(Constants.EXTRA_STORE_ID, task.storeId)
            }
            startActivity(intent)
        }

        binding.btnDeleteTask.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Task")
                .setMessage("Are you sure you want to delete this task? This operation will be saved locally and queued for sync.")
                .setPositiveButton("Delete") { _, _ ->
                    runTaskMutation(closeOnSuccess = true, action = viewModel::deleteTask)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnAddPhoto.setOnClickListener {
            launchCameraCapture()
        }

        binding.btnToggleComplete.setOnClickListener {
            runTaskMutation(action = viewModel::toggleTaskCompletion)
        }
    }

    private fun runTaskMutation(
        closeOnSuccess: Boolean = false,
        action: suspend (TaskEntity) -> Resource<Unit>
    ) {
        val task = currentTask ?: return
        if (isMutatingTask) return
        isMutatingTask = true
        updateTaskActions()
        lifecycleScope.launch {
            val result = action(task)
            isMutatingTask = false
            updateTaskActions()
            when (result) {
                is Resource.Success -> {
                    val userId = viewModel.getActiveUserId()
                    if (userId != null) {
                        com.example.storevisitmanagementsystem.sync.SyncManager.triggerImmediateSync(applicationContext, userId)
                    }
                    if (closeOnSuccess) {
                        Toast.makeText(this@TaskDetailActivity, "Task deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                is Resource.Error -> {
                    Toast.makeText(this@TaskDetailActivity, result.message, Toast.LENGTH_LONG).show()
                }
                else -> Unit
            }
        }
    }

    private fun updateTaskActions() {
        val enabled = currentTask != null && !isMutatingTask
        binding.btnEditTask.isEnabled = enabled
        binding.btnDeleteTask.isEnabled = enabled
        binding.btnToggleComplete.isEnabled = enabled
        binding.btnAddPhoto.isEnabled = enabled && tempCaptureFile == null &&
            viewModel.photoSaveState.value !is Resource.Loading
    }

    private fun launchCameraCapture() {
        val userId = viewModel.getActiveUserId() ?: return
        val imageStorageManager = ImageStorageManager(applicationContext)

        try {
            val file = imageStorageManager.createTempImageFile(userId)
            tempCaptureFile = file
            captureUserId = userId
            takePictureLauncher.launch(imageStorageManager.getUriForFile(file))
            updateTaskActions()
        } catch (e: Exception) {
            tempCaptureFile?.let(viewModel::discardCapture)
            tempCaptureFile = null
            captureUserId = null
            updateTaskActions()
            Toast.makeText(this, "Cannot launch camera: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeData() {
        val appDb = AppDatabase.getInstance(applicationContext)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val userId = viewModel.getActiveUserId()
                val taskFlow = viewModel.observeTask(taskId, storeId)
                if (userId == null || taskFlow == null) {
                    LoginActivity.requireSignIn(this@TaskDetailActivity)
                    return@repeatOnLifecycle
                }

                launch {
                    viewModel.photoSaveState.collect { result ->
                        binding.pbPhotoProcessing.visibility =
                            if (result is Resource.Loading) View.VISIBLE else View.GONE
                        updateTaskActions()
                        when (result) {
                            is Resource.Success -> {
                                Toast.makeText(this@TaskDetailActivity, "Photo evidence attached", Toast.LENGTH_SHORT).show()
                                val activeUser = viewModel.getActiveUserId()
                                if (activeUser != null) {
                                    com.example.storevisitmanagementsystem.sync.SyncManager.triggerImmediateSync(applicationContext, activeUser)
                                }
                                viewModel.clearPhotoSaveResult()
                            }
                            is Resource.Error -> {
                                Toast.makeText(this@TaskDetailActivity, result.message, Toast.LENGTH_LONG).show()
                                viewModel.clearPhotoSaveResult()
                            }
                            else -> Unit
                        }
                    }
                }

                // Observe Task Entity
                launch {
                    taskFlow.collect { task ->
                        currentTask = task
                        updateTaskActions()
                        if (task != null) {
                            bindTaskInfo(task)
                        } else if (!isMutatingTask) {
                            Toast.makeText(this@TaskDetailActivity, "Task not found", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }

                // Observe Photo Evidence Entities
                launch {
                    appDb.photoEvidenceDao().getPhotosForTask(taskId, userId).collect { photos ->
                        photoAdapter.submitList(photos)
                        if (photos.isEmpty()) {
                            binding.tvNoPhotos.visibility = View.VISIBLE
                        } else {
                            binding.tvNoPhotos.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun bindTaskInfo(task: TaskEntity) {
        binding.tvTaskTitle.text = task.title
        binding.tvDescription.text = task.description.ifBlank { "No detailed description." }
        binding.tvCategoryBadge.text = task.category
        binding.tvPriorityBadge.text = task.priority
        binding.tvStatusBadge.text = task.status
        binding.tvCreatedAt.text = "Created: ${task.createdAt.take(19).replace('T', ' ')}"

        val isCompleted = task.status.uppercase() == "COMPLETED"
        if (isCompleted) {
            binding.btnToggleComplete.text = "Reopen Task"
            binding.btnToggleComplete.setBackgroundColor(Color.parseColor("#344252")) // sv_border
        } else {
            binding.btnToggleComplete.text = "Mark Task Completed"
            binding.btnToggleComplete.setBackgroundColor(Color.parseColor("#1685FF")) // sv_accent
        }
    }

    private fun showPhotoDetailDialog(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "Photo file not found", Toast.LENGTH_SHORT).show()
            return
        }

        val imageView = android.widget.ImageView(this).apply {
            setImageURI(Uri.fromFile(file))
            adjustViewBounds = true
            setPadding(16, 16, 16, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("Photo Evidence")
            .setView(imageView)
            .setPositiveButton("Close", null)
            .show()
    }

    companion object {
        fun createIntent(context: Context, task: TaskEntity, storeName: String) =
            Intent(context, TaskDetailActivity::class.java).apply {
                putExtra(Constants.EXTRA_TASK_ID, task.taskId)
                putExtra(Constants.EXTRA_STORE_ID, task.storeId)
                putExtra(Constants.EXTRA_STORE_NAME, storeName)
            }

        private const val STATE_CAPTURE_PATH = "capture_path"
        private const val STATE_CAPTURE_USER = "capture_user"
    }
}
