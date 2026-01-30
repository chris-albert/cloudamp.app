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
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.api.JellyfinApiClient
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.auth.GoogleDriveAuthManager
import com.cloudamp.music.auth.JellyfinAuthManager
import com.cloudamp.music.auth.SpotifyAuthManager
import com.cloudamp.music.cache.LibraryCache
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var authManager: SpotifyAuthManager
    private lateinit var libraryCache: LibraryCache

    // Spotify OAuth fields
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

    // Google Drive fields
    private lateinit var gdriveAuthManager: GoogleDriveAuthManager
    private lateinit var gdriveClientIdEditText: EditText
    private lateinit var gdriveClientSecretEditText: EditText
    private lateinit var saveGdriveCredentialsButton: Button
    private lateinit var loginWithGdriveButton: Button
    private lateinit var clearGdriveButton: Button
    private lateinit var gdriveValidationStatus: TextView

    // Jellyfin fields
    private lateinit var jellyfinAuthManager: JellyfinAuthManager
    private lateinit var jellyfinServerUrlEditText: EditText
    private lateinit var jellyfinUsernameEditText: EditText
    private lateinit var jellyfinPasswordEditText: EditText
    private lateinit var loginWithJellyfinButton: Button
    private lateinit var clearJellyfinButton: Button
    private lateinit var jellyfinValidationStatus: TextView

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
        gdriveAuthManager = GoogleDriveAuthManager(this)
        jellyfinAuthManager = JellyfinAuthManager(this)

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

        // Google Drive views
        setupGoogleDrive()

        // Jellyfin views
        setupJellyfin()
    }

    private fun setupGoogleDrive() {
        gdriveClientIdEditText = findViewById(R.id.gdriveClientIdEditText)
        gdriveClientSecretEditText = findViewById(R.id.gdriveClientSecretEditText)
        saveGdriveCredentialsButton = findViewById(R.id.saveGdriveCredentialsButton)
        loginWithGdriveButton = findViewById(R.id.loginWithGdriveButton)
        clearGdriveButton = findViewById(R.id.clearGdriveButton)
        gdriveValidationStatus = findViewById(R.id.gdriveValidationStatus)

        // Load existing credentials
        gdriveAuthManager.getClientId()?.let { gdriveClientIdEditText.setText(it) }
        gdriveAuthManager.getClientSecret()?.let { gdriveClientSecretEditText.setText(it) }

        // Show current status
        if (gdriveAuthManager.hasAccessToken()) {
            updateGdriveStatus("Connected to Google Drive", true)
            validateGdriveToken()
        }

        loginWithGdriveButton.isEnabled = gdriveAuthManager.hasClientCredentials()

        saveGdriveCredentialsButton.setOnClickListener {
            val clientId = gdriveClientIdEditText.text.toString().trim()
            val clientSecret = gdriveClientSecretEditText.text.toString().trim()
            if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                gdriveAuthManager.saveClientCredentials(clientId, clientSecret)
                loginWithGdriveButton.isEnabled = true
                Toast.makeText(this, "Google Drive credentials saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter both Client ID and Secret", Toast.LENGTH_SHORT).show()
            }
        }

        loginWithGdriveButton.setOnClickListener {
            startGoogleDriveLogin()
        }

        clearGdriveButton.setOnClickListener {
            gdriveAuthManager.clearCredentials()
            gdriveClientIdEditText.setText("")
            gdriveClientSecretEditText.setText("")
            loginWithGdriveButton.isEnabled = false
            updateGdriveStatus("", false)
            Toast.makeText(this, "Google Drive credentials cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGoogleDriveLogin() {
        try {
            val authUrl = gdriveAuthManager.getAuthorizationUrl()

            loginWithGdriveButton.isEnabled = false
            updateGdriveStatus("Waiting for authorization...", false)

            // Start listening for the loopback callback in background
            scope.launch {
                val codeResult = withContext(Dispatchers.IO) {
                    gdriveAuthManager.waitForAuthorizationCode()
                }

                codeResult.onSuccess { code ->
                    updateGdriveStatus("Exchanging token...", false)
                    val tokenResult = gdriveAuthManager.exchangeCodeForToken(code)
                    tokenResult.onSuccess {
                        updateGdriveStatus("Connected to Google Drive!", true)
                        validateGdriveToken()
                    }.onFailure { error ->
                        updateGdriveStatus("Token exchange failed: ${error.message}", false)
                    }
                }.onFailure { error ->
                    updateGdriveStatus("Authorization failed: ${error.message}", false)
                }

                loginWithGdriveButton.isEnabled = true
            }

            // Open browser for authorization
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            startActivity(intent)
        } catch (e: Exception) {
            loginWithGdriveButton.isEnabled = true
            updateGdriveStatus("Error: ${e.message}", false)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun validateGdriveToken() {
        scope.launch {
            try {
                val driveClient = GoogleDriveApiClient.getInstance(this@SettingsActivity)
                val response = driveClient.api.getAbout()
                if (response.isSuccessful) {
                    val displayName = response.body()?.user?.displayName ?: "User"
                    val email = response.body()?.user?.emailAddress ?: ""
                    updateGdriveStatus("Connected: $displayName ($email)", true)
                } else {
                    updateGdriveStatus("Token expired - please re-login", false)
                }
            } catch (e: Exception) {
                updateGdriveStatus("Connection error", false)
            }
        }
    }

    private fun updateGdriveStatus(message: String, isValid: Boolean) {
        gdriveValidationStatus.text = message
        gdriveValidationStatus.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        gdriveValidationStatus.setTextColor(
            if (isValid) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
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

    // ── Jellyfin Setup ────────────────────────────────────────────────

    private fun setupJellyfin() {
        jellyfinServerUrlEditText = findViewById(R.id.jellyfinServerUrlEditText)
        jellyfinUsernameEditText = findViewById(R.id.jellyfinUsernameEditText)
        jellyfinPasswordEditText = findViewById(R.id.jellyfinPasswordEditText)
        loginWithJellyfinButton = findViewById(R.id.loginWithJellyfinButton)
        clearJellyfinButton = findViewById(R.id.clearJellyfinButton)
        jellyfinValidationStatus = findViewById(R.id.jellyfinValidationStatus)

        // Load existing server URL and username
        jellyfinAuthManager.getServerUrl()?.let { jellyfinServerUrlEditText.setText(it) }
        jellyfinAuthManager.getUsername()?.let { jellyfinUsernameEditText.setText(it) }

        // Show current status
        if (jellyfinAuthManager.hasAccessToken()) {
            updateJellyfinStatus("Connected to Jellyfin", true)
            validateJellyfinToken()
        }

        loginWithJellyfinButton.setOnClickListener {
            val serverUrl = jellyfinServerUrlEditText.text.toString().trim()
            val username = jellyfinUsernameEditText.text.toString().trim()
            val password = jellyfinPasswordEditText.text.toString().trim()

            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (username.isEmpty()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginWithJellyfinButton.isEnabled = false
            updateJellyfinStatus("Connecting...", false)

            scope.launch {
                val result = jellyfinAuthManager.authenticate(serverUrl, username, password)
                result.onSuccess { displayName ->
                    // Reset the API client singleton so it picks up the new server URL
                    JellyfinApiClient.resetInstance()
                    updateJellyfinStatus("Connected: $displayName", true)
                    Toast.makeText(this@SettingsActivity, "Logged in as $displayName", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    updateJellyfinStatus("Login failed: ${error.message}", false)
                    Toast.makeText(this@SettingsActivity, "Login failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
                loginWithJellyfinButton.isEnabled = true
            }
        }

        clearJellyfinButton.setOnClickListener {
            jellyfinAuthManager.clearCredentials()
            JellyfinApiClient.resetInstance()
            jellyfinServerUrlEditText.setText("")
            jellyfinUsernameEditText.setText("")
            jellyfinPasswordEditText.setText("")
            updateJellyfinStatus("", false)
            Toast.makeText(this, "Jellyfin credentials cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateJellyfinToken() {
        scope.launch {
            val result = jellyfinAuthManager.validateToken()
            result.onSuccess { displayName ->
                updateJellyfinStatus("Connected: $displayName", true)
            }.onFailure {
                updateJellyfinStatus("Session expired - please re-login", false)
            }
        }
    }

    private fun updateJellyfinStatus(message: String, isValid: Boolean) {
        jellyfinValidationStatus.text = message
        jellyfinValidationStatus.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        jellyfinValidationStatus.setTextColor(
            if (isValid) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
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
