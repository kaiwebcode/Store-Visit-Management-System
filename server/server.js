const express = require('express');
const cors = require('cors');
const multer = require('multer');
const { decode } = require('jpeg-js');
const path = require('node:path');
const fs = require('node:fs/promises');
const crypto = require('node:crypto');
const { withDatabase, transaction, ready, uploadsDir } = require('./db');

const app = express();
const port = Number(process.env.PORT || 3000);
const upload = multer({ limits: { files: 1, fields: 3, fileSize: 5 * 1024 * 1024 } });
const failedOnceOps = new Set();
const testFaultsEnabled = process.env.STORE_VISIT_TEST_FAULTS === '1';
const faultModes = new Set(['interrupt', '500', '504', '504-after-save', 'expire']);
const localAddresses = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);
let nextSyncFault;

app.use(cors());
app.use(express.json({ limit: '10mb' }));

class HttpError extends Error {
    constructor(status, code, message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

function reply(res, data, status = 200) {
    res.status(status).json({ success: true, data, error: null, timestamp: new Date().toISOString() });
}

const route = handler => (req, res, next) => {
    Promise.resolve().then(() => handler(req, res, next)).catch(next);
};

async function session(req, res, next) {
    const match = req.get('authorization')?.match(/^Bearer (\S+)$/);
    const user = match && await withDatabase(db => db.get(
        "SELECT user_id FROM sessions WHERE token = ? AND julianday(expires_at) > julianday('now')",
        [match[1]]
    ));
    if (!user) throw new HttpError(401, 'TOKEN_EXPIRED', 'Session token expired or invalid.');
    req.userId = user.user_id;
    req.token = match[1];
    next();
}

const acceptsLegacyFaultHeaders = req => testFaultsEnabled &&
    process.env.STORE_VISIT_TEST_HEADERS === '1' && localAddresses.has(req.socket.remoteAddress);
const faultSessionId = token => crypto.createHash('sha256').update(token).digest('hex');

function simulateFailure(req) {
    if (!acceptsLegacyFaultHeaders(req)) return;
    if (req.get('X-Mock-Force-401') === 'true') {
        throw new HttpError(401, 'TOKEN_EXPIRED', 'Simulated session expiration.');
    }
    if (req.get('X-Mock-Fail-500') === 'true' || req.get('X-Mock-Timeout') === 'true') {
        const status = req.get('X-Mock-Fail-500') === 'true' ? 500 : 504;
        throw new HttpError(status, 'TRANSIENT_FAILURE', 'Simulated failure before save.');
    }
}

function simulateTimeoutAfterSave(req) {
    if (req.syncFault === '504-after-save' ||
        (acceptsLegacyFaultHeaders(req) && req.get('X-Mock-Timeout-After-Save') === 'true')) {
        throw new HttpError(504, 'TIMEOUT_AFTER_SAVE', 'Simulated timeout after server save.');
    }
}

// Only the developer's machine can arm a single fault on the test backend.
app.post('/v1/debug/sync-fault', route((req, res) => {
    if (!testFaultsEnabled || !localAddresses.has(req.socket.remoteAddress) || req.get('Origin')) {
        throw new HttpError(404, 'NOT_FOUND', 'Endpoint not found.');
    }
    const { target, mode, operationId, sessionId } = req.body || {};
    if (!['task', 'photo'].includes(target) ||
        (!faultModes.has(mode) && !(target === 'photo' && mode === '400')) ||
        !validId(operationId) || typeof sessionId !== 'string' || !/^[a-f0-9]{64}$/.test(sessionId)) {
        throw new HttpError(400, 'BAD_FAULT', 'Invalid sync fault target or mode.');
    }
    nextSyncFault = { target, mode, operationId, sessionId };
    reply(res, { target, mode, operationId });
}));

const injectSyncFault = target => route(async (req, res, next) => {
    const fault = nextSyncFault;
    if (!testFaultsEnabled || fault?.target !== target || fault.sessionId !== faultSessionId(req.token)) {
        return next();
    }
    const operationMatches = target === 'task'
        ? Array.isArray(req.body?.operations) && req.body.operations.some(op => op?.operationId === fault.operationId)
        : req.body?.operationId === fault.operationId;
    if (!operationMatches) return next();

    req.syncFault = fault.mode;
    nextSyncFault = undefined;
    console.info(`Sync fault received authenticated ${target} request: ${req.syncFault} (operationId=${fault.operationId})`);

    if (req.syncFault === 'expire') {
        await withDatabase(db => db.run(
            "UPDATE sessions SET expires_at = datetime('now', '-1 second') WHERE token = ?",
            [req.token]
        ));
        return session(req, res, next);
    }
    if (req.syncFault === '400') {
        throw new HttpError(400, 'BAD_PHOTO', 'Injected permanent photo rejection before save.');
    }
    if (req.syncFault === '500' || req.syncFault === '504') {
        throw new HttpError(Number(req.syncFault), 'TRANSIENT_FAILURE', 'Injected failure before save.');
    }
    if (req.syncFault === 'interrupt') {
        // Hold an already-received request long enough to switch off the phone's network.
        // If the connection stays open, close it without a response or write.
        const holdMs = 8000;
        let fixtureClosed = false;
        const timer = setTimeout(() => {
            fixtureClosed = true;
            req.socket.destroy();
        }, holdMs);
        const closed = () => {
            clearTimeout(timer);
            const source = fixtureClosed ? 'fixture deadline' : 'peer connection';
            console.info(`Sync fault ${target} request closed without saving (${source})`);
        };
        res.once('close', closed);
        return;
    }
    next();
});

app.post('/v1/auth/login', route(async (req, res) => {
    const { username, password } = req.body || {};
    if (typeof username !== 'string' || typeof password !== 'string' || !username || !password) {
        throw new HttpError(400, 'BAD_REQUEST', 'Username and password required.');
    }
    const user = await withDatabase(db => db.get(
        'SELECT user_id, username, full_name, role FROM users WHERE username = ? AND password = ?',
        [username, password]
    ));
    if (!user) throw new HttpError(401, 'INVALID_CREDENTIALS', 'Invalid username or password.');

    const token = crypto.randomBytes(32).toString('hex');
    const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
    await withDatabase(db => db.run('INSERT INTO sessions VALUES (?, ?, ?)', [token, user.user_id, expiresAt]));
    if (testFaultsEnabled) {
        console.info(`Sync test session armed-target ID: userId=${user.user_id}, sessionId=${faultSessionId(token)}`);
    }
    reply(res, { token, userId: user.user_id, username: user.username, name: user.full_name, role: user.role, expiresAt });
}));

app.post('/v1/auth/logout', route(session), route(async (req, res) => {
    await withDatabase(db => db.run('DELETE FROM sessions WHERE token = ?', [req.token]));
    reply(res, { message: 'Logged out successfully.' });
}));

app.get('/v1/stores/assigned', route(session), route(async (req, res) => {
    const query = typeof req.query.query === 'string' ? req.query.query.trim() : '';
    const stores = await withDatabase(db => db.all(
        `SELECT * FROM assigned_stores WHERE assigned_user_id = ?
         AND (? = '' OR name LIKE ? OR store_code LIKE ? OR address LIKE ?) ORDER BY store_id`,
        [req.userId, query, ...Array(3).fill('%' + query + '%')]
    ));
    reply(res, stores.map(store => ({
        id: store.store_id, code: store.store_code, name: store.name, address: store.address,
        category: store.category, assignedUserId: store.assigned_user_id, lastVisited: store.last_visited
    })));
}));

const validId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value);
const hash = (value, algorithm = 'sha256') => crypto.createHash(algorithm).update(value).digest('hex');

function canonicalJson(value) {
    if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
    if (value !== null && typeof value === 'object') {
        return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonicalJson(value[key])).join(',') + '}';
    }
    return JSON.stringify(value);
}

