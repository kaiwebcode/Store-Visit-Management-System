package com.example.storevisitmanagementsystem.data.repository

import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.util.Resource

interface IStoreRepository {
    suspend fun getAssignedStores(query: String? = null): Resource<List<StoreDto>>
    fun saveSelectedStoreId(storeId: String?)
    fun getSelectedStoreId(): String?
}
