package com.example.storevisitmanagementsystem.data.local.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue WHERE user_id = :userId AND status = 'PENDING' ORDER BY created_at ASC")
    Flow<List<SyncQueueEntity>> getPendingOperations(String userId);

    @Query("SELECT * FROM sync_queue WHERE user_id = :userId AND status = 'PENDING' ORDER BY created_at ASC")
    List<SyncQueueEntity> getPendingOperationsSync(String userId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOperation(SyncQueueEntity operation);

    @Query("DELETE FROM sync_queue WHERE operation_id = :operationId")
    void deleteOperation(String operationId);
}