function replayMismatch() {
    return new HttpError(409, 'OPERATION_PAYLOAD_MISMATCH', 'Operation ID was already used for a different request.');
}

function forbidden() {
    return new HttpError(403, 'FORBIDDEN', 'The operation does not belong to this account or assigned store.');
}

const findOperation = (db, userId, operationId) => db.get(
    'SELECT payload_hash, response_json FROM processed_operations WHERE user_id = ? AND operation_id = ?',
    [userId, operationId]
);

const recordOperation = (db, userId, operationId, payloadHash, result) => db.run(
    'INSERT INTO processed_operations (operation_id, user_id, payload_hash, response_json, processed_at) VALUES (?, ?, ?, ?, ?)',
    [operationId, userId, payloadHash, JSON.stringify(result), Date.now()]
);

function parseTaskOperation(op, userId) {
    if (!op || !validId(op.operationId) || !validId(op.entityId) || op.entityType !== 'TASK' ||
        !['CREATE', 'UPDATE', 'DELETE'].includes(op.operationType) ||
        !Number.isSafeInteger(op.sequenceNumber ?? 0) || (op.sequenceNumber ?? 0) < 0) {
        throw new HttpError(400, 'BAD_OPERATION', 'Invalid task operation.');
    }
    if (op.userId !== userId) throw forbidden();

    let task;
    try {
        task = typeof op.payload === 'string' ? JSON.parse(op.payload) : op.payload;
    } catch {
        throw new HttpError(400, 'BAD_OPERATION', 'Invalid task payload.');
    }
    if (!task || task.taskId !== op.entityId || !validId(task.storeId) ||
        (op.operationType !== 'DELETE' && (typeof task.title !== 'string' || !task.title.trim()))) {
        throw new HttpError(400, 'BAD_OPERATION', 'Invalid task payload.');
    }
    if (task.userId !== undefined && task.userId !== userId) throw forbidden();
    return task;
}

