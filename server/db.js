const fs = require('node:fs');
const path = require('node:path');
const sqlite3 = require('sqlite3');

const dataDir = process.env.STORE_VISIT_DATA_DIR || path.join(__dirname, 'data');
const uploadsDir = path.resolve(process.env.STORE_VISIT_UPLOADS_DIR || path.join(__dirname, 'uploads'));
fs.mkdirSync(dataDir, { recursive: true });
const connection = new sqlite3.Database(path.join(dataDir, 'backend.db'));
connection.configure('busyTimeout', 5000);

const run = (sql, params = []) => new Promise((resolve, reject) => {
    connection.run(sql, params, err => err ? reject(err) : resolve());
});
const get = (sql, params = []) => new Promise((resolve, reject) => {
    connection.get(sql, params, (err, row) => err ? reject(err) : resolve(row));
});
const all = (sql, params = []) => new Promise((resolve, reject) => {
    connection.all(sql, params, (err, rows) => err ? reject(err) : resolve(rows));
});

const queries = { run, get, all };
let tail = Promise.resolve();

// A single connection cannot interleave requests with an open transaction.
function withDatabase(action) {
    const result = tail.then(() => action(queries));
    tail = result.catch(() => {});
    return result;
}

function transaction(action) {
    return withDatabase(async db => {
        await db.run('BEGIN IMMEDIATE');
        try {
            const result = await action(db);
            await db.run('COMMIT');
            return result;
        } catch (error) {
            try {
                await db.run('ROLLBACK');
            } catch (rollbackError) {
                console.error('SQLite rollback failed', rollbackError);
                // Discard this connection before any other request can use it.
                await new Promise(resolve => connection.close(resolve));
            }
            throw error;
        }
    });
}

const operationsSchema = `CREATE TABLE IF NOT EXISTS processed_operations (
    operation_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    payload_hash TEXT NOT NULL,
    response_json TEXT NOT NULL,
    processed_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, operation_id)
)`;

function initialize() {
    return transaction(async db => {
        await db.run(`CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                password TEXT NOT NULL,
                full_name TEXT NOT NULL,
                role TEXT NOT NULL
            )`);
        await db.run(`CREATE TABLE IF NOT EXISTS assigned_stores (
                store_id TEXT PRIMARY KEY,
                store_code TEXT NOT NULL,
                name TEXT NOT NULL,
                address TEXT NOT NULL,
                category TEXT NOT NULL,
                assigned_user_id TEXT NOT NULL,
                last_visited TEXT,
                FOREIGN KEY (assigned_user_id) REFERENCES users(user_id)
            )`);
        await db.run(`CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(user_id)
            )`);
        await db.run(`CREATE TABLE IF NOT EXISTS tasks (
                task_id TEXT PRIMARY KEY,
                store_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT,
                category TEXT,
                priority TEXT,
                status TEXT,
                due_date TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                is_deleted INTEGER DEFAULT 0
            )`);
        await db.run(`CREATE TABLE IF NOT EXISTS photo_evidence (
                photo_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                remote_url TEXT NOT NULL,
                file_size_bytes INTEGER,
                mime_type TEXT,
                created_at TEXT NOT NULL
            )`);
        await db.run(`INSERT OR IGNORE INTO users VALUES ('usr_001', 'agent1', 'password123', 'Alex Mercer', 'FIELD_AGENT')`);
        await db.run(`INSERT OR IGNORE INTO users VALUES ('usr_002', 'agent2', 'password123', 'Sarah Connor', 'FIELD_SUPERVISOR')`);
        await db.run(`INSERT OR IGNORE INTO assigned_stores VALUES ('str_101', 'STR-NY-001', 'Downtown Megastore', '123 Main St, New York, NY', 'SUPERMARKET', 'usr_001', '2026-03-25T14:30:00Z')`);
        await db.run(`INSERT OR IGNORE INTO assigned_stores VALUES ('str_102', 'STR-NY-002', 'Metro Express Mart', '456 Broadway Ave, New York, NY', 'CONVENIENCE', 'usr_001', NULL)`);
        await db.run(`INSERT OR IGNORE INTO assigned_stores VALUES ('str_103', 'STR-BK-003', 'Brooklyn Center Superstore', '789 Atlantic Ave, Brooklyn, NY', 'DEPARTMENT_STORE', 'usr_001', '2026-03-28T11:00:00Z')`);
        await db.run(`INSERT OR IGNORE INTO assigned_stores VALUES ('str_201', 'STR-LA-001', 'Westside Supercenter', '100 Wilshire Blvd, Los Angeles, CA', 'SUPERMARKET', 'usr_002', '2026-03-20T16:45:00Z')`);
        await db.run(`INSERT OR IGNORE INTO assigned_stores VALUES ('str_202', 'STR-SF-002', 'Bay View Convenience', '200 Market St, San Francisco, CA', 'CONVENIENCE', 'usr_002', NULL)`);
        await db.run(operationsSchema);
        const operationColumns = await db.all('PRAGMA table_info(processed_operations)');
        if (!operationColumns.some(column => column.name === 'user_id' && column.pk)) {
            await db.run('ALTER TABLE processed_operations RENAME TO processed_operations_v1');
            await db.run(operationsSchema);
            await db.run(`
                INSERT INTO processed_operations
                SELECT operation_id, user_id, payload_hash, response_json, processed_at
                FROM processed_operations_v1
            `);
            await db.run('DROP TABLE processed_operations_v1');

            // Repair owners produced by the old mock token parser. Only an exact
            // seeded user ID plus its old eight-hex-character suffix qualifies.
            for (const table of ['tasks', 'photo_evidence', 'processed_operations']) {
                await db.run(`
                    UPDATE ${table}
                    SET user_id = (
                        SELECT users.user_id FROM users
                        WHERE substr(${table}.user_id, 1, length(users.user_id) + 1) = users.user_id || '_'
                          AND length(${table}.user_id) = length(users.user_id) + 9
                          AND substr(${table}.user_id, -8) NOT GLOB '*[^0-9a-f]*'
                    )
                    WHERE NOT EXISTS (SELECT 1 FROM users WHERE users.user_id = ${table}.user_id)
                      AND EXISTS (
                        SELECT 1 FROM users
                        WHERE substr(${table}.user_id, 1, length(users.user_id) + 1) = users.user_id || '_'
                          AND length(${table}.user_id) = length(users.user_id) + 9
                          AND substr(${table}.user_id, -8) NOT GLOB '*[^0-9a-f]*'
                    )
                `);
            }
        }
        await db.run(`CREATE TABLE IF NOT EXISTS photo_bytes (
            photo_id TEXT PRIMARY KEY,
            jpeg BLOB NOT NULL,
            FOREIGN KEY (photo_id) REFERENCES photo_evidence(photo_id)
        )`);
    });
}

const ready = initialize();
module.exports = { withDatabase, transaction, ready, uploadsDir };
