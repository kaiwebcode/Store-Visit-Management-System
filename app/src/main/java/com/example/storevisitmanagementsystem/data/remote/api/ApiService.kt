package com.example.storevisitmanagementsystem.data.remote.api

import com.example.storevisitmanagementsystem.data.remote.model.ApiResponse
import com.example.storevisitmanagementsystem.data.remote.model.LoginRequest
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<ApiResponse<LoginResponse>>

    @POST("auth/logout")
    suspend fun logout(
        @Header("Authorization") token: String
    ): Response<ApiResponse<Map<String, String>>>

    @GET("stores/assigned")
    suspend fun getAssignedStores(
        @Header("Authorization") token: String,
        @Query("query") query: String? = null
    ): Response<ApiResponse<List<StoreDto>>>
}