async function applyTask(db, userId, op, task) {
    const savedTask = await db.get('SELECT user_id, store_id, is_deleted FROM tasks WHERE task_id = ?', [op.entityId]);
    const store = await db.get('SELECT assigned_user_id FROM assigned_stores WHERE store_id = ?', [task.storeId]);
    if (store?.assigned_user_id !== userId ||
        (savedTask && (savedTask.user_id !== userId || savedTask.store_id !== task.storeId))) throw forbidden();

    const payloadHash = hash(canonicalJson({ entityType: op.entityType, entityId: op.entityId, type: op.operationType, task }));
    const existing = await findOperation(db, userId, op.operationId);
    if (existing) {
        const legacyHash = hash(typeof op.payload === 'string' ? op.payload : JSON.stringify(task), 'md5');
        if (existing.payload_hash !== payloadHash) {
            const cached = JSON.parse(existing.response_json);
            if (existing.payload_hash !== legacyHash || cached.taskId !== op.entityId ||
                (cached.status === 'DELETED') !== (op.operationType === 'DELETE')) throw replayMismatch();
        }
        return;
    }

    if (op.operationType === 'CREATE' && savedTask) {
        throw new HttpError(409, 'TASK_EXISTS', 'Task ID was already used.');
    }

    const now = new Date().toISOString();
    if (op.operationType === 'DELETE') {
        if (!savedTask) throw new HttpError(409, 'MISSING_TASK', 'The task must be created before deletion.');
        await db.run('UPDATE tasks SET is_deleted = 1, updated_at = ? WHERE task_id = ? AND user_id = ?',
            [task.updatedAt || now, op.entityId, userId]);
    } else {
        if (op.operationType === 'UPDATE' && (!savedTask || savedTask.is_deleted)) {
            throw new HttpError(409, 'MISSING_TASK', 'The task must be created before updating.');
        }
        await db.run(`
            INSERT INTO tasks
                (task_id, store_id, user_id, title, description, category, priority, status, due_date, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(task_id) DO UPDATE SET
                title = excluded.title, description = excluded.description, category = excluded.category,
                priority = excluded.priority, status = excluded.status, due_date = excluded.due_date,
                updated_at = excluded.updated_at
        `, [op.entityId, task.storeId, userId, task.title, task.description || '', task.category || 'GENERAL',
            task.priority || 'MEDIUM', task.status || 'PENDING', task.dueDate || null, task.createdAt || now, task.updatedAt || now]);
    }
    await recordOperation(db, userId, op.operationId, payloadHash,
        { taskId: op.entityId, status: op.operationType === 'DELETE' ? 'DELETED' : 'SAVED' });
}

