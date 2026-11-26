const http = require('http');
const fs = require('fs');
const path = require('path');

function request(options, bodyData, headers = {}) {
    return new Promise((resolve, reject) => {
        const req = http.request({
            hostname: 'localhost',
            port: 3000,
            path: options.path,
            method: options.method || 'GET',
            headers: {
                'Content-Type': 'application/json',
                ...headers
            }
        }, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    resolve({ status: res.statusCode, body: JSON.parse(data) });
                } catch (e) {
                    resolve({ status: res.statusCode, raw: data });
                }
            });
        });

        req.on('error', reject);
        if (bodyData) {
            req.write(typeof bodyData === 'string' ? bodyData : JSON.stringify(bodyData));
        }
        req.end();
    });
}

async function runVerification() {
    console.log("=== STARTING DAY 17 VERIFICATION TESTS ===");

    // 1. Test Login
    const loginRes = await request({ path: '/v1/auth/login', method: 'POST' }, { username: 'agent1', password: 'password123' });
    console.log("1. Login status:", loginRes.status);
    const token = loginRes.body?.data?.token;

    // 2. Test Get Assigned Stores
    const storesRes = await request({ path: '/v1/stores/assigned', method: 'GET' }, null, { 'Authorization': `Bearer ${token}` });
    console.log("2. Assigned Stores status:", storesRes.status, "Store Count:", storesRes.body?.data?.length);

    // 3. Test Batch Sync (3 Tasks)
    const op1 = { operationId: 'op_test_001', entityType: 'TASK', entityId: 'tsk_test_001', operationType: 'CREATE', userId: 'usr_001', sequenceNumber: 1, payload: { taskId: 'tsk_test_001', storeId: 'str_101', title: 'Task 1 Offline Audit', description: 'Check signage', category: 'SIGNAGE', priority: 'HIGH', status: 'PENDING' } };
    const op2 = { operationId: 'op_test_002', entityType: 'TASK', entityId: 'tsk_test_002', operationType: 'CREATE', userId: 'usr_001', sequenceNumber: 2, payload: { taskId: 'tsk_test_002', storeId: 'str_101', title: 'Task 2 Offline Display Check', description: 'Check stock', category: 'DISPLAY', priority: 'MEDIUM', status: 'PENDING' } };
    const op3 = { operationId: 'op_test_003', entityType: 'TASK', entityId: 'tsk_test_003', operationType: 'CREATE', userId: 'usr_001', sequenceNumber: 3, payload: { taskId: 'tsk_test_003', storeId: 'str_101', title: 'Task 3 Price Tag Verification', description: 'Verify prices', category: 'STOCK', priority: 'LOW', status: 'PENDING' } };

    const syncRes1 = await request({ path: '/v1/tasks/sync', method: 'POST' }, { batchId: 'batch_001', operations: [op1, op2, op3] }, { 'Authorization': `Bearer ${token}` });
    console.log("3. Task Batch Sync status:", syncRes1.status, "Succeeded Ops:", syncRes1.body?.data?.succeededOperationIds);

    // 4. Test Timeout-After-Save Idempotency (Simulating network timeout right after DB commit)
    const opTimeout = { operationId: 'op_timeout_004', entityType: 'TASK', entityId: 'tsk_timeout_004', operationType: 'CREATE', userId: 'usr_001', sequenceNumber: 4, payload: { taskId: 'tsk_timeout_004', storeId: 'str_101', title: 'Timeout Task', description: 'Simulated timeout after save', category: 'GENERAL', priority: 'HIGH', status: 'PENDING' } };

    // First try: returns 504 but saves op_timeout_004 in DB
    const syncResTimeout = await request({ path: '/v1/tasks/sync', method: 'POST' }, { batchId: 'batch_002', operations: [opTimeout] }, { 'Authorization': `Bearer ${token}`, 'X-Mock-Timeout-After-Save': 'true' });
    console.log("4a. Timeout-After-Save status:", syncResTimeout.status, "(Simulated 504 Timeout)");

    // Second try: Retry exact same operationId
    const syncResRetry = await request({ path: '/v1/tasks/sync', method: 'POST' }, { batchId: 'batch_002', operations: [opTimeout] }, { 'Authorization': `Bearer ${token}` });
    console.log("4b. Retry exact same operationId status:", syncResRetry.status, "Succeeded Ops:", syncResRetry.body?.data?.succeededOperationIds);

    // 5. Test HTTP 500 error & recovery
    const syncRes500 = await request({ path: '/v1/tasks/sync', method: 'POST' }, { batchId: 'batch_003', operations: [op3] }, { 'Authorization': `Bearer ${token}`, 'X-Mock-Fail-500': 'true' });
    console.log("5a. HTTP 500 Simulated Error status:", syncRes500.status);

    // 6. Test HTTP 401 session expired
    const syncRes401 = await request({ path: '/v1/tasks/sync', method: 'POST' }, { batchId: 'batch_004', operations: [op3] }, { 'Authorization': `Bearer ${token}`, 'X-Mock-Force-401': 'true' });
    console.log("6. HTTP 401 Simulated Expiry status:", syncRes401.status);

    // 7. Verify Database Records
    const summaryRes = await request({ path: '/v1/debug/db-summary', method: 'GET' });
    console.log("7. DB Summary - Total Server Tasks:", summaryRes.body?.tasks?.length);
    console.log("   Server Task IDs:", summaryRes.body?.tasks?.map(t => t.task_id));
    console.log("   Processed Operation IDs:", summaryRes.body?.processedOperations?.map(o => o.operation_id));

    // Assert no duplicate task records for tsk_timeout_004
    const timeoutTaskMatches = summaryRes.body?.tasks?.filter(t => t.task_id === 'tsk_timeout_004');
    console.log("   Matches for 'tsk_timeout_004':", timeoutTaskMatches?.length, "(EXPECTED EXACTLY 1)");

    if (timeoutTaskMatches?.length === 1) {
        console.log("SUCCESS: IDEMPOTENCY PROVEN - EXACTLY 1 LOGICAL RECORD ON SERVER!");
    } else {
        console.error("FAILURE: Duplicate record found!");
    }
}

runVerification().catch(console.error);
