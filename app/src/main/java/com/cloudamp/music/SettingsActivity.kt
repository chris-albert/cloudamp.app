package com.cloudamp.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.auth.SpotifyAuthManager
import com.cloudamp.music.cache.LibraryCache
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var authManager: SpotifyAuthManager
    private lateinit var libraryCache: LibraryCache

    // OAuth fields
    private lateinit var clientIdEditText: EditText
    private lateinit var clientSecretEditText: EditText
    private lateinit var saveCredentialsButton: Button
    private lateinit var loginWithSpotifyButton: Button

    // Manual token fields
    private lateinit var tokenEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button

    private lateinit var validationProgress: ProgressBar
    private lateinit var validationStatus: TextView

    // Library fields
    private lateinit var lastLoadedText: TextView
    private lateinit var reloadLibraryButton: Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        var onLibraryReloadRequested: (() -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        spotifyClient = SpotifyApiClient.getInstance(this)
        authManager = SpotifyAuthManager(this)
        libraryCache = LibraryCache.getInstance(this)

        // OAuth views
        clientIdEditText = findViewById(R.id.clientIdEditText)
        clientSecretEditText = findViewById(R.id.clientSecretEditText)
        saveCredentialsButton = findViewById(R.id.saveCredentialsButton)
        loginWithSpotifyButton = findViewById(R.id.loginWithSpotifyButton)

        // Manual token views
        tokenEditText = findViewById(R.id.tokenEditText)
        saveButton = findViewById(R.id.saveTokenButton)
        clearButton = findViewById(R.id.clearTokenButton)

        validationProgress = findViewById(R.id.validationProgress)
        validationStatus = findViewById(R.id.validationStatus)

        // Load existing credentials
        authManager.getClientId()?.let { clientIdEditText.setText(it) }
        authManager.getClientSecret()?.let { clientSecretEditText.setText(it) }

        // Load existing token
        spotifyClient.getAccessToken()?.let {
            tokenEditText.setText(it)
            updateValidationStatus("Token saved", true)
        }

        // OAuth flow buttons
        saveCredentialsButton.setOnClickListener {
            val clientId = clientIdEditText.text.toString().trim()
            val clientSecret = clientSecretEditText.text.toString().trim()

            if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                authManager.saveClientCredentials(clientId, clientSecret)
                loginWithSpotifyButton.isEnabled = true
                Toast.makeText(this, "Credentials saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter both Client ID and Secret", Toast.LENGTH_SHORT).show()
            }
        }

        loginWithSpotifyButton.isEnabled = authManager.hasClientCredentials()
        loginWithSpotifyButton.setOnClickListener {
            try {
                val authUrl = authManager.getAuthorizationUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Manual token buttons
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
            authManager.clearClientCredentials()
            clientIdEditText.setText("")
            clientSecretEditText.setText("")
            tokenEditText.setText("")
            loginWithSpotifyButton.isEnabled = false
            updateValidationStatus("", false)
            Toast.makeText(this, "All credentials cleared", Toast.LENGTH_SHORT).show()
        }

        // Library views
        lastLoadedText = findViewById(R.id.lastLoadedText)
        reloadLibraryButton = findViewById(R.id.reloadLibraryButton)

        updateLastLoadedDisplay()

        reloadLibraryButton.setOnClickListener {
            onLibraryReloadRequested?.invoke()
            Toast.makeText(this, "Library will reload", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateLastLoadedDisplay() {
        val lastLoaded = libraryCache.getLastLoadedFormatted()
        lastLoadedText.text = if (lastLoaded != null) {
            "Last loaded: $lastLoaded"
        } else {
            "Last loaded: Never"
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