app.post('/v1/tasks/sync', route(session), injectSyncFault('task'), route(async (req, res) => {
    simulateFailure(req);
    const { operations } = req.body || {};
    if (!Array.isArray(operations)) throw new HttpError(400, 'BAD_REQUEST', 'Missing operations array.');
    const ordered = operations.map(op => ({ op, task: parseTaskOperation(op, req.userId) }))
        .sort((left, right) => (left.op.sequenceNumber ?? 0) - (right.op.sequenceNumber ?? 0));
    const failOnce = acceptsLegacyFaultHeaders(req) && req.get('X-Mock-Fail-Once');
    const failureKey = JSON.stringify([req.userId, failOnce]);
    if (failOnce && !failedOnceOps.has(failureKey) && ordered.some(({ op }) => op.operationId === failOnce)) {
        failedOnceOps.add(failureKey);
        throw new HttpError(500, 'TRANSIENT_FAILURE', 'Simulated one-time failure.');
    }

    const succeededOperationIds = await transaction(async db => {
        const acknowledged = new Set();
        for (const { op, task } of ordered) {
            await applyTask(db, req.userId, op, task);
            acknowledged.add(op.operationId);
        }
        return [...acknowledged];
    });

    simulateTimeoutAfterSave(req);
    reply(res, { processedCount: succeededOperationIds.length, succeededOperationIds, failedOperations: [] });
}));

// Decode with bounded memory and resolution before writing any upload data.
function isJpeg(bytes) {
    if (!bytes || bytes.length < 4 || bytes.readUInt16BE(0) !== 0xffd8 ||
        bytes.readUInt16BE(bytes.length - 2) !== 0xffd9) return false;
    try {
        const image = decode(bytes, {
            tolerantDecoding: false,
            useTArray: true,
            formatAsRGBA: false,
            maxResolutionInMP: 12,
            maxMemoryUsageInMB: 64
        });
        return image.width > 0 && image.height > 0;
    } catch {
        return false;
    }
}

const photoUrl = (req, photoId) => req.protocol + '://' + req.get('host') + '/v1/uploads/' + photoId + '.jpg';
const legacyFile = photoId => path.join(uploadsDir, photoId + '.jpg');

