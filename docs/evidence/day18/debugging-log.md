# Day 18 debugging and inspection

## Recorded observations

- The OnePlus Logcat capture at 14:41 shows a worker starting with **two** pending operations, acknowledging **one** task operation, uploading photo `pho_387c072c-f60`, and reporting **two** total acknowledgements. The next worker saw **zero** operations. The backend snapshot links that photo to `tsk_dd0487b8-15c` (`Day18 Network A 01`). Its server-side `PENDING` value is the business task status. [Logcat](logs/auth-fix-verification.txt) · [SQLite summary](api/network-recovery-success.json)
- The local-only recovery and network-interruption traces show the worker exiting before a sync request. The four baseline SQLite summaries have the same 31 tasks, 17 photos and 73 processed operations. [Local-only trace](logs/auth-recovery-debug.txt) · [Interruption trace](logs/network-interruption.txt) · [Before](api/network-before.json) · [After](api/network-after.json) · [Baseline](api/before-network-test.json) · [Other saved baseline](api/recovery-verified.json)
- The later backend summary contains exactly one `Day18 Offline fix 1` task with one linked photo. Its saved device Logcat file is empty. [SQLite summary](api/offline-fix-recovery.json) · [Saved log](logs/offline-fix-recovery.txt)

## Final code blocker verification

JVM tests exercise a worker-style invalidation while the store request is suspended, direct store 401, a late 401 after a successful replacement login, and offline fallback followed by 401. Node integration tests use competing requests and two sessions for one account. They check targeted faults and replay, and verify that the server diagnostics contain a session fingerprint without issuing credentials or bearer tokens in the log.

## Device inspection still to capture

No breakpoint inspection, Android network capture, or Room inspection from the corrected build has been captured. At the next device run, use these actual code locations and record the values observed there. Record a boolean for token matching and omit token values.

| Breakpoint or inspection | Values to capture |
| --- | --- |
| [StoreRepositoryImpl.getAssignedStores()](../../../app/src/main/java/com/example/storevisitmanagementsystem/data/repository/StoreRepositoryImpl.kt), transport exception handler | Active user ID, exception type, cached store IDs, restored selection, and the sign-in navigation after a real 401. Record only whether the request and active session matched. |
| [TaskCreateEditActivity](../../../app/src/main/java/com/example/storevisitmanagementsystem/ui/task/TaskCreateEditActivity.kt), create-save authorization block | Store and account IDs, local authorization result, newly queued task and operation IDs in Room. |
| [SyncRepositoryImpl](../../../app/src/main/java/com/example/storevisitmanagementsystem/data/repository/SyncRepositoryImpl.kt), task and photo response branches | HTTP status, matching-token result for 401, requested and acknowledged operation IDs, and queue rows before and after the acknowledgement transaction. |
| Network Inspector, Logcat, Room Database Inspector, and backend SQLite summary | Request receipt and failure timestamps, worker attempt/result, rows in `tasks`, `photo_evidence`, and `sync_queue`, photograph still viewable offline, and one final backend task and linked photo. |

The historical [pending screenshot](screenshots/day18-current.png) is usable and shows `PENDING | PENDING`. [network-recovered.png](screenshots/network-recovered.png) shows the launcher, and [offline-recovered.png](screenshots/offline-recovered.png) is black. Keep these out of the submission evidence. Replacement app screenshots are pending a device run.
