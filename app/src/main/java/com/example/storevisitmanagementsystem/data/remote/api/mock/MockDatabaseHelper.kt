package com.example.storevisitmanagementsystem.data.remote.api.mock

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import java.util.UUID

class MockDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "mock_server.db"
        const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE users (
                user_id TEXT PRIMARY KEY,
                username TEXT UNIQUE NOT NULL,
                password TEXT NOT NULL,
                full_name TEXT NOT NULL,
                role TEXT NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE assigned_stores (
                store_id TEXT PRIMARY KEY,
                store_code TEXT NOT NULL,
                name TEXT NOT NULL,
                address TEXT NOT NULL,
                category TEXT NOT NULL,
                assigned_user_id TEXT NOT NULL,
                last_visited TEXT,
                FOREIGN KEY (assigned_user_id) REFERENCES users(user_id)
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE sessions (
                token TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(user_id)
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE processed_operations (
                operation_id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                response_json TEXT NOT NULL,
                processed_at INTEGER NOT NULL
            );
            """.trimIndent()
        )

        seedInitialData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS processed_operations")
        db.execSQL("DROP TABLE IF EXISTS sessions")
        db.execSQL("DROP TABLE IF EXISTS assigned_stores")
        db.execSQL("DROP TABLE IF EXISTS users")
        onCreate(db)
    }

    private fun seedInitialData(db: SQLiteDatabase) {
        // Seed Synthetic Users
        insertUser(db, "usr_001", "agent1", "password123", "Alex Mercer", "FIELD_AGENT")
        insertUser(db, "usr_002", "agent2", "password123", "Sarah Connor", "FIELD_SUPERVISOR")

        // Seed Assigned Stores for agent1 (usr_001)
        insertStore(db, "str_101", "STR-NY-001", "Downtown Megastore", "123 Main St, New York, NY", "SUPERMARKET", "usr_001", "2026-03-25T14:30:00Z")
        insertStore(db, "str_102", "STR-NY-002", "Metro Express Mart", "456 Broadway Ave, New York, NY", "CONVENIENCE", "usr_001", null)
        insertStore(db, "str_103", "STR-BK-003", "Brooklyn Center Superstore", "789 Atlantic Ave, Brooklyn, NY", "DEPARTMENT_STORE", "usr_001", "2026-03-28T11:00:00Z")

        // Seed Assigned Stores for agent2 (usr_002)
        insertStore(db, "str_201", "STR-LA-001", "Westside Supercenter", "100 Wilshire Blvd, Los Angeles, CA", "SUPERMARKET", "usr_002", "2026-03-20T16:45:00Z")
        insertStore(db, "str_202", "STR-SF-002", "Bay View Convenience", "200 Market St, San Francisco, CA", "CONVENIENCE", "usr_002", null)
    }

    private fun insertUser(db: SQLiteDatabase, userId: String, username: String, pass: String, name: String, role: String) {
        val cv = ContentValues().apply {
            put("user_id", userId)
            put("username", username)
            put("password", pass)
            put("full_name", name)
            put("role", role)
        }
        db.insert("users", null, cv)
    }

    private fun insertStore(db: SQLiteDatabase, id: String, code: String, name: String, address: String, cat: String, userId: String, lastVisited: String?) {
        val cv = ContentValues().apply {
            put("store_id", id)
            put("store_code", code)
            put("name", name)
            put("address", address)
            put("category", cat)
            put("assigned_user_id", userId)
            put("last_visited", lastVisited)
        }
        db.insert("assigned_stores", null, cv)
    }

    fun authenticate(username: String, pass: String): LoginResponse? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT user_id, username, full_name, role FROM users WHERE username = ? AND password = ?",
            arrayOf(username, pass)
        )

        return cursor.use { c ->
            if (c.moveToFirst()) {
                val userId = c.getString(c.getColumnIndexOrThrow("user_id"))
                val un = c.getString(c.getColumnIndexOrThrow("username"))
                val name = c.getString(c.getColumnIndexOrThrow("full_name"))
                val role = c.getString(c.getColumnIndexOrThrow("role"))
                val token = "mock_token_${userId}_${UUID.randomUUID().toString().take(8)}"
                val expiresAt = "2026-12-31T23:59:59Z"

                saveSession(token, userId, expiresAt)

                LoginResponse(
                    token = token,
                    userId = userId,
                    username = un,
                    name = name,
                    role = role,
                    expiresAt = expiresAt
                )
            } else {
                null
            }
        }
    }

    private fun saveSession(token: String, userId: String, expiresAt: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("token", token)
            put("user_id", userId)
            put("expires_at", expiresAt)
        }
        db.insertWithOnConflict("sessions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun validateToken(token: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT user_id FROM sessions WHERE token = ?", arrayOf(token))
        return cursor.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun revokeToken(token: String) {
        val db = writableDatabase
        db.delete("sessions", "token = ?", arrayOf(token))
    }

    fun getAssignedStores(userId: String, query: String? = null): List<StoreDto> {
        val db = readableDatabase
        val sql = if (query.isNullOrBlank()) {
            "SELECT * FROM assigned_stores WHERE assigned_user_id = ?"
        } else {
            "SELECT * FROM assigned_stores WHERE assigned_user_id = ? AND (name LIKE ? OR store_code LIKE ? OR address LIKE ?)"
        }

        val selectionArgs = if (query.isNullOrBlank()) {
            arrayOf(userId)
        } else {
            val q = "%$query%"
            arrayOf(userId, q, q, q)
        }

        val stores = mutableListOf<StoreDto>()
        val cursor = db.rawQuery(sql, selectionArgs)
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow("store_id")
            val codeIdx = c.getColumnIndexOrThrow("store_code")
            val nameIdx = c.getColumnIndexOrThrow("name")
            val addrIdx = c.getColumnIndexOrThrow("address")
            val catIdx = c.getColumnIndexOrThrow("category")
            val userIdx = c.getColumnIndexOrThrow("assigned_user_id")
            val visitedIdx = c.getColumnIndexOrThrow("last_visited")

            while (c.moveToNext()) {
                stores.add(
                    StoreDto(
                        id = c.getString(idIdx),
                        code = c.getString(codeIdx),
                        name = c.getString(nameIdx),
                        address = c.getString(addrIdx),
                        category = c.getString(catIdx),
                        assignedUserId = c.getString(userIdx),
                        lastVisited = c.getString(visitedIdx)
                    )
                )
            }
        }
        return stores
    }
}
