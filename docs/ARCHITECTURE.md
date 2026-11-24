# Technical Architecture Document
**Project:** Store Visit Management System  
**Package:** `com.example.storevisitmanagementsystem`  
**Architecture Pattern:** MVVM + Clean Architecture + Offline-First SSOT

---

## 1. Architectural Overview

The Store Visit Management System follows Google's recommended modern Android application architecture based on an **Offline-First**, **Single Source of Truth (SSOT)** design.

```
                  +-----------------------------------+
                  |            UI Layer               |
                  |  (XML Views, ViewBinding, State)  |
                  +-----------------+-----------------+
                                    | Observes StateFlow
                                    v
                  +-----------------------------------+
                  |          ViewModel Layer          |
                  | (StoreViewModel, TaskViewModel,   |
                  |   PhotoViewModel, SyncViewModel)  |
                  +-----------------+-----------------+
                                    | Calls Repository
                                    v
                  +-----------------------------------+
                  |         Repository Layer          |
                  | (AuthRepo, StoreRepo, TaskRepo,   |
                  |   PhotoRepo, SyncRepository)      |
                  +--------+-----------------+--------+
                           |                 |
         Reads/Writes Local|                 | Triggers WorkManager / HTTP
                           v                 v
        +--------------------+             +-----------------------------+
        |   Local Data Source|             |      Remote Data Source     |
        |  (Room DB, Files,  |             | (Retrofit -> Standalone     |
        |   TokenManager)    |             |  SQLite-Backed Mock Backend)|
        +--------------------+             +-----------------------------+
```

---

## 2. Core Architectural Mechanisms

### 2.1 Session Safety, Delayed 401 Handling & Account Isolation

#### Account Isolation
All local data entities (`tasks`, `photo_evidence`, `sync_queue`, `store_assignments`) and photo file directories are strictly isolated by `userId`.
- Room scoping: `SELECT * FROM tasks WHERE user_id = :userId`
- File storage scoping: `context.filesDir/task_photos/{userId}/photo_{photoId}.jpg`

#### Delayed 401 Protection
To prevent a delayed 401 response from an old network request from invalidating a newly authenticated session:
1. `TokenManager` tracks `activeToken` and `activeUserId`.
2. When a 401 response is intercepted, `AuthInterceptor` checks:
   ```kotlin
   if (requestToken == tokenManager.getActiveToken()) {
       tokenManager.clearActiveTokenOnly()
       eventBus.emit(SessionExpiredEvent)
   } // Else: Ignore delayed 401 from superseded session token
   ```

#### Data Retention on Logout & Expiry
Logging out or encountering session expiry clears active credentials in `TokenManager`, BUT **NEVER DELETES** Room database records, queue operations, or photo files. Unsynced data is preserved under `userId` and recovered when the user re-authenticates.

---

### 2.2 Account-Scoped Store Assignment Model

To prevent two agents sharing a store from corrupting cached assignments:
- **`StoreEntity`**: Store metadata (`storeId`, `storeCode`, `name`, `address`, `category`, `lastVisited`).
- **`StoreAssignmentEntity`**: Account-scoped assignment junction (`userId`, `storeId`, `assignedAt`).
- **Server-Side Authorization:** The backend validates that the Bearer token's `userId` matches `StoreAssignment` before fulfilling store or task requests.
- **Client Validation:** `TaskRepository.createTask(...)` asserts `isStoreAssignedToUser(storeId, currentUserId)` before enqueuing task creation.

---

### 2.3 Standalone SQLite-Backed Mock API & Idempotent Replay Protocol

#### Backend Architecture (`mock_server.db`)
The mock backend is a standalone SQLite-backed HTTP service containing:
- `users`, `stores`, `store_assignments`, `tasks`, `photo_evidence`, `processed_operations`.

#### Replay Keys & Payload Validation
1. **Disambiguation:** `taskId`/`photoId` = stable domain entity IDs; `operationId` = immutable sync operation ID.
2. **Replay Table Schema:**
   ```sql
   CREATE TABLE processed_operations (
       operation_id TEXT PRIMARY KEY,
       user_id TEXT NOT NULL,
       payload_hash TEXT NOT NULL,
       response_json TEXT NOT NULL,
       processed_at INTEGER NOT NULL
   );
   ```
3. **Idempotency Rule:**
   - If `operation_id` exists and `payload_hash` matches: Returns cached `200 OK` response envelope.
   - If `operation_id` exists BUT `payload_hash` differs: Returns `400 BAD_REQUEST` / `409 CONFLICT` ("Operation ID payload mismatch").

---

### 2.4 Photo Evidence Lifecycle & Durable File Management

