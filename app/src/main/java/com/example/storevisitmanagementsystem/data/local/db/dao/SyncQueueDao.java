package com.example.storevisitmanagementsystem.data.local.db.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue WHERE user_id = :userId AND status = 'PENDING' ORDER BY sequence_number ASC, created_at ASC")
    Flow<List<SyncQueueEntity>> getPendingOperations(String userId);

    @Query("SELECT * FROM sync_queue WHERE user_id = :userId AND status IN ('PENDING', 'SYNCING', 'FAILED') ORDER BY sequence_number ASC, created_at ASC, operation_id ASC")
    List<SyncQueueEntity> getPendingOperationsSync(String userId);

    @Query("SELECT MAX(sequence_number) FROM sync_queue")
    @Nullable
    Long getMaxSequenceNumber();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOperation(SyncQueueEntity operation);

    @Query("UPDATE sync_queue SET status = :status, error_message = :errorMessage WHERE operation_id = :operationId")
    void updateOperationStatus(String operationId, String status, @Nullable String errorMessage);

    @Query("DELETE FROM sync_queue WHERE operation_id = :operationId")
    void deleteOperation(String operationId);

    @Query("DELETE FROM sync_queue WHERE operation_id IN (:operationIds)")
    void deleteCompletedOperations(List<String> operationIds);
}
