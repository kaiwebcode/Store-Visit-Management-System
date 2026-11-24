package com.example.storevisitmanagementsystem.data.repository

import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.util.Resource

interface IAuthRepository {
    suspend fun login(username: String, pass: String): Resource<LoginResponse>
    suspend fun logout(): Resource<Unit>
    fun isLoggedIn(): Boolean
    fun getActiveUserId(): String?
    fun getActiveUserName(): String?
}
