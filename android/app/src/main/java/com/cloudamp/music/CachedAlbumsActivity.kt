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
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.cache.GDriveLibraryCache
import com.cloudamp.music.cache.MediaCache
import com.cloudamp.music.playback.CloudAmpService
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.ui.CachedAlbumsAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class CachedAlbumsActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var libraryCache: GDriveLibraryCache
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CachedAlbumsAdapter
    private lateinit var emptyContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cached_albums)

        libraryCache = GDriveLibraryCache.getInstance(this)

        setupDrawer()
        setupRecyclerView()
        loadCachedAlbums()
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
        navigationView.setCheckedItem(R.id.nav_cached)
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.cachedAlbumsRecyclerView)
        emptyContainer = findViewById(R.id.emptyContainer)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = CachedAlbumsAdapter(
            onAlbumExpand = { album, position -> loadAlbumTracks(album, position) },
            onTrackClick = { track, albumTracks, trackPosition -> playTracks(albumTracks, trackPosition) }
        )
        recyclerView.adapter = adapter
    }

    private fun loadCachedAlbums() {
        if (!libraryCache.hasFullCache()) {
            emptyContainer.visibility = View.VISIBLE
            return
        }

        scope.launch {
            try {
                val cachedAlbums = withContext(Dispatchers.IO) {
                    val mediaCache = MediaCache.getInstance(this@CachedAlbumsActivity)
                    val cachedTracks = mediaCache.stats().tracks
                    if (cachedTracks.isEmpty()) {
                        emptyList()
                    } else {
                        val artists = libraryCache.getArtists() ?: emptyList()
                        val allAlbums = artists.flatMap { artist ->
                            libraryCache.getArtistAlbums(artist.id) ?: emptyList()
                        }.filter { it.trackCount > 0 }

                        val addedAtByFileId = cachedTracks.associate { it.fileId to it.addedAt }
                        allAlbums.mapNotNull { album ->
                            val tracks = libraryCache.getAlbumTracks(album.id) ?: emptyList()
                            val maxAddedAt = tracks
                                .mapNotNull { addedAtByFileId[it.file.id] }
                                .maxOrNull()
                            if (maxAddedAt != null) album to maxAddedAt else null
                        }.sortedByDescending { it.second }.map { it.first }
                    }
                }

                if (cachedAlbums.isEmpty()) {
                    emptyContainer.visibility = View.VISIBLE
                } else {
                    adapter.setAlbums(cachedAlbums)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@CachedAlbumsActivity,
                    "Error loading cached albums: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadAlbumTracks(album: GDriveAlbum, position: Int) {
        val tracks = libraryCache.getAlbumTracks(album.id) ?: emptyList()
        adapter.setAlbumTracks(position, tracks)
    }

    private fun playTracks(tracks: List<GDriveTrack>, startPosition: Int) {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No tracks found", Toast.LENGTH_SHORT).show()
            return
        }

        val gdrivePlayback = GDrivePlaybackManager.getInstance(this)
        CloudAmpService.ensureForeground(this)
        gdrivePlayback.playGDriveTracks(tracks, startPosition)

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
                startActivity(Intent(this, GDriveHomeActivity::class.java))
                finish()
            }
            R.id.nav_cached -> {
                // Already here
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
