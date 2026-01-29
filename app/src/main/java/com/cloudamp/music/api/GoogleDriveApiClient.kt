package com.cloudamp.music.api

import android.content.Context
import android.util.Log
import com.cloudamp.music.auth.GoogleDriveAuthManager
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

class GoogleDriveApiClient(private val context: Context) {

    companion object {
        private const val TAG = "GoogleDriveApiClient"

        @Volatile
        private var INSTANCE: GoogleDriveApiClient? = null

        fun getInstance(context: Context): GoogleDriveApiClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GoogleDriveApiClient(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val authManager = GoogleDriveAuthManager(context)

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val authInterceptor = Interceptor { chain ->
        val token = authManager.getAccessToken()
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.header("X-Token-Refreshed") != null) {
                Log.w(TAG, "Token refresh already attempted, not retrying")
                return null
            }

            Log.d(TAG, "Received 401, attempting to refresh Google Drive token")

            return runBlocking {
                val result = authManager.refreshAccessToken()
                result.fold(
                    onSuccess = { newToken ->
                        Log.d(TAG, "Google Drive token refreshed successfully")
                        response.request.newBuilder()
                            .removeHeader("Authorization")
                            .addHeader("Authorization", "Bearer $newToken")
                            .addHeader("X-Token-Refreshed", "true")
                            .build()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Google Drive token refresh failed: ${error.message}")
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
        .baseUrl("https://www.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: GoogleDriveApiService = retrofit.create(GoogleDriveApiService::class.java)

    /**
     * Returns the OkHttpClient with auth headers for use by ExoPlayer's OkHttpDataSource.
     */
    fun getAuthenticatedHttpClient(): OkHttpClient = okHttpClient

    fun hasAccessToken(): Boolean = authManager.hasAccessToken()
}
