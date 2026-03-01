package com.cloudamp.music

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import com.cloudamp.music.ui.JellyfinRecentAlbumsAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class JellyfinRecentAlbumsActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_RECENTLY_PLAYED = "recently_played"
        const val MODE_RECENTLY_ADDED = "recently_added"
    }

    private lateinit var jellyfinClient: JellyfinApiClient
    private lateinit var authManager: JellyfinAuthManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var albumsAdapter: JellyfinRecentAlbumsAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var headerTextView: TextView

    private var mode: String = MODE_RECENTLY_PLAYED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin_recent_albums)

        jellyfinClient = JellyfinApiClient.getInstance(this)
        authManager = JellyfinAuthManager(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_RECENTLY_PLAYED

        setupDrawer()
        setupRecyclerView()

        headerTextView = findViewById(R.id.headerTextView)
        headerTextView.text = if (mode == MODE_RECENTLY_PLAYED) {
            "\u25b6 RECENTLY PLAYED"
        } else {
            "\u25b6 RECENTLY ADDED"
        }

        loadAlbums()
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
        if (mode == MODE_RECENTLY_PLAYED) {
            navigationView.setCheckedItem(R.id.nav_jellyfin_recent_played)
        } else {
            navigationView.setCheckedItem(R.id.nav_jellyfin_recent_added)
        }
    }

    private fun setupRecyclerView() {
        albumsRecyclerView = findViewById(R.id.albumsRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)
        albumsRecyclerView.layoutManager = LinearLayoutManager(this)

        val serverUrl = authManager.getServerUrl()?.trimEnd('/') ?: ""
        val apiKey = authManager.getApiKey()

        albumsAdapter = JellyfinRecentAlbumsAdapter(
            serverUrl = serverUrl,
            apiKey = apiKey,
            onAlbumClick = { album, position ->
                loadAlbumTracks(album, position)
            },
            onTrackClick = { track, allTracks, trackPosition ->
                playTrackWithQueue(track, allTracks, trackPosition)
            }
        )

        albumsRecyclerView.adapter = albumsAdapter
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadAlbums() {
        showLoading(true)
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch

                val albums: List<JellyfinItem>
                val lastPlayedTracks = mutableMapOf<String, String>()
                if (mode == MODE_RECENTLY_PLAYED) {
                    // Query recently played tracks, then deduplicate by album
                    val response = jellyfinClient.api.getRecentlyPlayedTracks(userId)
                    if (!response.isSuccessful) { handleApiError(response.code()); return@launch }
                    val tracks = response.body()?.Items ?: emptyList()
                    val seenAlbumIds = mutableSetOf<String>()
                    albums = tracks.mapNotNull { track ->
                        val albumId = track.AlbumId ?: return@mapNotNull null
                        if (!seenAlbumIds.add(albumId)) return@mapNotNull null
                        val albumName = track.Album ?: return@mapNotNull null
                        // Remember the most recently played track for this album
                        lastPlayedTracks[albumId] = track.Name
                        // Build a synthetic album item from the track's metadata
                        JellyfinItem(
                            Id = albumId,
                            Name = albumName,
                            Type = "MusicAlbum",
                            AlbumArtist = track.AlbumArtist,
                            Year = track.Year
                        )
                    }
                } else {
                    val response = jellyfinClient.api.getRecentlyAddedAlbums(userId)
                    if (!response.isSuccessful) { handleApiError(response.code()); return@launch }
                    albums = response.body()?.Items ?: emptyList()
                }

                albumsAdapter.setAlbums(albums, lastPlayedTracks)

                if (albums.isEmpty()) {
                    val label = if (mode == MODE_RECENTLY_PLAYED) "recently played" else "recently added"
                    Toast.makeText(
                        this@JellyfinRecentAlbumsActivity,
                        "No $label albums",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@JellyfinRecentAlbumsActivity,
                    "Error loading albums: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun loadAlbumTracks(album: JellyfinItem, position: Int) {
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val response = jellyfinClient.api.getAlbumTracks(userId, album.Id)

                if (response.isSuccessful) {
                    val tracks = response.body()?.Items ?: emptyList()
                    val lastPlayedPos = albumsAdapter.setAlbumTracks(position, tracks)
                    if (lastPlayedPos >= 0) {
                        albumsRecyclerView.post {
                            (albumsRecyclerView.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(lastPlayedPos, albumsRecyclerView.height / 3)
                        }
                    }
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@JellyfinRecentAlbumsActivity,
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
                startActivity(Intent(this, JellyfinPlaylistsActivity::class.java))
                finish()
            }
            R.id.nav_jellyfin_recent_played -> {
                if (mode != MODE_RECENTLY_PLAYED) {
                    val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
                    intent.putExtra(EXTRA_MODE, MODE_RECENTLY_PLAYED)
                    startActivity(intent)
                    finish()
                }
            }
            R.id.nav_jellyfin_recent_added -> {
                if (mode != MODE_RECENTLY_ADDED) {
                    val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
                    intent.putExtra(EXTRA_MODE, MODE_RECENTLY_ADDED)
                    startActivity(intent)
                    finish()
                }
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
