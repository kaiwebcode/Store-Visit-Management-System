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
    String DISPLAY_COLUMNS = "tasks.task_id, tasks.store_id, tasks.user_id, tasks.title, tasks.description, " +
        "tasks.category, tasks.priority, tasks.status, tasks.due_date, tasks.created_at, tasks.updated_at, " +
        "CASE WHEN EXISTS (SELECT 1 FROM photo_evidence p WHERE p.task_id = tasks.task_id " +
        "AND p.user_id = tasks.user_id AND p.upload_status = 'UPLOAD_FAILED') THEN 'SYNC_FAILED' " +
        "WHEN tasks.sync_status IN ('SYNCED', 'SYNCING') AND EXISTS (SELECT 1 FROM photo_evidence p " +
        "WHERE p.task_id = tasks.task_id AND p.user_id = tasks.user_id AND p.upload_status != 'UPLOADED') " +
        "THEN 'PENDING_UPDATE' WHEN tasks.sync_status = 'SYNCING' THEN 'PENDING_UPDATE' " +
        "ELSE tasks.sync_status END AS sync_status, tasks.sync_error, tasks.is_deleted";

    @Query("SELECT " + DISPLAY_COLUMNS + " FROM tasks WHERE user_id = :userId AND store_id = :storeId AND is_deleted = 0 ORDER BY created_at DESC")
    Flow<List<TaskEntity>> getTasksForStore(String userId, String storeId);

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND store_id = :storeId AND is_deleted = 0 ORDER BY created_at DESC")
    List<TaskEntity> getTasksForStoreSync(String userId, String storeId);

    @Query("SELECT " + DISPLAY_COLUMNS + " FROM tasks WHERE task_id = :taskId AND user_id = :userId AND store_id = :storeId AND is_deleted = 0 LIMIT 1")
    Flow<TaskEntity> getTaskById(String taskId, String userId, String storeId);

    @Nullable
    @Query("SELECT * FROM tasks WHERE task_id = :taskId AND user_id = :userId AND store_id = :storeId AND is_deleted = 0 LIMIT 1")
    TaskEntity getTaskByIdSync(String taskId, String userId, String storeId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertTask(TaskEntity task);

    @Update
    void updateTask(TaskEntity task);

    @Query("UPDATE tasks SET sync_status = :syncStatus, sync_error = :syncError WHERE task_id = :taskId")
    void updateTaskSyncStatus(String taskId, String syncStatus, @Nullable String syncError);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSyncQueue(SyncQueueEntity syncOp);
}
