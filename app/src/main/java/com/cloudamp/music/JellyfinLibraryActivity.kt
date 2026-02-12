package com.cloudamp.music

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
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
import com.cloudamp.music.cache.JellyfinLibraryCache
import com.cloudamp.music.playback.JellyfinPlaybackManager
import com.cloudamp.music.ui.AlphabetSidebarView
import com.cloudamp.music.ui.JellyfinLibraryAdapter
import com.cloudamp.music.ui.JellyfinLibraryItem
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class JellyfinLibraryActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var jellyfinClient: JellyfinApiClient
    private lateinit var authManager: JellyfinAuthManager
    private lateinit var libraryCache: JellyfinLibraryCache
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
    private lateinit var alphabetSidebar: AlphabetSidebarView

    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchCloseButton: ImageView
    private var isSearchVisible = false

    private var hasLoadedContent = false

    // Playlist navigation: track if we're viewing a playlist's tracks
    private var currentPlaylist: JellyfinItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin_library)

        jellyfinClient = JellyfinApiClient.getInstance(this)
        authManager = JellyfinAuthManager(this)
        libraryCache = JellyfinLibraryCache.getInstance(this)

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
        recyclerView.addItemDecoration(LibraryDividerDecoration(this))

        val serverUrl = authManager.getServerUrl()?.trimEnd('/') ?: ""

        adapter = JellyfinLibraryAdapter(
            serverUrl = serverUrl,
            onArtistExpand = { artist, position ->
                loadArtistAlbums(artist, position)
            },
            onAlbumExpand = { album, artistId, position ->
                loadAlbumTracks(album, position)
            },
            onTrackClick = { track, allTracks, position ->
                onTrackClicked(track, allTracks, position)
            },
            onPlaylistClick = { playlist -> navigateToPlaylist(playlist) }
        )

        recyclerView.adapter = adapter

        alphabetSidebar = findViewById(R.id.alphabetSidebar)
        alphabetSidebar.listener = object : AlphabetSidebarView.OnLetterSelectedListener {
            override fun onLetterSelected(letter: String) {
                val position = adapter.getLetterPosition(letter)
                if (position >= 0) {
                    (recyclerView.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(position, 0)
                }
            }
        }
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
        SettingsActivity.onJellyfinLibraryReloadRequested = {
            reloadLibrary()
        }
        checkAuthAndLoad()
    }

    private fun checkAuthAndLoad() {
        if (!authManager.isConfigured()) {
            showEmptyState("Connect Jellyfin in Settings\nto browse your music library")
        } else {
            hideEmptyState()
            if (!hasLoadedContent && currentPlaylist == null) {
                loadMyLibrary()
            }
        }
    }

    private fun loadMyLibrary() {
        // Try to load from cache first
        if (libraryCache.hasCache()) {
            val cachedArtists = libraryCache.getArtists()

            if (cachedArtists != null && cachedArtists.isNotEmpty()) {
                adapter.setArtists(cachedArtists, emptyList())
                showAlphabetSidebar(true)
                hasLoadedContent = true
                preloadAlbumsForArtistsWithoutImages()
                return
            }
        }

        // No cache, load from API
        loadRoot()
    }

    fun reloadLibrary() {
        libraryCache.clearCache()
        hasLoadedContent = false
        adapter.setArtists(emptyList(), emptyList())
        showAlphabetSidebar(false)
        loadRoot()
    }

    private fun showEmptyState(message: String) {
        emptyContainer.visibility = View.VISIBLE
        emptyTextView.text = message
        recyclerView.visibility = View.GONE
        showAlphabetSidebar(false)
    }

    private fun hideEmptyState() {
        emptyContainer.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showAlphabetSidebar(show: Boolean) {
        alphabetSidebar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updatePath() {
        if (currentPlaylist != null) {
            pathTextView.text = "JELLYFIN / ${currentPlaylist!!.Name}"
        } else {
            pathTextView.text = "JELLYFIN"
        }
    }

    // ── Data Loading ────────────────────────────────────────────────────

    private fun loadRoot() {
        currentPlaylist = null
        updatePath()
        showLoading(true)

        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch

                val response = jellyfinClient.api.getArtists(userId)

                val artists = if (response.isSuccessful) {
                    response.body()?.Items ?: emptyList()
                } else emptyList()

                // Save to cache
                libraryCache.saveArtists(artists)

                adapter.setArtists(artists, emptyList())
                hasLoadedContent = true
                showAlphabetSidebar(artists.isNotEmpty())
                preloadAlbumsForArtistsWithoutImages()

                if (artists.isEmpty()) {
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

    private fun preloadAlbumsForArtistsWithoutImages() {
        val artists = adapter.getArtistsWithoutImages()
        if (artists.isEmpty()) return
        scope.launch {
            val userId = authManager.getUserId() ?: return@launch
            for (artist in artists) {
                try {
                    val response = jellyfinClient.api.getArtistAlbums(userId, artist.Id)
                    if (response.isSuccessful) {
                        val albums = response.body()?.Items ?: emptyList()
                        adapter.preloadArtistAlbums(artist.Id, albums)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun loadArtistAlbums(artist: JellyfinItem, position: Int) {
        scope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val response = jellyfinClient.api.getArtistAlbums(userId, artist.Id)

                if (response.isSuccessful) {
                    val albums = response.body()?.Items ?: emptyList()
                    adapter.setArtistAlbums(position, albums)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@JellyfinLibraryActivity, "Error loading albums: ${e.message}", Toast.LENGTH_LONG).show()
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
                    adapter.setAlbumTracks(position, tracks)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@JellyfinLibraryActivity, "Error loading tracks: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Playlist Navigation ─────────────────────────────────────────────

    private fun navigateToPlaylist(playlist: JellyfinItem) {
        if (isSearchVisible) hideSearchBar()
        currentPlaylist = playlist
        updatePath()
        showAlphabetSidebar(false)
        loadPlaylistItems(playlist.Id)
    }

    private fun navigateBackFromPlaylist() {
        if (isSearchVisible) hideSearchBar()
        currentPlaylist = null
        hasLoadedContent = false
        loadRoot()
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
                    items.addAll(tracks.map { JellyfinLibraryItem.TrackItem(it, playlistId) })
                    adapter.setPlaylistTracks(items)
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

    // ── Track Playback ──────────────────────────────────────────────────

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
            R.id.nav_jellyfin_playlists -> {
                startActivity(Intent(this, JellyfinPlaylistsActivity::class.java))
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
        } else if (currentPlaylist != null) {
            navigateBackFromPlaylist()
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
                // Hide alphabet sidebar during search
                showAlphabetSidebar(s.isNullOrEmpty() && hasLoadedContent && currentPlaylist == null)
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
        showAlphabetSidebar(false)
    }

    private fun hideSearchBar() {
        isSearchVisible = false
        searchBarContainer.visibility = View.GONE
        searchEditText.setText("")
        adapter.clearFilter()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
        showAlphabetSidebar(hasLoadedContent && currentPlaylist == null)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Draws a thin divider line between content items (artists, albums, tracks, playlists)
     * but not after headers or footers.
     */
    private class LibraryDividerDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val paint = Paint().apply {
            color = context.getColor(R.color.winamp_section_header)
            strokeWidth = (context.resources.displayMetrics.density * 1f) // 1dp
        }

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter as? JellyfinLibraryAdapter ?: return
            val childCount = parent.childCount

            for (i in 0 until childCount - 1) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION || pos + 1 >= adapter.itemCount) continue

                val currentType = adapter.getItemViewType(pos)
                val nextType = adapter.getItemViewType(pos + 1)

                // Draw divider between two content items of the same type
                val contentTypes = setOf(1, 2, 3, 4) // ARTIST, ALBUM, TRACK, PLAYLIST
                if (currentType in contentTypes && nextType in contentTypes) {
                    val y = child.bottom.toFloat() + child.translationY
                    c.drawLine(
                        parent.paddingLeft.toFloat(),
                        y,
                        (parent.width - parent.paddingRight).toFloat(),
                        y,
                        paint
                    )
                }
            }
        }
    }
}
