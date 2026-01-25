package com.cloudamp.music.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cloudamp.music.api.SpotifyApiClient
import kotlinx.coroutines.*

class SpotifyCallbackActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        if (uri != null) {
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")

            when {
                code != null -> exchangeCodeForToken(code)
                error != null -> {
                    Toast.makeText(this, "Authorization failed: $error", Toast.LENGTH_LONG).show()
                    finish()
                }
                else -> {
                    Toast.makeText(this, "Invalid callback", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } else {
            finish()
        }
    }

    private fun exchangeCodeForToken(code: String) {
        scope.launch {
            try {
                val authManager = SpotifyAuthManager(this@SpotifyCallbackActivity)
                val result = authManager.exchangeCodeForToken(code)

                result.onSuccess { accessToken ->
                    // Save the access token
                    val spotifyClient = SpotifyApiClient.getInstance(this@SpotifyCallbackActivity)
                    spotifyClient.saveAccessToken(accessToken)

                    Toast.makeText(
                        this@SpotifyCallbackActivity,
                        "Successfully authenticated!",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Return to main app
                    finishAffinity()
                    startActivity(Intent(this@SpotifyCallbackActivity, Class.forName("com.cloudamp.music.MainActivity")))
                }.onFailure { error ->
                    Toast.makeText(
                        this@SpotifyCallbackActivity,
                        "Failed to get token: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@SpotifyCallbackActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
