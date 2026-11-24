# Development Plan & Roadmap
**Project:** Store Visit Management System  
**Package:** `com.example.storevisitmanagementsystem`  
**Baseline Effort Target:** 40 Hours (Week 4, Days 15–19)

---

## 1. Scope & Execution Rules

> **CRITICAL SCOPE LIMITATION:**  
> Day 15 (Monday) is strictly limited to project inspection, architecture correction, mock backend implementation for Auth/Store selection, and Day 15 UI feature verification.  
> **No Task CRUD, Camera, Room Task persistence, or WorkManager sync classes are to be implemented during Day 15.** Future-day deliverables remain documented specifications until their designated development day.

---

## 2. Day 15–19 Milestones

```
+-----------------------------------------------------------------------------------+
| DAY 15: Planning, Requirements, Architecture, Mock API Auth/Store & Day 15 Features|
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| DAY 16: Full Room Database Integration, Task Entity & Store Offline Cache         |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| DAY 17: Task CRUD, Soft Deletion, Atomic Room Task+Queue Transactions             |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| DAY 18: PhotoEvidence Entity, Atomic File Writes, Compression & Preview UI        |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| DAY 19: WorkManager Unique Sync, Standalone SQLite Mock Sync, Replay & E2E Test   |
+-----------------------------------------------------------------------------------+
```

### Day 15: Planning, Architecture Specification & Auth/Store Implementation (Current Milestone)
- **Deliverables:** Corrected documentation suite, updated `.gitignore`, `LoginActivity` with synthetic auth, `StoreSelectionActivity` with real-time search, standalone SQLite mock backend for Auth and Assigned Stores.

### Day 16: Room Persistence Setup & Store Caching
- **Deliverables:**
  - `AppDatabase`, `StoreDao`, `StoreAssignmentDao`, `TaskDao`, `PhotoEvidenceDao`, `SyncQueueDao`.
  - Offline store caching and store assignment validation.

### Day 17: Task Management & Atomic Queueing
- **Deliverables:**
  - Task CRUD, status updates, soft deletion (`isDeleted = true`).
  - Atomic `@Transaction` methods inserting tasks and `SyncQueueEntity` entries.
  - UI Layer: `TaskListActivity`, task filter tabs.

### Day 18: Photo Evidence & Durable File Management
- **Deliverables:**
  - CameraX / Intent capture, `ImageStorageManager` with atomic `.tmp` -> `.jpg` pipeline.
  - Downscaling (max 1024x1024) and JPEG compression (~80%, < 300KB) off main thread.
  - UI Layer: Photo thumbnail grid adapter, preview screen.

### Day 19: WorkManager Synchronization, Replay Engine & Stress Verification
- **Deliverables:**
  - WorkManager `SyncWorker` enqueued via `enqueueUniqueWork`.
  - Replay engine on standalone SQLite mock server (`processed_operations`).
  - Stress testing execution for 100 offline tasks and 50 photos.

---

## 3. Effort Estimates & Schedule Contingency Plan

The project baseline effort target is **40 Hours** over Days 15–19. To manage technical risks on Days 18 and 19 without exceeding the target:

| Day | Feature Area | Target Hours | Contingency & Optimization Strategy |
|:---|:---|:---:|:---|
| **Day 15** | Architecture Correction, Mock Auth & Store UI | 8h | Baseline Day 15 scope completed efficiently in 8h. |
| **Day 16** | Room Database & Store Caching | 8h | Standard DAO generation and repository wiring. |
| **Day 17** | Task CRUD & Atomic Queueing | 8h | Modular `@Transaction` implementation in Room. |
| **Day 18** | Photo Evidence & Atomic File Storage | 8h | **Schedule Risk:** Camera capture + compression density.<br/>*Contingency:* Use system Camera Intent as fallback if CameraX configuration exceeds budget. |
| **Day 19** | WorkManager Sync & Stress Verification | 8h | **Schedule Risk:** Sync queue + SQLite mock server replay engine density.<br/>*Contingency:* Pre-built SQLite mock server schema and automated test fixture script absorb testing overhead. |
| **Total** | **Baseline Target** | **40h** | |

---

## 4. Risk Matrix

| Risk ID | Risk Description | Severity | Mitigation Strategy |
|:---|:---|:---:|:---|
| **R-1** | **Session Logout / Expiry deleting unsynced data** | Critical | Enforce token-only clearing in `TokenManager`. Room tables and photo files remain preserved under `userId`. |
| **R-2** | **Delayed 401 clearing active session** | High | Check `requestToken == tokenManager.getActiveToken()` before invalidating session. |
| **R-3** | **Duplicate Server Records on Retries** | Critical | Enforce `operationId` + `payload_hash` tracking in `processed_operations` table on mock server. |
| **R-4** | **Multi-User Store Corruption** | Medium | Use composite `StoreAssignment` (`userId`, `storeId`) key for local store caching. |
| **R-5** | **Photo Evidence Storage Overflow / OOM** | High | Downscale images to max 1024x1024, JPEG ~80%, target size < 300KB. |
