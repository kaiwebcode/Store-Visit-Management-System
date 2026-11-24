package com.example.storevisitmanagementsystem.ui.store

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.storevisitmanagementsystem.data.local.preferences.TokenManagerImpl
import com.example.storevisitmanagementsystem.data.remote.api.ApiClient
import com.example.storevisitmanagementsystem.data.repository.AuthRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.StoreRepositoryImpl
import com.example.storevisitmanagementsystem.databinding.ActivityStoreSelectionBinding
import com.example.storevisitmanagementsystem.ui.auth.LoginActivity
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.launch

class StoreSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStoreSelectionBinding
    private lateinit var storeAdapter: StoreAdapter

    private val viewModel: StoreViewModel by viewModels {
        val tokenManager = TokenManagerImpl(applicationContext)
        val apiService = ApiClient.getApiService(applicationContext)
        val storeRepo = StoreRepositoryImpl(apiService, tokenManager)
        val authRepo = AuthRepositoryImpl(apiService, tokenManager)
        StoreViewModel.Factory(storeRepo, authRepo)
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
            Toast.makeText(this, "Active store selected: ${store.name}", Toast.LENGTH_SHORT).show()
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

                                // If session expired, navigate to Login
                                if (resource.message?.contains("Session expired") == true) {
                                    navigateToLogin()
                                }
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
                    viewModel.loggedOut.collect { loggedOut ->
                        if (loggedOut) {
                            navigateToLogin()
                        }
                    }
                }
            }
        }
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
