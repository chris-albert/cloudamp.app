package com.cloudamp.music.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.cloudamp.music.api.SpotifyApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class SpotifyAuthManager(private val context: Context) {

    companion object {
        private const val REDIRECT_URI = "cloudamp://callback"
        private const val AUTH_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

        // Required scopes for CloudAmp
        private val SCOPES = listOf(
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-top-read",
            "streaming",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-read-email",
            "user-read-private"
        )
    }

    private val prefs = context.getSharedPreferences("spotify_auth", Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient()

    fun saveClientCredentials(clientId: String, clientSecret: String) {
        prefs.edit().apply {
            putString("client_id", clientId)
            putString("client_secret", clientSecret)
            apply()
        }
    }

    fun getClientId(): String? = prefs.getString("client_id", null)
    fun getClientSecret(): String? = prefs.getString("client_secret", null)

    fun hasClientCredentials(): Boolean {
        return !getClientId().isNullOrEmpty() && !getClientSecret().isNullOrEmpty()
    }

    fun clearClientCredentials() {
        prefs.edit().apply {
            remove("client_id")
            remove("client_secret")
            remove("refresh_token")
            apply()
        }
    }

    // Generate PKCE code verifier and challenge
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun getAuthorizationUrl(): String {
        val clientId = getClientId() ?: throw IllegalStateException("Client ID not set")

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        // Save code verifier for later token exchange
        prefs.edit().putString("code_verifier", codeVerifier).apply()

        return Uri.parse(AUTH_URL).buildUpon().apply {
            appendQueryParameter("client_id", clientId)
            appendQueryParameter("response_type", "code")
            appendQueryParameter("redirect_uri", REDIRECT_URI)
            appendQueryParameter("scope", SCOPES.joinToString(" "))
            appendQueryParameter("code_challenge_method", "S256")
            appendQueryParameter("code_challenge", codeChallenge)
        }.build().toString()
    }

    suspend fun exchangeCodeForToken(code: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val clientId = getClientId() ?: return@withContext Result.failure(
                IllegalStateException("Client ID not set")
            )
            val codeVerifier = prefs.getString("code_verifier", null) ?: return@withContext Result.failure(
                IllegalStateException("Code verifier not found")
            )

            val requestBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", clientId)
                .add("code_verifier", codeVerifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token", null)

                // Save refresh token for later
                if (refreshToken != null) {
                    prefs.edit().putString("refresh_token", refreshToken).apply()
                }

                // Clear code verifier
                prefs.edit().remove("code_verifier").apply()

                Result.success(accessToken)
            } else {
                Result.failure(Exception("Token exchange failed: ${response.code} - $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val clientId = getClientId() ?: return@withContext Result.failure(
                IllegalStateException("Client ID not set")
            )
            val clientSecret = getClientSecret() ?: return@withContext Result.failure(
                IllegalStateException("Client Secret not set")
            )
            val refreshToken = prefs.getString("refresh_token", null) ?: return@withContext Result.failure(
                IllegalStateException("No refresh token available")
            )

            val credentials = "$clientId:$clientSecret"
            val encodedCredentials = Base64.encodeToString(
                credentials.toByteArray(),
                Base64.NO_WRAP
            )

            val requestBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .header("Authorization", "Basic $encodedCredentials")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val accessToken = json.getString("access_token")

                // Update refresh token if a new one is provided
                val newRefreshToken = json.optString("refresh_token", null)
                if (newRefreshToken != null) {
                    prefs.edit().putString("refresh_token", newRefreshToken).apply()
                }

                Result.success(accessToken)
            } else {
                Result.failure(Exception("Token refresh failed: ${response.code} - $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
