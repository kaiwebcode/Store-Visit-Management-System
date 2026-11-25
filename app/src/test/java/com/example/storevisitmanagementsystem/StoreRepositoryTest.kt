package com.example.storevisitmanagementsystem

import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.ApiResponse
import com.example.storevisitmanagementsystem.data.remote.model.LoginRequest
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.data.repository.StoreRepositoryImpl
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class StoreRepositoryTest {

    private lateinit var fakeApiService: FakeApiService
    private lateinit var fakeTokenManager: FakeTokenManager
    private lateinit var repository: StoreRepositoryImpl

    @Before
    fun setUp() {
        fakeApiService = FakeApiService()
        fakeTokenManager = FakeTokenManager()
        repository = StoreRepositoryImpl(fakeApiService, fakeTokenManager)
    }

    @Test
    fun `getAssignedStores when logged in returns list of assigned stores`() = runBlocking {
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")

        val result = repository.getAssignedStores()

        assertTrue(result is Resource.Success)
        val stores = (result as Resource.Success).data
        assertNotNull(stores)
        assertEquals(2, stores?.size)
        assertEquals("STR-NY-001", stores?.get(0)?.code)
    }

    @Test
    fun `getAssignedStores with search query filters store list`() = runBlocking {
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")

        val result = repository.getAssignedStores("Express")

        assertTrue(result is Resource.Success)
        val stores = (result as Resource.Success).data
        assertNotNull(stores)
        assertEquals(1, stores?.size)
        assertEquals("STR-NY-002", stores?.get(0)?.code)
    }

    @Test
    fun `getAssignedStores when session expired returns 401 error and handles session invalidation`() = runBlocking {
        fakeTokenManager.saveSession("expired_token", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")

        val result = repository.getAssignedStores()

        assertTrue(result is Resource.Error)
        assertEquals("Session expired. Please log in again.", (result as Resource.Error).message)
        assertNull(fakeTokenManager.getAuthToken())
    }

    @Test
    fun `validateSelectedStore requires both the current selection and the assigned account`() = runBlocking {
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")
        fakeTokenManager.saveSelectedStoreId("str_101")
        assertTrue(repository.validateSelectedStore("usr_001", "str_101") is Resource.Success)
        assertTrue(repository.validateSelectedStore("usr_001", "str_102") is Resource.Error)

        fakeTokenManager.saveSession("token456", "usr_002", "agent2", "Sarah Connor", "FIELD_AGENT")
        assertTrue(repository.validateSelectedStore("usr_002", "str_101") is Resource.Error)
    }

    @Test
    fun `validateSelectedStore rejects an expired session`() = runBlocking {
        fakeTokenManager.saveSession("expired_token", "usr_001", "agent1", "Alex Mercer", "FIELD_AGENT")
        fakeTokenManager.saveSelectedStoreId("str_101")

        assertTrue(repository.validateSelectedStore("usr_001", "str_101") is Resource.Error)
        assertNull(fakeTokenManager.getAuthToken())
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
        private val storesList = listOf(
            StoreDto("str_101", "STR-NY-001", "Downtown Megastore", "123 Main St, New York, NY", "SUPERMARKET", "usr_001"),
            StoreDto("str_102", "STR-NY-002", "Metro Express Mart", "456 Broadway Ave, New York, NY", "CONVENIENCE", "usr_001")
        )

        override suspend fun login(request: LoginRequest): Response<ApiResponse<LoginResponse>> {
            return Response.success(ApiResponse(success = true, data = null))
        }

        override suspend fun logout(token: String): Response<ApiResponse<Map<String, String>>> {
            return Response.success(ApiResponse(success = true, data = emptyMap()))
        }

        override suspend fun getAssignedStores(token: String, query: String?): Response<ApiResponse<List<StoreDto>>> {
            if (token.contains("expired_token")) {
                return Response.error(401, okhttp3.ResponseBody.create(null, "Unauthorized"))
            }

            val filtered = if (query.isNullOrBlank()) {
                storesList
            } else {
                storesList.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.code.contains(query, ignoreCase = true) ||
                    it.address.contains(query, ignoreCase = true)
                }
            }
            return Response.success(ApiResponse(success = true, data = filtered))
        }
    }
}
