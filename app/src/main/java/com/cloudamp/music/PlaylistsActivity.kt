package com.cloudamp.music

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.api.Playlist
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Track
import com.cloudamp.music.playback.PlaybackManager
import com.cloudamp.music.ui.PlaylistsAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class PlaylistsActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var playlistsRecyclerView: RecyclerView
    private lateinit var playlistsAdapter: PlaylistsAdapter
    private lateinit var loadingContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)

        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)

        setupDrawer()
        setupRecyclerView()
        loadPlaylists()
    }

    private fun setupDrawer() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener(this)
        navigationView.setCheckedItem(R.id.nav_playlists)
    }

    private fun setupRecyclerView() {
        playlistsRecyclerView = findViewById(R.id.playlistsRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)
        playlistsRecyclerView.layoutManager = LinearLayoutManager(this)

        playlistsAdapter = PlaylistsAdapter(
            onPlaylistClick = { playlist, position ->
                loadPlaylistTracks(playlist, position)
            },
            onTrackClick = { track, allTracks, trackPosition ->
                playTrackWithQueue(track, allTracks, trackPosition)
            }
        )

        playlistsRecyclerView.adapter = playlistsAdapter
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadPlaylists() {
        showLoading(true)
        scope.launch {
            try {
                val allPlaylists = mutableListOf<Playlist>()
                var offset = 0
                val limit = 50

                do {
                    val response = spotifyClient.api.getMyPlaylists(limit = limit, offset = offset)
                    if (response.isSuccessful) {
                        val playlists = response.body()?.items ?: emptyList()
                        allPlaylists.addAll(playlists)
                        offset += limit
                        if (playlists.size < limit) break
                    } else {
                        handleApiError(response.code())
                        break
                    }
                } while (true)

                playlistsAdapter.setPlaylists(allPlaylists)

                if (allPlaylists.isEmpty()) {
                    Toast.makeText(
                        this@PlaylistsActivity,
                        "No playlists found",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@PlaylistsActivity,
                    "Error loading playlists: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun loadPlaylistTracks(playlist: Playlist, position: Int) {
        scope.launch {
            try {
                val response = spotifyClient.api.getPlaylistTracks(playlist.id)
                if (response.isSuccessful) {
                    val tracks = response.body()?.items
                        ?.mapNotNull { it.track }
                        ?: emptyList()
                    playlistsAdapter.setPlaylistTracks(position, tracks)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@PlaylistsActivity,
                    "Error loading tracks: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun playTrackWithQueue(track: Track, allTracks: List<Track>, trackPosition: Int) {
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

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_library -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
            R.id.nav_playlists -> {
                // Already here
            }
            R.id.nav_gdrive_library -> {
                startActivity(Intent(this, GDriveLibraryActivity::class.java))
                finish()
            }
            R.id.nav_saved_queues -> {
                startActivity(Intent(this, SavedQueuesActivity::class.java))
                finish()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return when (item.itemId) {
            R.id.action_now_playing -> {
                startActivity(Intent(this, NowPlayingActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // Hide search in playlists view for now
        menu.findItem(R.id.action_search)?.isVisible = false
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
