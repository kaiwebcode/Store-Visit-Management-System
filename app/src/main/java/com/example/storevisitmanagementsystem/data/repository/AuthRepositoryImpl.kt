package com.example.storevisitmanagementsystem.data.repository

import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.LoginRequest
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.util.Resource
import java.io.IOException

class AuthRepositoryImpl(
    private val apiService: ApiService,
    private val tokenManager: ITokenManager
) : IAuthRepository {

    override suspend fun login(username: String, pass: String): Resource<LoginResponse> {
        return try {
            val response = apiService.login(LoginRequest(username, pass))
            if (response.isSuccessful) {
                val apiResp = response.body()
                if (apiResp != null && apiResp.success && apiResp.data != null) {
                    val loginData = apiResp.data
                    tokenManager.saveSession(
                        token = loginData.token,
                        userId = loginData.userId,
                        username = loginData.username,
                        name = loginData.name,
                        role = loginData.role
                    )
                    Resource.Success(loginData)
                } else {
                    val msg = apiResp?.error?.message ?: "Invalid credentials"
                    Resource.Error(msg)
                }
            } else {
                Resource.Error("Invalid username or password.")
            }
        } catch (e: IOException) {
            Resource.Error("Network error. Please check your connection and try again.")
        } catch (e: Exception) {
            Resource.Error("An unexpected error occurred: ${e.localizedMessage}")
        }
    }

    override suspend fun logout(): Resource<Unit> {
        val currentToken = tokenManager.getAuthToken()
        if (currentToken != null) {
            tokenManager.handle401(currentToken)
            try {
                apiService.logout("Bearer $currentToken")
            } catch (ignored: Exception) {
                // Ignore network errors during logout
            }
        }
        return Resource.Success(Unit)
    }

    override fun isLoggedIn(): Boolean {
        return tokenManager.isLoggedIn()
    }

    override fun getActiveUserId(): String? {
        return tokenManager.getUserId()
    }

    override fun getActiveUserName(): String? {
        return tokenManager.getUserName()
    }
}
