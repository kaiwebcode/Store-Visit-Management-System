package com.example.storevisitmanagementsystem.data.remote.model

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
    val timestamp: String? = null
)

data class ApiError(
    val code: String,
    val message: String
)
