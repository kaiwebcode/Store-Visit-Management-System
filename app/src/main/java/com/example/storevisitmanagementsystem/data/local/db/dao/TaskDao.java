package com.example.storevisitmanagementsystem.data.local.db.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity;
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface TaskDao {
    @Query("SELECT * FROM tasks WHERE user_id = :userId AND store_id = :storeId AND is_deleted = 0 ORDER BY created_at DESC")
    Flow<List<TaskEntity>> getTasksForStore(String userId, String storeId);

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND store_id = :storeId AND is_deleted = 0 ORDER BY created_at DESC")
    List<TaskEntity> getTasksForStoreSync(String userId, String storeId);

    @Query("SELECT * FROM tasks WHERE task_id = :taskId AND user_id = :userId AND store_id = :storeId AND is_deleted = 0 LIMIT 1")
    Flow<TaskEntity> getTaskById(String taskId, String userId, String storeId);

    @Nullable
    @Query("SELECT * FROM tasks WHERE task_id = :taskId AND user_id = :userId AND store_id = :storeId AND is_deleted = 0 LIMIT 1")
    TaskEntity getTaskByIdSync(String taskId, String userId, String storeId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertTask(TaskEntity task);

    @Update
    void updateTask(TaskEntity task);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSyncQueue(SyncQueueEntity syncOp);
}
