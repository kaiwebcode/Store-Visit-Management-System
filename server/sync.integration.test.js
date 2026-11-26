const { before, after, test } = require('node:test');
const assert = require('node:assert/strict');
const { fork } = require('node:child_process');
const { createHash } = require('node:crypto');
const { mkdtemp, readdir, rm } = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const sqlite3 = require('sqlite3');
const { encode } = require('jpeg-js');

const jpeg = Buffer.from([
    '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFB',
    'QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF',
    'BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmq',
    'KjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC',
    'AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqK',
    'mqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD8qqKKKAP/2Q==',
].join(''), 'base64');

let testDir;
let server;
let db;
let base;
let agent1;
let agent2;

const run = (sql, args = []) => new Promise((resolve, reject) => {
    db.run(sql, args, error => error ? reject(error) : resolve());
});
const select = (sql, args = []) => new Promise((resolve, reject) => {
    db.all(sql, args, (error, rows) => error ? reject(error) : resolve(rows));
});

async function request(url, token, body, headers = {}) {
    if (token) headers = { ...headers, Authorization: 'Bearer ' + token };
    if (body && !(body instanceof FormData)) {
        headers = { ...headers, 'Content-Type': 'application/json' };
        body = JSON.stringify(body);
    }
    const response = await fetch(base + url, { method: body ? 'POST' : 'GET', body, headers });
    return { status: response.status, ...(await response.json()) };
}

async function login(username) {
    const loginResponse = await request('/auth/login', null, { username, password: 'password123' });
    assert.equal(loginResponse.status, 200);
    return loginResponse.data.token;
}

function operation(id, taskId, sequenceNumber, options = {}) {
    const { type = 'CREATE', userId = 'usr_001', storeId = 'str_101', title = taskId } = options;
    return {
        operationId: id, entityType: 'TASK', entityId: taskId, operationType: type,
        userId, sequenceNumber, payload: { taskId, storeId, title }
    };
}

const sync = (token, ops, headers) => request('/tasks/sync', token, { batchId: 'integration-test', operations: ops }, headers);

function photo(token, { photoId, taskId, operationId, file = jpeg }, headers) {
    const body = new FormData();
    body.append('photoId', photoId);
    body.append('taskId', taskId);
    body.append('operationId', operationId);
    if (file) body.append('file', new Blob([file], { type: 'image/jpeg' }), 'photo.jpg');
    return request('/photos/upload', token, body, headers);
}

const legacy = operation('legacy_op', 'legacy_task', 1);

before(async () => {
    testDir = await mkdtemp(path.join(os.tmpdir(), 'storevisit-day17-'));
    db = new sqlite3.Database(path.join(testDir, 'backend.db'));
    await run(`CREATE TABLE processed_operations (
        operation_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
        response_json TEXT NOT NULL, processed_at INTEGER NOT NULL)`);
    await run(`CREATE TABLE tasks (
        task_id TEXT PRIMARY KEY, store_id TEXT NOT NULL, user_id TEXT NOT NULL, title TEXT NOT NULL,
        description TEXT, category TEXT, priority TEXT, status TEXT, due_date TEXT,
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL, is_deleted INTEGER DEFAULT 0)`);
    await run('INSERT INTO tasks (task_id, store_id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)',
        ['legacy_task', 'str_101', 'usr_001_1234abcd', 'legacy_task', '2026-11-25', '2026-11-25']);
    await run('INSERT INTO processed_operations VALUES (?, ?, ?, ?, ?)', [
        legacy.operationId, 'usr_001_1234abcd',
        createHash('md5').update(JSON.stringify(legacy.payload)).digest('hex'),
        JSON.stringify({ taskId: legacy.entityId, status: 'SAVED' }), 1
    ]);

    server = fork(path.join(__dirname, 'server.js'), [], {
        env: { ...process.env, PORT: '0', STORE_VISIT_BIND_HOST: '127.0.0.1', STORE_VISIT_DATA_DIR: testDir, STORE_VISIT_UPLOADS_DIR: path.join(testDir, 'uploads') },
        stdio: ['ignore', 'ignore', 'ignore', 'ipc']
    });
    const port = await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('Test backend did not start')), 10000);
        server.once('message', message => { clearTimeout(timeout); resolve(message.port); });
        server.once('exit', code => { clearTimeout(timeout); reject(new Error('Test backend exited: ' + code)); });
        server.once('error', reject);
    });
    base = 'http://127.0.0.1:' + port + '/v1';
    agent1 = await login('agent1');
    agent2 = await login('agent2');
});

