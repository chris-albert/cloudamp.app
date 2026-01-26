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
import com.cloudamp.music.models.SimplifiedTrack
import com.cloudamp.music.playback.PlaybackManager
import com.cloudamp.music.ui.ExpandableLibraryAdapter
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var libraryRecyclerView: RecyclerView
    private lateinit var libraryAdapter: ExpandableLibraryAdapter

    private var hasLoadedContent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        checkSpotifyToken()
    }

    private fun setupRecyclerView() {
        libraryRecyclerView = findViewById(R.id.libraryRecyclerView)
        libraryRecyclerView.layoutManager = LinearLayoutManager(this)

        libraryAdapter = ExpandableLibraryAdapter(
            onArtistClick = { artist, position ->
                loadArtistAlbums(artist, position)
            },
            onAlbumClick = { album, artistId, position ->
                loadAlbumTracks(album, position)
            },
            onTrackClick = { track, allTracks, trackPosition ->
                playTrackWithQueue(track, allTracks, trackPosition)
            }
        )

        libraryRecyclerView.adapter = libraryAdapter
    }

    private fun checkSpotifyToken() {
        if (!spotifyClient.hasAccessToken()) {
            if (hasLoadedContent) {
                libraryAdapter.setArtists(emptyList())
                hasLoadedContent = false
            }
            Toast.makeText(this, "Please set your Spotify credentials in Settings", Toast.LENGTH_LONG).show()
        } else {
            if (!hasLoadedContent) {
                loadMyLibrary()
            }
        }
    }

    private fun loadMyLibrary() {
        scope.launch {
            try {
                // Load user's top artists with images
                val artistsResponse = spotifyClient.api.getMyTopArtists(limit = 50)
                if (artistsResponse.isSuccessful) {
                    val artists = artistsResponse.body()?.items?.sortedBy { it.name } ?: emptyList()

                    libraryAdapter.setArtists(artists)
                    hasLoadedContent = true

                    if (artists.isEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Your library is empty. Use Search to find music!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    handleApiError(artistsResponse.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token expired. Please re-login in Settings."
                    e.message?.contains("403") == true -> "Insufficient permissions. Please re-login in Settings."
                    else -> "Error loading library: ${e.message}"
                }
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadArtistAlbums(artist: Artist, position: Int) {
        scope.launch {
            try {
                val response = spotifyClient.api.getArtistAlbums(artist.id, limit = 50)
                if (response.isSuccessful) {
                    val albums = response.body()?.items ?: emptyList()
                    libraryAdapter.setArtistAlbums(position, albums)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error loading albums: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAlbumTracks(album: Album, position: Int) {
        scope.launch {
            try {
                val response = spotifyClient.api.getAlbumTracks(album.id)
                if (response.isSuccessful) {
                    // Convert SimplifiedTrack to Track by adding album info
                    val tracks = response.body()?.items?.map { simplifiedTrack ->
                        Track(
                            id = simplifiedTrack.id,
                            name = simplifiedTrack.name,
                            artists = simplifiedTrack.artists,
                            album = album,
                            uri = simplifiedTrack.uri,
                            durationMs = simplifiedTrack.durationMs,
                            trackNumber = simplifiedTrack.trackNumber
                        )
                    } ?: emptyList()
                    libraryAdapter.setAlbumTracks(position, tracks)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error loading tracks: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playTrackWithQueue(track: Track, allTracks: List<Track>, trackPosition: Int) {
        // Queue all tracks from the clicked track onwards
        val tracksToPlay = allTracks.drop(trackPosition)
        playbackManager.playTracks(tracksToPlay, 0)
        Toast.makeText(this, "Playing: ${track.name} (+${tracksToPlay.size - 1} in queue)", Toast.LENGTH_SHORT).show()
    }

    private fun handleApiError(code: Int) {
        val errorMsg = when (code) {
            401 -> "Token expired. Please re-login in Settings."
            403 -> "Insufficient permissions. Please re-login in Settings."
            404 -> "Content not found."
            else -> "Error loading content (code: $code)"
        }
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_now_playing -> {
                startActivity(Intent(this, NowPlayingActivity::class.java))
                true
            }
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
                    val artists = results?.artists?.items ?: emptyList()

                    if (artists.isNotEmpty()) {
                        libraryAdapter.setArtists(artists)
                    } else {
                        Toast.makeText(this@MainActivity, "No results found", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    handleApiError(response.code())
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
