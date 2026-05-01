package com.cloudamp.music.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

class GoogleDriveAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "GDriveAuth"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_PROXY_URL = "https://cloudamp.io/api/token"

        // Auth version: bump to force re-auth when credentials change.
        // v1 = proxy-based auth (no client secret on device).
        private const val KEY_AUTH_VERSION = "auth_version"
        private const val CURRENT_AUTH_VERSION = 1

        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive"
        )
    }

    private val prefs = context.getSharedPreferences("gdrive_auth", Context.MODE_PRIVATE)
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
            remove("loopback_port")
            apply()
        }
    }

    fun hasAccessToken(): Boolean {
        return !prefs.getString("access_token", null).isNullOrEmpty()
    }

    fun getAccessToken(): String? = prefs.getString("access_token", null)

    fun saveAccessToken(token: String) {
        prefs.edit().putString("access_token", token).apply()
    }

    fun clearAccessToken() {
        prefs.edit().remove("access_token").apply()
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

    private fun getRedirectUri(port: Int): String {
        return "http://$LOOPBACK_HOST:$port"
    }

    fun getAuthorizationUrl(): String {
        require(clientId.isNotEmpty()) { "GOOGLE_CLIENT_ID not configured at build time" }

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        // Find an available port and save it
        val port = findAvailablePort()
        prefs.edit().apply {
            putString("code_verifier", codeVerifier)
            putInt("loopback_port", port)
            apply()
        }

        val redirectUri = getRedirectUri(port)

        return Uri.parse(AUTH_URL).buildUpon().apply {
            appendQueryParameter("client_id", clientId)
            appendQueryParameter("response_type", "code")
            appendQueryParameter("redirect_uri", redirectUri)
            appendQueryParameter("scope", SCOPES.joinToString(" "))
            appendQueryParameter("code_challenge_method", "S256")
            appendQueryParameter("code_challenge", codeChallenge)
            appendQueryParameter("access_type", "offline")
            appendQueryParameter("prompt", "consent")
        }.build().toString()
    }

    private fun findAvailablePort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    /**
     * Start a loopback HTTP server to listen for the OAuth callback.
     * This blocks until the callback is received or times out.
     * Must be called from a background thread.
     */
    fun waitForAuthorizationCode(): Result<String> {
        val port = prefs.getInt("loopback_port", 0)
        if (port == 0) return Result.failure(IllegalStateException("No loopback port configured"))

        return try {
            val serverSocket = ServerSocket(port)
            serverSocket.soTimeout = 120_000 // 2 minute timeout

            val socket = serverSocket.accept()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: ""

            // Parse the GET request: "GET /?code=xxx&scope=yyy HTTP/1.1"
            val path = requestLine.split(" ").getOrNull(1) ?: ""
            val uri = Uri.parse("http://localhost$path")
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")

            // Send a response to the browser
            val responseHtml = if (code != null) {
                "<html><body style=\"font-family:monospace;background:#1a1a2e;color:#00ff41;display:flex;justify-content:center;align-items:center;height:100vh;margin:0\">" +
                "<div style=\"text-align:center\"><h1>CloudAmp</h1><p>Google Drive connected successfully!</p><p>You can close this window.</p></div></body></html>"
            } else {
                "<html><body style=\"font-family:monospace;background:#1a1a2e;color:#ff4444;display:flex;justify-content:center;align-items:center;height:100vh;margin:0\">" +
                "<div style=\"text-align:center\"><h1>CloudAmp</h1><p>Authorization failed: ${error ?: "unknown error"}</p><p>You can close this window.</p></div></body></html>"
            }

            val httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${responseHtml.toByteArray().size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    responseHtml

            socket.getOutputStream().write(httpResponse.toByteArray())
            socket.getOutputStream().flush()
            socket.close()
            serverSocket.close()

            if (code != null) {
                Result.success(code)
            } else {
                Result.failure(Exception("Authorization failed: ${error ?: "unknown error"}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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
            val port = prefs.getInt("loopback_port", 0)
            if (port == 0) return@withContext Result.failure(
                IllegalStateException("No loopback port configured")
            )
            val redirectUri = getRedirectUri(port)

            val jsonBody = JSONObject().apply {
                put("grant_type", "authorization_code")
                put("code", code)
                put("code_verifier", codeVerifier)
                put("redirect_uri", redirectUri)
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

                saveAccessToken(accessToken)
                if (refreshToken != null) {
                    prefs.edit().putString("refresh_token", refreshToken).apply()
                }

                // Clear code verifier and port
                prefs.edit().apply {
                    remove("code_verifier")
                    remove("loopback_port")
                    apply()
                }

                Result.success(accessToken)
            } else {
                Log.e(TAG, "Token exchange failed: ${response.code} $responseBody")
                Result.failure(Exception("Token exchange failed: ${response.code} - $responseBody"))
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

                saveAccessToken(accessToken)

                // Update refresh token if a new one is provided
                val newRefreshToken = json.optString("refresh_token", null)
                if (newRefreshToken != null) {
                    prefs.edit().putString("refresh_token", newRefreshToken).apply()
                }

                Result.success(accessToken)
            } else {
                Log.e(TAG, "Token refresh failed: ${response.code} $responseBody")
                Result.failure(Exception("Token refresh failed: ${response.code} - $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh error: ${e.message}", e)
            Result.failure(e)
        }
    }
}
