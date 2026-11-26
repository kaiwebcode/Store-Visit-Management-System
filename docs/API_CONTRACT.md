# API Contract Specification
**Project:** Store Visit Management System
**Base URL (Emulator):** `http://10.0.2.2:3000/v1/`
**Base URL (Physical Device):** `http://<HOST_LAN_IP>:3000/v1/`
**Protocol:** REST over HTTP/HTTPS
**Backend Engine:** Standalone Node.js + Express + SQLite Server (`server/data/backend.db`)

---

## 1. Global Headers & Network Configuration

### Request Headers
- `Content-Type: application/json`
- `Authorization: Bearer <token>` (Required for all authenticated endpoints)
- `X-Mock-Fail-500: true` (Simulates 500 Internal Server Error for testing retries)
- `X-Mock-Timeout: true` (Returns HTTP 504 before saving)
- `X-Mock-Timeout-After-Save: true` (Saves operation to server DB, then returns 504 Timeout to test idempotency)
- `X-Mock-Fail-Once: <op_id>` (Fails once with 500 for specified operationId, then succeeds on retry)
- `X-Mock-Force-401: true` (Simulates 401 Unauthorized token expiry)

---

## 2. Authentication & Store Endpoints

### 2.1 Login (`POST /v1/auth/login`)
- **Body:** `{ "username": "agent1", "password": "password123" }`
- **Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "token": "<backend-issued-bearer-token>",
    "userId": "usr_001",
    "username": "agent1",
    "name": "Alex Mercer",
    "role": "FIELD_AGENT",
    "expiresAt": "2026-04-30T10:00:00Z"
  },
  "error": null,
  "timestamp": "2026-03-31T10:00:00Z"
}
```

### 2.2 Get Assigned Stores (`GET /v1/stores/assigned`)
- **Headers:** `Authorization: Bearer <token>`
- **Response (200 OK):** List of stores for the account linked to the stored, unexpired session.

---

## 3. Synchronization Endpoints

### 3.1 Batch Task Synchronization (`POST /v1/tasks/sync`)

The server applies the batch in sequence order and commits task changes and replay records in a single transaction. A rejected or failed batch rolls back.

- **Headers:** `Authorization: Bearer <token>`
- **Request Body:**
```json
{
  "batchId": "batch_uuid_998877",
  "operations": [
    {
      "operationId": "op_uuid_11111",
      "entityType": "TASK",
      "entityId": "tsk_uuid_1001",
      "operationType": "CREATE",
      "userId": "usr_001",
      "sequenceNumber": 1,
      "payload": {
        "taskId": "tsk_uuid_1001",
        "storeId": "str_101",
        "title": "Audit Entrance Display",
        "description": "Check promotional signage.",
        "category": "SIGNAGE",
        "priority": "HIGH",
        "status": "PENDING",
        "createdAt": "2026-03-31T10:10:00Z",
        "updatedAt": "2026-03-31T10:10:00Z"
      }
    }
  ]
}
```
- **Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "processedCount": 1,
    "succeededOperationIds": ["op_uuid_11111"],
    "failedOperations": []
  },
  "error": null,
  "timestamp": "2026-03-31T10:10:05Z"
}
```

### 3.2 Photo Evidence Upload (`POST /v1/photos/upload`)
- **Headers:** `Authorization: Bearer <token>`
- **Request (Multipart Form-Data):** `operationId`, `photoId`, `taskId`, `file`
- The parent task must belong to the signed-in account. The server validates the JPEG and commits its bytes, metadata, and replay record together.
- **Response (201 Created, or 200 OK for matching replay):**
```json
{
  "success": true,
  "data": {
    "photoId": "pho_uuid_55555",
    "taskId": "tsk_uuid_1001",
    "operationId": "op_uuid_33333",
    "remoteUrl": "http://10.0.2.2:3000/v1/uploads/pho_uuid_55555.jpg",
    "status": "UPLOADED"
  },
  "error": null,
  "timestamp": "2026-03-31T10:12:00Z"
}
```

The URL in the acknowledgement requires a valid bearer token for the photo owner.

---

## 4. Idempotency & Error Categories

Replay uses the original operation ID, scoped to the signed-in account. A changed payload for the same operation ID returns 409.

1. **Transient errors.** WorkManager retries HTTP 5xx, network failures, and incomplete acknowledgements with exponential backoff. Pending rows and JPEGs remain available.
2. **Request rejection.** A non-retryable 4xx leaves the operation in Room, shows the affected item as failed, and ends the current ordered drain.
3. **Session expiry.** A 401 clears only the session used for that request. Work resumes after a valid sign-in and a new sync trigger.
