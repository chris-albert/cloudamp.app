package com.cloudamp.music.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class GoogleDriveCallbackActivity : AppCompatActivity() {

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
                    Toast.makeText(this, "Google Drive authorization failed: $error", Toast.LENGTH_LONG).show()
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
                val authManager = GoogleDriveAuthManager(this@GoogleDriveCallbackActivity)
                val result = authManager.exchangeCodeForToken(code)

                result.onSuccess { accessToken ->
                    Toast.makeText(
                        this@GoogleDriveCallbackActivity,
                        "Google Drive authenticated!",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Navigate to GDrive Library
                    finishAffinity()
                    startActivity(
                        Intent(
                            this@GoogleDriveCallbackActivity,
                            Class.forName("com.cloudamp.music.GDriveLibraryActivity")
                        )
                    )
                }.onFailure { error ->
                    Toast.makeText(
                        this@GoogleDriveCallbackActivity,
                        "Failed to get token: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@GoogleDriveCallbackActivity,
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
