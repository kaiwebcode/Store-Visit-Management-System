package com.example.storevisitmanagementsystem.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.storevisitmanagementsystem.util.Constants

class TokenManagerImpl(context: Context) : ITokenManager {

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
    ) {
        prefs.edit()
            .putString(Constants.KEY_AUTH_TOKEN, token)
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
    override fun clearSessionTokenOnly() {
        prefs.edit()
            .remove(Constants.KEY_AUTH_TOKEN)
            .remove(Constants.KEY_SELECTED_STORE_ID)
            .apply()
    }

    /**
     * Prevents a delayed 401 response from an old session from clearing a newer session.
     * Only invalidates session if requestToken matches activeToken.
     */
    override fun handle401(requestToken: String?): Boolean {
        val currentToken = getAuthToken()
        if (requestToken != null && requestToken == currentToken) {
            clearSessionTokenOnly()
            return true
        }
        return false
    }
}
