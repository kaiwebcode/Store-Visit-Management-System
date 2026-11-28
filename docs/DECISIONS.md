# Architectural Decisions, Session Limitations & Design Tradeoffs
**Project:** Store Visit Management System  
**Package:** `com.example.storevisitmanagementsystem`

---

## 1. Core Architectural Decisions

### ADR-01: Session Logout & Expiry Data Safety
- **Context:** Field agents logging out or encountering session expiry while offline must not lose unsynchronized audit tasks or photo evidence.
- **Decision:** Logout and HTTP 401 session expiry clear ONLY active authentication tokens in `TokenManager`. Room entities and photo files are preserved in account-isolated storage (`context.filesDir/task_photos/{userId}/`).
- **Rationale:** Prevents catastrophic data loss during offline field operations. Unsynced data is automatically recovered when the user re-authenticates.

### ADR-02: Standalone Node.js + Express + SQLite Test Backend (`server/`)
- **Context:** Synchronization requirements demand a durable test backend running as a separate process with persistent storage across restarts.
- **Decision:** Implement a Node.js Express server with SQLite storage (`server/data/backend.db`) and photo storage (`server/uploads/`).
- **Rationale:** Exposes real HTTP endpoints (`POST /v1/tasks/sync`, `POST /v1/photos/upload`), persists data across restarts, and supports controllable failure headers (`X-Mock-Fail-500`, `X-Mock-Timeout-After-Save`).

### ADR-03: Separation of Record IDs from Sync Operation IDs & Idempotency
- **Context:** Network retries without operation disambiguation risk creating duplicate database records on the server.
- **Decision:** Separate domain entity identities (`taskId`, `photoId`) from sync operation identities (`operationId`). Every queue item carries a unique `operationId`. The server records all executed operations in `processed_operations`.
- **Rationale:** When a timeout-after-server-save occurs and the client retries, the server recognizes the existing `operationId` in `processed_operations` and returns the cached response, producing exactly ONE logical record on the backend.

### ADR-04: Non-Destructive Room Migration (v1 -> v2) & Monotonic Queue Ordering
- **Context:** Operations must be executed in exact monotonic dependency order (Task Creation before Photo Upload). Existing v1 pending operations must retain deterministic order.
- **Decision:** Add `sequence_number` (INTEGER NOT NULL DEFAULT 0) to `SyncQueueEntity` and bump `AppDatabase` to version 2 with `MIGRATION_1_2` (`UPDATE sync_queue SET sequence_number = rowid WHERE sequence_number = 0`).
- **Rationale:** `MIGRATION_1_2` uses SQLite `rowid` to populate sequence numbers for pre-existing operations, guaranteeing deterministic replay order without data loss.

### ADR-05: WorkManager Unique Scheduling & Safe Operation Claiming
- **Context:** Background sync must run reliably when network connectivity is restored without launching duplicate concurrent sync jobs.
- **Decision:** Schedule sync with `WorkManager.enqueueUniqueWork("StoreVisitSyncWork_${userId}", KEEP, ...)` with `NetworkType.CONNECTED` constraints and exponential backoff.
- **Rationale:** Ensures single-instance execution per account, automatically retrying transient errors while avoiding race conditions.

### ADR-06: Release Synchronization Enabling & Permanent 400 Error Isolation
- **Context:** `BuildConfig.DEBUG` checks in `SyncWorker`, `SyncManager`, and `MockApiInterceptor` previously disabled synchronization in release build variants. Additionally, permanent client errors (HTTP 400) must not cause infinitely looping retries.
- **Decision:**
  1. Removed `!BuildConfig.DEBUG` blocks in `SyncWorker` and `SyncManager`, configured `MockApiInterceptor` to check `getBackendHost().isNotBlank()`, and updated `app/build.gradle.kts` release build type with `DAY17_BACKEND_HOST`.
  2. Permanent client rejections (HTTP 400–499 excluding 408/429) mark the queued operation status as `REJECTED` and the task as `SYNC_FAILED`.
- **Rationale:** Enables full synchronization capability for release build artifacts while isolating unrecoverable client errors so that valid independent queue items continue to synchronize without infinite retry loops. Local data and photos remain safely preserved on disk.
