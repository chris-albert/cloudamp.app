package com.cloudamp.music.api

import android.content.Context
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

        /**
         * Reset the singleton so the next getInstance() rebuilds the client.
         * Call this when the server URL changes.
         */
        fun resetInstance() {
            INSTANCE = null
        }
    }

    val authManager = JellyfinAuthManager(context)

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", authManager.buildAuthHeader())
            .build()
        Log.d(TAG, "Request: ${request.method} ${request.url}")
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Creates a Retrofit API instance using the stored server URL.
     * Returns null if no server URL is configured.
     */
    val api: JellyfinApiService? by lazy {
        val serverUrl = authManager.getServerUrl() ?: return@lazy null
        Retrofit.Builder()
            .baseUrl("$serverUrl/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JellyfinApiService::class.java)
    }

    /**
     * Build an image URL for a Jellyfin item.
     */
    fun getImageUrl(itemId: String, imageType: String = "Primary", maxWidth: Int = 300): String? {
        val serverUrl = authManager.getServerUrl() ?: return null
        return "$serverUrl/Items/$itemId/Images/$imageType?maxWidth=$maxWidth&maxHeight=$maxWidth"
    }

    /**
     * Build a streaming URL for a Jellyfin audio item.
     */
    fun getStreamUrl(itemId: String): String? {
        val serverUrl = authManager.getServerUrl() ?: return null
        val token = authManager.getAccessToken() ?: return null
        return "$serverUrl/Audio/$itemId/universal" +
                "?Container=opus,webm|opus,mp3,aac,m4a,flac" +
                "&MaxStreamingBitrate=140000000" +
                "&AudioCodec=aac" +
                "&TranscodingContainer=ts" +
                "&TranscodingProtocol=hls" +
                "&api_key=$token"
    }

    /**
     * Returns an OkHttpClient for streaming media (ExoPlayer).
     * Has auth headers but NO body logging to avoid buffering audio.
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

    fun hasAccessToken(): Boolean = authManager.hasAccessToken()

    fun getUserId(): String? = authManager.getUserId()
}
