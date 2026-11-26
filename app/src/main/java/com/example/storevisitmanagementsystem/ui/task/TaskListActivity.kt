package com.example.storevisitmanagementsystem.ui.task

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.storevisitmanagementsystem.data.local.db.AppDatabase
import com.example.storevisitmanagementsystem.data.local.preferences.TokenManagerImpl
import com.example.storevisitmanagementsystem.data.remote.api.ApiClient
import com.example.storevisitmanagementsystem.data.repository.AuthRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.PhotoRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.TaskRepositoryImpl
import com.example.storevisitmanagementsystem.databinding.ActivityTaskListBinding
import com.example.storevisitmanagementsystem.util.Constants
import com.example.storevisitmanagementsystem.ui.auth.LoginActivity
import com.example.storevisitmanagementsystem.util.ImageStorageManager
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.launch

class TaskListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskListBinding
    private lateinit var taskAdapter: TaskAdapter

    private var storeId: String = ""
    private var listUserId: String? = null
    private var storeName: String = "Store Tasks"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTaskWindowInsets()

        storeId = intent.getStringExtra(Constants.EXTRA_STORE_ID) ?: ""
        storeName = intent.getStringExtra(Constants.EXTRA_STORE_NAME) ?: "Store Tasks"

        setupUI()
        setupListeners()
        observeViewModel()

        listUserId = viewModel.getActiveUserId()
        listUserId?.let { userId ->
            viewModel.observeTasksForStore(storeId)
            com.example.storevisitmanagementsystem.sync.SyncManager.scheduleSync(applicationContext, userId)
        }
    }

    override fun onStart() {
        super.onStart()
        if (listUserId == null || viewModel.getActiveUserId() != listUserId) {
            LoginActivity.requireSignIn(this)
        }
    }

    private fun setupUI() {
        binding.tvHeaderStoreName.text = storeName
        binding.tvHeaderStoreCode.text = "Active Store ID: $storeId"

        taskAdapter = TaskAdapter(
            onTaskClick = { task ->
                if (viewModel.getActiveUserId() != task.userId) {
                    LoginActivity.requireSignIn(this)
                } else {
                    startActivity(TaskDetailActivity.createIntent(this, task, storeName))
                }
            },
            onCompleteToggle = { task ->
                lifecycleScope.launch {
                    val result = viewModel.toggleTaskCompletion(task)
                    if (result is Resource.Error) {
                        Toast.makeText(this@TaskListActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            photoCountProvider = { taskId, callback ->
                viewModel.loadPhotoCount(taskId, callback)
            }
        )

        binding.rvTasks.apply {
            layoutManager = LinearLayoutManager(this@TaskListActivity)
            adapter = taskAdapter
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.fabAddTask.setOnClickListener {
            val intent = Intent(this, TaskCreateEditActivity::class.java).apply {
                putExtra(Constants.EXTRA_STORE_ID, storeId)
                putExtra(Constants.EXTRA_STORE_NAME, storeName)
            }
            startActivity(intent)
        }

        binding.chipGroupStatus.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds.first()) {
                binding.chipFilterAll.id -> {
                    viewModel.setStatusFilter("ALL")
                    viewModel.setPriorityFilter("ALL")
                }
                binding.chipFilterPending.id -> {
                    viewModel.setStatusFilter("PENDING")
                    viewModel.setPriorityFilter("ALL")
                }
                binding.chipFilterCompleted.id -> {
                    viewModel.setStatusFilter("COMPLETED")
                    viewModel.setPriorityFilter("ALL")
                }
                binding.chipFilterHighPriority.id -> {
                    viewModel.setStatusFilter("ALL")
                    viewModel.setPriorityFilter("HIGH")
                }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredTasks.collect { list ->
                        taskAdapter.submitList(list)
                        if (list.isEmpty()) {
                            binding.tvEmptyTasks.visibility = View.VISIBLE
                        } else {
                            binding.tvEmptyTasks.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.totalCount.collect { count ->
                        binding.tvTotalCount.text = count.toString()
                    }
                }

                launch {
                    viewModel.pendingCount.collect { count ->
                        binding.tvPendingCount.text = count.toString()
                    }
                }

                launch {
                    viewModel.completedCount.collect { count ->
                        binding.tvCompletedCount.text = count.toString()
                    }
                }
            }
        }
    }
}
