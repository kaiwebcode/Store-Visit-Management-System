package com.example.storevisitmanagementsystem.data.remote.model

data class LoginResponse(
    val token: String,
    val userId: String,
    val username: String,
    val name: String,
    val role: String,
    val expiresAt: String
)
