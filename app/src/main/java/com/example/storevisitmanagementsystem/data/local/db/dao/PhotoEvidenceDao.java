package com.example.storevisitmanagementsystem.data.local.db.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity;
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface PhotoEvidenceDao {
    @Query("SELECT * FROM photo_evidence WHERE task_id = :taskId AND user_id = :userId ORDER BY created_at ASC")
    Flow<List<PhotoEvidenceEntity>> getPhotosForTask(String taskId, String userId);

    @Query("SELECT * FROM photo_evidence WHERE task_id = :taskId AND user_id = :userId ORDER BY created_at ASC")
    List<PhotoEvidenceEntity> getPhotosForTaskSync(String taskId, String userId);

    @Query("SELECT * FROM photo_evidence WHERE photo_id = :photoId AND user_id = :userId LIMIT 1")
    PhotoEvidenceEntity getPhotoById(String photoId, String userId);

    @Query("SELECT local_file_path FROM photo_evidence")
    List<String> getAllLocalPhotoFilePaths();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPhoto(PhotoEvidenceEntity photo);

    @Query("UPDATE photo_evidence SET upload_status = :uploadStatus, remote_url = :remoteUrl WHERE photo_id = :photoId")
    void updatePhotoUploadStatus(String photoId, String uploadStatus, @Nullable String remoteUrl);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSyncQueue(SyncQueueEntity syncOp);

    @Delete
    void deletePhoto(PhotoEvidenceEntity photo);
}
