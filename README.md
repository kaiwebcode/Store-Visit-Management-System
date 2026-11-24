# Store Visit Management System
**Package:** `com.example.storevisitmanagementsystem`  
**Git Branch:** `feature/day15-core`  
**Milestone:** Week 4, Day 15 (Monday) Complete

---

## 1. Executive Summary
The Store Visit Management System is an offline-first Android application designed for field agents conducting retail store audits. The Day 15 milestone delivers:
1. **Authentication & Session Safety:** Login, logout, session persistence, account-scoped data isolation, and delayed 401 handling.
2. **Assigned Store Management:** Fetching, searching (by name, code, address), selecting active store context, displaying detailed store metadata, and enforcing account store authorization.
3. **Standalone SQLite Mock Backend:** Integrated `mock_server.db` storing synthetic users and assigned store records across app process restarts.
4. **Architecture Specifications:** Complete design documents (`REQUIREMENTS.md`, `DEVELOPMENT_PLAN.md`, `ARCHITECTURE.md`, `API_CONTRACT.md`, `DECISIONS.md`, `CURRENT_STATUS.md`, and Mermaid ER/sequence diagrams).

---

## 2. Synthetic Test Credentials

The standalone SQLite mock backend is pre-seeded with two synthetic test accounts:

| Username | Password | Full Name | Role | Assigned Stores Count |
|:---|:---|:---|:---|:---:|
| `agent1` | `password123` | Alex Mercer | FIELD_AGENT | 3 Stores (New York & Brooklyn) |
| `agent2` | `password123` | Sarah Connor | FIELD_SUPERVISOR | 2 Stores (Los Angeles & San Francisco) |

---

## 3. How to Run the App & Standalone Mock Backend

### Prerequisites
- Android Studio Ladybug (or newer) / AGP 9.5.2
- JDK 17 / Android SDK 37 (Min SDK 24)
- Android Emulator or Physical Device running Android 7.0+ (API 24+)

### Steps to Run
1. **Clone & Open Project:**
   Open `C:\Users\zeeshan\AndroidStudioProjects\StoreVisitManagementSystem` in Android Studio.
2. **Build Debug APK:**
   Run Gradle task in terminal or IDE:
   ```bash
   ./gradlew app:assembleDebug
   ```
3. **Run Unit Tests:**
   ```bash
   ./gradlew app:testDebugUnitTest
   ```
4. **Launch on Device / Emulator:**
   Select the `app` run configuration and click **Run** (`Shift + F10`).
5. **Standalone Mock Server Operation:**
   The mock API runs in-process backed by `mock_server.db` SQLite database. Base URL is configured in `Constants.MOCK_BASE_URL` (`https://api.storevisit.mock/v1/`). All HTTP requests are intercepted by `MockApiInterceptor` and routed directly to the durable SQLite database `mock_server.db`.

---

## 4. Documentation Index

- [REQUIREMENTS.md](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/REQUIREMENTS.md) — Functional/non-functional specs & measurable acceptance criteria.
- [DEVELOPMENT_PLAN.md](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/DEVELOPMENT_PLAN.md) — Day 15–19 roadmap & 40-hour baseline effort target with schedule contingency plan.
- [ARCHITECTURE.md](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/ARCHITECTURE.md) — Technical architecture, account isolation, standalone SQLite mock backend, photo pipeline, and DI boundaries.
- [API_CONTRACT.md](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/API_CONTRACT.md) — REST contract endpoints, request/response models, idempotency rules, and error categories.
- [database.mmd](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/diagrams/database.mmd) — Mermaid ER diagram (`User`, `Store`, `StoreAssignment`, `Task`, `PhotoEvidence`, `SyncQueue`).
- [offline-sync.mmd](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/diagrams/offline-sync.mmd) — Mermaid sequence flow diagram for offline tasks, atomic transactions, and WorkManager sync recovery.
- [DECISIONS.md](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/DECISIONS.md) — Recorded Codex review findings, architectural decisions, and deferred multi-device conflict risks.
- [CURRENT_STATUS.md](file:///C:/Users/zeeshan/AndroidStudioProjects/StoreVisitManagementSystem/docs/CURRENT_STATUS.md) — Detailed report of completed Day 15 features and verification results.
