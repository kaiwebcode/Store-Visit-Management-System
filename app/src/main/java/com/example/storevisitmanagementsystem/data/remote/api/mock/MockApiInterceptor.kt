package com.example.storevisitmanagementsystem.data.remote.api.mock

import android.content.Context
import com.example.storevisitmanagementsystem.BuildConfig
import com.example.storevisitmanagementsystem.data.remote.model.LoginResponse
import com.google.gson.reflect.TypeToken
import com.example.storevisitmanagementsystem.data.remote.model.ApiResponse
import com.example.storevisitmanagementsystem.data.remote.model.ApiError
import com.example.storevisitmanagementsystem.data.remote.model.LoginRequest
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

class MockApiInterceptor(context: Context) : Interceptor {

    private val dbHelper = MockDatabaseHelper(context)
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun isEmulator(): Boolean {
        return android.os.Build.FINGERPRINT.startsWith("generic") ||
               android.os.Build.FINGERPRINT.startsWith("unknown") ||
               android.os.Build.MODEL.contains("google_sdk") ||
               android.os.Build.MODEL.contains("Emulator") ||
               android.os.Build.MODEL.contains("Android SDK built for x86") ||
               android.os.Build.HARDWARE.contains("goldfish") ||
               android.os.Build.HARDWARE.contains("ranchu") ||
               android.os.Build.PRODUCT.contains("sdk")
    }

    private fun getBackendHost(): String {
        return if (isEmulator()) "10.0.2.2" else BuildConfig.DAY17_BACKEND_HOST
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val path = request.url.encodedPath
        val force401 = request.header("X-Mock-Force-401") == "true"

        // Route to mock engine if URL matches mock host or local host
        if (url.contains("api.storevisit.mock") || url.contains("10.0.2.2") || url.contains("localhost")) {
            val responseBuilder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)

            if (force401 && !path.contains("/auth/login")) {
                return makeJsonResponse(responseBuilder, 401, gson.toJson(
                    ApiResponse<Unit>(
                        success = false,
                        error = ApiError("TOKEN_EXPIRED", "Session token has expired. Please authenticate again.")
                    )
                ))
            }

            // Keep store assignment available from the local fixtures after an online sign-in.
            if (BuildConfig.DEBUG && request.url.host == "api.storevisit.mock") {
                val backendUrl = request.url.newBuilder().scheme("http").host(getBackendHost()).port(3000).build()
                val backendRequest = request.newBuilder().url(backendUrl).build()
                when {
                    path.endsWith("/auth/login") -> {
                        val response = try {
                            chain.proceed(backendRequest)
                        } catch (_: IOException) {
                            // The standalone Day 15 mock still supports local sign-in.
                            return handleLogin(request, responseBuilder)
                        }
                        try {
                            if (response.isSuccessful) {
                                val loginType = object : TypeToken<ApiResponse<LoginResponse>>() {}.type
                                val login = gson.fromJson<ApiResponse<LoginResponse>>(
                                    response.peekBody(65_536).string(), loginType
                                )?.data
                                login?.let { dbHelper.saveSession(it.token, it.userId, it.expiresAt) }
                            }
                            return response
                        } catch (e: Exception) {
                            response.close()
                            throw IOException("Could not cache the backend session", e)
                        }
                    }
                    path.endsWith("/auth/logout") -> {
                        extractBearerToken(request)?.let(dbHelper::revokeToken)
                        return chain.proceed(backendRequest)
                    }
                    path.endsWith("/tasks/sync") || path.endsWith("/photos/upload") ->
                        return chain.proceed(backendRequest)
                }
            }

            return when {
                path.endsWith("/auth/login") && request.method == "POST" -> {
                    handleLogin(request, responseBuilder)
                }
                path.endsWith("/auth/logout") && request.method == "POST" -> {
                    handleLogout(request, responseBuilder)
                }
                path.endsWith("/stores/assigned") && request.method == "GET" -> {
                    handleGetAssignedStores(request, responseBuilder)
                }
                else -> {
                    chain.proceed(request)
                }
            }
        }

        return chain.proceed(request)
    }

    private fun handleLogin(request: Request, builder: Response.Builder): Response {
        val bodyString = request.body?.let { body ->
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        } ?: ""

        val loginReq = try {
            gson.fromJson(bodyString, LoginRequest::class.java)
        } catch (e: Exception) {
            null
        }

        if (loginReq == null) {
            return makeJsonResponse(builder, 400, gson.toJson(
                ApiResponse<Unit>(success = false, error = ApiError("BAD_REQUEST", "Malformed JSON body."))
            ))
        }

        val loginResp = dbHelper.authenticate(loginReq.username, loginReq.password)
        return if (loginResp != null) {
            makeJsonResponse(builder, 200, gson.toJson(
                ApiResponse(success = true, data = loginResp)
            ))
        } else {
            makeJsonResponse(builder, 401, gson.toJson(
                ApiResponse<Unit>(success = false, error = ApiError("INVALID_CREDENTIALS", "Invalid username or password."))
            ))
        }
    }

    private fun handleLogout(request: Request, builder: Response.Builder): Response {
        val token = extractBearerToken(request)
        if (token != null) {
            dbHelper.revokeToken(token)
        }
        return makeJsonResponse(builder, 200, gson.toJson(
            ApiResponse(success = true, data = mapOf("message" to "Logged out successfully."))
        ))
    }

    private fun handleGetAssignedStores(request: Request, builder: Response.Builder): Response {
        val token = extractBearerToken(request)
        val userId = if (token != null) dbHelper.validateToken(token) else null

        if (userId == null) {
            return makeJsonResponse(builder, 401, gson.toJson(
                ApiResponse<Unit>(success = false, error = ApiError("TOKEN_EXPIRED", "Unauthorized: Valid Bearer token required."))
            ))
        }

        val searchQuery = request.url.queryParameter("query")
        val stores = dbHelper.getAssignedStores(userId, searchQuery)

        return makeJsonResponse(builder, 200, gson.toJson(
            ApiResponse(success = true, data = stores)
        ))
    }

    private fun extractBearerToken(request: Request): String? {
        val authHeader = request.header("Authorization") ?: return null
        return if (authHeader.startsWith("Bearer ")) {
            authHeader.substring(7).trim()
        } else null
    }

    private fun makeJsonResponse(builder: Response.Builder, code: Int, json: String): Response {
        return builder
            .code(code)
            .message(if (code == 200 || code == 201) "OK" else "Error")
            .body(json.toResponseBody(jsonMediaType))
            .build()
    }
}
