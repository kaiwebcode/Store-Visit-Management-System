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
        |  (Room DB v2,      |             | (Retrofit -> Dedicated      |
        |   Files, TokenMgr) |             |  Node.js + SQLite Backend)  |
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
2. When a 401 response is intercepted:
   - Active token is cleared in `TokenManager`.
   - WorkManager halts retries (`Result.failure()`).
   - **CRITICAL DATA PRESERVATION:** All local Room database records, queue operations, and photo files remain preserved under `userId`.

---

### 2.2 WorkManager Synchronization & Monotonic Replay Engine

#### Unique Scheduling & Constraints
Sync operations are enqueued via `SyncManager` using `WorkManager.enqueueUniqueWork("StoreVisitSyncWork_${userId}", ExistingWorkPolicy.KEEP, ...)` with `NetworkType.CONNECTED` constraint and `BackoffPolicy.EXPONENTIAL`.

#### Room Non-Destructive Migration (v1 -> v2) & Monotonic Ordering
`AppDatabase` version 2 adds `sequence_number` (INTEGER NOT NULL DEFAULT 0) to `SyncQueueEntity`.
`MIGRATION_1_2` populates `sequence_number = rowid` for existing v1 operations, preserving exact insertion order.
Operations are queried with `ORDER BY sequence_number ASC, created_at ASC` to guarantee task creation precedes dependent photo uploads.

---

### 2.3 Standalone Node.js + Express + SQLite Sync Backend (`server/`)

#### Backend Architecture
A dedicated Node.js server running in `server/` with persistent SQLite database `server/data/backend.db` and photo upload folder `server/uploads/`.

#### Idempotency & Replay Protocol
The backend tracks `processed_operations(operation_id, user_id, payload_hash, response_json, processed_at)`.
When a replayed request arrives (e.g. following a timeout-after-server-save scenario), the server returns the stored success response without creating duplicate database records on the backend.

---

### 2.4 Package Structure & DI Interfaces

```
com.example.storevisitmanagementsystem/
├── data/
│   ├── local/
│   │   ├── db/
│   │   │   ├── AppDatabase.java (v2 + MIGRATION_1_2)
│   │   │   ├── dao/
│   │   │   │   ├── TaskDao.java
│   │   │   │   ├── PhotoEvidenceDao.java
│   │   │   │   └── SyncQueueDao.java
│   │   │   └── entity/
│   │   │       ├── TaskEntity.kt
│   │   │       ├── PhotoEvidenceEntity.kt
│   │   │       └── SyncQueueEntity.kt
│   │   └── preferences/
│   │       ├── ITokenManager.kt
│   │       └── TokenManagerImpl.kt
│   ├── remote/
│   │   ├── api/
│   │   │   ├── ApiService.kt
│   │   │   ├── ApiClient.kt
│   │   │   └── mock/
│   │   │       └── MockApiInterceptor.kt
│   │   └── model/
│   │       └── SyncModels.kt
│   └── repository/
│       ├── AuthRepositoryImpl.kt
│       ├── StoreRepositoryImpl.kt
│       ├── TaskRepositoryImpl.kt
│       ├── PhotoRepositoryImpl.kt
│       └── SyncRepositoryImpl.kt
├── sync/
│   ├── SyncWorker.kt
│   └── SyncManager.kt
├── ui/
│   └── task/
│       ├── TaskListActivity.kt
│       ├── TaskCreateEditActivity.kt
│       ├── TaskDetailActivity.kt
│       ├── TaskAdapter.kt
│       └── PhotoAdapter.kt
└── util/
    └── Constants.kt
```
