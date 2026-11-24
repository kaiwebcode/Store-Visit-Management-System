# Architectural Decisions, Session Limitations & Design Tradeoffs
**Project:** Store Visit Management System  
**Package:** `com.example.storevisitmanagementsystem`

---

## 1. Documented Current Session Limitations (Day 15 Implementation)

> **IMPORTANT DISCLOSURE:** The following security & session features are documented designs for future enhancement and are NOT claimed as fully passed in the Day 15 baseline:

1. **Unencrypted SharedPreferences:**
   - *Current Implementation:* `TokenManagerImpl` utilizes standard Android `SharedPreferences` (`MODE_PRIVATE`) for storing authentication tokens, active `userId`, and session metadata.
   - *Future Enhancement:* Migration to `EncryptedSharedPreferences` / `androidx.security.crypto` is planned for future iterations.
2. **Mock Server Token Timestamp Expiration Enforcement:**
   - *Current Implementation:* The in-process mock database (`mock_server.db`) records an `expires_at` timestamp during login, but the current Day 15 `MockApiInterceptor` validates token presence and active session mapping without rejecting requests based on elapsed wall-clock timestamp comparison.
   - *Future Enhancement:* Dynamic wall-clock expiration checking on the mock server is planned for Day 17 sync integration.

---

## 2. Architectural Decisions & Rationale

### ADR-01: Session Logout & Expiry Data Safety (CRITICAL)
- **Context:** Field agents logging out or encountering session expiry while offline must not lose unsynchronized audit tasks or photo evidence.
- **Decision:** Logout and HTTP 401 session expiry clear ONLY active authentication tokens in `TokenManager`. Room entities and photo files are preserved in account-isolated storage (`context.filesDir/task_photos/{userId}/`).
- **Rationale:** Prevents catastrophic data loss during offline field operations. Unsynced data is automatically recovered when the user re-authenticates.

### ADR-02: In-Process SQLite-Backed Mock API Architecture
- **Context:** An in-process mock server backed by SQLite ensures data persistence across app process restarts without requiring external server deployment.
- **Decision:** Implement `MockApiInterceptor` backed by an Android SQLite database (`mock_server.db` via `SQLiteOpenHelper`).
- **Rationale:** Operates seamlessly in-process, requiring zero external server setup, port forwarding, or network configuration. (A separate standalone HTTP service process is planned as future work).

### ADR-03: Separation of Record IDs from Sync Operation IDs
- **Context:** Network retries without operation disambiguation risk creating duplicate database records on the server.
- **Decision:** Separate domain entity identities (`taskId`, `photoId`) from sync operation identities (`operationId`). Every queue item is assigned a unique, immutable `operationId`.
- **Rationale:** The backend tracks `operationId` in a `processed_operations` table. Replayed sync requests return `200 OK` with the stored result without creating duplicate records.

### ADR-04: Account-Scoped Store Assignments
- **Context:** Two agents sharing a physical store must not corrupt each other's cached store assignments in local storage.
- **Decision:** Model store assignments using a composite key (`userId`, `storeId`) in `StoreAssignmentEntity`.
- **Rationale:** Guarantees complete data isolation when multiple users share store locations.

---

## 3. Documented Tradeoffs

| Area | Option Chosen | Alternative Considered | Tradeoff Analysis |
|:---|:---|:---|:---|
| **Data Safety** | Retain unsynced tasks on logout/401 | Wipe local DB on logout | Wiping local DB is simpler for single-user security, but causes unacceptable data loss for field agents. Account isolation by `userId` provides both security and data safety. |
| **Mock Engine** | In-Process SQLite Mock API (`mock_server.db`) | Separate External HTTP Server | In-process SQLite mock interceptor requires zero port forwarding or external dependencies; a separate HTTP service process is deferred as future work. |
| **Store Caching** | Composite `StoreAssignment` (`userId`, `storeId`) | Global `Store` table only | Global store tables corrupt cached assignments when multiple users share a physical store. Composite assignment keys guarantee account isolation. |

---

## 4. Deferred Risks & Schedule Contingency

### Deferred Risk 1: Multi-Device Concurrent Editing Conflicts
- **Status:** Deferred to backend policy.
- **Client Policy:** Client uses Last-Write-Wins (LWW) based on client timestamp. Mock server returns `409 CONFLICT` if server record has newer update timestamp, moving queue item to `SYNC_CONFLICT` state for user review.

### Deferred Risk 2: Schedule Alignment with Week 4 PPT
- **Status:** Managed via modular DI boundaries.
- **Schedule:** Tuesday = Tasks/Camera/Room; Wednesday = Sync/Uploads/Recovery; Thursday = Debugging/Monitoring; Friday = Testing/Delivery.
