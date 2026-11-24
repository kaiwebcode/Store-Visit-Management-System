# Requirements & Acceptance Criteria
**Project:** Store Visit Management System  
**Package:** `com.example.storevisitmanagementsystem`  
**Milestone:** Week 4 (Days 15–19)

---

## 1. Executive Summary
The Store Visit Management System is an offline-first Android application designed for field agents conducting store visits. It enables agents to authenticate, select assigned stores, manage audit tasks, capture photo evidence, and reliably synchronize updates with a central backend when connectivity is available.

---

## 2. Functional Requirements (FR)

### FR-1: Authentication, Session Management & Delayed 401 Safety
- **FR-1.1 (Login & Session Launch):** Field agents authenticate with username and password against the backend API. An existing valid cached session token allows launching the app offline.
- **FR-1.2 (Online Re-Authentication):** Once the user manually logs out or the session expires, online re-authentication is strictly required to obtain a new Bearer token.
- **FR-1.3 (Data Safety on Logout & Expiry):** **CRITICAL:** Session expiry (HTTP 401) and manual logout MUST NEVER delete unsynchronized tasks, photo evidence, or pending sync queue items.
- **FR-1.4 (Account Isolation):** All local database records (`tasks`, `photo_evidence`, `sync_queue`, `store_assignments`) and photo storage paths (`context.filesDir/task_photos/{userId}/`) are strictly account-scoped by `userId`.
- **FR-1.5 (Delayed 401 Protection):** A delayed HTTP 401 error originating from an old network request or expired token MUST NOT clear or invalidate a newer active session. The app compares the 401 response's token with the current active token in `TokenManager` before triggering session invalidation.
- **FR-1.6 (Re-authentication & Recovery):** When an agent signs back in, pending unsynced operations for that `userId` are automatically recovered and queued for synchronization.

### FR-2: Account-Scoped Stores & Authorization
- **FR-2.1 (Store Assignment Model):** Store assignments are modeled via a composite account/store key (`userId`, `storeId`) in a `store_assignments` table to prevent cached store corruption when multiple agents share the same physical store.
- **FR-2.2 (Server-Side Authorization):** Backend API endpoints (`GET /stores/assigned`, `POST /stores/{id}/tasks`) validate that the Bearer token's `userId` is authorized for the requested store.
- **FR-2.3 (Search Behavior):** Real-time case-insensitive search across store name, store code, and address.
- **FR-2.4 (Store Context & Validation):** Selecting a store sets the active store context. Task creation validates that `storeId` is assigned to `currentUserId`.

### FR-3: Store Task Management & Deletion Semantics
- **FR-3.1 (Task Schema):** Tasks contain `taskId` (UUID), `storeId`, `userId`, `title`, `description`, `category`, `priority`, `status`, `dueDate`, `createdAt`, `updatedAt`, `syncStatus`, `syncError`, `isDeleted`.
- **FR-3.2 (Task CRUD & Atomic Queueing):** Creating, updating, or completing a task writes to `tasks` and inserts an immutable operation into `sync_queue` in a single atomic Room `@Transaction`.
- **FR-3.3 (Soft Deletion):** Deleting a task sets `isDeleted = true` locally and enqueues a `DELETE_TASK` operation in `sync_queue`. The local record and photos are purged only after receiving a server sync ACK.

### FR-4: Photo Evidence & Lifecycle Pipeline
- **FR-4.1 (Photo Evidence Model):** Tasks support 0..N photos modeled via `PhotoEvidenceEntity` (`photoId`, `taskId`, `userId`, `localFilePath`, `remoteUrl`, `fileSizeBytes`, `mimeType`, `uploadStatus` [`PENDING_UPLOAD`, `UPLOADING`, `UPLOADED`, `FAILED`]).
- **FR-4.2 (Execution Ordering):**
  1. Task created (`PENDING_INSERT`).
  2. Photo captured & saved atomically (`PhotoEvidence` created with `PENDING_UPLOAD`).
  3. Photo uploaded to server (`POST /photos/upload`) -> Server returns `remoteUrl` and marks photo `UPLOADED`.
  4. Task completion operation synced (`PUT /tasks/{id}`) referencing `remoteUrl`.
  5. Task and photo marked `SYNCED` locally only after both operations are acknowledged by server.
- **FR-4.3 (Crash & Storage Recovery):** Temporary capture files (`.tmp`) older than 24 hours are cleaned up on startup. Finalized images (`.jpg`) are stored atomically (`.tmp` write -> descriptor flush -> `.jpg` rename) in `context.filesDir/task_photos/{userId}/`.
- **FR-4.4 (Compression vs API Validation):** Image compressor downscales to max 1024x1024, JPEG ~80%, target size < 300KB. API upload validator enforces max 500KB and JPEG mime type.

