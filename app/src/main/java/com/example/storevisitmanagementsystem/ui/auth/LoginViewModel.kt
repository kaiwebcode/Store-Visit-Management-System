package com.example.storevisitmanagementsystem.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.data.repository.IAuthRepository
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: IAuthRepository
) : ViewModel() {

    private val _loginState = MutableStateFlow<Resource<LoginResponse>?>(null)
    val loginState: StateFlow<Resource<LoginResponse>?> = _loginState.asStateFlow()

    private val _isLoggedIn = MutableStateFlow<Boolean>(authRepository.isLoggedIn())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun login(username: String, pass: String) {
        if (username.isBlank() || pass.isBlank()) {
            _loginState.value = Resource.Error("Please enter both username and password.")
            return
        }

        viewModelScope.launch {
            _loginState.value = Resource.Loading()
            val result = authRepository.login(username.trim(), pass.trim())
            _loginState.value = result
            if (result is Resource.Success) {
                _isLoggedIn.value = true
            }
        }
    }

    fun resetLoginState() {
        _loginState.value = null
    }

    class Factory(private val authRepository: IAuthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(authRepository) as T
        }
    }
}
