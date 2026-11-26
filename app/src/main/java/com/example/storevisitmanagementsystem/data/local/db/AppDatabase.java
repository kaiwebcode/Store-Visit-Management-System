package com.example.storevisitmanagementsystem.data.local.db;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
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
    version = 2,
    exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract TaskDao taskDao();
    public abstract PhotoEvidenceDao photoEvidenceDao();
    public abstract SyncQueueDao syncQueueDao();

    public static final String DATABASE_NAME = "store_visit_app.db";
    private static volatile AppDatabase INSTANCE;

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE sync_queue ADD COLUMN sequence_number INTEGER NOT NULL DEFAULT 0");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_queue_user_id_status_sequence_number` ON `sync_queue` (`user_id`, `status`, `sequence_number`)");
            database.execSQL("UPDATE sync_queue SET sequence_number = rowid WHERE sequence_number = 0");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        DATABASE_NAME
                    )
                    .addMigrations(MIGRATION_1_2)
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
