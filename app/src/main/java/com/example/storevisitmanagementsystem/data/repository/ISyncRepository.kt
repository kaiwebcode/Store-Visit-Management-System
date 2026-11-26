package com.example.storevisitmanagementsystem.data.repository

import com.example.storevisitmanagementsystem.util.Resource

interface ISyncRepository {
    suspend fun syncPendingOperations(userId: String): Resource<Int>
}
