# Current project status

**Project** Store Visit Management System

**Branch** `feature/day19-testing-release`

**Milestone** Friday Day 19, November 28, 2026. Bootcamp demo artifact verified.

- **Automated verification** — 15/15 Node backend integration tests and 46/46 Android JVM tests passed. The saved OnePlus XML records 11/11 connected instrumentation tests.
- **Stress and restart recovery** — 100 tasks with unique server IDs and 50 linked photos synchronized. Offline restart recovery was verified with one task and one linked photo.
- **Fault regression coverage** — The backend fixture closes a partially received photo request before multipart parsing and verifies a clean replay. Automated coverage also checks HTTP 500 retry, HTTP 504-after-save idempotency and operation/session matching, and HTTP 401 recovery.
- **Physical fault status** — Physical HTTP 500, HTTP 504-after-save, mid-upload interruption, and full HTTP 401 recovery remain **UNVERIFIED**.
- **Demo artifact** — The debug-signed `app-debug.apk` was installed on the OnePlus EB2101. The release APK is unsigned and outside the bootcamp demo submission.

[Day 19 test report](evidence/day19/day19-test-report.md) · [Sync state diagram](diagrams/offline-sync.mmd)
