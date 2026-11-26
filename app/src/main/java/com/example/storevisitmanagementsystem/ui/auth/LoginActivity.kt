package com.example.storevisitmanagementsystem.ui.auth

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.storevisitmanagementsystem.BuildConfig
import com.example.storevisitmanagementsystem.data.local.preferences.TokenManagerImpl
import com.example.storevisitmanagementsystem.data.remote.api.ApiClient
import com.example.storevisitmanagementsystem.data.repository.AuthRepositoryImpl
import com.example.storevisitmanagementsystem.databinding.ActivityLoginBinding
import com.example.storevisitmanagementsystem.ui.store.StoreSelectionActivity
import com.example.storevisitmanagementsystem.util.Resource
import com.example.storevisitmanagementsystem.util.Constants
import com.example.storevisitmanagementsystem.sync.SyncManager
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    companion object {
        fun requireSignIn(activity: Activity) {
            Toast.makeText(activity, "Session expired. Sign in again.", Toast.LENGTH_SHORT).show()
            activity.startActivity(Intent(activity, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    private lateinit var binding: ActivityLoginBinding

    private val viewModel: LoginViewModel by viewModels {
        val tokenManager = TokenManagerImpl(applicationContext)
        val apiService = ApiClient.getApiService(applicationContext)
        val authRepo = AuthRepositoryImpl(apiService, tokenManager)
        LoginViewModel.Factory(authRepo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show debug credentials container only in debug builds
        binding.debugCredentialsContainer.isVisible = BuildConfig.DEBUG

        checkExistingSession()
        setupListeners()
        observeViewModel()
    }

    private fun checkExistingSession() {
        val tokenManager = TokenManagerImpl(applicationContext)
        if (tokenManager.isLoggedIn()) {
            tokenManager.getUserId()?.let { SyncManager.scheduleSync(applicationContext, it) }
            navigateToStoreSelection()
        }
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString()
            val password = binding.etPassword.text.toString()
            viewModel.login(username, password)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collect { resource ->
                    when (resource) {
                        is Resource.Loading -> {
                            binding.pbLoading.visibility = View.VISIBLE
                            binding.btnLogin.isEnabled = false
                            binding.tvError.visibility = View.GONE
                        }
                        is Resource.Success -> {
                            binding.pbLoading.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                            resource.data?.let { session ->
                                if (BuildConfig.DEBUG && session.token.startsWith(Constants.LOCAL_MOCK_TOKEN_PREFIX)) {
                                    Toast.makeText(this@LoginActivity,
                                        "Local sign-in. Sign in again with the sync server running to upload.",
                                        Toast.LENGTH_LONG).show()
                                } else {
                                    SyncManager.triggerImmediateSync(applicationContext, session.userId)
                                }
                            }
                            navigateToStoreSelection()
                        }
                        is Resource.Error -> {
                            binding.pbLoading.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                            binding.tvError.text = resource.message
                            binding.tvError.visibility = View.VISIBLE
                        }
                        null -> {
                            binding.pbLoading.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                            binding.tvError.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun navigateToStoreSelection() {
        val intent = Intent(this, StoreSelectionActivity::class.java)
        startActivity(intent)
        finish()
    }
}
