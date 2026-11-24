# Day 15 Evidence & Verification Report
**Project:** Store Visit Management System  
**Package:** `com.example.storevisitmanagementsystem`  
**Date:** Week 4, Day 15 (Monday)

---

## 1. Unit Test Verification Results

Gradle Command Executed:
```bash
./gradlew app:testDebugUnitTest
```

**Output Summary:**
- Task: `:app:testDebugUnitTest`
- Tests Run: **7 passed, 0 failed, 0 skipped**
- Status: **BUILD SUCCESSFUL**

### Test Breakdown
1. `AuthRepositoryTest.login with valid credentials saves session and returns Success` — PASSED
2. `AuthRepositoryTest.login with invalid credentials returns Error and does not save session` — PASSED
3. `AuthRepositoryTest.logout clears session token without clearing fake local database data` — PASSED
4. `StoreRepositoryTest.getAssignedStores when logged in returns list of assigned stores` — PASSED
5. `StoreRepositoryTest.getAssignedStores with search query filters store list` — PASSED
6. `StoreRepositoryTest.getAssignedStores when session expired returns 401 error and handles session invalidation` — PASSED
7. `ExampleUnitTest.addition_isCorrect` — PASSED

---

## 2. Debug APK Build Verification

Gradle Command Executed:
```bash
./gradlew app:assembleDebug
```

**Output Summary:**
- Task: `:app:assembleDebug`
- Status: **BUILD SUCCESSFUL**
- Output APK Path: `app/build/intermediates/apk/debug/app-debug.apk`

---

## 3. Implemented Feature Matrix

| Feature Component | Status | Notes |
|:---|:---:|:---|
| **LoginActivity** | Implemented | XML ViewBinding, synthetic login, loading state, error text |
| **TokenManager** | Implemented | SharedPreferences with account isolation & token-only clear on logout |
| **StoreSelectionActivity**| Implemented | RecyclerView adapter, real-time search filter, active store card |
| **Standalone SQLite Mock API**| Implemented | `mock_server.db` storing synthetic users & store assignments |
| **Unit Test Suite** | Implemented | 7 unit tests passing |
