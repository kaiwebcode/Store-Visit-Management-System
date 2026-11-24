# API Contract Specification
**Project:** Store Visit Management System  
**Base URL:** `https://api.storevisit.mock/v1` (Configurable via `BuildConfig.MOCK_BASE_URL`)  
**Protocol:** REST over HTTPS  
**Backend Engine:** Standalone SQLite-backed Mock Server (`mock_server.db`)

---

## 1. Global Headers & Requirements

### Request Headers
- `Content-Type: application/json`
- `Authorization: Bearer <token>` (Required for all endpoints except `/auth/login`)
- `X-Mock-Fail-Rate: <0-100>` (Optional test header for synthetic failure rate)
- `X-Mock-Force-401: true` (Optional test header to force session expiry)

### Standard Response Envelope
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "timestamp": "2026-03-30T10:00:00Z"
}
```

---

## 2. Authentication Endpoints

### 2.1 Login
- **Endpoint:** `POST /auth/login`
- **Auth Required:** No
- **Request Body:**
```json
{
  "username": "agent1",
  "password": "password123"
}
```
- **Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "token": "mock_jwt_token_agent1_987654321",
    "userId": "usr_001",
    "username": "agent1",
    "name": "Alex Mercer",
    "role": "FIELD_AGENT",
    "expiresAt": "2026-03-31T10:00:00Z"
  },
  "error": null,
  "timestamp": "2026-03-30T10:00:00Z"
}
```

### 2.2 Logout
- **Endpoint:** `POST /auth/logout`
- **Auth Required:** Yes
- **Contract Note:** Server invalidates token on backend. Client clears local active token in `TokenManager`, BUT preserves all unsynced Room records and photo evidence files scoped to `userId`.
- **Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "message": "Logged out successfully. Unsynced local data retained for userId usr_001."
  },
  "error": null,
  "timestamp": "2026-03-30T10:05:00Z"
}
```

---

## 3. Store Management Endpoints

### 3.1 Get Assigned Stores
- **Endpoint:** `GET /stores/assigned`
- **Auth Required:** Yes
- **Server Authorization:** Validates Bearer token `userId` against `store_assignments` table.
- **Query Params:** `query` (optional search filter string)
- **Response (200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "id": "str_101",
      "code": "STR-NY-001",
      "name": "Downtown Megastore",
      "address": "123 Main St, New York, NY",
      "category": "SUPERMARKET",
      "assignedUserId": "usr_001",
      "lastVisited": "2026-03-25T14:30:00Z"
    }
  ],
  "error": null,
  "timestamp": "2026-03-30T10:01:00Z"
}
```

---

## 4. Task Management & Sync Endpoints

### 4.1 Batch Task & Operation Sync
- **Endpoint:** `POST /tasks/sync`
- **Auth Required:** Yes
- **Authoritative Operation IDs:** The `operationId` in each operation object in the request body is authoritative.
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
      "payload": {
        "taskId": "tsk_uuid_1001",
        "storeId": "str_101",
        "title": "Audit Entrance Display",
        "description": "Check promotional signage.",
        "category": "SIGNAGE",
        "priority": "HIGH",
        "status": "PENDING",
        "dueDate": "2026-03-31T18:00:00Z",
        "createdAt": "2026-03-30T10:10:00Z",
        "updatedAt": "2026-03-30T10:10:00Z"
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
  "timestamp": "2026-03-30T10:10:05Z"
}
```

### 4.2 Photo Evidence Upload Endpoint
- **Endpoint:** `POST /photos/upload`
- **Auth Required:** Yes
- **Validation Rules:** File size <= 500KB; content-type `image/jpeg`.
- **Request (Multipart / Form-Data):**
  - `photoId`: `pho_uuid_55555`
  - `taskId`: `tsk_uuid_1001`
  - `operationId`: `op_uuid_33333`
  - `userId`: `usr_001`
  - `file`: `[binary image bytes]`
- **Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "photoId": "pho_uuid_55555",
    "taskId": "tsk_uuid_1001",
    "operationId": "op_uuid_33333",
    "remoteUrl": "https://api.storevisit.mock/v1/photos/pho_uuid_55555.jpg",
    "status": "UPLOADED"
  },
  "error": null,
  "timestamp": "2026-03-30T10:12:00Z"
}
```

---

## 5. Idempotency & Error Response Definitions

### Error Response Categories

1. **Transient Errors (Retryable via WorkManager Backoff):**
   - **500 Internal Server Error:** Server-side database lock or temporary failure.
   - **503 Service Unavailable:** Mock server synthetic maintenance mode.
2. **Permanent Errors (Marked `SYNC_FAILED` locally):**
   - **400 Bad Request:** Malformed payload, size limit exceeded (>500KB photo), or payload mismatch for replayed `operationId`.
   - **422 Unprocessable Entity:** Invalid task fields or invalid store assignment.
3. **Conflict Errors (Moved to `SYNC_CONFLICT` state):**
   - **409 Conflict:** Server record has a newer update timestamp than client operation.

### Replay Payload Mismatch Error (400 Bad Request)
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "OPERATION_PAYLOAD_MISMATCH",
    "message": "Operation ID op_uuid_11111 already processed with a different payload hash."
  },
  "timestamp": "2026-03-30T10:14:00Z"
}
```
