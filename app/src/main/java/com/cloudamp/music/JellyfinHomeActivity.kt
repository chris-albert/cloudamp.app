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

    private lateinit var recentPlayedAdapter: JellyfinHomeAdapter
    private lateinit var mostPlayedAdapter: JellyfinHomeAdapter
    private lateinit var recentAddedAdapter: JellyfinHomeAdapter
    private lateinit var discoverAdapter: JellyfinHomeAdapter
    private lateinit var loadingContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin_home)

        jellyfinClient = JellyfinApiClient.getInstance(this)
        authManager = JellyfinAuthManager(this)

        setupDrawer()
        setupRecyclerViews()
        loadContent()
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

    private fun setupHorizontalRecyclerView(
        recyclerView: RecyclerView,
        moreMode: String? = null
    ): JellyfinHomeAdapter {
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        val serverUrl = authManager.getServerUrl()?.trimEnd('/') ?: ""
        val apiKey = authManager.getApiKey()

        val adapter = JellyfinHomeAdapter(
            serverUrl = serverUrl,
            apiKey = apiKey,
            onAlbumClick = { album -> playAlbum(album) },
            onMoreClick = if (moreMode != null) {
                {
                    val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
                    intent.putExtra(JellyfinRecentAlbumsActivity.EXTRA_MODE, moreMode)
                    startActivity(intent)
                }
            } else {
                { }
            }
        )

        recyclerView.adapter = adapter
        return adapter
    }

    private fun setupRecyclerViews() {
        loadingContainer = findViewById(R.id.loadingContainer)

        recentPlayedAdapter = setupHorizontalRecyclerView(
            findViewById(R.id.recentPlayedRecyclerView),
            JellyfinRecentAlbumsActivity.MODE_RECENTLY_PLAYED
        )
        mostPlayedAdapter = setupHorizontalRecyclerView(
            findViewById(R.id.mostPlayedRecyclerView)
        )
        recentAddedAdapter = setupHorizontalRecyclerView(
            findViewById(R.id.recentAddedRecyclerView),
            JellyfinRecentAlbumsActivity.MODE_RECENTLY_ADDED
        )
        discoverAdapter = setupHorizontalRecyclerView(
            findViewById(R.id.discoverRecyclerView)
        )
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadContent() {
        showLoading(true)
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch

                // Load all sections in parallel
                val playedDeferred = async { loadRecentlyPlayed(userId) }
                val mostPlayedDeferred = async { loadMostPlayed(userId) }
                val addedDeferred = async { loadRecentlyAdded(userId) }
                val discoverDeferred = async { loadDiscover(userId) }

                recentPlayedAdapter.setAlbums(playedDeferred.await())
                mostPlayedAdapter.setAlbums(mostPlayedDeferred.await())
                recentAddedAdapter.setAlbums(addedDeferred.await())
                discoverAdapter.setAlbums(discoverDeferred.await())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@JellyfinHomeActivity,
                    "Error loading content: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun loadRecentlyPlayed(userId: String): List<JellyfinItem> {
        val response = jellyfinClient.api.getRecentlyPlayedTracks(userId)
        if (!response.isSuccessful) return emptyList()
        val tracks = response.body()?.Items ?: emptyList()

        val seenAlbumIds = mutableSetOf<String>()
        return tracks.mapNotNull { track ->
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
    }

    private suspend fun loadMostPlayed(userId: String): List<JellyfinItem> {
        val response = jellyfinClient.api.getMostPlayedAlbums(userId)
        if (!response.isSuccessful) return emptyList()
        return (response.body()?.Items ?: emptyList()).take(10)
    }

    private suspend fun loadRecentlyAdded(userId: String): List<JellyfinItem> {
        val response = jellyfinClient.api.getRecentlyAddedAlbums(userId)
        if (!response.isSuccessful) return emptyList()
        return (response.body()?.Items ?: emptyList()).take(10)
    }

    private suspend fun loadDiscover(userId: String): List<JellyfinItem> {
        val response = jellyfinClient.api.getRandomAlbums(userId)
        if (!response.isSuccessful) return emptyList()
        return (response.body()?.Items ?: emptyList()).take(10)
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
