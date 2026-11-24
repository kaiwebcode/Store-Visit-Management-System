package com.example.storevisitmanagementsystem.data.local.preferences

interface ITokenManager {
    fun saveSession(token: String, userId: String, username: String, name: String, role: String)
    fun getAuthToken(): String?
    fun getUserId(): String?
    fun getUsername(): String?
    fun getUserName(): String?
    fun getUserRole(): String?
    fun saveSelectedStoreId(storeId: String?)
    fun getSelectedStoreId(): String?
    fun isLoggedIn(): Boolean
    fun clearSessionTokenOnly()
    fun handle401(requestToken: String?): Boolean
}
