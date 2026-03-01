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
import com.cloudamp.music.api.JellyfinApiClient
import com.cloudamp.music.api.JellyfinItem
import com.cloudamp.music.auth.JellyfinAuthManager
import com.cloudamp.music.playback.CloudAmpService
import com.cloudamp.music.playback.JellyfinPlaybackManager
import com.cloudamp.music.ui.JellyfinPlaylistsAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class JellyfinPlaylistsActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var jellyfinClient: JellyfinApiClient
    private lateinit var authManager: JellyfinAuthManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var playlistsRecyclerView: RecyclerView
    private lateinit var playlistsAdapter: JellyfinPlaylistsAdapter
    private lateinit var loadingContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin_playlists)

        jellyfinClient = JellyfinApiClient.getInstance(this)
        authManager = JellyfinAuthManager(this)

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
        navigationView.setCheckedItem(R.id.nav_jellyfin_playlists)
    }

    private fun setupRecyclerView() {
        playlistsRecyclerView = findViewById(R.id.playlistsRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)
        playlistsRecyclerView.layoutManager = LinearLayoutManager(this)

        val serverUrl = authManager.getServerUrl()?.trimEnd('/') ?: ""
        val apiKey = authManager.getApiKey()

        playlistsAdapter = JellyfinPlaylistsAdapter(
            serverUrl = serverUrl,
            apiKey = apiKey,
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
                val userId = authManager.getUserId() ?: return@launch
                val response = jellyfinClient.api.getPlaylists(userId)

                if (response.isSuccessful) {
                    val playlists = response.body()?.Items ?: emptyList()
                    playlistsAdapter.setPlaylists(playlists)

                    if (playlists.isEmpty()) {
                        Toast.makeText(
                            this@JellyfinPlaylistsActivity,
                            "No playlists found",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@JellyfinPlaylistsActivity,
                    "Error loading playlists: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun loadPlaylistTracks(playlist: JellyfinItem, position: Int) {
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val response = jellyfinClient.api.getPlaylistItems(playlist.Id, userId)

                if (response.isSuccessful) {
                    val tracks = response.body()?.Items ?: emptyList()
                    playlistsAdapter.setPlaylistTracks(position, tracks)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@JellyfinPlaylistsActivity,
                    "Error loading tracks: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun playTrackWithQueue(track: JellyfinItem, allTracks: List<JellyfinItem>, trackPosition: Int) {
        val jellyfinPlayback = JellyfinPlaybackManager.getInstance(this)
        val queueItems = allTracks.subList(trackPosition, allTracks.size) +
                allTracks.subList(0, trackPosition)
        CloudAmpService.ensureForeground(this)
        jellyfinPlayback.playItems(queueItems, 0)
        Toast.makeText(this, "Playing: ${track.Name}", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun handleApiError(code: Int) {
        val errorMsg = when (code) {
            401 -> "Jellyfin authentication failed. Check your credentials in Settings."
            403 -> "Insufficient permissions."
            404 -> "Content not found."
            else -> "Error loading content (code: $code)"
        }
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_gdrive_library -> {
                startActivity(Intent(this, GDriveLibraryActivity::class.java))
                finish()
            }
            R.id.nav_jellyfin_home -> {
                startActivity(Intent(this, JellyfinHomeActivity::class.java))
                finish()
            }
            R.id.nav_jellyfin_library -> {
                startActivity(Intent(this, JellyfinLibraryActivity::class.java))
                finish()
            }
            R.id.nav_jellyfin_playlists -> {
                // Already here
            }
            R.id.nav_jellyfin_recent_played -> {
                val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
                intent.putExtra(JellyfinRecentAlbumsActivity.EXTRA_MODE, JellyfinRecentAlbumsActivity.MODE_RECENTLY_PLAYED)
                startActivity(intent)
                finish()
            }
            R.id.nav_jellyfin_recent_added -> {
                val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
                intent.putExtra(JellyfinRecentAlbumsActivity.EXTRA_MODE, JellyfinRecentAlbumsActivity.MODE_RECENTLY_ADDED)
                startActivity(intent)
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
