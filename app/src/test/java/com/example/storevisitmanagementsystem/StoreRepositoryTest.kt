package com.example.storevisitmanagementsystem

import com.example.storevisitmanagementsystem.data.local.preferences.ITokenManager
import com.example.storevisitmanagementsystem.data.remote.api.ApiService
import com.example.storevisitmanagementsystem.data.remote.model.ApiResponse
import com.example.storevisitmanagementsystem.data.remote.model.LoginRequest
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.data.repository.StoreRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.AssignedStoreCache
import com.example.storevisitmanagementsystem.data.repository.TaskRepositoryImpl
import com.example.storevisitmanagementsystem.data.repository.AuthRepositoryImpl
import com.example.storevisitmanagementsystem.ui.store.StoreViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.example.storevisitmanagementsystem.util.Resource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun cachedAssignmentSurvivesRepositoryRecreationAndCannotCrossAccounts() = runBlocking {
        val cache = FakeStoreCache()
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex", "FIELD_AGENT")
        val online = StoreRepositoryImpl(fakeApiService, fakeTokenManager, cache)
        assertEquals(2, online.getAssignedStores().data?.size)
        online.saveSelectedStoreId("str_102")

        fakeApiService.offline = true
        val reopened = StoreRepositoryImpl(fakeApiService, fakeTokenManager, cache)
        assertEquals("str_102", reopened.getSelectedStoreId())
        assertEquals(listOf("str_102"), reopened.getAssignedStores("express").data?.map { it.id })

        fakeTokenManager.saveSession("token456", "usr_002", "agent2", "Sarah", "FIELD_AGENT")
        assertTrue(reopened.getAssignedStores() is Resource.Error)
        assertTrue(reopened.validateSelectedStoreLocally("usr_002", "str_102") is Resource.Error)
    }

    @Test
    fun offlineTaskCreationUsesCachedAuthorizationWithoutContactingStoreApi() = runBlocking {
        val cache = FakeStoreCache()
        val stores = StoreRepositoryImpl(fakeApiService, fakeTokenManager, cache)
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex", "FIELD_AGENT")
        assertTrue(stores.getAssignedStores() is Resource.Success)
        stores.saveSelectedStoreId("str_101")
        fakeApiService.offline = true
        val requestsBeforeSave = fakeApiService.storeRequests

        val authorization = stores.validateSelectedStoreLocally("usr_001", "str_101")
        val token = checkNotNull(authorization.data)
        val dao = TaskRepositoryTest.FakeTaskDao()
        val result = TaskRepositoryImpl(dao).createTask(
            "usr_001", "str_101", "Offline save", "", "GENERAL", "MEDIUM"
        ) { stores.isSelectedStoreSession("usr_001", "str_101", token) }

        assertTrue(result is Resource.Success)
        assertEquals(requestsBeforeSave, fakeApiService.storeRequests)
        assertEquals(dao.tasks.single().taskId, dao.syncOps.single().entityId)
        assertEquals("PENDING", dao.syncOps.single().status)
    }

    @Test
    fun authoritativeUnauthorizedResponseNeverReturnsCachedAssignment() = runBlocking {
        val cache = FakeStoreCache()
        val stores = StoreRepositoryImpl(fakeApiService, fakeTokenManager, cache)
        fakeTokenManager.saveSession("token123", "usr_001", "agent1", "Alex", "FIELD_AGENT")
        assertTrue(stores.getAssignedStores() is Resource.Success)
        fakeApiService.offline = true
        assertEquals(2, stores.getAssignedStores().data?.size)
        fakeApiService.offline = false
        fakeTokenManager.saveSession("expired_token", "usr_001", "agent1", "Alex", "FIELD_AGENT")

        assertTrue(stores.getAssignedStores() is Resource.Error)
        assertNull(fakeTokenManager.getAuthToken())
        assertTrue(cache.read("usr_001").isEmpty())
    }

    @Test
    fun workerInvalidationRequestsLoginWhileStoreRequestIsStillPending() = runTest {
        fakeTokenManager.saveSession("expired_token", "usr_001", "agent1", "Alex", "FIELD_AGENT")
        val responseReady = CompletableDeferred<Unit>()
        fakeApiService.beforeStoreResponse = { responseReady.await() }
        withStoreViewModel { viewModel ->
            runCurrent()
            assertEquals(1, fakeApiService.storeRequests)
            assertFalse(viewModel.requiresLogin.first())

            // The worker uses this same matching-token invalidation entry point.
            assertTrue(fakeTokenManager.handle401("expired_token"))
            assertTrue(viewModel.requiresLogin.first())
            assertTrue(viewModel.shouldNavigateToLogin())

            responseReady.complete(Unit)
            runCurrent()
            assertEquals("Session expired. Please log in again.", viewModel.storesState.value.message)
        }
    }

    @Test
    fun matchingStore401RequestsLogin() = runTest {
        fakeTokenManager.saveSession("expired_token", "usr_001", "agent1", "Alex", "FIELD_AGENT")
        withStoreViewModel { viewModel ->
            runCurrent()
            assertTrue(viewModel.requiresLogin.first())
            assertTrue(viewModel.shouldNavigateToLogin())
            assertNull(fakeTokenManager.getAuthToken())
        }
    }

    @Test
    fun delayedStore401DoesNotRequestLoginAfterSuccessfulReplacement() = runTest {
        fakeTokenManager.saveSession("expired_token", "usr_001", "agent1", "Alex", "FIELD_AGENT")
        val responseReady = CompletableDeferred<Unit>()
        fakeApiService.beforeStoreResponse = { responseReady.await() }
        withStoreViewModel { viewModel ->
            runCurrent()
            assertEquals(1, fakeApiService.storeRequests)
            fakeApiService.loginResponse = LoginResponse("replacement", "usr_002", "agent2", "Sarah", "FIELD_AGENT", "")
            val auth = AuthRepositoryImpl(fakeApiService, fakeTokenManager)
            assertTrue(auth.login("agent2", "fixture input") is Resource.Success)
            responseReady.complete(Unit)
            runCurrent()
            assertFalse(viewModel.requiresLogin.first())
            assertFalse(viewModel.shouldNavigateToLogin())
            assertEquals("replacement", fakeTokenManager.getAuthTokenForUser("usr_002"))
            assertEquals("The account has changed.", viewModel.storesState.value.message)
        }
    }

    private suspend fun TestScope.withStoreViewModel(block: suspend (StoreViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val auth = AuthRepositoryImpl(fakeApiService, fakeTokenManager)
        val viewModel = StoreViewModel(repository, auth, fakeTokenManager.sessionActive)
        try {
            block(viewModel)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class FakeStoreCache : AssignedStoreCache {
        private val stores = mutableMapOf<String, List<StoreDto>>()
        override fun read(userId: String): List<StoreDto> = stores[userId].orEmpty()
        override fun write(userId: String, stores: List<StoreDto>) { this.stores[userId] = stores }
        override fun clear(userId: String) { stores.remove(userId) }
    }

    // --- Fake Test Doubles ---
    private class FakeTokenManager : ITokenManager {
        val sessionActive = MutableStateFlow(false)
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
            sessionActive.value = true
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
            sessionActive.value = false
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
        var offline = false
        var storeRequests = 0
        var beforeStoreResponse: suspend () -> Unit = {}
        var loginResponse: LoginResponse? = null
        private val storesList = listOf(
            StoreDto("str_101", "STR-NY-001", "Downtown Megastore", "123 Main St, New York, NY", "SUPERMARKET", "usr_001"),
            StoreDto("str_102", "STR-NY-002", "Metro Express Mart", "456 Broadway Ave, New York, NY", "CONVENIENCE", "usr_001")
        )

        override suspend fun login(request: LoginRequest): Response<ApiResponse<LoginResponse>> {
            return Response.success(ApiResponse(success = true, data = loginResponse))
        }

        override suspend fun logout(token: String): Response<ApiResponse<Map<String, String>>> {
            return Response.success(ApiResponse(success = true, data = emptyMap()))
        }

        override suspend fun getAssignedStores(token: String, query: String?): Response<ApiResponse<List<StoreDto>>> {
            storeRequests++
            beforeStoreResponse()
            if (offline) throw IOException("Offline")
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

        override suspend fun syncTasks(token: String, request: com.example.storevisitmanagementsystem.data.remote.model.SyncBatchRequest): Response<ApiResponse<com.example.storevisitmanagementsystem.data.remote.model.SyncBatchResponse>> {
            return Response.success(ApiResponse(success = true, data = null))
        }

        override suspend fun uploadPhoto(token: String, operationIdHeader: String, operationId: okhttp3.RequestBody, photoId: okhttp3.RequestBody, taskId: okhttp3.RequestBody, file: okhttp3.MultipartBody.Part): Response<ApiResponse<com.example.storevisitmanagementsystem.data.remote.model.PhotoUploadResponse>> {
            return Response.success(ApiResponse(success = true, data = null))
        }
    }
}
