# Day 18 root causes and fixes

Branch `feature/day18-debug-monitor`. Target device OnePlus EB2101. Debug backend `192.168.1.111:3000`.

## Authentication fallback

- **Expected** — Debug sign-in with the Node backend configured establishes a backend session. If the backend is unreachable, sign-in reports a network error and any queued work remains on the device.
- **Observed on the earlier build** — The Android worker skipped queued work because it held a local-only mock session. The earlier debug host default was `192.168.1.110`. The login interceptor caught backend connection failures and silently used local mock login. Logcat establishes the local-only pause. The original failed connection itself was not captured. [Pause trace](logs/auth-recovery-debug.txt)
- **Fix and evidence** — Debug Node login propagates transport failures and routes legacy local-only sessions back to sign-in. Both task and photo sync reject local-only sessions. An earlier successful backend-session run acknowledged a task and a linked photo. This is evidence of recovery from the local-only session problem. [Recovery trace](logs/auth-fix-verification.txt) · [Backend rows](api/network-recovery-success.json)

## Offline task creation and cold restart

- **Expected** — After online sign-in and store selection, agents can create tasks, attach photos, and reopen them offline after a process restart.
- **Observed on the earlier build** — The reported task save returned “Network error. Could not connect to store service.” The old save paths used `validateSelectedStore()`, which called the assigned-stores API before writing to Room. A subsequent change routed store selection through Node and left the launcher unable to restore the store after a cold offline restart.
- **Fix and evidence** — The store repository caches assignments per account and uses them on transport failure. Authoritative HTTP 401 invalidates the matching session without serving cached stores. Task and photo saves check the active account, selected store, and cached authorization without calling the API. JVM tests verify offline save and repository recreation. The historical backend data contains one `Day18 Offline fix 1` task and one linked photo. The saved Android log for that run is empty, so the snapshot establishes the backend result only. [Offline recovery rows](api/offline-fix-recovery.json)

## Final code blocker fixes

- **Concurrent 401 responses.** If the worker cleared the token first, the store endpoint's late 401 was treated as an account change. Store Selection now observes the current session and requests sign-in when it is cleared. It checks the session again before navigating. An old response cannot clear or redirect a replacement login.
- **Unscoped sync faults.** The old fixture consumed the next authenticated request of the selected type. A fault now matches both the queued operation ID and a fingerprint of the intended authenticated session. Only the local operator can arm it, and legacy headers require a separate loopback test-runner opt-in.

## Audit decisions

| Finding | Decision |
| --- | --- |
| A task could show `SYNCED` before linked photos finished | Aggregate task and photo acknowledgement and failure states. Overlay live in-flight state from the active request, leaving durable queue records available across process death. |
| One permanent failure stopped the account queue | Retain the rejected operation and its error. Split rejected atomic task batches, continue independent work, and hold later operations for affected tasks. |
| Task-list completion could leave work unscheduled | Schedule WorkManager for the account that successfully completed or reopened the task. |
| The optional debug menu delayed requests before transmission | Remove the menu. Use a loopback-only, opt-in server fixture that reports receipt of the targeted authenticated sync request. |
| Fixture values and simulated 401 leaked into shared Android code | Remove credential defaults and client-side mock 401 injection. Expire a stored session through the test backend to exercise real HTTP 401. |

See [the debugging log](debugging-log.md) for inspection evidence and [the regression report](regression-results.md) for the run status and remaining device procedures.