after(async () => {
    if (server && server.exitCode === null && server.signalCode === null) {
        await new Promise(resolve => { server.once('exit', resolve); server.kill(); });
    }
    if (db) await new Promise((resolve, reject) => db.close(error => error ? reject(error) : resolve()));
    if (testDir) await rm(testDir, { recursive: true, force: true });
});

test('stored sessions return only the authenticated account stores and enforce expiry/logout', async () => {
    for (const [token, userId, count] of [[agent1, 'usr_001', 3], [agent2, 'usr_002', 2]]) {
        const stores = await request('/stores/assigned', token);
        assert.equal(stores.status, 200);
        assert.equal(stores.data.length, count);
        assert.ok(stores.data.every(store => store.assignedUserId === userId));
    }
    assert.equal((await request('/stores/assigned', 'unregistered-test-token')).status, 401);

    const expired = await login('agent1');
    await run("UPDATE sessions SET expires_at = '2000-01-01T00:00:00Z' WHERE token = ?", [expired]);
    assert.equal((await request('/stores/assigned', expired)).status, 401);

    const revoked = await login('agent1');
    assert.equal((await request('/auth/logout', revoked, {})).status, 200);
    assert.equal((await request('/stores/assigned', revoked)).status, 401);
});

test('legacy operation and task owners are repaired without dropping data', async () => {
    assert.equal((await sync(agent1, [legacy])).status, 200);
    assert.deepEqual(await select("SELECT user_id FROM tasks WHERE task_id = 'legacy_task'"), [{ user_id: 'usr_001' }]);
    assert.deepEqual(await select("SELECT user_id FROM processed_operations WHERE operation_id = 'legacy_op'"),
        [{ user_id: 'usr_001' }]);
});

test('tasks are applied in sequence, recognize replay, and isolate operation IDs and account ownership', async () => {
    const create = operation('ordered_create', 'ordered_task', 1);
    const update = operation('ordered_update', 'ordered_task', 2, { type: 'UPDATE', title: 'Final title' });
    const ordered = await sync(agent1, [update, create]);
    assert.equal(ordered.status, 200);
    assert.deepEqual(ordered.data.succeededOperationIds, ['ordered_create', 'ordered_update']);
    assert.deepEqual(await select("SELECT title FROM tasks WHERE task_id = 'ordered_task'"), [{ title: 'Final title' }]);
    assert.equal((await sync(agent1, [create])).status, 200);
    assert.deepEqual(await select("SELECT title FROM tasks WHERE task_id = 'ordered_task'"), [{ title: 'Final title' }]);
    assert.equal((await sync(agent1, [operation('ordered_create', 'ordered_task', 4, { title: 'Different' })])).status, 409);
    assert.equal((await sync(agent2, [create])).status, 403);
    assert.equal((await sync(agent2, [operation('cross_task', 'ordered_task', 3,
        { type: 'DELETE', userId: 'usr_002', storeId: 'str_201' })])).status, 403);
    assert.equal((await sync(agent2, [operation('cross_store', 'other_task', 4,
        { userId: 'usr_002', storeId: 'str_101' })])).status, 403);
    assert.equal((await sync(agent2, [operation('ordered_create', 'agent2_task', 1,
        { userId: 'usr_002', storeId: 'str_201' })])).status, 200);
    assert.equal((await select("SELECT 1 FROM processed_operations WHERE operation_id = 'ordered_create'")).length, 2);
});

test('a database error rolls back the whole task batch and the same operations can retry', async () => {
    const batch = [operation('atomic_first', 'atomic_one', 1), operation('atomic_second', 'atomic_two', 2)];
    await run(`CREATE TRIGGER fail_task_ack BEFORE INSERT ON processed_operations
        WHEN NEW.operation_id = 'atomic_second' BEGIN SELECT RAISE(ABORT, 'test write failure'); END`);
    try {
        assert.equal((await sync(agent1, batch)).status, 500);
        assert.equal((await select("SELECT 1 FROM tasks WHERE task_id IN ('atomic_one', 'atomic_two')")).length, 0);
        assert.equal((await select("SELECT 1 FROM processed_operations WHERE operation_id IN ('atomic_first', 'atomic_second')")).length, 0);
    } finally {
        await run('DROP TRIGGER fail_task_ack');
    }
    assert.equal((await sync(agent1, batch)).status, 200);
});

