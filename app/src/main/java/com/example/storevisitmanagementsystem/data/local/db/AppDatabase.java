package com.example.storevisitmanagementsystem.data.local.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import com.example.storevisitmanagementsystem.data.local.db.dao.PhotoEvidenceDao;
import com.example.storevisitmanagementsystem.data.local.db.dao.SyncQueueDao;
import com.example.storevisitmanagementsystem.data.local.db.dao.TaskDao;
import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity;
import com.example.storevisitmanagementsystem.data.local.db.entity.SyncQueueEntity;
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity;

@Database(
    entities = {
        TaskEntity.class,
        PhotoEvidenceEntity.class,
        SyncQueueEntity.class
    },
    version = 1,
    exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract TaskDao taskDao();
    public abstract PhotoEvidenceDao photoEvidenceDao();
    public abstract SyncQueueDao syncQueueDao();

    public static final String DATABASE_NAME = "store_visit_app.db";
    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        DATABASE_NAME
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