#### Lifecycle Ordering
1. **Task Creation:** Local `TaskEntity` created (`syncStatus = PENDING_INSERT`).
2. **Photo Capture:** Image captured to `.tmp`, compressed, and atomically renamed to `.jpg` in `context.filesDir/task_photos/{userId}/`. `PhotoEvidenceEntity` created (`uploadStatus = PENDING_UPLOAD`).
3. **Photo Upload:** `POST /photos/upload` executes. Mock server returns `remoteUrl` and sets status `UPLOADED`.
4. **Task Completion:** `PUT /tasks/{id}` executes referencing `remoteUrl`.
5. **Final Local ACK:** Task and Photo marked `SYNCED` locally only when both operations receive server ACK.

#### File Crash Recovery
- On app launch, `ImageStorageManager.cleanupOrphanedTempFiles()` deletes `.tmp` files older than 24 hours.
- Finalized `.jpg` files remain preserved until server task ACK.

#### Compression vs API Validation
- **Client Compressor:** Downscales to max 1024x1024, JPEG ~80%, target size < 300KB.
- **API Upload Validator:** Server validates file size <= 500KB and mime type `image/jpeg`.

---

### 2.5 WorkManager Recovery Engine & Safe Acknowledgements

#### Unique Work & Recovery Triggers
Work is enqueued via `WorkManager.enqueueUniqueWork("StoreVisitSyncWork", ExistingWorkPolicy.KEEP, ...)` with `NetworkType.CONNECTED`.

Synchronization is triggered by 4 distinct events:
1. **App Startup:** `SyncRecoveryManager.onAppLaunch()` checks pending queue items.
2. **Re-Login:** `LoginViewModel` triggers sync upon successful re-authentication.
3. **Network Reconnect:** `ConnectivityManager` listener triggers sync on network recovery.
4. **Manual Retry:** User taps "Retry Failed Sync" button in Sync Status UI.

#### Revision ACKs & Transient vs Permanent Errors
- Server returns `succeededOperationIds` with operation revision numbers.
- A task transitions to `SYNCED` only when all required operations (creation/update + photo upload) receive ACK.
- **Transient Failures (5xx, Network Timeout):** Trigger exponential backoff retry.
- **Permanent Failures (400, 422):** Mark item `SYNC_FAILED` with error reason for user review.

---

### 2.6 Package Structure & DI Interfaces

```
com.example.storevisitmanagementsystem/
├── data/
│   ├── local/
│   │   ├── db/
│   │   │   ├── AppDatabase.kt
│   │   │   ├── dao/
│   │   │   │   ├── StoreDao.kt
│   │   │   │   ├── StoreAssignmentDao.kt
│   │   │   │   ├── TaskDao.kt
│   │   │   │   ├── PhotoEvidenceDao.kt
│   │   │   │   └── SyncQueueDao.kt
│   │   │   └── entity/
│   │   │       ├── StoreEntity.kt
│   │   │       ├── StoreAssignmentEntity.kt
│   │   │       ├── TaskEntity.kt
│   │   │       ├── PhotoEvidenceEntity.kt
│   │   │       └── SyncQueueEntity.kt
│   │   ├── preferences/
│   │   │   ├── ITokenManager.kt
│   │   │   └── TokenManagerImpl.kt
│   │   └── storage/
│   │       ├── IImageStorageManager.kt
│   │       └── ImageStorageManagerImpl.kt
│   ├── remote/
│   │   ├── api/
│   │   │   ├── ApiService.kt
│   │   │   └── mock/
│   │   │       ├── MockApiInterceptor.kt
│   │   │       ├── StandaloneMockServer.kt
│   │   │       └── MockDatabase.kt  // SQLite mock_server.db
│   │   └── model/
│   │       ├── request/
│   │       └── response/
│   └── repository/
│       ├── IAuthRepository.kt / AuthRepositoryImpl.kt
│       ├── IStoreRepository.kt / StoreRepositoryImpl.kt
│       ├── ITaskRepository.kt / TaskRepositoryImpl.kt
│       ├── IPhotoRepository.kt / PhotoRepositoryImpl.kt
│       └── ISyncRepository.kt / SyncRepositoryImpl.kt
├── sync/
│   ├── SyncWorker.kt
│   └── SyncRecoveryManager.kt
├── ui/
│   ├── auth/
│   │   ├── LoginActivity.kt
│   │   └── LoginViewModel.kt
│   ├── store/
│   │   ├── StoreSelectionActivity.kt
│   │   ├── StoreAdapter.kt
│   │   └── StoreViewModel.kt
│   ├── task/
│   │   ├── TaskListActivity.kt
│   │   ├── TaskCreateEditActivity.kt
│   │   ├── TaskAdapter.kt
│   │   └── TaskViewModel.kt
│   ├── photo/
│   │   ├── PhotoPreviewActivity.kt
│   │   └── PhotoViewModel.kt
│   └── sync/
│       ├── SyncStatusActivity.kt
│       └── SyncStatusViewModel.kt
└── util/
    ├── ImageCompressor.kt
    ├── NetworkUtils.kt
    └── Constants.kt
```
