package com.cloudamp.music.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

class GoogleDriveAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "GDriveAuth"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_PROXY_URL = "https://cloudamp.io/api/token"
        private const val REDIRECT_URI = "https://cloudamp.io/api/android-callback"

        // Auth version: bump to force re-auth when credentials change.
        // v1 = proxy-based auth (no client secret on device).
        private const val KEY_AUTH_VERSION = "auth_version"
        private const val CURRENT_AUTH_VERSION = 1

        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive"
        )

        private const val ENCRYPTED_PREFS_FILE = "gdrive_auth_encrypted"
        private const val LEGACY_PREFS_FILE = "gdrive_auth"
    }

    private val prefs: SharedPreferences = createEncryptedPrefs()

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        migrateLegacyPrefs(encryptedPrefs)

        return encryptedPrefs
    }

    /**
     * One-time migration from plaintext SharedPreferences to EncryptedSharedPreferences.
     * Copies tokens/state, then deletes the legacy file.
     */
    private fun migrateLegacyPrefs(encryptedPrefs: SharedPreferences) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
        if (legacyPrefs.all.isEmpty()) return

        Log.d(TAG, "Migrating legacy plaintext prefs to encrypted storage")
        val editor = encryptedPrefs.edit()
        for ((key, value) in legacyPrefs.all) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.apply()
        legacyPrefs.edit().clear().apply()
    }

    private val httpClient = OkHttpClient()
    private val clientId: String = com.cloudamp.music.BuildConfig.GOOGLE_CLIENT_ID

    init {
        migrateAuthVersionIfNeeded()
    }

    /**
     * If the stored auth version is older than current, clear all tokens
     * so the user re-authenticates with the new credentials flow.
     */
    private fun migrateAuthVersionIfNeeded() {
        val stored = prefs.getInt(KEY_AUTH_VERSION, 0)
        if (stored >= CURRENT_AUTH_VERSION) return
        Log.d(TAG, "Auth version migration: v$stored -> v$CURRENT_AUTH_VERSION, clearing tokens")
        prefs.edit().apply {
            remove("client_id")
            remove("client_secret")
            remove("refresh_token")
            remove("access_token")
            remove("code_verifier")
            remove("loopback_port")
            putInt(KEY_AUTH_VERSION, CURRENT_AUTH_VERSION)
            apply()
        }
    }

    fun clearCredentials() {
        prefs.edit().apply {
            remove("refresh_token")
            remove("access_token")
            remove("code_verifier")
            remove("oauth_state")
            apply()
        }
    }

    fun hasAccessToken(): Boolean {
        return !prefs.getString("access_token", null).isNullOrEmpty()
    }

    fun getAccessToken(): String? = prefs.getString("access_token", null)

    /**
     * Returns true if the stored access token has expired (or will expire within 60s).
     * If no expiration is stored, assumes expired to trigger a refresh.
     */
    fun isAccessTokenExpired(): Boolean {
        val expiresAt = prefs.getLong("expires_at", 0L)
        if (expiresAt == 0L) return true
        return System.currentTimeMillis() >= expiresAt - 60_000
    }

    private fun saveAccessToken(token: String, expiresInSeconds: Long? = null) {
        prefs.edit().apply {
            putString("access_token", token)
            if (expiresInSeconds != null) {
                putLong("expires_at", System.currentTimeMillis() + expiresInSeconds * 1000)
            }
            apply()
        }
    }

    fun clearAccessToken() {
        prefs.edit().remove("access_token").remove("expires_at").apply()
    }

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

    private fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Build the Google OAuth authorization URL.
     * Uses a custom URI scheme (com.cloudamp.music://oauth2callback) as the
     * redirect URI so the browser redirects back to the app via deep link.
     */
    fun getAuthorizationUrl(): String {
        require(clientId.isNotEmpty()) { "GOOGLE_CLIENT_ID not configured at build time" }

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        prefs.edit()
            .putString("code_verifier", codeVerifier)
            .putString("oauth_state", state)
            .apply()

        return Uri.parse(AUTH_URL).buildUpon().apply {
            appendQueryParameter("client_id", clientId)
            appendQueryParameter("response_type", "code")
            appendQueryParameter("redirect_uri", REDIRECT_URI)
            appendQueryParameter("scope", SCOPES.joinToString(" "))
            appendQueryParameter("code_challenge_method", "S256")
            appendQueryParameter("code_challenge", codeChallenge)
            appendQueryParameter("access_type", "offline")
            appendQueryParameter("prompt", "consent")
            appendQueryParameter("state", state)
        }.build().toString()
    }

    /**
     * Validate the OAuth state parameter returned in the callback.
     * Returns true if the state matches what was sent; false otherwise.
     */
    fun validateState(returnedState: String?): Boolean {
        val expectedState = prefs.getString("oauth_state", null)
        if (expectedState == null || expectedState != returnedState) return false
        prefs.edit().remove("oauth_state").apply()
        return true
    }

    /**
     * Exchange authorization code for tokens via the CloudAmp token proxy.
     * The proxy holds the client secret and forwards to Google.
     */
    suspend fun exchangeCodeForToken(code: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val codeVerifier = prefs.getString("code_verifier", null) ?: return@withContext Result.failure(
                IllegalStateException("Code verifier not found")
            )

            val jsonBody = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("code", code)
                put("code_verifier", codeVerifier)
                put("redirect_uri", REDIRECT_URI)
            }.toString()

            val request = Request.Builder()
                .url(TOKEN_PROXY_URL)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token", null)
                val expiresIn = if (json.has("expires_in")) json.getLong("expires_in") else null

                saveAccessToken(accessToken, expiresIn)
                if (refreshToken != null) {
                    prefs.edit().putString("refresh_token", refreshToken).apply()
                }

                // Clear code verifier
                prefs.edit().remove("code_verifier").apply()

                Result.success(accessToken)
            } else {
                if (com.cloudamp.music.BuildConfig.DEBUG) {
                    Log.e(TAG, "Token exchange failed: ${response.code} $responseBody")
                } else {
                    Log.e(TAG, "Token exchange failed: ${response.code}")
                }
                Result.failure(Exception("Token exchange failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Refresh the access token via the CloudAmp token proxy.
     */
    suspend fun refreshAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val refreshToken = prefs.getString("refresh_token", null) ?: return@withContext Result.failure(
                IllegalStateException("No refresh token available")
            )

            val jsonBody = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
            }.toString()

            val request = Request.Builder()
                .url(TOKEN_PROXY_URL)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                val accessToken = json.getString("access_token")
                val expiresIn = if (json.has("expires_in")) json.getLong("expires_in") else null

                saveAccessToken(accessToken, expiresIn)

                val newRefreshToken = json.optString("refresh_token", null)
                if (newRefreshToken != null) {
                    prefs.edit().putString("refresh_token", newRefreshToken).apply()
                }

                Result.success(accessToken)
            } else {
                if (com.cloudamp.music.BuildConfig.DEBUG) {
                    Log.e(TAG, "Token refresh failed: ${response.code} $responseBody")
                } else {
                    Log.e(TAG, "Token refresh failed: ${response.code}")
                }
                Result.failure(Exception("Token refresh failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error: ${e.message}", e)
            Result.failure(e)
        }
    }
}
