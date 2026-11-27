package com.example.storevisitmanagementsystem.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object InFlightSync {
    data class Record(val userId: String, val id: String)

    private val activeTasks = MutableStateFlow<Set<Record>>(emptySet())
    private val activePhotos = MutableStateFlow<Set<Record>>(emptySet())
    val tasks = activeTasks.asStateFlow()
    val photos = activePhotos.asStateFlow()

    suspend fun <T> during(
        taskRecords: Set<Record>,
        photoRecord: Record? = null,
        request: suspend () -> T
    ): T {
        activeTasks.update { it + taskRecords }
        photoRecord?.let { photo -> activePhotos.update { it + photo } }
        return try {
            request()
        } finally {
            activeTasks.update { it - taskRecords }
            photoRecord?.let { photo -> activePhotos.update { it - photo } }
        }
    }
}
