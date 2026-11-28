# Day 19 — Testing and Release Validation Report

**Date:** 28 November 2026
**Device:** OnePlus EB2101 (Android 12)
**Branch:** `feature/day19-testing-release`

---

## 1. Verified Automated Test Suites

- **PASS** — Node.js backend integration tests (15/15, exit 0). [Full console output](logs/backend-integration.txt)
  - The pre-multer regression sends part of a JPEG, verifies that the fixture closes the incomplete request after receiving those bytes, checks that photo metadata, JPEG bytes, and the operation acknowledgment were absent, and replays the same operation to save one photo.
  - Includes photo upload `504-after-save` idempotency and header/session mismatch regression tests (verifying `504-after-save` does not apply when `X-Operation-Id` header differs from body operationId or session differs).
  - Includes session expiry (`expire`), task batch atomic rollback, legacy repair, and session isolation.
- **PASS** — Android JVM unit tests (46/46, exit 0). [Full console output and test counts](logs/android-jvm.txt)
  - Validates network interruption, HTTP 500/504 retry, HTTP 401 token clearing with local data preservation, re-login recovery, and repository recreation.
- **PASS** — Connected instrumentation tests (11/11). [Saved OnePlus XML](logs/TEST-EB2101%20-%2012.xml)
  - Verified by saved instrumentation XML. Room v2 schema migrations (`MIGRATION_1_2`), DAO operations, and WorkManager sync constraints passed successfully.

---

## 2. Stress Test & Offline Restart Evidence

- **100-Task / 50-Photo Stress Test**:
  - Initial JSON snapshot (`0/0`): Represents the initial baseline state before stress ingestion.
  - Final stress result: 100 task records synchronized with 100 unique server IDs and 50 linked photos.
  - **Task Title Observations**: 98 distinct task titles among the 100 records. Repeated titles: Task 53 and Task 62. Missing labels in sequence: Task 55 and Task 88. All 100 task IDs were unique.
- **Offline Restart Recovery**:
  - Offline task and photo creation retained across app force-stop/restart and synchronized upon reconnect (1 task and 1 linked photo verified after synchronization).

---

## 3. Bootcamp Demo APK

- **Artifact** — Debug-signed `app/build/outputs/apk/debug/app-debug.apk`
- **Package** — `com.example.storevisitmanagementsystem.day18`
- **Distribution SHA-256** — `A4D88FCE7CBDCD0893D50E9D04F3D21603EE6EB938150DD519B25AC5B3454565`
- **Build command** — `./gradlew app:assembleDebug -Pday17BackendHost=192.168.1.102`
- **Backend URL on the phone** — `http://192.168.1.102:3000/v1/`
- **Device** — OnePlus EB2101, Android 12

The following installation update returned `Success` with exit code 0. A separate device-side SHA-256 check confirmed that the installed `base.apk` matched the distribution APK for this installation. [Installation output](logs/debug-apk-install.txt)

```powershell
adb -s adb-13f6f5c0-EgDxpR._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk
```

The release APK (`app-release-unsigned.apk`) is unsigned and outside the bootcamp demo submission. Release network configuration remains a production limitation.

---

## 4. Physical Fault Acceptance Status & Procedures

### Physical test status

Physical HTTP 500, HTTP 504-after-save, mid-upload stream interruption, and full HTTP 401 recovery remain **UNVERIFIED**. The server log records a matched `expire` request and a subsequent login. The interruption Logcat shows a worker run with zero pending operations. A physical pass requires an observed failure and recovery of the same queued operation. [Server log](logs/physical-server.log) · [Interruption Logcat](logs/interrupt-04-logcat.txt)

### Corrected Test Procedures (OnePlus EB2101)
1. **Start Backend Server (`192.168.1.102`)**:
   Set `STORE_VISIT_TEST_FAULTS=1` (legacy `STORE_VISIT_TEST_HEADERS=1` removed). Loopback restricted (`127.0.0.1`).
   ```powershell
   $env:STORE_VISIT_TEST_FAULTS="1"
   Remove-Item Env:STORE_VISIT_TEST_HEADERS -ErrorAction SilentlyContinue
   $env:STORE_VISIT_BIND_HOST="0.0.0.0"
   cd C:\Users\KAIF\AndroidStudioProjects\Store-Visit-Management-System\server
   node server.js
   ```
2. **Obtain Real Session Fingerprint**:
   Sign in on OnePlus (`com.example.storevisitmanagementsystem.day18`). Server stdout logs:
   `Sync test session armed-target ID: userId=usr_001, sessionId=a1b2c3d4e5f6...`
3. **Inspect Real Pending Operation IDs via ADB**:
   ```powershell
   adb shell run-as com.example.storevisitmanagementsystem.day18 sqlite3 /data/data/com.example.storevisitmanagementsystem.day18/databases/store_visit_app.db "SELECT operation_id, entity_type, entity_id FROM sync_queue WHERE status = 'PENDING';"
   ```
4. **Arm Fault & Re-sync**:
   Arm specific faults via `POST /v1/debug/sync-fault` using real `sessionId` and Room `operationId`.
