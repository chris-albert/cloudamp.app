package com.cloudamp.music

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cloudamp.music.api.SpotifyApiClient
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var tokenEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button
    private lateinit var validationProgress: ProgressBar
    private lateinit var validationStatus: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        spotifyClient = SpotifyApiClient.getInstance(this)

        tokenEditText = findViewById(R.id.tokenEditText)
        saveButton = findViewById(R.id.saveTokenButton)
        clearButton = findViewById(R.id.clearTokenButton)
        validationProgress = findViewById(R.id.validationProgress)
        validationStatus = findViewById(R.id.validationStatus)

        // Load existing token
        spotifyClient.getAccessToken()?.let {
            tokenEditText.setText(it)
            updateValidationStatus("Token saved", true)
        }

        saveButton.setOnClickListener {
            val token = tokenEditText.text.toString().trim()
            if (token.isNotEmpty()) {
                validateAndSaveToken(token)
            } else {
                Toast.makeText(this, "Please enter a valid token", Toast.LENGTH_SHORT).show()
            }
        }

        clearButton.setOnClickListener {
            spotifyClient.clearAccessToken()
            tokenEditText.setText("")
            updateValidationStatus("", false)
            Toast.makeText(this, "Token cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateAndSaveToken(token: String) {
        scope.launch {
            try {
                // Show loading
                validationProgress.visibility = View.VISIBLE
                updateValidationStatus("Validating token...", false)
                saveButton.isEnabled = false

                // Temporarily save token for validation
                spotifyClient.saveAccessToken(token)

                // Validate by calling Spotify API
                val response = spotifyClient.api.getCurrentUserProfile()

                if (response.isSuccessful && response.body() != null) {
                    val user = response.body()!!
                    val displayName = user.display_name ?: "User"
                    updateValidationStatus("✓ Valid token for: $displayName", true)
                    Toast.makeText(this@SettingsActivity, "Token validated successfully!", Toast.LENGTH_SHORT).show()

                    // Delay briefly to show success message
                    delay(1000)
                    finish()
                } else {
                    // Invalid token - clear it
                    spotifyClient.clearAccessToken()
                    val errorMsg = when (response.code()) {
                        401 -> "Invalid or expired token"
                        403 -> "Token doesn't have required permissions"
                        else -> "Failed to validate token (${response.code()})"
                    }
                    updateValidationStatus("✗ $errorMsg", false)
                    Toast.makeText(this@SettingsActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                spotifyClient.clearAccessToken()
                updateValidationStatus("✗ Network error: ${e.message}", false)
                Toast.makeText(this@SettingsActivity, "Validation failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                validationProgress.visibility = View.GONE
                saveButton.isEnabled = true
            }
        }
    }

    private fun updateValidationStatus(message: String, isValid: Boolean) {
        validationStatus.text = message
        validationStatus.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        validationStatus.setTextColor(
            if (isValid)
                getColor(android.R.color.holo_green_dark)
            else
                getColor(android.R.color.holo_red_dark)
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
