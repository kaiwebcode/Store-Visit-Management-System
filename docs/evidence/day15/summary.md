# Day 15 Evidence & Manual Verification Summary
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
1. `AuthRepositoryTest.login with valid credentials saves session and returns Success` — **PASSED**
2. `AuthRepositoryTest.login with invalid credentials returns Error and does not save session` — **PASSED**
3. `AuthRepositoryTest.logout clears session token without clearing fake local database data` — **PASSED**
4. `StoreRepositoryTest.getAssignedStores when logged in returns list of assigned stores` — **PASSED**
5. `StoreRepositoryTest.getAssignedStores with search query filters store list` — **PASSED**
6. `StoreRepositoryTest.getAssignedStores when session expired returns 401 error and handles session invalidation` — **PASSED**
7. `ExampleUnitTest.addition_isCorrect` — **PASSED**

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

## 3. Manual Workflow Test Results

| Test Case ID | Test Scenario | Input / Action | Expected Result | Result Status |
|:---|:---|:---|:---|:---:|
| **TC-AUTH-01** | Valid User Login | `agent1` / `password123` | Authenticates via `MockApiInterceptor`, saves session in `TokenManager`, navigates to `StoreSelectionActivity`. | **PASSED** |
| **TC-AUTH-02** | Invalid Password | `agent1` / `wrongpass` | Displays error message: "Invalid username or password.", retains user on Login screen. | **PASSED** |
| **TC-AUTH-03** | Existing Session Launch | App open with active session | Automatically bypasses Login screen and opens `StoreSelectionActivity` directly. | **PASSED** |
| **TC-AUTH-04** | User Logout | Tap "Logout" button | Clears active token in `TokenManager`, redirects to `LoginActivity`. (Local data preserved under `userId`). | **PASSED** |
| **TC-STORE-01**| Assigned Store List | Login as `agent1` | Loads 3 assigned stores (Downtown Megastore, Metro Express Mart, Brooklyn Center Superstore). | **PASSED** |
| **TC-STORE-02**| Store Real-Time Search | Type "Express" in search bar | Instantly filters list to "Metro Express Mart (STR-NY-002)". | **PASSED** |
| **TC-STORE-03**| Store Selection | Tap on "Downtown Megastore" | Updates active store banner: "ACTIVE STORE CONTEXT: Downtown Megastore (STR-NY-001)". | **PASSED** |
| **TC-STORE-04**| Account Store Isolation | Login as `agent2` | Loads 2 assigned stores for `agent2` (Westside Supercenter, Bay View Convenience); `agent1` stores excluded. | **PASSED** |

---

## 4. Screenshot Evidence Note

*Note:* Screenshot capture files (`.png`) are captured during live connected emulator/device demonstration runs and saved directly in `docs/evidence/day15/`. (All current automated unit test scenarios have been run and verified 100% green).
