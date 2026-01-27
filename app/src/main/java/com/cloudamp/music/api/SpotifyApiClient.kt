package com.cloudamp.music.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cloudamp.music.auth.SpotifyAuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class SpotifyApiClient(private val context: Context) {

    companion object {
        private const val TAG = "SpotifyApiClient"

        @Volatile
        private var INSTANCE: SpotifyApiClient? = null

        fun getInstance(context: Context): SpotifyApiClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpotifyApiClient(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cloudamp_prefs", Context.MODE_PRIVATE)

    private val authManager = SpotifyAuthManager(context)

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val authInterceptor = Interceptor { chain ->
        val token = getAccessToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    /**
     * Authenticator that automatically refreshes the access token when a 401 is received.
     * This handles token expiration transparently without requiring user re-authentication.
     */
    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // Don't retry if we've already tried to refresh
            if (response.request.header("X-Token-Refreshed") != null) {
                Log.w(TAG, "Token refresh already attempted, not retrying")
                return null
            }

            Log.d(TAG, "Received 401, attempting to refresh token")

            return runBlocking {
                val result = authManager.refreshAccessToken()
                result.fold(
                    onSuccess = { newToken ->
                        Log.d(TAG, "Token refreshed successfully")
                        // Save the new token
                        saveAccessToken(newToken)

                        // Retry the request with the new token
                        response.request.newBuilder()
                            .removeHeader("Authorization")
                            .addHeader("Authorization", "Bearer $newToken")
                            .addHeader("X-Token-Refreshed", "true")
                            .build()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Token refresh failed: ${error.message}")
                        null
                    }
                )
            }
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .authenticator(tokenAuthenticator)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.spotify.com/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val api: SpotifyApiService = retrofit.create(SpotifyApiService::class.java)
    
    // Token Management
    fun saveAccessToken(token: String) {
        prefs.edit().putString("spotify_access_token", token).apply()
    }
    
    fun getAccessToken(): String? {
        return prefs.getString("spotify_access_token", null)
    }
    
    fun clearAccessToken() {
        prefs.edit().remove("spotify_access_token").apply()
    }
    
    fun hasAccessToken(): Boolean {
        return getAccessToken() != null
    }
}
