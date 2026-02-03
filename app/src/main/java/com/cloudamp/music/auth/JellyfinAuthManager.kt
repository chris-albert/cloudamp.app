package com.cloudamp.music.auth

import android.content.Context

/**
 * Manages Jellyfin server URL + access token persistence.
 * Uses username/password authentication to obtain an access token.
 */
class JellyfinAuthManager(context: Context) {

    private val prefs = context.getSharedPreferences("jellyfin_auth", Context.MODE_PRIVATE)

    fun getServerUrl(): String? = prefs.getString("server_url", null)

    fun setServerUrl(url: String) {
        prefs.edit().putString("server_url", url).apply()
    }

    /** Access token obtained from AuthenticateByName. */
    fun getAccessToken(): String? = prefs.getString("access_token", null)

    fun setAccessToken(token: String) {
        prefs.edit().putString("access_token", token).apply()
    }

    /** For backward compatibility, also check old "api_key" field. */
    fun getApiKey(): String? = getAccessToken() ?: prefs.getString("api_key", null)

    fun getUserId(): String? = prefs.getString("user_id", null)

    fun setUserId(id: String) {
        prefs.edit().putString("user_id", id).apply()
    }

    fun getUsername(): String? = prefs.getString("username", null)

    fun setUsername(username: String) {
        prefs.edit().putString("username", username).apply()
    }

    fun isConfigured(): Boolean {
        return !getServerUrl().isNullOrEmpty() && !getAccessToken().isNullOrEmpty()
    }

    fun hasServerUrl(): Boolean {
        return !getServerUrl().isNullOrEmpty()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
