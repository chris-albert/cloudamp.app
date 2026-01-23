package com.cloudamp.music

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cloudamp.music.api.SpotifyApiClient

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var tokenEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        
        spotifyClient = SpotifyApiClient.getInstance(this)
        
        tokenEditText = findViewById(R.id.tokenEditText)
        saveButton = findViewById(R.id.saveTokenButton)
        clearButton = findViewById(R.id.clearTokenButton)
        
        // Load existing token
        spotifyClient.getAccessToken()?.let {
            tokenEditText.setText(it)
        }
        
        saveButton.setOnClickListener {
            val token = tokenEditText.text.toString().trim()
            if (token.isNotEmpty()) {
                spotifyClient.saveAccessToken(token)
                Toast.makeText(this, "Spotify token saved!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Please enter a valid token", Toast.LENGTH_SHORT).show()
            }
        }
        
        clearButton.setOnClickListener {
            spotifyClient.clearAccessToken()
            tokenEditText.setText("")
            Toast.makeText(this, "Token cleared", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
