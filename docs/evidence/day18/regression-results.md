# Day 18 regression and acceptance results

See [root causes and decisions](root-cause-reports.md) and [inspection evidence](debugging-log.md).

## Automated checks

| Check | Result |
| --- | --- |
| Android JVM tests | 46 passed, 0 skipped, 0 failed in a fresh run. |
| Debug APK | Assembled for the final two fixes with `-Pday17BackendHost=192.168.1.111`. The Android test APK was assembled in the preceding review. |
| Node/SQLite integration | 13 passed, 0 failed. Coverage includes scoped 500/504, interrupted connections, session expiry isolated across two logins, replay after server save, and credentials excluded from diagnostics. The suite uses a temporary database. |
| Release isolation | Release Kotlin and Java compilation and manifest processing passed. Full release assembly requires the Android lint dependency missing from the offline cache. |
| Android instrumentation and physical checks | The test APK compiled. The Room migration, cached store restoration and status projection tests require a connected Android target for execution. |

The JVM checks cover offline task creation after cached authorization, store cache reuse and account isolation, 401 precedence, queued and in-flight photos, durable rejection with independent work, stable task operation IDs across retries, and completion scheduling for the matching account. The Room migration test checks saved task and photo metadata, queue ordering, and the existing JPEG bytes. Day 18 changes require no new Room schema version.

The final Android regressions cover worker-style invalidation while Store Selection is loading, a matching store 401, a late 401 after a successful replacement login, and transport fallback followed by authoritative 401.

## Physical coverage

| Scenario | Current physical evidence | Day 18 acceptance |
| --- | --- | --- |
| A · Network | One earlier task and linked photo reached the server. The saved interruption log records a pause before transmission. | Pending a disconnect after request receipt and recovery of the same records. |
| B · Server | JVM and isolated Node coverage only. | Pending Android-received 500, timeout, and WorkManager retries. |
| C · Session | The saved logs show recovery from a local-only session. | Pending Android-received 401 from an invalidated backend session, preservation, and re-login. |
| D · Restart | Earlier persistence observations and a Room migration test exist. | Pending force-stop and cold offline launcher recovery on the corrected build. |

## OnePlus test procedure

Keep the current application, Room data and backend database. Install the debug APK as an update. Restart the backend from the project root with its existing data directory with `$env:STORE_VISIT_TEST_FAULTS='1'; npm --prefix server start`. Sign in online and open the assigned store once to populate its cache. Use a new, distinct task name per run. Save Logcat and the local inspection before continuing to the next scenario. Arm each fault while the phone is offline and the intended operation is still queued. Copy the operation ID from Room's `sync_queue` and use the `sessionId` fingerprint printed by the Node backend at that phone's sign-in. The fixture matches both identifiers and the request type. Keep `STORE_VISIT_TEST_HEADERS` unset for device runs.

Arm a fault from the PC with PowerShell. Fill in the intended operation ID and current session fingerprint. Use a new queued operation for each run.

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:3000/v1/debug/sync-fault -ContentType 'application/json' -Body '{"target":"task","mode":"interrupt","operationId":"OPERATION_ID","sessionId":"SESSION_FINGERPRINT"}'
```

1. **Network.** Create a task and attach a photo offline, arm `interrupt`, then reconnect. At the server's request-receipt line, immediately disable Wi-Fi and mobile data. Keep network off long enough to capture pending rows and prove the worker stopped or the transport failed after receipt. Reconnect and capture the later acknowledgement and exactly one linked task/photo pair in the SQLite summary. The fixture closes any still-open socket after eight seconds. Record when the device network was disabled so a fixture-only close can be identified.
2. **Server.** In separate runs, arm `500` and `504-after-save`, then reconnect with offline work queued. Compare Android Logcat and the server receipt timestamps. Capture the retained operation IDs and the next WorkManager attempt, then verify one backend task and one linked photo. Use `"target":"photo"` and that photo's queued upload operation ID to exercise replay after a photo save.
3. **Session.** Queue a task and photo offline, arm `expire`, then reconnect. The fixture expires the real token presented by the next authenticated sync request. Capture the Android 401, pending rows, and viewable local photo. Reopen from the launcher if the current screen is still visible, sign in again as the same account, and capture the acknowledgements and backend rows for those records.
4. **Restart.** While still offline, create a task and attach a photo. Capture the task, photo and queue IDs. Force-stop the debug app without clearing data, reopen it from the launcher while offline, select the cached assigned store and open the same pending task and photo. Restore connectivity and capture the acknowledgements and backend rows.

Use the app screenshots from these runs to record `PENDING`, `SYNCING`, `SYNCED` and `FAILED`, with business status visible separately. To capture a genuine terminal failure, use a separate test record and arm `"target":"photo","mode":"400"` with its upload operation ID and session fingerprint before its first upload. Keep its Room row, photo, and rejected queue entry for inspection.
