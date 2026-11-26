package com.example.storevisitmanagementsystem.data.remote.model

import com.google.gson.annotations.SerializedName

data class SyncOperationDto(
    @SerializedName("operationId") val operationId: String,
    @SerializedName("entityType") val entityType: String,
    @SerializedName("entityId") val entityId: String,
    @SerializedName("operationType") val operationType: String,
    @SerializedName("userId") val userId: String,
    @SerializedName("payload") val payload: Any,
    @SerializedName("sequenceNumber") val sequenceNumber: Long = 0L
)

data class SyncBatchRequest(
    @SerializedName("batchId") val batchId: String,
    @SerializedName("operations") val operations: List<SyncOperationDto>
)

data class FailedOperationDto(
    @SerializedName("operationId") val operationId: String,
    @SerializedName("reason") val reason: String
)

data class SyncBatchResponse(
    @SerializedName("processedCount") val processedCount: Int,
    @SerializedName("succeededOperationIds") val succeededOperationIds: List<String>,
    @SerializedName("failedOperations") val failedOperations: List<FailedOperationDto> = emptyList()
)

data class PhotoUploadResponse(
    @SerializedName("photoId") val photoId: String,
    @SerializedName("taskId") val taskId: String,
    @SerializedName("operationId") val operationId: String,
    @SerializedName("remoteUrl") val remoteUrl: String,
    @SerializedName("status") val status: String
)
