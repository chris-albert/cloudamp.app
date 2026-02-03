package com.cloudamp.music

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
import com.cloudamp.music.playback.JellyfinPlaybackManager
import com.cloudamp.music.ui.JellyfinLibraryAdapter
import com.cloudamp.music.ui.JellyfinLibraryItem
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class JellyfinLibraryActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var jellyfinClient: JellyfinApiClient
    private lateinit var authManager: JellyfinAuthManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: JellyfinLibraryAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var emptyContainer: LinearLayout
    private lateinit var emptyTextView: TextView
    private lateinit var pathTextView: TextView
    private lateinit var settingsButton: Button

    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchCloseButton: ImageView
    private var isSearchVisible = false

    // Navigation stack: pairs of (level, name) where level is "root", "artists", "artist_<id>", "album_<id>", etc.
    private val navStack = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin_library)

        jellyfinClient = JellyfinApiClient.getInstance(this)
        authManager = JellyfinAuthManager(this)

        setupDrawer()
        setupRecyclerView()
        setupEmptyState()
        setupSearchBar()
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
        navigationView.setCheckedItem(R.id.nav_jellyfin_library)
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.jellyfinRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)
        pathTextView = findViewById(R.id.pathTextView)

        recyclerView.layoutManager = LinearLayoutManager(this)

        val serverUrl = authManager.getServerUrl()?.trimEnd('/') ?: ""

        adapter = JellyfinLibraryAdapter(
            serverUrl = serverUrl,
            onArtistClick = { artist -> navigateToArtist(artist) },
            onAlbumClick = { album -> navigateToAlbum(album) },
            onTrackClick = { track, allTracks, position -> onTrackClicked(track, allTracks, position) },
            onPlaylistClick = { playlist -> navigateToPlaylist(playlist) },
            onBackClick = { navigateBack() }
        )

        recyclerView.adapter = adapter
    }

    private fun setupEmptyState() {
        emptyContainer = findViewById(R.id.emptyContainer)
        emptyTextView = findViewById(R.id.emptyTextView)
        settingsButton = findViewById(R.id.jellyfinSettingsButton)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        checkAuthAndLoad()
    }

    private fun checkAuthAndLoad() {
        if (!authManager.isConfigured()) {
            showEmptyState("Connect Jellyfin in Settings\nto browse your music library")
        } else {
            hideEmptyState()
            if (navStack.isEmpty()) {
                loadRoot()
            }
        }
    }

    private fun showEmptyState(message: String) {
        emptyContainer.visibility = View.VISIBLE
        emptyTextView.text = message
        recyclerView.visibility = View.GONE
    }

    private fun hideEmptyState() {
        emptyContainer.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updatePath() {
        if (navStack.isEmpty()) {
            pathTextView.text = "JELLYFIN"
        } else {
            val path = "JELLYFIN / " + navStack.joinToString(" > ") { it.second }
            pathTextView.text = path
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────

    private fun loadRoot() {
        navStack.clear()
        updatePath()
        showLoading(true)

        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch

                // Load artists and playlists in parallel
                val artistsDeferred = async {
                    jellyfinClient.api.getArtists(userId)
                }
                val playlistsDeferred = async {
                    jellyfinClient.api.getPlaylists(userId)
                }

                val artistsResponse = artistsDeferred.await()
                val playlistsResponse = playlistsDeferred.await()

                val items = mutableListOf<JellyfinLibraryItem>()

                // Add artists
                if (artistsResponse.isSuccessful) {
                    val artists = artistsResponse.body()?.Items ?: emptyList()
                    items.addAll(artists.map { JellyfinLibraryItem.ArtistItem(it) })
                }

                // Add playlists at the end
                if (playlistsResponse.isSuccessful) {
                    val playlists = playlistsResponse.body()?.Items ?: emptyList()
                    items.addAll(playlists.map { JellyfinLibraryItem.PlaylistItem(it) })
                }

                adapter.setItems(items)

                if (items.isEmpty()) {
                    showEmptyState("No music found in your Jellyfin library")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@JellyfinLibraryActivity, "Error loading library: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun navigateToArtist(artist: JellyfinItem) {
        if (isSearchVisible) hideSearchBar()
        navStack.add(Pair("artist_${artist.Id}", artist.Name))
        updatePath()
        loadArtistAlbums(artist.Id)
    }

    private fun navigateToAlbum(album: JellyfinItem) {
        if (isSearchVisible) hideSearchBar()
        navStack.add(Pair("album_${album.Id}", album.Name))
        updatePath()
        loadAlbumTracks(album.Id)
    }

    private fun navigateToPlaylist(playlist: JellyfinItem) {
        if (isSearchVisible) hideSearchBar()
        navStack.add(Pair("playlist_${playlist.Id}", playlist.Name))
        updatePath()
        loadPlaylistItems(playlist.Id)
    }

    private fun navigateBack() {
        if (isSearchVisible) hideSearchBar()
        if (navStack.isNotEmpty()) {
            navStack.removeAt(navStack.size - 1)
            updatePath()

            if (navStack.isEmpty()) {
                loadRoot()
            } else {
                val current = navStack.last()
                when {
                    current.first.startsWith("artist_") -> {
                        val artistId = current.first.removePrefix("artist_")
                        loadArtistAlbums(artistId)
                    }
                    current.first.startsWith("album_") -> {
                        val albumId = current.first.removePrefix("album_")
                        loadAlbumTracks(albumId)
                    }
                    current.first.startsWith("playlist_") -> {
                        val playlistId = current.first.removePrefix("playlist_")
                        loadPlaylistItems(playlistId)
                    }
                }
            }
        }
    }

    private fun loadArtistAlbums(artistId: String) {
        showLoading(true)
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val response = jellyfinClient.api.getArtistAlbums(userId, artistId)

                if (response.isSuccessful) {
                    val albums = response.body()?.Items ?: emptyList()
                    val items = mutableListOf<JellyfinLibraryItem>()
                    items.add(JellyfinLibraryItem.BackItem)
                    items.addAll(albums.map { JellyfinLibraryItem.AlbumItem(it) })
                    adapter.setItems(items)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@JellyfinLibraryActivity, "Error loading albums: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun loadAlbumTracks(albumId: String) {
        showLoading(true)
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val response = jellyfinClient.api.getAlbumTracks(userId, albumId)

                if (response.isSuccessful) {
                    val tracks = response.body()?.Items ?: emptyList()
                    val items = mutableListOf<JellyfinLibraryItem>()
                    items.add(JellyfinLibraryItem.BackItem)
                    items.addAll(tracks.map { JellyfinLibraryItem.TrackItem(it) })
                    adapter.setItems(items)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@JellyfinLibraryActivity, "Error loading tracks: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun loadPlaylistItems(playlistId: String) {
        showLoading(true)
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val response = jellyfinClient.api.getPlaylistItems(playlistId, userId)

                if (response.isSuccessful) {
                    val tracks = response.body()?.Items ?: emptyList()
                    val items = mutableListOf<JellyfinLibraryItem>()
                    items.add(JellyfinLibraryItem.BackItem)
                    items.addAll(tracks.map { JellyfinLibraryItem.TrackItem(it) })
                    adapter.setItems(items)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@JellyfinLibraryActivity, "Error loading playlist: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun onTrackClicked(track: JellyfinItem, allTracks: List<JellyfinItem>, position: Int) {
        val jellyfinPlayback = JellyfinPlaybackManager.getInstance(this)

        // Build queue from clicked position
        val queueItems = allTracks.subList(position, allTracks.size) +
                allTracks.subList(0, position)

        jellyfinPlayback.playItems(queueItems, 0)

        Toast.makeText(this, "Playing: ${track.Name}", Toast.LENGTH_SHORT).show()

        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun handleApiError(code: Int) {
        val errorMsg = when (code) {
            401 -> "Jellyfin authentication failed. Check your API key in Settings."
            403 -> "Insufficient permissions."
            404 -> "Content not found."
            else -> "Error loading content (code: $code)"
        }
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
    }

    // ── Navigation Drawer ───────────────────────────────────────────────

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_library -> {
                val intent = Intent(this, MainActivity::class.java)
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
            R.id.nav_jellyfin_library -> {
                // Already here
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
            R.id.action_search -> {
                toggleSearchBar()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (isSearchVisible) {
            hideSearchBar()
        } else if (navStack.isNotEmpty()) {
            navigateBack()
        } else {
            super.onBackPressed()
        }
    }

    // ── Search ──────────────────────────────────────────────────────────

    private fun setupSearchBar() {
        searchBarContainer = findViewById(R.id.searchBarContainer)
        searchEditText = findViewById(R.id.searchEditText)
        searchCloseButton = findViewById(R.id.searchCloseButton)

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filterItems(s?.toString() ?: "")
            }
        })

        searchCloseButton.setOnClickListener {
            hideSearchBar()
        }
    }

    private fun toggleSearchBar() {
        if (isSearchVisible) hideSearchBar() else showSearchBar()
    }

    private fun showSearchBar() {
        isSearchVisible = true
        searchBarContainer.visibility = View.VISIBLE
        searchEditText.setText("")
        searchEditText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchBar() {
        isSearchVisible = false
        searchBarContainer.visibility = View.GONE
        searchEditText.setText("")
        adapter.clearFilter()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
