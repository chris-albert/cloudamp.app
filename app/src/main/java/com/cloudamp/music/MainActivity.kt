package com.cloudamp.music

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Redirect to Jellyfin Library as the default screen
        val jellyfinIntent = Intent(this, JellyfinLibraryActivity::class.java)
        jellyfinIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(jellyfinIntent)
        finish()
    }
}
