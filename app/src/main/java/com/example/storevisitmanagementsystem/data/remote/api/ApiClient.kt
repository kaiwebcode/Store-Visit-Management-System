package com.example.storevisitmanagementsystem.data.remote.api

import android.content.Context
import com.example.storevisitmanagementsystem.data.remote.api.mock.MockApiInterceptor
import com.example.storevisitmanagementsystem.util.Constants
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    @Volatile
    private var instance: ApiService? = null

    fun getApiService(context: Context): ApiService {
        return instance ?: synchronized(this) {
            instance ?: createApiService(context).also { instance = it }
        }
    }

    private fun createApiService(context: Context): ApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(MockApiInterceptor(context.applicationContext))
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(Constants.MOCK_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    /**
     * Resets instance for testing purposes.
     */
    fun resetForTesting() {
        instance = null
    }
}
