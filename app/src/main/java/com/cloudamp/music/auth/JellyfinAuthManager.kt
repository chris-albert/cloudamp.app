package com.cloudamp.music.auth

import android.content.Context

/**
 * Manages Jellyfin server URL + API key persistence.
 * Much simpler than OAuth managers — no token refresh needed.
 */
class JellyfinAuthManager(context: Context) {

    private val prefs = context.getSharedPreferences("jellyfin_auth", Context.MODE_PRIVATE)

    fun getServerUrl(): String? = prefs.getString("server_url", null)

    fun setServerUrl(url: String) {
        prefs.edit().putString("server_url", url).apply()
    }

    fun getApiKey(): String? = prefs.getString("api_key", null)

    fun setApiKey(key: String) {
        prefs.edit().putString("api_key", key).apply()
    }

    fun getUserId(): String? = prefs.getString("user_id", null)

    fun setUserId(id: String) {
        prefs.edit().putString("user_id", id).apply()
    }

    fun isConfigured(): Boolean {
        return !getServerUrl().isNullOrEmpty() && !getApiKey().isNullOrEmpty()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
