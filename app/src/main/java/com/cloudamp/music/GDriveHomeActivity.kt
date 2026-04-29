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
import com.cloudamp.music.api.GDriveAlbum
import com.cloudamp.music.cache.GDriveLibraryCache
import com.cloudamp.music.cache.GDrivePlaybackHistory
import com.cloudamp.music.cache.LibraryScanManager
import com.cloudamp.music.playback.CloudAmpService
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.ui.GDriveHomeAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class GDriveHomeActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var libraryCache: GDriveLibraryCache
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var recentPlayedAdapter: GDriveHomeAdapter
    private lateinit var recentAddedAdapter: GDriveHomeAdapter
    private lateinit var discoverAdapter: GDriveHomeAdapter
    private lateinit var loadingContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gdrive_home)

        libraryCache = GDriveLibraryCache.getInstance(this)

        setupDrawer()
        setupRecyclerViews()
        loadContent()
    }

    override fun onResume() {
        super.onResume()
        LibraryScanManager.resumePrefetchIfNeeded(this)
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
        navigationView.setCheckedItem(R.id.nav_gdrive_home)
    }

    private fun setupHorizontalRecyclerView(recyclerView: RecyclerView): GDriveHomeAdapter {
        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val adapter = GDriveHomeAdapter(
            onAlbumClick = { album -> playAlbum(album) }
        )
        recyclerView.adapter = adapter
        return adapter
    }

    private fun setupRecyclerViews() {
        loadingContainer = findViewById(R.id.loadingContainer)
        recentPlayedAdapter = setupHorizontalRecyclerView(findViewById(R.id.recentPlayedRecyclerView))
        recentAddedAdapter = setupHorizontalRecyclerView(findViewById(R.id.recentAddedRecyclerView))
        discoverAdapter = setupHorizontalRecyclerView(findViewById(R.id.discoverRecyclerView))
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadContent() {
        if (!libraryCache.hasFullCache()) {
            // No cache available, nothing to show
            return
        }

        showLoading(true)
        scope.launch {
            try {
                val allAlbums = withContext(Dispatchers.IO) {
                    val artists = libraryCache.getArtists() ?: emptyList()
                    artists.flatMap { artist ->
                        libraryCache.getArtistAlbums(artist.id) ?: emptyList()
                    }.filter { it.trackCount > 0 }
                }

                // Recently Played: from NDJSON history (falls back to SharedPreferences)
                val recentlyPlayed = withContext(Dispatchers.IO) {
                    val history = GDrivePlaybackHistory.getInstance(this@GDriveHomeActivity)
                    val historyAlbumIds = history.getRecentlyPlayedAlbumIds(10)
                    // Use history IDs if available, else fall back to cache
                    val albumIds = historyAlbumIds.ifEmpty {
                        libraryCache.getRecentlyPlayedIds().take(10)
                    }
                    // Resolve IDs to album objects
                    val albumById = allAlbums.associateBy { it.id }
                    albumIds.mapNotNull { albumById[it] }
                }
                if (recentlyPlayed.isNotEmpty()) {
                    recentPlayedAdapter.setAlbums(recentlyPlayed)
                    findViewById<View>(R.id.recentPlayedHeader).visibility = View.VISIBLE
                    findViewById<View>(R.id.recentPlayedRecyclerView).visibility = View.VISIBLE
                }

                // Recently Added: sort by modifiedTime desc, take 10
                val recentlyAdded = allAlbums
                    .sortedByDescending { it.modifiedTime ?: "" }
                    .take(10)
                recentAddedAdapter.setAlbums(recentlyAdded)

                // Discover: random 10 albums
                val discover = allAlbums.shuffled().take(10)
                discoverAdapter.setAlbums(discover)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@GDriveHomeActivity,
                    "Error loading content: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun playAlbum(album: GDriveAlbum) {
        val tracks = libraryCache.getAlbumTracks(album.id)
        if (tracks.isNullOrEmpty()) {
            Toast.makeText(this, "No tracks found", Toast.LENGTH_SHORT).show()
            return
        }

        val gdrivePlayback = GDrivePlaybackManager.getInstance(this)
        CloudAmpService.ensureForeground(this)
        gdrivePlayback.playGDriveTracks(tracks, 0)

        Toast.makeText(this, "Playing: ${album.name}", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_gdrive_library -> {
                startActivity(Intent(this, GDriveLibraryActivity::class.java))
                finish()
            }
            R.id.nav_gdrive_music_library -> {
                startActivity(Intent(this, GDriveStructuredLibraryActivity::class.java))
                finish()
            }
            R.id.nav_gdrive_home -> {
                // Already here
            }
            R.id.nav_saved_queues -> {
                startActivity(Intent(this, SavedQueuesActivity::class.java))
                finish()
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
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
