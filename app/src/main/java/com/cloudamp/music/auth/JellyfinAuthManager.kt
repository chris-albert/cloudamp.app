package com.cloudamp.music.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JellyfinAuthManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("jellyfin_auth", Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun saveServerUrl(url: String) {
        // Normalize: remove trailing slash
        val normalized = url.trimEnd('/')
        prefs.edit().putString("server_url", normalized).apply()
    }

    fun getServerUrl(): String? = prefs.getString("server_url", null)

    fun getAccessToken(): String? = prefs.getString("access_token", null)

    fun getUserId(): String? = prefs.getString("user_id", null)

    fun hasAccessToken(): Boolean = !getAccessToken().isNullOrEmpty()

    fun hasServerUrl(): Boolean = !getServerUrl().isNullOrEmpty()

    fun clearCredentials() {
        prefs.edit().apply {
            remove("server_url")
            remove("access_token")
            remove("user_id")
            remove("username")
            apply()
        }
    }

    fun getUsername(): String? = prefs.getString("username", null)

    private fun getDeviceId(): String {
        val stored = prefs.getString("device_id", null)
        if (stored != null) return stored

        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: java.util.UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    /**
     * Build the Jellyfin authorization header value.
     * Format: MediaBrowser Client="CloudAmp", Device="...", DeviceId="...", Version="1.0"[, Token="..."]
     */
    fun buildAuthHeader(includeToken: Boolean = true): String {
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val parts = mutableListOf(
            "Client=\"CloudAmp\"",
            "Device=\"$deviceName\"",
            "DeviceId=\"${getDeviceId()}\"",
            "Version=\"1.0\""
        )
        if (includeToken) {
            getAccessToken()?.let { token ->
                parts.add("Token=\"$token\"")
            }
        }
        return "MediaBrowser ${parts.joinToString(", ")}"
    }

    /**
     * Authenticate with a Jellyfin server using username and password.
     * Returns Result.success with the display name on success.
     */
    suspend fun authenticate(serverUrl: String, username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = serverUrl.trimEnd('/')
                saveServerUrl(normalizedUrl)

                val jsonBody = JSONObject().apply {
                    put("Username", username)
                    put("Pw", password)
                }.toString()

                val request = Request.Builder()
                    .url("$normalizedUrl/Users/AuthenticateByName")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", buildAuthHeader(includeToken = false))
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val accessToken = json.getString("AccessToken")
                    val user = json.getJSONObject("User")
                    val userId = user.getString("Id")
                    val displayName = user.optString("Name", username)

                    prefs.edit().apply {
                        putString("access_token", accessToken)
                        putString("user_id", userId)
                        putString("username", username)
                        apply()
                    }

                    Result.success(displayName)
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "Invalid username or password"
                        else -> "Authentication failed (${response.code})"
                    }
                    Result.failure(Exception(errorMsg))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Validate the current token by fetching user info.
     * Returns the display name on success.
     */
    suspend fun validateToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val serverUrl = getServerUrl() ?: return@withContext Result.failure(
                Exception("No server URL configured")
            )
            val userId = getUserId() ?: return@withContext Result.failure(
                Exception("No user ID stored")
            )

            val request = Request.Builder()
                .url("$serverUrl/Users/$userId")
                .addHeader("Authorization", buildAuthHeader())
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val displayName = json.optString("Name", "User")
                Result.success(displayName)
            } else {
                Result.failure(Exception("Token invalid (${response.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
