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
import com.cloudamp.music.ui.JellyfinHomeAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class JellyfinHomeActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var jellyfinClient: JellyfinApiClient
    private lateinit var authManager: JellyfinAuthManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var recentAlbumsRecyclerView: RecyclerView
    private lateinit var homeAdapter: JellyfinHomeAdapter
    private lateinit var loadingContainer: LinearLayout

    // Keep album list for playback
    private val albumsList = mutableListOf<JellyfinItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin_home)

        jellyfinClient = JellyfinApiClient.getInstance(this)
        authManager = JellyfinAuthManager(this)

        setupDrawer()
        setupRecyclerView()
        setupLinks()
        loadRecentAlbums()
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
        navigationView.setCheckedItem(R.id.nav_jellyfin_home)
    }

    private fun setupRecyclerView() {
        recentAlbumsRecyclerView = findViewById(R.id.recentAlbumsRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)

        recentAlbumsRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        val serverUrl = authManager.getServerUrl()?.trimEnd('/') ?: ""
        val apiKey = authManager.getApiKey()

        homeAdapter = JellyfinHomeAdapter(
            serverUrl = serverUrl,
            apiKey = apiKey,
            onAlbumClick = { album -> playAlbum(album) },
            onMoreClick = {
                val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
                intent.putExtra(JellyfinRecentAlbumsActivity.EXTRA_MODE, JellyfinRecentAlbumsActivity.MODE_RECENTLY_PLAYED)
                startActivity(intent)
            }
        )

        recentAlbumsRecyclerView.adapter = homeAdapter
    }

    private fun setupLinks() {
        findViewById<TextView>(R.id.linkLibrary).setOnClickListener {
            startActivity(Intent(this, JellyfinLibraryActivity::class.java))
        }
        findViewById<TextView>(R.id.linkPlaylists).setOnClickListener {
            startActivity(Intent(this, JellyfinPlaylistsActivity::class.java))
        }
        findViewById<TextView>(R.id.linkRecentAdded).setOnClickListener {
            val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
            intent.putExtra(JellyfinRecentAlbumsActivity.EXTRA_MODE, JellyfinRecentAlbumsActivity.MODE_RECENTLY_ADDED)
            startActivity(intent)
        }
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadRecentAlbums() {
        showLoading(true)
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch

                val response = jellyfinClient.api.getRecentlyPlayedTracks(userId)
                if (!response.isSuccessful) { handleApiError(response.code()); return@launch }
                val tracks = response.body()?.Items ?: emptyList()

                val seenAlbumIds = mutableSetOf<String>()
                val albums = tracks.mapNotNull { track ->
                    val albumId = track.AlbumId ?: return@mapNotNull null
                    if (!seenAlbumIds.add(albumId)) return@mapNotNull null
                    val albumName = track.Album ?: return@mapNotNull null
                    JellyfinItem(
                        Id = albumId,
                        Name = albumName,
                        Type = "MusicAlbum",
                        AlbumArtist = track.AlbumArtist,
                        Year = track.Year
                    )
                }.take(10)

                albumsList.clear()
                albumsList.addAll(albums)
                homeAdapter.setAlbums(albums)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@JellyfinHomeActivity,
                    "Error loading recent albums: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun playAlbum(album: JellyfinItem) {
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val response = jellyfinClient.api.getAlbumTracks(userId, album.Id)

                if (response.isSuccessful) {
                    val tracks = response.body()?.Items ?: emptyList()
                    if (tracks.isNotEmpty()) {
                        val jellyfinPlayback = JellyfinPlaybackManager.getInstance(this@JellyfinHomeActivity)
                        CloudAmpService.ensureForeground(this@JellyfinHomeActivity)
                        jellyfinPlayback.playItems(tracks, 0)
                        Toast.makeText(this@JellyfinHomeActivity, "Playing: ${album.Name}", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@JellyfinHomeActivity, NowPlayingActivity::class.java))
                    }
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@JellyfinHomeActivity,
                    "Error loading tracks: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
            R.id.nav_library -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("from_nav", true)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
            R.id.nav_playlists -> {
                startActivity(Intent(this, PlaylistsActivity::class.java))
                finish()
            }
            R.id.nav_gdrive_library -> {
                startActivity(Intent(this, GDriveLibraryActivity::class.java))
                finish()
            }
            R.id.nav_jellyfin_home -> {
                // Already here
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
