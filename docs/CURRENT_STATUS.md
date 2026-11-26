# Current project status

**Project** Store Visit Management System

**Branch** `feature/day17-reliable-sync`

**Milestone** Wednesday Day 17, November 25, 2026. Implementation and required acceptance scenarios complete.

**Final verification** November 26, 2026.

- **Offline and recovery.** Room keeps task and photo operations pending while WorkManager sends them. An active worker is left running when a mutation appends a successor. A new run restores legacy claims for its own account. Transient errors retry with exponential backoff.
- **Session and ownership.** The Node fixture validates stored, unexpired sessions. WorkManager uses the queued account's active token and clears only the token that received a 401. The server checks store, task, and photo ownership.
- **Durable sync.** The server applies task operations in sequence and commits writes with account-scoped replay records. Photo bytes, metadata, and replay records commit together. Android keeps local JPEGs and removes an upload operation only after a matching acknowledgement.
- **Physical-device acceptance.** The OnePlus Nord CE retained the pending task and photo after an offline force-stop and restart. During the verified backend outage, the app stayed in the background as connectivity returned. After the backend restarted, the task uploaded and displayed `SYNCED`. The outage timing, background state, and badge were observed manually. The server snapshots independently verify that `Retry day17` went from absent to exactly one active task and one linked photo. [Before recovery](evidence/day17/server-before-recovery.json) · [After recovery](evidence/day17/server-after-recovery.json)
- **Automated verification.** The saved results show 31 JVM tests, 8 emulator instrumentation tests, and 7 isolated backend tests passed. Saved API logs show HTTP 504 after save, followed by HTTP 200 for the same operation ID and one logical task and operation. [Retry evidence](evidence/day17/retry-test-20261126141101.txt)
- **Known limits.** The Node backend is a debug fixture. Sync requires a backend-issued session. A permanent request rejection retains the affected operation and pauses that ordered queue until the cause is corrected and sync runs again.
- **GitHub submission.** The final audit found no remote Day 17 branch or PR. Publication remains pending.

[Day 17 evidence and known limitations](evidence/day17/summary.md) · [Sync state diagram](diagrams/offline-sync.mmd)
