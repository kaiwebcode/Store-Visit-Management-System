package com.example.storevisitmanagementsystem.ui.store

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.storevisitmanagementsystem.data.local.preferences.TokenManagerImpl
import com.example.storevisitmanagementsystem.data.remote.api.ApiClient
import com.example.storevisitmanagementsystem.data.repository.AuthRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.PreferencesAssignedStoreCache
import com.example.storevisitmanagementsystem.data.repository.StoreRepositoryImpl
import com.example.storevisitmanagementsystem.databinding.ActivityStoreSelectionBinding
import com.example.storevisitmanagementsystem.ui.auth.LoginActivity
import com.example.storevisitmanagementsystem.ui.task.TaskListActivity
import com.example.storevisitmanagementsystem.util.Constants
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.launch

class StoreSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoreSelectionBinding
    private lateinit var storeAdapter: StoreAdapter
    private var navigatingToLogin = false

    private val viewModel: StoreViewModel by viewModels {
        val tokenManager = TokenManagerImpl(applicationContext)
        val apiService = ApiClient.getApiService(applicationContext)
        val storeRepo = StoreRepositoryImpl(apiService, tokenManager, PreferencesAssignedStoreCache(applicationContext))
        val authRepo = AuthRepositoryImpl(apiService, tokenManager)
        StoreViewModel.Factory(storeRepo, authRepo, tokenManager.observeSessionActive())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStoreSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
        observeViewModel()
    }

    private fun setupUI() {
        binding.tvGreeting.text = "Welcome, ${viewModel.getActiveUserName()}"

        storeAdapter = StoreAdapter { store ->
            viewModel.selectStore(store)
            navigateToTaskList(store.id, store.name)
        }

        binding.rvStores.apply {
            layoutManager = LinearLayoutManager(this@StoreSelectionActivity)
            adapter = storeAdapter
        }
    }

    private fun setupListeners() {
        binding.btnLogout.setOnClickListener {
            viewModel.logout()
        }

        binding.cardSelectedStore.setOnClickListener {
            val store = viewModel.selectedStore.value
            if (store != null) {
                navigateToTaskList(store.id, store.name)
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.loadAssignedStores(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.storesState.collect { resource ->
                        when (resource) {
                            is Resource.Loading -> {
                                binding.pbLoading.visibility = View.VISIBLE
                                binding.tvError.visibility = View.GONE
                                binding.tvEmpty.visibility = View.GONE
                            }
                            is Resource.Success -> {
                                binding.pbLoading.visibility = View.GONE
                                binding.tvError.visibility = View.GONE
                                val list = resource.data ?: emptyList()
                                storeAdapter.submitList(list)
                                binding.tvStoreCount.text = "${list.size} stores"
                                if (list.isEmpty()) {
                                    binding.tvEmpty.visibility = View.VISIBLE
                                } else {
                                    binding.tvEmpty.visibility = View.GONE
                                }
                            }
                            is Resource.Error -> {
                                binding.pbLoading.visibility = View.GONE
                                binding.tvError.text = resource.message
                                binding.tvError.visibility = View.VISIBLE
                                binding.tvEmpty.visibility = View.GONE
                            }
                        }
                    }
                }

                launch {
                    viewModel.selectedStore.collect { store ->
                        if (store != null) {
                            storeAdapter.setSelectedStoreId(store.id)
                            binding.tvSelectedStoreInfo.text = "${store.name} (${store.code})"
                            binding.cardSelectedStore.visibility = View.VISIBLE
                        } else {
                            binding.cardSelectedStore.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.requiresLogin.collect { required ->
                        if (required) navigateToLogin()
                    }
                }
            }
        }
    }

    private fun navigateToTaskList(storeId: String, storeName: String) {
        val intent = Intent(this, TaskListActivity::class.java).apply {
            putExtra(Constants.EXTRA_STORE_ID, storeId)
            putExtra(Constants.EXTRA_STORE_NAME, storeName)
        }
        startActivity(intent)
    }

    private fun navigateToLogin() {
        if (navigatingToLogin || !viewModel.shouldNavigateToLogin()) return
        navigatingToLogin = true
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
