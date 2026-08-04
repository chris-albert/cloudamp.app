package com.cloudamp.music

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.api.GDriveAlbum
import com.cloudamp.music.auth.GoogleDriveAuthManager
import com.cloudamp.music.cache.FavoritesCore
import com.cloudamp.music.cache.FavoritesRepository
import com.cloudamp.music.cache.GDriveLibraryCache
import com.cloudamp.music.cache.GDrivePlaybackHistory
import com.cloudamp.music.cache.LibraryScanManager
import com.cloudamp.music.playback.CloudAmpService
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.ui.GDriveHomeAdapter
import com.cloudamp.music.ui.MiniPlayerBar
import com.google.android.material.navigation.NavigationView
import com.google.firebase.appdistribution.FirebaseAppDistribution
import kotlinx.coroutines.*

class GDriveHomeActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var libraryCache: GDriveLibraryCache
    private lateinit var authManager: GoogleDriveAuthManager
    private lateinit var favoritesRepository: FavoritesRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var recentPlayedAdapter: GDriveHomeAdapter
    private lateinit var recentAddedAdapter: GDriveHomeAdapter
    private lateinit var favoritesAdapter: GDriveHomeAdapter
    private lateinit var discoverAdapter: GDriveHomeAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var contentScrollView: View
    private lateinit var emptyContainer: LinearLayout
    private lateinit var emptyTextView: TextView
    private lateinit var settingsButton: Button
    private var allAlbumsCache: List<GDriveAlbum> = emptyList()
    private lateinit var miniPlayerBar: MiniPlayerBar
    private lateinit var appliedThemeId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeId = ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gdrive_home)

        libraryCache = GDriveLibraryCache.getInstance(this)
        authManager = GoogleDriveAuthManager(this)
        favoritesRepository = FavoritesRepository.getInstance(this)

        FirebaseAppDistribution.getInstance().updateIfNewReleaseAvailable()

        setupDrawer()
        setupRecyclerViews()
        setupEmptyState()

        miniPlayerBar = MiniPlayerBar(this, scope)
        miniPlayerBar.attach()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.recreateIfThemeChanged(this, appliedThemeId)
        LibraryScanManager.resumePrefetchIfNeeded(this)
        checkAuthAndLoad()
        miniPlayerBar.startUpdates()
    }

    override fun onPause() {
        miniPlayerBar.stopUpdates()
        super.onPause()
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
        favoritesAdapter = setupHorizontalRecyclerView(findViewById(R.id.favoritesRecyclerView))

        val discoverRv = findViewById<RecyclerView>(R.id.discoverRecyclerView)
        discoverRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        discoverAdapter = GDriveHomeAdapter(
            onAlbumClick = { album -> playAlbum(album) },
            onShuffleClick = { reshuffleDiscover() }
        )
        discoverRv.adapter = discoverAdapter
    }

    private fun reshuffleDiscover() {
        if (allAlbumsCache.isNotEmpty()) {
            val discover = allAlbumsCache.shuffled().take(9)
            discoverAdapter.setAlbums(discover, showShuffleButton = true)
        }
    }

    private fun setupEmptyState() {
        contentScrollView = findViewById(R.id.contentScrollView)
        emptyContainer = findViewById(R.id.emptyContainer)
        emptyTextView = findViewById(R.id.emptyTextView)
        settingsButton = findViewById(R.id.settingsButton)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun checkAuthAndLoad() {
        if (!authManager.hasAccessToken()) {
            showEmptyState("Connect Google Drive in Settings\nto browse your music library")
        } else if (libraryCache.getRootFolderId() == null) {
            showEmptyState("Configure your music folder\nin Settings to get started")
        } else if (!libraryCache.hasFullCache()) {
            showEmptyState("Scan your music library\nin Settings to get started")
        } else {
            hideEmptyState()
            loadContent()
        }
    }

    private fun showEmptyState(message: String) {
        emptyContainer.visibility = View.VISIBLE
        emptyTextView.text = message
        contentScrollView.visibility = View.GONE
    }

    private fun hideEmptyState() {
        emptyContainer.visibility = View.GONE
        contentScrollView.visibility = View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadContent() {

        showLoading(true)
        scope.launch {
            try {
                val allAlbums = withContext(Dispatchers.IO) {
                    val artists = libraryCache.getArtists() ?: emptyList()
                    artists.flatMap { artist ->
                        libraryCache.getArtistAlbums(artist.id) ?: emptyList()
                    }.filter { it.trackCount > 0 }
                }
                val albumById = allAlbums.associateBy { it.id }

                // Recently Played: from NDJSON history (falls back to SharedPreferences)
                val recentlyPlayed = withContext(Dispatchers.IO) {
                    val history = GDrivePlaybackHistory.getInstance(this@GDriveHomeActivity)
                    val historyAlbumIds = history.getRecentlyPlayedAlbumIds(10)
                    // Use history IDs if available, else fall back to cache
                    val albumIds = historyAlbumIds.ifEmpty {
                        libraryCache.getRecentlyPlayedIds().take(10)
                    }
                    // Resolve IDs to album objects
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

                // Favorites: most recently favorited first, orphans hidden
                // (ADR-0001), capped at 10. A Drive failure just leaves the
                // row hidden; the next resume retries.
                val favoriteEntries = withContext(Dispatchers.IO) {
                    try {
                        favoritesRepository.load()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                val favoriteAlbums = FavoritesCore
                    .resolveFavoriteAlbums(favoriteEntries, albumById)
                    .take(10)
                val favoritesVisibility = if (favoriteAlbums.isEmpty()) View.GONE else View.VISIBLE
                favoritesAdapter.setAlbums(favoriteAlbums)
                findViewById<View>(R.id.favoritesHeader).visibility = favoritesVisibility
                findViewById<View>(R.id.favoritesRecyclerView).visibility = favoritesVisibility

                // Discover: random 9 albums + shuffle button
                allAlbumsCache = allAlbums
                val discover = allAlbums.shuffled().take(9)
                discoverAdapter.setAlbums(discover, showShuffleButton = true)


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
            R.id.nav_cached -> {
                startActivity(Intent(this, CachedAlbumsActivity::class.java))
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
