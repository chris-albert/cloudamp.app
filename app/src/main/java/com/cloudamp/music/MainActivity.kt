package com.cloudamp.music

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Album
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Track
import com.cloudamp.music.playback.PlaybackManager
import com.cloudamp.music.ui.ArtistAdapter
import com.cloudamp.music.ui.AlbumAdapter
import com.cloudamp.music.ui.TrackAdapter
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var artistRecyclerView: RecyclerView
    private lateinit var albumRecyclerView: RecyclerView
    private lateinit var trackRecyclerView: RecyclerView

    private var currentArtists = listOf<Artist>()
    private var currentAlbums = listOf<Album>()
    private var currentTracks = listOf<Track>()
    private var hasLoadedContent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager(this, spotifyClient)

        setupRecyclerViews()
    }

    override fun onResume() {
        super.onResume()
        checkSpotifyToken()
    }

    private fun setupRecyclerViews() {
        // Artists
        artistRecyclerView = findViewById(R.id.artistRecyclerView)
        artistRecyclerView.layoutManager = LinearLayoutManager(this)
        artistRecyclerView.adapter = ArtistAdapter(currentArtists) { artist ->
            onArtistClick(artist)
        }

        // Albums
        albumRecyclerView = findViewById(R.id.albumRecyclerView)
        albumRecyclerView.layoutManager = LinearLayoutManager(this)
        albumRecyclerView.adapter = AlbumAdapter(currentAlbums) { album ->
            onAlbumClick(album)
        }

        // Tracks
        trackRecyclerView = findViewById(R.id.trackRecyclerView)
        trackRecyclerView.layoutManager = LinearLayoutManager(this)
        trackRecyclerView.adapter = TrackAdapter(currentTracks) { track, position ->
            onTrackClick(track, position)
        }
    }

    private fun checkSpotifyToken() {
        if (!spotifyClient.hasAccessToken()) {
            // Clear any previously loaded content
            if (hasLoadedContent) {
                clearContent()
                hasLoadedContent = false
            }
            Toast.makeText(this, "Please set your Spotify token in Settings", Toast.LENGTH_LONG).show()
        } else {
            // Only load if we haven't loaded yet or if we just got a token
            if (!hasLoadedContent) {
                loadDefaultArtists()
            }
        }
    }

    private fun clearContent() {
        currentArtists = emptyList()
        currentAlbums = emptyList()
        currentTracks = emptyList()
        (artistRecyclerView.adapter as ArtistAdapter).updateData(currentArtists)
        (albumRecyclerView.adapter as AlbumAdapter).updateData(currentAlbums)
        (trackRecyclerView.adapter as TrackAdapter).updateData(currentTracks)
    }

    private fun loadDefaultArtists() {
        scope.launch {
            try {
                // Search for some popular artists to get started
                val response = spotifyClient.api.search("rock", "artist", limit = 20)
                if (response.isSuccessful) {
                    currentArtists = response.body()?.artists?.items ?: emptyList()
                    (artistRecyclerView.adapter as ArtistAdapter).updateData(currentArtists)
                    hasLoadedContent = true
                } else {
                    val errorMsg = when (response.code()) {
                        401 -> "Token expired. Please update it in Settings."
                        403 -> "Token doesn't have required permissions."
                        else -> "Error loading content (${response.code()})"
                    }
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error loading artists: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onArtistClick(artist: Artist) {
        scope.launch {
            try {
                val response = spotifyClient.api.getArtistAlbums(artist.id)
                if (response.isSuccessful) {
                    currentAlbums = response.body()?.items ?: emptyList()
                    (albumRecyclerView.adapter as AlbumAdapter).updateData(currentAlbums)
                    currentTracks = emptyList()
                    (trackRecyclerView.adapter as TrackAdapter).updateData(currentTracks)
                } else {
                    handleApiError(response.code(), "albums")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error loading albums: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onAlbumClick(album: Album) {
        scope.launch {
            try {
                val response = spotifyClient.api.getAlbumTracks(album.id)
                if (response.isSuccessful) {
                    currentTracks = response.body()?.items ?: emptyList()
                    (trackRecyclerView.adapter as TrackAdapter).updateData(currentTracks)
                } else {
                    handleApiError(response.code(), "tracks")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error loading tracks: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onTrackClick(track: Track, position: Int) {
        val trackUris = currentTracks.map { it.uri }
        playbackManager.playTracks(trackUris, position)
        Toast.makeText(this, "Playing: ${track.name}", Toast.LENGTH_SHORT).show()
    }

    private fun handleApiError(code: Int, contentType: String) {
        val errorMsg = when (code) {
            401 -> "Token expired. Please update it in Settings."
            403 -> "Insufficient permissions."
            404 -> "Content not found."
            else -> "Error loading $contentType (code: $code)"
        }
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_search -> {
                performSearch()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performSearch() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Search Music")

        val input = android.widget.EditText(this)
        input.hint = "Enter artist, album, or track name"
        builder.setView(input)

        builder.setPositiveButton("Search") { _, _ ->
            val query = input.text.toString()
            if (query.isNotEmpty()) {
                searchMusic(query)
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun searchMusic(query: String) {
        scope.launch {
            try {
                val response = spotifyClient.api.search(query, "artist,album,track")
                if (response.isSuccessful) {
                    val results = response.body()
                    currentArtists = results?.artists?.items ?: emptyList()
                    currentAlbums = results?.albums?.items ?: emptyList()
                    currentTracks = results?.tracks?.items ?: emptyList()

                    (artistRecyclerView.adapter as ArtistAdapter).updateData(currentArtists)
                    (albumRecyclerView.adapter as AlbumAdapter).updateData(currentAlbums)
                    (trackRecyclerView.adapter as TrackAdapter).updateData(currentTracks)
                } else {
                    handleApiError(response.code(), "search results")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
