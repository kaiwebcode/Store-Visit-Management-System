package com.example.storevisitmanagementsystem.data.repository

import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.util.Resource
import java.io.IOException

class StoreRepositoryImpl(
    private val apiService: ApiService,
    private val tokenManager: ITokenManager
) : IStoreRepository {

    override suspend fun getAssignedStores(query: String?): Resource<List<StoreDto>> {
        val token = tokenManager.getAuthToken()
            ?: return Resource.Error("Session expired. Please log in again.")

        return try {
            val response = apiService.getAssignedStores("Bearer $token", query)
            if (response.isSuccessful) {
                val apiResp = response.body()
                if (apiResp != null && apiResp.success && apiResp.data != null) {
                    Resource.Success(apiResp.data)
                } else {
                    val msg = apiResp?.error?.message ?: "Failed to load assigned stores."
                    Resource.Error(msg)
                }
            } else if (response.code() == 401) {
                tokenManager.handle401(token)
                Resource.Error("Session expired. Please log in again.")
            } else {
                Resource.Error("Error loading stores (HTTP ${response.code()}).")
            }
        } catch (e: IOException) {
            Resource.Error("Network error. Could not connect to store service.")
        } catch (e: Exception) {
            Resource.Error("An unexpected error occurred: ${e.localizedMessage}")
        }
    }

    suspend fun validateSelectedStore(userId: String, storeId: String): Resource<String> {
        val token = tokenManager.getAuthToken()?.takeIf { it.isNotBlank() }
            ?: return Resource.Error("Session expired. Please log in again.")
        if (!isSelectedStoreSession(userId, storeId, token)) {
            return Resource.Error("The account or selected store has changed.")
        }

        val result = getAssignedStores()
        if (result !is Resource.Success) {
            return Resource.Error(result.message ?: "Could not verify the selected store.")
        }
        if (!isSelectedStoreSession(userId, storeId, token)) {
            return Resource.Error("The account or selected store has changed.")
        }
        if (result.data.orEmpty().none { it.id == storeId && it.assignedUserId == userId }) {
            return Resource.Error("This store is not assigned to your account.")
        }
        return Resource.Success(token)
    }

    fun isSelectedStoreSession(userId: String, storeId: String, token: String): Boolean {
        return userId.isNotBlank() && storeId.isNotBlank() && token.isNotBlank() &&
            tokenManager.getAuthToken() == token && tokenManager.getUserId() == userId &&
            tokenManager.getSelectedStoreId() == storeId
    }

    override fun saveSelectedStoreId(storeId: String?) {
        tokenManager.saveSelectedStoreId(storeId)
    }

    override fun getSelectedStoreId(): String? {
        return tokenManager.getSelectedStoreId()
    }
}
