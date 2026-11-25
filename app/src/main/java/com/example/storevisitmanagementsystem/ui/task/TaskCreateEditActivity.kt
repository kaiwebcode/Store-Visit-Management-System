package com.example.storevisitmanagementsystem.ui.task

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.preferences.TokenManagerImpl
import com.example.storevisitmanagementsystem.data.remote.api.ApiClient
import com.example.storevisitmanagementsystem.data.repository.AuthRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.PhotoRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.TaskRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.StoreRepositoryImpl
import com.example.storevisitmanagementsystem.databinding.ActivityTaskCreateEditBinding
import com.example.storevisitmanagementsystem.util.Constants
import com.example.storevisitmanagementsystem.util.ImageStorageManager
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class TaskCreateEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskCreateEditBinding

    private var storeId: String = ""
    private var taskId: String? = null
    private var taskLoaded = false

    private val storeRepository by lazy {
        StoreRepositoryImpl(
            ApiClient.getApiService(applicationContext),
            TokenManagerImpl(applicationContext)
        )
    }

    private val taskRepository by lazy {
        val appDb = AppDatabase.getInstance(applicationContext)
        TaskRepositoryImpl(appDb.taskDao(), appDb)
    }

    private val viewModel: TaskViewModel by viewModels {
        val appDb = AppDatabase.getInstance(applicationContext)
        val tokenManager = TokenManagerImpl(applicationContext)
        val apiService = ApiClient.getApiService(applicationContext)
        val authRepo = AuthRepositoryImpl(apiService, tokenManager)
        val imageStorageManager = ImageStorageManager(applicationContext)
        val photoRepo = PhotoRepositoryImpl(appDb.photoEvidenceDao(), appDb, imageStorageManager)
        TaskViewModel.Factory(taskRepository, photoRepo, authRepo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskCreateEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTaskWindowInsets()

        storeId = intent.getStringExtra(Constants.EXTRA_STORE_ID) ?: ""
        taskId = intent.getStringExtra(Constants.EXTRA_TASK_ID)?.takeIf { it.isNotBlank() }

        setupListeners()
        taskId?.let { id ->
            binding.tvScreenTitle.text = "Edit Task"
            loadTaskDetails(id, restoreDraft = savedInstanceState?.getBoolean(STATE_TASK_LOADED) == true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TASK_LOADED, taskLoaded)
    }

    private fun loadTaskDetails(taskId: String, restoreDraft: Boolean) {
        binding.scrollForm.visibility = View.INVISIBLE
        lifecycleScope.launch {
            val task = viewModel.observeTask(taskId, storeId).firstOrNull()
            if (task == null) {
                Toast.makeText(this@TaskCreateEditActivity, "Task not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            if (!restoreDraft) {
                binding.etTitle.setText(task.title)
                binding.etDescription.setText(task.description)
                binding.chipGroupCategory.check(
                    when (task.category.uppercase()) {
                        "SIGNAGE" -> binding.chipCatSignage.id
                        "STOCK" -> binding.chipCatStock.id
                        "DISPLAY" -> binding.chipCatDisplay.id
                        else -> binding.chipCatGeneral.id
                    }
                )
                binding.chipGroupPriority.check(
                    when (task.priority.uppercase()) {
                        "HIGH" -> binding.chipPriHigh.id
                        "LOW" -> binding.chipPriLow.id
                        else -> binding.chipPriMedium.id
                    }
                )
            }
            taskLoaded = true
            binding.scrollForm.visibility = View.VISIBLE
        }
    }

    private fun getSelectedCategory(): String {
        return when (binding.chipGroupCategory.checkedChipId) {
            binding.chipCatSignage.id -> "SIGNAGE"
            binding.chipCatStock.id -> "STOCK"
            binding.chipCatDisplay.id -> "DISPLAY"
            else -> "GENERAL"
        }
    }

    private fun getSelectedPriority(): String {
        return when (binding.chipGroupPriority.checkedChipId) {
            binding.chipPriHigh.id -> "HIGH"
            binding.chipPriLow.id -> "LOW"
            else -> "MEDIUM"
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSaveTask.setOnClickListener {
            val editingTaskId = taskId
            val title = binding.etTitle.text.toString().trim()
            val description = binding.etDescription.text.toString().trim()
            val category = getSelectedCategory()
            val priority = getSelectedPriority()

            if (title.isBlank()) {
                binding.tvError.text = "Please enter a valid task title."
                binding.tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (storeId.isBlank()) {
                binding.tvError.text = "Invalid store reference."
                binding.tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            binding.tvError.visibility = View.GONE
            binding.pbSaving.visibility = View.VISIBLE
            binding.btnSaveTask.isEnabled = false

            lifecycleScope.launch {
                val userId = viewModel.getActiveUserId()
                val result = when {
                    userId == null -> Resource.Error("No active user.")
                    editingTaskId != null -> taskRepository.updateTask(
                        userId = userId,
                        taskId = editingTaskId,
                        storeId = storeId,
                        title = title,
                        description = description,
                        category = category,
                        priority = priority
                    )
                    else -> {
                        val authorization = storeRepository.validateSelectedStore(userId, storeId)
                        val token = authorization.data
                        if (token == null) {
                            Resource.Error(authorization.message ?: "Could not verify the selected store.")
                        } else {
                            taskRepository.createTask(
                                userId = userId,
                                storeId = storeId,
                                title = title,
                                description = description,
                                category = category,
                                priority = priority,
                                isSessionCurrent = { storeRepository.isSelectedStoreSession(userId, storeId, token) }
                            )
                        }
                    }
                }

                binding.pbSaving.visibility = View.GONE
                binding.btnSaveTask.isEnabled = true

                when (result) {
                    is Resource.Success -> {
                        Toast.makeText(
                            this@TaskCreateEditActivity,
                            if (editingTaskId != null) "Task updated" else "Task created successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                    is Resource.Error -> {
                        binding.tvError.text = result.message
                        binding.tvError.visibility = View.VISIBLE
                    }
                    else -> {}
                }
            }
        }
    }

    private companion object {
        const val STATE_TASK_LOADED = "state_task_loaded"
    }
}
