package com.example.storevisitmanagementsystem.data.repository

import android.content.Context
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal interface AssignedStoreCache {
    fun read(userId: String): List<StoreDto>
    fun write(userId: String, stores: List<StoreDto>)
    fun clear(userId: String)
}

internal class PreferencesAssignedStoreCache(context: Context) : AssignedStoreCache {
    private val prefs = context.getSharedPreferences("assigned_store_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<StoreDto>>() {}.type

    override fun read(userId: String): List<StoreDto> {
        val json = prefs.getString(userId, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<StoreDto>>(json, listType).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.assignedUserId == userId }
    }

    override fun write(userId: String, stores: List<StoreDto>) {
        prefs.edit().putString(userId, gson.toJson(stores)).apply()
    }

    override fun clear(userId: String) {
        prefs.edit().remove(userId).apply()
    }
}