### FR-5: WorkManager Recovery & Sync Scheduling
- **FR-5.1 (Unique Work Scheduling):** Scheduled via `WorkManager.enqueueUniqueWork("StoreVisitSyncWork", ExistingWorkPolicy.KEEP, ...)` with `NetworkType.CONNECTED`.
- **FR-5.2 (Recovery Triggers):** Sync is automatically triggered on: (a) App startup via `SyncRecoveryManager`, (b) Re-login, (c) Network reconnect listener (`ConnectivityManager`), and (d) Manual retry button tap.
- **FR-5.3 (Operation & Revision ACKs):** Server acknowledges matching `operationId` + `revision`. A task becomes `SYNCED` only when all required operations (creation/update + photo upload) receive ACK.
- **FR-5.4 (Error Disambiguation):** Transient errors (5xx, timeouts) trigger exponential backoff retry. Permanent errors (400, 422) mark item `SYNC_FAILED` with error reason for user review.

### FR-6: Idempotency & Operation Replay Protection
- **FR-6.1 (Disambiguation):** Domain Record IDs (`taskId`, `photoId`) are separated from Immutable Sync Operation IDs (`operationId`).
- **FR-6.2 (Account-Scoped Replay Keys):** Server checks `processed_operations` table by `userId` + `operationId`. Replaying an `operationId` returns `200 OK` with the stored response envelope.
- **FR-6.3 (Payload Validation):** Submitting the same `operationId` with a modified payload returns `400 BAD_REQUEST` / `409 CONFLICT`.

### FR-7: Standalone SQLite-Backed Mock Backend
- **FR-7.1 (Durable Mock Server):** Backed by a standalone SQLite database (`mock_server.db`) storing users, store assignments, tasks, photos, `processed_operations`, and session tokens.
- **FR-7.2 (Interceptors as Test Doubles):** OkHttp interceptors act as client-side test doubles and proxy routers for test failure injection (`X-Mock-Fail-Rate`, `X-Mock-Force-401`).

---

## 3. Non-Functional Requirements (NFR)

- **NFR-1 (Performance):** Cold launch < 2s; Room queries < 100ms for 100 tasks; Image compression < 500ms; smooth 60fps list scrolling.
- **NFR-2 (Data Safety & Reliability):** ZERO data loss for unsynced tasks or photos during logout, session expiry, app crash, or reboot.
- **NFR-3 (Storage Efficiency):** 50 photos consume < 15MB total internal storage space.
- **NFR-4 (Security & Privacy):** Tokens saved in `EncryptedSharedPreferences`. Photos stored in app-private directory.
- **NFR-5 (Target Schedule Baseline):** Target project effort is 40 hours. Schedule contingency buffer is allocated from setup optimization to absorb Day 18/19 complexity.

---

## 4. Measurable Acceptance Criteria

| Criteria ID | Feature Area | Measurable Acceptance Condition | Verification Method |
|:---|:---|:---|:---|
| **AC-AUTH-SAFETY-1** | Session Expiry Data Safety | Delayed 401 from old token does not clear active token; session expiry preserves 100% of unsynced Room tasks and photo files. | Mock 401 trigger + DB inspection |
| **AC-AUTH-SAFETY-2** | Offline Logout & Recovery | Logging out offline retains pending tasks under `userId`. Logging back in as same user restores queue and pending tasks. | Offline Logout + Re-login Test |
| **AC-STORE-ASSIGN-1**| Store Assignment Isolation | Store assignments use composite (`userId`, `storeId`) key. Two users sharing a store do not overwrite or corrupt each other's cached assignments. | Room StoreAssignment DAO Test |
| **AC-STRESS-100-50** | Scale (100 Tasks / 50 Photos) | Run-scoped test fixture creating 100 tasks and 50 photos offline persists cleanly; post-commit response-drop retry produces zero duplicate server records (`COUNT(*) = 100`). | Stress Test Script / Test Suite |
| **AC-SYNC-IDEMPOTENCY-1**| Operation Replay Protection | Submitting identical `operationId` returns cached `200 OK`. Submitting same `operationId` with altered payload returns `400 BAD_REQUEST`. | Mock Server Integration Test |
| **AC-WORK-RECOVERY-1**| WorkManager Recovery | Sync triggers on startup, re-login, network reconnect, and manual retry. Task becomes `SYNCED` only when both task and photo ACKs arrive. | WorkManager Integration Test |
