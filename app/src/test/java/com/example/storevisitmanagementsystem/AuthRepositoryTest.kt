package com.example.storevisitmanagementsystem

import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.ApiResponse
import com.example.storevisitmanagementsystem.data.remote.model.LoginRequest
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.data.repository.AuthRepositoryImpl
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class AuthRepositoryTest {

    private lateinit var fakeApiService: FakeApiService
    private lateinit var fakeTokenManager: FakeTokenManager
    private lateinit var repository: AuthRepositoryImpl

    @Before
    fun setUp() {
        fakeApiService = FakeApiService()
        fakeTokenManager = FakeTokenManager()
        repository = AuthRepositoryImpl(fakeApiService, fakeTokenManager)
    }

    @Test
    fun `login with valid credentials saves session and returns Success`() = runBlocking {
        val result = repository.login("agent1", "password123")

        assertTrue(result is Resource.Success)
        val data = (result as Resource.Success).data
        assertNotNull(data)
        assertEquals("usr_001", data?.userId)
        assertEquals("mock_token_usr_001", fakeTokenManager.getAuthToken())
        assertEquals("usr_001", fakeTokenManager.getUserId())
    }

    @Test
    fun `login with invalid credentials returns Error and does not save session`() = runBlocking {
        val result = repository.login("agent1", "wrong_password")

        assertTrue(result is Resource.Error)
        assertEquals("Invalid username or password.", (result as Resource.Error).message)
        assertNull(fakeTokenManager.getAuthToken())
    }

    @Test
    fun `logout clears session token without clearing fake local database data`() = runBlocking {
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")
        assertTrue(repository.isLoggedIn())

        val result = repository.logout()

        assertTrue(result is Resource.Success)
        assertNull(fakeTokenManager.getAuthToken())
        // User ID remains available for offline data mapping
        assertEquals("usr_001", fakeTokenManager.getUserId())
    }

    // --- Fake Test Doubles ---
    private class FakeTokenManager : ITokenManager {
        private var token: String? = null
        private var userId: String? = null
        private var username: String? = null
        private var name: String? = null
        private var role: String? = null
        private var selectedStoreId: String? = null

        override fun saveSession(token: String, userId: String, username: String, name: String, role: String) {
            this.token = token
            this.userId = userId
            this.username = username
            this.name = name
            this.role = role
        }

        override fun getAuthToken(): String? = token
        override fun getUserId(): String? = userId
        override fun getUsername(): String? = username
        override fun getUserName(): String? = name
        override fun getUserRole(): String? = role
        override fun saveSelectedStoreId(storeId: String?) { this.selectedStoreId = storeId }
        override fun getSelectedStoreId(): String? = selectedStoreId
        override fun isLoggedIn(): Boolean = !token.isNullOrBlank()

        override fun clearSessionTokenOnly() {
            this.token = null
            this.selectedStoreId = null
            // userId and local offline data are preserved!
        }

        override fun handle401(requestToken: String?): Boolean {
            if (requestToken != null && requestToken == token) {
                clearSessionTokenOnly()
                return true
            }
            return false
        }
    }

    private class FakeApiService : ApiService {
        override suspend fun login(request: LoginRequest): Response<ApiResponse<LoginResponse>> {
            return if (request.username == "agent1" && request.password == "password123") {
                val loginResp = LoginResponse(
                    token = "mock_token_usr_001",
                    userId = "usr_001",
                    username = "agent1",
                    name = "Alex Mercer",
                    role = "FIELD_AGENT",
                    expiresAt = "2026-12-31T23:59:59Z"
                )
                Response.success(ApiResponse(success = true, data = loginResp))
            } else {
                Response.error(401, okhttp3.ResponseBody.create(null, "Unauthorized"))
            }
        }

        override suspend fun logout(token: String): Response<ApiResponse<Map<String, String>>> {
            return Response.success(ApiResponse(success = true, data = mapOf("message" to "Logged out")))
        }

        override suspend fun getAssignedStores(token: String, query: String?): Response<ApiResponse<List<com.example.storevisitmanagementsystem.data.remote.model.StoreDto>>> {
            return Response.success(ApiResponse(success = true, data = emptyList()))
        }
    }
}
