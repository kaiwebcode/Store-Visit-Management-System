package com.example.storevisitmanagementsystem.ui.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.data.repository.IAuthRepository
import com.example.storevisitmanagementsystem.data.repository.IStoreRepository
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StoreViewModel(
    private val storeRepository: IStoreRepository,
    private val authRepository: IAuthRepository
) : ViewModel() {

    private val _storesState = MutableStateFlow<Resource<List<StoreDto>>>(Resource.Loading())
    val storesState: StateFlow<Resource<List<StoreDto>>> = _storesState.asStateFlow()

    private val _selectedStore = MutableStateFlow<StoreDto?>(null)
    val selectedStore: StateFlow<StoreDto?> = _selectedStore.asStateFlow()

    private val _loggedOut = MutableStateFlow<Boolean>(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    init {
        loadAssignedStores()
    }

    fun loadAssignedStores(query: String? = null) {
        viewModelScope.launch {
            _storesState.value = Resource.Loading()
            val result = storeRepository.getAssignedStores(query)
            _storesState.value = result

            // Restore previously selected store if available
            if (result is Resource.Success && result.data != null) {
                val savedId = storeRepository.getSelectedStoreId()
                if (savedId != null) {
                    val matching = result.data.find { it.id == savedId }
                    if (matching != null) {
                        _selectedStore.value = matching
                    }
                }
            }
        }
    }

    fun selectStore(store: StoreDto) {
        _selectedStore.value = store
        storeRepository.saveSelectedStoreId(store.id)
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            storeRepository.saveSelectedStoreId(null)
            _loggedOut.value = true
        }
    }

    fun getActiveUserName(): String {
        return authRepository.getActiveUserName() ?: "Agent"
    }

    class Factory(
        private val storeRepository: IStoreRepository,
        private val authRepository: IAuthRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StoreViewModel(storeRepository, authRepository) as T
        }
    }
}