test('task timeout after commit returns 504 and retry acknowledges exactly one saved operation', async () => {
    const pending = operation('task_timeout_op', 'task_timeout', 1);
    assert.equal((await sync(agent1, [pending], { 'X-Mock-Fail-500': 'true' })).status, 500);
    assert.equal((await sync(agent1, [pending], { 'X-Mock-Force-401': 'true' })).status, 401);
    assert.equal((await select("SELECT 1 FROM tasks WHERE task_id = 'task_timeout'")).length, 0);
    assert.equal((await sync(agent1, [pending], { 'X-Mock-Timeout-After-Save': 'true' })).status, 504);
    assert.deepEqual((await sync(agent1, [pending])).data.succeededOperationIds, [pending.operationId]);
    assert.equal((await select("SELECT 1 FROM tasks WHERE task_id = 'task_timeout'")).length, 1);
    assert.equal((await select("SELECT 1 FROM processed_operations WHERE operation_id = 'task_timeout_op'")).length, 1);
});

test('photo upload rejects unauthenticated, invalid JPEG, missing and cross-account parents without saving', async () => {
    const upload = { photoId: 'rejected_photo', taskId: 'ordered_task', operationId: 'rejected_photo_op' };
    assert.equal((await photo(null, upload)).status, 401);
    assert.equal((await photo(agent1, { ...upload, file: null })).status, 400);
    assert.equal((await photo(agent1, { ...upload, file: Buffer.from([0xff, 0xd8, 0xff, 0xd9]) })).status, 400);
    assert.equal((await photo(agent1, { ...upload, taskId: 'missing_parent' })).status, 409);
    assert.equal((await photo(agent2, upload)).status, 403);
    assert.equal((await select("SELECT 1 FROM photo_evidence WHERE photo_id = 'rejected_photo'")).length, 0);
    assert.equal((await select("SELECT 1 FROM photo_bytes WHERE photo_id = 'rejected_photo'")).length, 0);
    assert.ok(!(await readdir(testDir)).includes('uploads'));
});

test('photo write failure leaves no metadata or bytes and retry is idempotent after a 504', async () => {
    const upload = { photoId: 'saved_photo', taskId: 'ordered_task', operationId: 'saved_photo_op' };
    await run(`CREATE TRIGGER fail_photo_ack BEFORE INSERT ON processed_operations
        WHEN NEW.operation_id = 'saved_photo_op' BEGIN SELECT RAISE(ABORT, 'test write failure'); END`);
    try {
        assert.equal((await photo(agent1, upload)).status, 500);
        assert.equal((await select("SELECT 1 FROM photo_evidence WHERE photo_id = 'saved_photo'")).length, 0);
        assert.equal((await select("SELECT 1 FROM photo_bytes WHERE photo_id = 'saved_photo'")).length, 0);
    } finally {
        await run('DROP TRIGGER fail_photo_ack');
    }

    assert.equal((await photo(agent1, upload, { 'X-Mock-Timeout-After-Save': 'true' })).status, 504);
    const repeated = await photo(agent1, upload);
    assert.equal(repeated.status, 200);
    assert.equal(repeated.data.operationId, upload.operationId);
    const received = await fetch(repeated.data.remoteUrl, { headers: { Authorization: 'Bearer ' + agent1 } });
    assert.equal(received.status, 200);
    assert.deepEqual(Buffer.from(await received.arrayBuffer()), jpeg);
    assert.equal((await fetch(repeated.data.remoteUrl, { headers: { Authorization: 'Bearer ' + agent2 } })).status, 404);
    assert.equal((await photo(agent1, { ...upload, photoId: 'different_photo' })).status, 409);
    const changedJpeg = encode({ width: 1, height: 1, data: Buffer.from([255, 0, 0, 255]) }).data;
    assert.equal((await photo(agent1, { ...upload, file: changedJpeg })).status, 409);
    assert.equal((await select("SELECT 1 FROM photo_evidence WHERE photo_id = 'saved_photo'")).length, 1);
    assert.equal((await select("SELECT 1 FROM processed_operations WHERE operation_id = 'saved_photo_op'")).length, 1);
});
