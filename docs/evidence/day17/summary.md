# Day 17 sync verification

**Project** Store Visit Management System

**Branch** `feature/day17-reliable-sync`

**Week 4 milestone** Wednesday, November 25, 2026

**Final verification** November 26, 2026

## Independently inspected automated results

| Check | Result |
| --- | --- |
| JVM tests | 31 tests passed in `:app:testDebugUnitTest`. New regressions cover incomplete task ACKs, a photo 500 with later operations queued, cancellation, stale `SYNCING` rows, account mismatch, delayed 401, missing local files, and incorrect photo ACKs followed by a successful retry. |
| Emulator instrumentation | 8 tests passed, including the real Room v1-to-v2 upgrade, the offline lookup regression using a reopened Room database, and the WorkManager test that appends new work behind an active worker. |
| Debug APK | `:app:assembleDebug` and `:app:assembleDebugAndroidTest` both succeeded. |
| Backend integration | Seven tests passed with `npm --prefix server test`. The suite uses a temporary SQLite database and an ephemeral loopback port. It verifies the seeded accounts return three and two assigned stores, legacy owner repair, ownership and ordering, SQLite rollback, task and photo replay after 504, JPEG validation, and account-scoped photo reads. |

## Completed OnePlus acceptance scenarios

- **Offline capture and restart.** On the OnePlus Nord CE, a task and photo were created offline. Both remained visible and pending after a force-stop and restart while the phone was still offline.
- **Backend outage and recovery.** The backend outage and restart were manually verified. The app stayed in the background during reconnection and recovery, and the task displayed `SYNCED` when the app was reopened. The saved snapshots verify persisted records. `Retry day17` was absent before recovery and appears afterward as exactly one active task with one linked photo. [Before recovery](server-before-recovery.json) · [After recovery](server-after-recovery.json)

## Saved API evidence

- The [before snapshot](server-before-device-sync.json) and [after snapshot](server-after-device-sync.json) record an increase from 4 to 10 tasks and from 0 to 7 photos during the prior physical-device exercise.
- The two [earlier](retry-test-20261126140945.txt) and [later](retry-test-20261126141101.txt) retry logs each record a task timeout-after-save response of HTTP 504, a replay response of HTTP 200, and one matching task and operation. Their zero assigned-store counts belong to the prior implementation, which treated part of the token as part of the account ID.

## Current behavior and limits

The [sync state diagram](../../diagrams/offline-sync.mmd) tracks queue recovery and the [current status](../../CURRENT_STATUS.md) records completed verification and publication status. The queue stays pending during transport. Successful local ACK handling updates Room and removes only acknowledged queue rows. JPEGs stay available locally for offline viewing. New server images are stored in SQLite with their metadata and acknowledgement. Existing uploaded files remain readable by their owner.

The Node fixture is used by debug builds. Sign in online against the running backend to obtain a sync session. The local mock remains available for standalone sign-in and offline store browsing. A local-only token must be replaced by signing in online before uploads can proceed. A permanent request rejection retains the record and stops that ordered drain until the cause is corrected and work is triggered again.

The original server snapshots and retry logs are preserved. The final audit found no published Day 17 branch or PR.
