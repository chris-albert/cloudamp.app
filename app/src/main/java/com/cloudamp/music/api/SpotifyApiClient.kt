package com.cloudamp.music.api

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class SpotifyApiClient(private val context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("cloudamp_prefs", Context.MODE_PRIVATE)
    
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
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
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
    
    companion object {
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
}
