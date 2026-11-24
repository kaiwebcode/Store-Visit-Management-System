package com.example.storevisitmanagementsystem.data.remote.model

data class StoreDto(
    val id: String,
    val code: String,
    val name: String,
    val address: String,
    val category: String,
    val assignedUserId: String,
    val lastVisited: String? = null
)
