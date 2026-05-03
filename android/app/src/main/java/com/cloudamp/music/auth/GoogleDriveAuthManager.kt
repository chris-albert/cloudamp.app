package com.cloudamp.music.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

class GoogleDriveAuthManager(private val context: Context) {

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/drive.file"
        )
    }

    private val prefs = context.getSharedPreferences("gdrive_auth", Context.MODE_PRIVATE)
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

    fun clearCredentials() {
        prefs.edit().apply {
            remove("client_id")
            remove("client_secret")
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

    /**
     * Returns the redirect URI using a loopback address.
     * Google OAuth supports http://127.0.0.1:<port> for installed/desktop apps.
     */
    private fun getRedirectUri(port: Int): String {
        return "http://$LOOPBACK_HOST:$port"
    }

    fun getAuthorizationUrl(): String {
        val clientId = getClientId() ?: throw IllegalStateException("Client ID not set")

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

    /**
     * Find an available port on the loopback interface.
     */
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
     *
     * Returns the authorization code or throws an exception.
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

    suspend fun exchangeCodeForToken(code: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val clientId = getClientId() ?: return@withContext Result.failure(
                IllegalStateException("Client ID not set")
            )
            val clientSecret = getClientSecret() ?: return@withContext Result.failure(
                IllegalStateException("Client Secret not set")
            )
            val codeVerifier = prefs.getString("code_verifier", null) ?: return@withContext Result.failure(
                IllegalStateException("Code verifier not found")
            )
            val port = prefs.getInt("loopback_port", 0)
            if (port == 0) return@withContext Result.failure(
                IllegalStateException("No loopback port configured")
            )
            val redirectUri = getRedirectUri(port)

            val requestBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
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

                // Save tokens
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

            val requestBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
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

                saveAccessToken(accessToken)

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
