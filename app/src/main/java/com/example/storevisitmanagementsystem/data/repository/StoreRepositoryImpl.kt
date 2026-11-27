package com.example.storevisitmanagementsystem.data.repository

import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.util.Resource
import java.io.IOException
import kotlinx.coroutines.CancellationException

class StoreRepositoryImpl internal constructor(
    private val apiService: ApiService,
    private val tokenManager: ITokenManager,
    private val cache: AssignedStoreCache? = null
) : IStoreRepository {

    override suspend fun getAssignedStores(query: String?): Resource<List<StoreDto>> {
        val userId = tokenManager.getUserId()
            ?: return Resource.Error("Session expired. Please log in again.")
        val token = tokenManager.getAuthTokenForUser(userId)
            ?: return Resource.Error("Session expired. Please log in again.")

        return try {
            val response = apiService.getAssignedStores("Bearer $token", query)
            if (response.isSuccessful) {
                val apiResp = response.body()
                if (apiResp != null && apiResp.success && apiResp.data != null) {
                    if (tokenManager.getAuthTokenForUser(userId) != token) {
                        return sessionChanged()
                    }
                    val stores = apiResp.data.filter { it.assignedUserId == userId }
                    cache?.let {
                        val authorized = if (query.isNullOrBlank()) stores
                            else (it.read(userId) + stores).associateBy(StoreDto::id).values.toList()
                        it.write(userId, authorized)
                    }
                    Resource.Success(stores)
                } else {
                    val msg = apiResp?.error?.message ?: "Failed to load assigned stores."
                    Resource.Error(msg)
                }
            } else if (response.code() == 401) {
                if (tokenManager.handle401(token)) cache?.clear(userId)
                sessionChanged()
            } else {
                Resource.Error("Error loading stores (HTTP ${response.code()}).")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (tokenManager.getAuthTokenForUser(userId) != token) return sessionChanged()
            val cached = cache?.read(userId)
            if (cached.isNullOrEmpty()) {
                Resource.Error("Network error. Could not connect to store service.")
            } else {
                val search = query.orEmpty().trim()
                Resource.Success(cached.filter { store ->
                    store.assignedUserId == userId &&
                        listOf(store.name, store.code, store.address).any { it.contains(search, ignoreCase = true) }
                })
            }
        } catch (e: Exception) {
            Resource.Error("An unexpected error occurred: ${e.localizedMessage}")
        }
    }

    private fun sessionChanged(): Resource.Error<List<StoreDto>> =
        if (tokenManager.isLoggedIn()) Resource.Error("The account has changed.")
        else Resource.Error("Session expired. Please log in again.")

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

    fun validateSelectedStoreLocally(userId: String, storeId: String): Resource<String> {
        val token = tokenManager.getAuthToken()?.takeIf { it.isNotBlank() }
            ?: return Resource.Error("Session expired. Please log in again.")
        if (!isSelectedStoreSession(userId, storeId, token)) {
            return Resource.Error("The account or selected store has changed.")
        }
        return Resource.Success(token)
    }

    fun isSelectedStoreSession(userId: String, storeId: String, token: String): Boolean {
        return userId.isNotBlank() && storeId.isNotBlank() && token.isNotBlank() &&
            tokenManager.getAuthToken() == token && tokenManager.getUserId() == userId &&
            tokenManager.getSelectedStoreId() == storeId &&
            (cache == null || cache.read(userId).any { it.id == storeId && it.assignedUserId == userId })
    }

    override fun saveSelectedStoreId(storeId: String?) {
        tokenManager.saveSelectedStoreId(storeId)
    }

    override fun getSelectedStoreId(): String? {
        return tokenManager.getSelectedStoreId()
    }
}