app.post('/v1/photos/upload', route(session), route((req, res, next) => {
    simulateFailure(req);
    next();
}), upload.single('file'), injectSyncFault('photo'), route(async (req, res) => {
    const { operationId, photoId, taskId } = req.body || {};
    if (![operationId, photoId, taskId].every(validId) || req.file?.mimetype !== 'image/jpeg' || !isJpeg(req.file.buffer)) {
        throw new HttpError(400, 'BAD_PHOTO', 'Operation ID, photo ID, task ID and a valid JPEG are required.');
    }
    const jpeg = req.file.buffer;
    const jpegHash = hash(jpeg);
    const payloadHash = hash(canonicalJson({ entityType: 'PHOTO', photoId, taskId, jpegHash }));
    const { ack, created } = await transaction(async db => {
        const parent = await db.get('SELECT user_id, is_deleted FROM tasks WHERE task_id = ?', [taskId]);
        if (!parent) throw new HttpError(409, 'MISSING_TASK', 'The photo parent task does not exist.');
        if (parent.user_id !== req.userId) throw forbidden();

        const existing = await findOperation(db, req.userId, operationId);
        const photo = await db.get('SELECT user_id, task_id FROM photo_evidence WHERE photo_id = ?', [photoId]);
        const ack = { operationId, photoId, taskId, remoteUrl: photoUrl(req, photoId), status: 'UPLOADED' };
        if (existing) {
            if (!photo || photo.user_id !== req.userId || photo.task_id !== taskId) throw replayMismatch();
            if (existing.payload_hash !== payloadHash) {
                const cached = JSON.parse(existing.response_json);
                const savedBytes = existing.payload_hash.length === 32 && cached.operationId === operationId &&
                    cached.photoId === photoId && cached.taskId === taskId
                    ? await fs.readFile(legacyFile(photoId)).catch(() => null) : null;
                if (!savedBytes || hash(savedBytes) !== jpegHash) throw replayMismatch();
            }
            return { ack, created: false };
        }

        if (parent.is_deleted) throw new HttpError(409, 'MISSING_TASK', 'The photo parent task was deleted.');
        if (photo) {
            if (photo.user_id !== req.userId) throw forbidden();
            throw new HttpError(409, 'PHOTO_EXISTS', 'Photo ID was already used.');
        }
        await db.run(
            'INSERT INTO photo_evidence VALUES (?, ?, ?, ?, ?, ?, ?)',
            [photoId, taskId, req.userId, ack.remoteUrl, jpeg.length, 'image/jpeg', new Date().toISOString()]
        );
        await db.run('INSERT INTO photo_bytes (photo_id, jpeg) VALUES (?, ?)', [photoId, jpeg]);
        await recordOperation(db, req.userId, operationId, payloadHash, ack);
        return { ack, created: true };
    });

    simulateTimeoutAfterSave(req);
    reply(res, ack, created ? 201 : 200);
}));

app.get('/v1/uploads/:filename', route(session), route(async (req, res, next) => {
    const filename = req.params.filename;
    const photoId = filename.slice(0, -4);
    if (!filename.endsWith('.jpg') || !validId(photoId)) throw new HttpError(404, 'NOT_FOUND', 'Photo not found.');
    const photo = await withDatabase(db => db.get(
        'SELECT jpeg FROM photo_evidence LEFT JOIN photo_bytes USING (photo_id) WHERE photo_id = ? AND user_id = ?',
        [photoId, req.userId]
    ));
    if (!photo) throw new HttpError(404, 'NOT_FOUND', 'Photo not found.');
    if (photo.jpeg) return res.type('jpeg').send(photo.jpeg);
    res.sendFile(legacyFile(photoId), error => { if (error) next(error); });
}));

// Local fixture inspection for the Day 17 demo.
app.get('/v1/debug/db-summary', route(async (req, res) => {
    if (!localAddresses.has(req.socket.remoteAddress)) {
        throw new HttpError(404, 'NOT_FOUND', 'Endpoint not found.');
    }
    res.json(await withDatabase(async db => ({
        tasks: await db.all('SELECT task_id, title, status, is_deleted FROM tasks'),
        photos: await db.all('SELECT photo_id, task_id, remote_url FROM photo_evidence'),
        processedOperations: await db.all('SELECT operation_id, user_id, processed_at FROM processed_operations')
    })));
}));

app.use((error, req, res, next) => {
    if (res.headersSent) return next(error);
    const status = error instanceof multer.MulterError ? 400 : error.status || 500;
    if (status >= 500 && !(error instanceof HttpError)) console.error(error);
    res.status(status).json({
        success: false, data: null,
        error: { code: error instanceof HttpError ? error.code : 'REQUEST_ERROR',
            message: status >= 500 && !(error instanceof HttpError) ? 'The server could not save the request.' : error.message },
        timestamp: new Date().toISOString()
    });
});

ready.then(() => {
    const server = app.listen(port, process.env.STORE_VISIT_BIND_HOST || '0.0.0.0', () => {
        const actualPort = server.address().port;
        console.log('Standalone Store Visit Backend running at http://localhost:' + actualPort + '/v1/');
        if (process.send) process.send({ port: actualPort });
    });
}).catch(error => {
    console.error(error);
    process.exitCode = 1;
});
