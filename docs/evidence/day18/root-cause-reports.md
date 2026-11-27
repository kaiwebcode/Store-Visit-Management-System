# Day 18 Root Cause & Diagnosis Reports

**Project:** Store Visit Management System  
**Branch:** `feature/day18-debug-monitor`  
**Device:** OnePlus EB2101  
**Package:** `com.example.storevisitmanagementsystem.day18`  
**Backend:** `http://192.168.1.111:3000/v1`  
**Date:** Thursday (Week 4 — Day 18)  

---

## 1. Confirmed Root Cause: Authentication Fallback & WorkManager Sync Pause

### Initial Problem Statement
Initial tasks and photo evidence created on the physical OnePlus device remained stuck in local `PENDING` status, and the Node.js backend SQLite database summary showed 0 matching records.

### Root Cause Analysis & Evidence
1. **Outdated Backend Host Default:** `app/build.gradle.kts` defaulted `day17BackendHost` to `"192.168.1.110"`. When built without passing `-Pday17BackendHost=192.168.1.111`, `BuildConfig.DAY17_BACKEND_HOST` compiled as `"192.168.1.110"`.
2. **Silent Local Fallback:** On the OnePlus device (where backend was running at `192.168.1.111`), `MockApiInterceptor.kt` attempted connecting to `192.168.1.110:3000/v1/auth/login`. The connection failed with `IOException`.
3. **Local Mock Token Trap:** `MockApiInterceptor.kt` caught `IOException` and silently called `handleLogin()`, generating a local mock session token starting with `mock_token_` (`Constants.LOCAL_MOCK_TOKEN_PREFIX`).
4. **WorkManager Paused:** `SyncRepositoryImpl.kt` intentionally skips backend API sync calls when the session token starts with `mock_token_` to prevent sending unauthenticated requests. Logcat recorded:
   ```text
   D/StoreVisitSync: Sync skipped: Local-only mock session for userId=usr_001
   I/StoreVisitSync: SyncWorker ... paused waiting for backend sign-in: Local-only session. Backend sign-in required.
   ```
5. **Data Protection Preserved:** Local Room database records (`TaskEntity`, `PhotoEvidenceEntity`, `SyncQueueEntity`) for `usr_001` remained 100% intact in SQLite on the OnePlus device.

### Verified Fix & Recovery Path
- Updated Gradle default backend host to `192.168.1.111`, updated interceptor error handling to throw `IOException` on connection failure, and updated session checks in `LoginActivity.kt`.
- **Physical Verification Logcat:** [`logs/auth-fix-verification.txt`](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/evidence/day18/logs/auth-fix-verification.txt)
- **Backend Database Snapshot:** [`api/network-recovery-success.json`](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/evidence/day18/api/network-recovery-success.json) (`Day18 Network A 01`, `tsk_dd0487b8-15c`, photo `pho_387c072c-f60`).

---

## 2. Mandatory Scenarios Audit & Evidence Integrity

### Scenario A: Network Interruption & Recovery
- **Status:** **PHYSICAL PASS** (Offline-to-online recovery) / **AUTOMATED TEST PASS** (Mid-sync cancellation & socket error recovery).
- **Evidence Links:** [`logs/auth-fix-verification.txt`](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/evidence/day18/logs/auth-fix-verification.txt), [`api/network-recovery-success.json`](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/evidence/day18/api/network-recovery-success.json), `Day18DebuggingAndMonitoringTest.kt`.
- **Notes:** Physical device testing proved successful offline creation followed by online recovery (`auth-fix-verification.txt`). Mid-sync TCP socket disconnection recovery is fully covered by automated unit tests in `Day18DebuggingAndMonitoringTest.kt`.

### Scenario B: Server Failures (HTTP 500 / 504)
- **Status:** **AUTOMATED TEST PASS** / **NOT VERIFIED (Live Physical Device Mid-Stream)**.
- **Evidence Links:** `Day18DebuggingAndMonitoringTest.kt` (`Scenario B - HTTP 500 and 504 server failures...`), `server.js` failure-injection middleware (`X-Mock-Fail-500`, `X-Mock-Timeout`).
- **Notes:** Validated comprehensively via automated unit tests verifying that 5xx errors revert queue items to `PENDING` for WorkManager exponential retry and allow duplicate-free idempotent replay. Live physical device injection of 500/504 headers during active sync was not captured in raw Logcat text.
- **Minimum OnePlus Test Procedure to Obtain Live Physical Evidence:** Start app with backend running, send request with header `X-Mock-Fail-500: true`, observe Logcat retry, remove header, observe success.

### Scenario C: Session Expiry (HTTP 401 Unauthorized)
- **Status:** **PHYSICAL PASS** (Session recovery from local mock to real backend session) / **AUTOMATED TEST PASS** (HTTP 401 response handling & token clearing).
- **Evidence Links:** [`logs/auth-fix-verification.txt`](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/evidence/day18/logs/auth-fix-verification.txt), `Day18DebuggingAndMonitoringTest.kt` (`Scenario C - Session expiry (HTTP 401)...`), `TokenManagerImpl.handle401()`.
- **Notes:** Physical device testing successfully demonstrated transitioning from local mock session state to authenticated backend session without losing pending Room records (`auth-fix-verification.txt`). Automated tests verify explicit HTTP 401 response handling.

### Scenario D: Application Process Restart
- **Status:** **PHYSICAL PASS** & **AUTOMATED TEST PASS**.
- **Evidence Links:** [`logs/auth-fix-verification.txt`](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/evidence/day18/logs/auth-fix-verification.txt), `RoomDaoTest.kt`, `Day18DebuggingAndMonitoringTest.kt` (`Scenario D - App restart simulation...`).
- **Notes:** Verified on physical OnePlus EB2101 device by force-stopping the application via `adb shell am force-stop com.example.storevisitmanagementsystem.day18`, reopening the app, confirming Room DB persistence of pending task & photo evidence, and observing successful background synchronization upon network availability.
