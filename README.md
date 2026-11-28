# Store Visit Management System
**Package:** `com.example.storevisitmanagementsystem`
**Git Branch:** `feature/day19-testing-release`
**Milestone:** Week 4, Day 19 bootcamp demo

---

## 1. Executive Summary
The Store Visit Management System is an offline-first Android application designed for field agents conducting retail store audits. The Day 19 milestone delivers:
1. **Authentication & Session Safety:** Login, logout, session persistence, account-scoped data isolation, HTTP 401 session expiry handling with zero local data loss, and delayed-401 safety.
2. **Assigned Store Management & Local Audit Tasks:** Room v2 database with `sequence_number` queue ordering (`MIGRATION_1_2`), task creation/updates/deletions, and camera photo capture stored in account-isolated disk storage.
3. **Resilient WorkManager Synchronization:** Idempotent task batching and photo multipart streaming (`POST /v1/tasks/sync`, `POST /v1/photos/upload`) backed by a standalone Node.js Express server (`server/server.js`).
4. **Verified testing** — 15/15 Node integration tests, 46/46 Android JVM tests, and 11/11 connected instrumentation tests from saved XML. The stress run synchronized 100 tasks and 50 linked photos. Offline restart recovery was verified with one task and one linked photo.
5. **Physical fault status** — Physical HTTP 500, HTTP 504-after-save, mid-upload interruption, and full HTTP 401 recovery remain **UNVERIFIED**.
6. **Bootcamp demo artifact** — The debug-signed APK was installed on the OnePlus EB2101. The release APK is unsigned and outside the demo submission.

---

## 2. Synthetic Test Credentials

The SQLite-backed mock database is pre-seeded with two synthetic test accounts:

| Username | Password | Full Name | Role | Assigned Stores Count |
|:---|:---|:---|:---|:---:|
| `agent1` | `password123` | Alex Mercer | FIELD_AGENT | 3 Stores (New York & Brooklyn) |
| `agent2` | `password123` | Sarah Connor | FIELD_SUPERVISOR | 2 Stores (Los Angeles & San Francisco) |

---

## 3. How to Run the App & Mock Backend

### Prerequisites
- Android Studio Ladybug (or newer) / AGP 9.5.2
- JDK 17 / Android SDK 37 (Min SDK 24)
- Node.js 18+ for standalone test backend (`server/`)
- Android Emulator or Physical Device running Android 7.0+ (API 24+) (e.g. OnePlus EB2101)

### Steps to Run & Test

1. **Start Standalone Node Backend:**
   ```powershell
   $env:STORE_VISIT_TEST_FAULTS="1"
   Remove-Item Env:STORE_VISIT_TEST_HEADERS -ErrorAction SilentlyContinue
   $env:STORE_VISIT_BIND_HOST="0.0.0.0"
   cd server
   node server.js
   ```

2. **Run Node Integration Tests:**
   ```powershell
   cd server
   npm test
   ```

3. **Run Android JVM Unit Tests:**
   ```bash
   ./gradlew app:testDebugUnitTest
   ```

4. **Build APKs:**
   - Bootcamp demo APK — `./gradlew app:assembleDebug -Pday17BackendHost=192.168.1.102` (`app/build/outputs/apk/debug/app-debug.apk`)
   - Unsigned release output — `./gradlew app:assembleRelease -Pday17BackendHost=192.168.1.102` (`app/build/outputs/apk/release/app-release-unsigned.apk`)

---

## 4. Documentation Index

- [Day 19 test report](docs/evidence/day19/day19-test-report.md) — Test results, debug APK checksum and installation, and physical fault status.
- [REQUIREMENTS.md](file:///C:/Users/KAIF/AndroidStudioProjects/Store-Visit-Management-System/docs/REQUIREMENTS.md) — Functional/non-functional specs & measurable acceptance criteria.
- [DEVELOPMENT_PLAN.md](file:///C:/Users/KAIF/AndroidStudioProjects/Store-Visit-Management-System/docs/DEVELOPMENT_PLAN.md) — Day 15–19 roadmap aligned with Week 4 PPT.
- [ARCHITECTURE.md](file:///C:/Users/KAIF/AndroidStudioProjects/Store-Visit-Management-System/docs/ARCHITECTURE.md) — Technical architecture, account isolation, standalone Express backend, photo pipeline, and DI boundaries.
- [API_CONTRACT.md](file:///C:/Users/KAIF/AndroidStudioProjects/Store-Visit-Management-System/docs/API_CONTRACT.md) — REST contract endpoints, request/response models, idempotency rules, and error categories.
- [DECISIONS.md](file:///C:/Users/KAIF/AndroidStudioProjects/Store-Visit-Management-System/docs/DECISIONS.md) — Log of architectural decisions, release sync resolution, session safety, and fault recovery.
- [CURRENT_STATUS.md](file:///C:/Users/KAIF/AndroidStudioProjects/Store-Visit-Management-System/docs/CURRENT_STATUS.md) — Detailed status report of completed Day 19 features, verification results, and physical testing.
