# Architectural Decisions & Design Tradeoffs
**Project:** Store Visit Management System  
**Package:** `com.example.storevisitmanagementsystem`

---

## 1. Summary of Codex Architectural Findings & Resolutions

### Finding 1: Session Expiry & Delayed 401 Protection (CRITICAL)
- **Correction:** Manual logout and 401 session expiry clear ONLY active authentication tokens in `TokenManager`. Room entities and photo files are preserved in account-isolated storage (`context.filesDir/task_photos/{userId}/`).
- **Delayed 401 Fix:** `AuthInterceptor` verifies that a 401 response's Bearer token matches the currently active token in `TokenManager` before triggering session invalidation, preventing delayed responses from superseded sessions from clearing new logins.

### Finding 2: Standalone SQLite-Backed Mock Backend & Operation Replay (CRITICAL)
- **Correction:** Replaced in-process mock server with a standalone SQLite-backed test backend (`mock_server.db`).
- **Idempotency Fix:** Domain Record IDs (`taskId`, `photoId`) are separated from Immutable Operation IDs (`operationId`). The mock server maintains a `processed_operations` table (`operation_id`, `user_id`, `payload_hash`, `response_json`). Submitting a duplicate `operationId` returns cached `200 OK`; submitting a duplicate `operationId` with an altered payload returns `400 BAD_REQUEST`.

### Finding 3: Photo Evidence Lifecycle & File Recovery (HIGH)
- **Correction:** Defined explicit execution ordering: Task Create -> Photo Capture -> Photo Upload (`POST /photos/upload`) -> Task Complete (`PUT /tasks/{id}`) -> Server ACK -> Local `SYNCED`.
- **Crash Recovery:** Startup script purges temporary `.tmp` files older than 24 hours. Finalized `.jpg` files remain preserved until server ACK.
- **Compression Alignment:** Client compressor enforces max 1024x1024, JPEG ~80%, target size < 300KB. API validator enforces max 500KB and JPEG mime type.

### Finding 4: WorkManager Recovery & Safe Acknowledgements (HIGH)
- **Correction:** Specified 4 recovery triggers: App startup (`SyncRecoveryManager`), Re-login, Network reconnect (`ConnectivityManager`), and Manual retry UI button.
- **Safe ACK:** A task becomes `SYNCED` locally only when both the photo upload operation and task completion operation receive server ACK.

### Finding 5: Account-Scoped Store Assignments (MEDIUM/LOW)
- **Correction:** Created `StoreAssignmentEntity` with composite key (`userId`, `storeId`). Prevents multi-user store caching corruption. Server validates Bearer token `userId` for store access authorization.

### Finding 6: Stress-Test Acceptance & Baseline Schedule (MEDIUM/LOW)
- **Correction:** Defined run-scoped stress-test fixture for 100 tasks and 50 photos. Specified post-commit response-drop scenario where server stores record but network drops response, verifying zero duplicate server records on client retry.
- **Schedule Target:** Preserved 40-hour baseline target. Contingency buffer allocated from setup optimization to absorb Days 18–19 complexity.

---

## 2. Documented Tradeoffs

| Area | Option Chosen | Alternative Considered | Tradeoff Analysis |
|:---|:---|:---|:---|
| **Data Safety** | Retain unsynced tasks on logout/401 | Wipe local DB on logout | Wiping local DB is simpler for single-user security, but causes unacceptable data loss for field agents. Account isolation by `userId` provides both security and data safety. |
| **Mock Engine** | Standalone SQLite Mock API (`mock_server.db`) | Pure In-Memory Interceptor | In-memory interceptors require less setup, but cannot test multi-session persistence, app process restarts, or real database idempotency. |
| **Store Caching** | Composite `StoreAssignment` (`userId`, `storeId`) | Global `Store` table only | Global store tables corrupt cached assignments when multiple users share a physical store. Composite assignment keys guarantee account isolation. |

---

## 3. Deferred Risks & Schedule Contingency

### Deferred Risk 1: Multi-Device Concurrent Editing Conflicts
- **Status:** Deferred to backend policy.
- **Client Policy:** Client uses Last-Write-Wins (LWW) based on client timestamp. Mock server returns `409 CONFLICT` if server record has newer update timestamp, moving queue item to `SYNC_CONFLICT` state for user review.

### Deferred Risk 2: Schedule Density on Days 18 and 19
- **Status:** Managed via modular DI boundaries.
- **Contingency Plan:** Using modular interfaces (`ITaskRepository`, `IPhotoRepository`, `ISyncRepository`, `IImageStorageManager`) enables isolated testing and fallback to Camera Intent if CameraX setup exceeds budget.
