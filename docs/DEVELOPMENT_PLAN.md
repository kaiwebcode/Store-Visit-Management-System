# Development Plan & Roadmap
**Project:** Store Visit Management System  
**Package:** `com.example.storevisitmanagementsystem`  
**Baseline Effort Target:** 40 Hours (Week 4, Days 15–19 — Aligned with Week 4 PPT)

---

## 1. Scope & Execution Rules

> **CRITICAL SCOPE LIMITATION:**  
> Day 15 (Monday) is strictly limited to project inspection, architecture correction, mock backend implementation for Auth/Store selection, and Day 15 UI feature verification.  
> **No Task CRUD, Camera, Room Task persistence, or WorkManager sync classes are to be implemented during Day 15.** Future-day deliverables remain documented specifications until their designated development day.

---

## 2. Day 15–19 Milestones (Aligned with Week 4 PPT)

```
+-----------------------------------------------------------------------------------+
| DAY 15 (Mon): Requirements, Architecture, Mock Auth/Stores & Day 15 Feature Setup  |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| DAY 16 (Tue): Tasks Management, Camera Capture & Room Database Persistence        |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| DAY 17 (Wed): WorkManager Sync, Photo Uploads & Failure Recovery Engine           |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| DAY 18 (Thu): System Debugging, Monitoring, Logging & Edge Case Resilience        |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| DAY 19 (Fri): 100-Task / 50-Photo Stress Testing, Final Polish & Delivery        |
+-----------------------------------------------------------------------------------+
```

### Day 15 (Monday): Requirements, Architecture & Auth/Stores Implementation (Current Milestone)
- **Deliverables:** Corrected documentation suite, updated `.gitignore`, `LoginActivity` with synthetic auth, `StoreSelectionActivity` with real-time search, in-process SQLite-backed mock backend for Auth and Assigned Stores.

### Day 16 (Tuesday): Tasks, Camera & Room Persistence
- **Deliverables:**
  - Room Entities (`AppDatabase`, `TaskDao`, `PhotoEvidenceDao`, `StoreAssignmentDao`).
  - Task CRUD, status updates, soft deletion (`isDeleted = true`).
  - Camera integration & atomic image compression pipeline (`.tmp` write -> `.jpg` rename).

### Day 17 (Wednesday): Synchronization, Uploads & Recovery
- **Deliverables:**
  - WorkManager `SyncWorker` enqueued via `enqueueUniqueWork`.
  - Photo evidence upload pipeline (`POST /photos/upload`).
  - Operation replay protection and error recovery (`SyncRecoveryManager`).

### Day 18 (Thursday): Debugging, Monitoring & Edge Cases
- **Deliverables:**
  - System logging, error monitoring, network retry visualization.
  - Edge case handling (delayed 401 response handling, network drops, conflict states).

### Day 19 (Friday): Testing, Polish & Delivery
- **Deliverables:**
  - 100-Task / 50-Photo stress test execution against the mock SQLite backend.
  - Final UI polish, walkthrough documentation, and project submission delivery.

---

## 3. Effort Estimates & Schedule Contingency Plan

The project baseline effort target is **40 Hours** over Days 15–19 (8 hours/day). To manage technical risks on Days 16 and 17 without exceeding the target:

| Day | Feature Area (PPT Schedule) | Target Hours | Contingency & Optimization Strategy |
|:---|:---|:---:|:---|
| **Day 15 (Mon)** | Architecture Correction, Mock Auth & Store UI | 8h | Baseline Day 15 scope completed efficiently in 8h. |
| **Day 16 (Tue)** | Tasks Management, Camera & Room Persistence | 8h | **Schedule Risk:** Camera capture + Room entity density.<br/>*Contingency:* Use system Camera Intent as fallback if CameraX configuration exceeds budget. |
| **Day 17 (Wed)** | WorkManager Sync, Photo Uploads & Recovery | 8h | **Schedule Risk:** Sync queue + replay engine complexity.<br/>*Contingency:* Pre-built SQLite mock server schema and replay table absorb testing overhead. |
| **Day 18 (Thu)** | Debugging, Monitoring & Edge Cases | 8h | Dedicated buffer for edge case resolution and logging. |
| **Day 19 (Fri)** | Stress Testing, Polish & Delivery | 8h | Automated test fixture scripts execute 100-task/50-photo stress validation. |
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
