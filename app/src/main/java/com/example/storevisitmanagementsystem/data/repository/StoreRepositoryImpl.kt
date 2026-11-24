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

    override fun saveSelectedStoreId(storeId: String?) {
        tokenManager.saveSelectedStoreId(storeId)
    }

    override fun getSelectedStoreId(): String? {
        return tokenManager.getSelectedStoreId()
    }
}
