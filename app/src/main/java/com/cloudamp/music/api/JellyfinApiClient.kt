package com.cloudamp.music.api

import android.content.Context
import android.os.Build
import android.util.Log
import com.cloudamp.music.auth.JellyfinAuthManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class JellyfinApiClient private constructor(private val context: Context) {

    companion object {
        private const val TAG = "JellyfinApiClient"

        @Volatile
        private var INSTANCE: JellyfinApiClient? = null

        fun getInstance(context: Context): JellyfinApiClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JellyfinApiClient(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val authManager = JellyfinAuthManager(context)

    // Track the base URL so we can rebuild Retrofit when it changes
    private var currentBaseUrl: String? = null
    private var retrofit: Retrofit? = null
    private var apiService: JellyfinApiService? = null

    private val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    private val deviceId: String
        get() = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "cloudamp-unknown"

    /**
     * Auth interceptor: adds the Jellyfin MediaBrowser authorization header.
     * The header is required even for auth requests, but Token is optional for login.
     */
    private val authInterceptor = Interceptor { chain ->
        val accessToken = authManager.getAccessToken()

        // Always add MediaBrowser header with client info
        // Token is only included if we have one (not for initial login)
        val authValueBuilder = StringBuilder()
        authValueBuilder.append("MediaBrowser Client=\"CloudAmp\", ")
        authValueBuilder.append("Device=\"$deviceName\", ")
        authValueBuilder.append("DeviceId=\"$deviceId\", ")
        authValueBuilder.append("Version=\"1.0\"")

        if (accessToken != null) {
            authValueBuilder.append(", Token=\"$accessToken\"")
        }

        val request = chain.request().newBuilder()
            .addHeader("Authorization", authValueBuilder.toString())
            .addHeader("Content-Type", "application/json")
            .build()

        chain.proceed(request)
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Get the Retrofit API service. Rebuilds if the server URL has changed.
     */
    val api: JellyfinApiService
        get() {
            val serverUrl = authManager.getServerUrl()?.trimEnd('/')
                ?: throw IllegalStateException("Jellyfin server URL not configured")

            // Rebuild Retrofit if base URL changed
            if (serverUrl != currentBaseUrl || apiService == null) {
                currentBaseUrl = serverUrl
                retrofit = Retrofit.Builder()
                    .baseUrl("$serverUrl/")
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                apiService = retrofit!!.create(JellyfinApiService::class.java)
            }
            return apiService!!
        }

    /**
     * Returns an OkHttpClient for streaming media (ExoPlayer).
     * Has auth but NO body logging (which would buffer entire audio files).
     */
    fun getStreamingHttpClient(): OkHttpClient {
        val headersOnlyLogging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(headersOnlyLogging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(): Boolean = authManager.isConfigured()

    fun getServerUrl(): String? = authManager.getServerUrl()

    fun getApiKey(): String? = authManager.getApiKey()
}
