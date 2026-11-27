package com.example.storevisitmanagementsystem.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.storevisitmanagementsystem.util.Constants
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

class TokenManagerImpl(context: Context) : ITokenManager {

    private companion object {
        private const val TAG = "StoreVisitSync"
        val sessionLock = Any()
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        Constants.PREF_NAME,
        Context.MODE_PRIVATE
    )

    override fun saveSession(
        token: String,
        userId: String,
        username: String,
        name: String,
        role: String
    ) = synchronized(sessionLock) {
        val editor = prefs.edit()
        if (getUserId() != userId) editor.remove(Constants.KEY_SELECTED_STORE_ID)
        editor.putString(Constants.KEY_AUTH_TOKEN, token)
            .putString(Constants.KEY_USER_ID, userId)
            .putString(Constants.KEY_USERNAME, username)
            .putString(Constants.KEY_USER_NAME, name)
            .putString(Constants.KEY_USER_ROLE, role)
            .apply()
    }

    override fun getAuthToken(): String? {
        return prefs.getString(Constants.KEY_AUTH_TOKEN, null)
    }

    override fun getUserId(): String? {
        return prefs.getString(Constants.KEY_USER_ID, null)
    }

    override fun getAuthTokenForUser(userId: String): String? = synchronized(sessionLock) {
        getAuthToken()?.takeIf { getUserId() == userId }
    }

    override fun getUsername(): String? {
        return prefs.getString(Constants.KEY_USERNAME, null)
    }

    override fun getUserName(): String? {
        return prefs.getString(Constants.KEY_USER_NAME, null)
    }

    override fun getUserRole(): String? {
        return prefs.getString(Constants.KEY_USER_ROLE, null)
    }

    override fun saveSelectedStoreId(storeId: String?) {
        prefs.edit().putString(Constants.KEY_SELECTED_STORE_ID, storeId).apply()
    }

    override fun getSelectedStoreId(): String? {
        return prefs.getString(Constants.KEY_SELECTED_STORE_ID, null)
    }

    fun observeSessionActive(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Constants.KEY_AUTH_TOKEN || key == null) trySend(isLoggedIn())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(isLoggedIn())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.CONFLATED)

    override fun isLoggedIn(): Boolean {
        return !getAuthToken().isNull_or_empty_or_blank()
    }

    private fun String?.isNull_or_empty_or_blank(): Boolean {
        return this.isNullOrBlank()
    }

    /**
     * Clears active authentication tokens on logout or session expiry.
     * CRITICAL REQUIREMENT: This NEVER deletes unsynchronized tasks or photo evidence files!
     */
    override fun clearSessionTokenOnly() = synchronized(sessionLock) {
        Log.w(TAG, "Clearing session token (local queued tasks and photos preserved)")
        prefs.edit()
            .remove(Constants.KEY_AUTH_TOKEN)
            .remove(Constants.KEY_SELECTED_STORE_ID)
            .apply()
    }

    /**
     * Prevents a delayed 401 response from an old session from clearing a newer session.
     * Only invalidates session if requestToken matches activeToken.
     */
    override fun handle401(requestToken: String?): Boolean = synchronized(sessionLock) {
        if (requestToken == null || requestToken != getAuthToken()) {
            Log.d(TAG, "Ignored stale HTTP 401 response for non-matching token")
            return@synchronized false
        }
        Log.w(TAG, "Handling HTTP 401: Active session token invalidated. Local queued data remains safe.")
        clearSessionTokenOnly()
        true
    }
}
