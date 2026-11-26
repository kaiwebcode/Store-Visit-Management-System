package com.example.storevisitmanagementsystem.data.remote.api

import com.example.storevisitmanagementsystem.data.remote.model.ApiResponse
import com.example.storevisitmanagementsystem.data.remote.model.LoginRequest
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.data.remote.model.PhotoUploadResponse
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.data.remote.model.SyncBatchRequest
import com.example.storevisitmanagementsystem.data.remote.model.SyncBatchResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
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

    @POST("tasks/sync")
    suspend fun syncTasks(
        @Header("Authorization") token: String,
        @Body request: SyncBatchRequest
    ): Response<ApiResponse<SyncBatchResponse>>

    @Multipart
    @POST("photos/upload")
    suspend fun uploadPhoto(
        @Header("Authorization") token: String,
        @Part("operationId") operationId: RequestBody,
        @Part("photoId") photoId: RequestBody,
        @Part("taskId") taskId: RequestBody,
        @Part file: MultipartBody.Part
    ): Response<ApiResponse<PhotoUploadResponse>>
}
