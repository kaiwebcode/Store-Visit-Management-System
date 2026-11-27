package com.example.storevisitmanagementsystem.ui.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.data.repository.IAuthRepository
import com.example.storevisitmanagementsystem.data.repository.IStoreRepository
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StoreViewModel(
    private val storeRepository: IStoreRepository,
    private val authRepository: IAuthRepository,
    sessionActive: Flow<Boolean>
) : ViewModel() {

    private val _storesState = MutableStateFlow<Resource<List<StoreDto>>>(Resource.Loading())
    val storesState: StateFlow<Resource<List<StoreDto>>> = _storesState.asStateFlow()

    private val _selectedStore = MutableStateFlow<StoreDto?>(null)
    val selectedStore: StateFlow<StoreDto?> = _selectedStore.asStateFlow()

    private val logoutInProgress = MutableStateFlow(false)
    val requiresLogin = combine(sessionActive, logoutInProgress) { active, loggingOut ->
        !active && !loggingOut
    }

    fun shouldNavigateToLogin(): Boolean = !logoutInProgress.value && !authRepository.isLoggedIn()

    private var storeLoad: Job? = null

    init {
        loadAssignedStores()
    }

    fun loadAssignedStores(query: String? = null) {
        storeLoad?.cancel()
        storeLoad = viewModelScope.launch {
            _storesState.value = Resource.Loading()
            val result = storeRepository.getAssignedStores(query)
            _storesState.value = result

            if (result is Resource.Success && query.isNullOrBlank()) {
                val savedId = storeRepository.getSelectedStoreId()
                _selectedStore.value = result.data.orEmpty().find { it.id == savedId }
                if (savedId != null && _selectedStore.value == null) {
                    storeRepository.saveSelectedStoreId(null)
                }
            }
        }
    }

    fun selectStore(store: StoreDto) {
        _selectedStore.value = store
        storeRepository.saveSelectedStoreId(store.id)
    }

    fun logout() {
        if (logoutInProgress.value) return
        logoutInProgress.value = true
        storeLoad?.cancel()
        viewModelScope.launch {
            try {
                authRepository.logout()
            } finally {
                logoutInProgress.value = false
            }
        }
    }

    fun getActiveUserName(): String {
        return authRepository.getActiveUserName() ?: "Agent"
    }

    class Factory(
        private val storeRepository: IStoreRepository,
        private val authRepository: IAuthRepository,
        private val sessionActive: Flow<Boolean>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StoreViewModel(storeRepository, authRepository, sessionActive) as T
        }
    }
}
