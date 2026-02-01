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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.cache.LibraryCache
import com.cloudamp.music.models.Album
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Track
import com.cloudamp.music.models.SimplifiedTrack
import com.cloudamp.music.playback.PlaybackManager
import com.cloudamp.music.ui.AlphabetSidebarView
import com.cloudamp.music.ui.ExpandableLibraryAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private lateinit var libraryCache: LibraryCache
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var libraryRecyclerView: RecyclerView
    private lateinit var libraryAdapter: ExpandableLibraryAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var alphabetSidebar: AlphabetSidebarView

    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchCloseButton: ImageView
    private var isSearchVisible = false

    private var hasLoadedContent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)
        libraryCache = LibraryCache.getInstance(this)

        setupDrawer()
        setupRecyclerView()
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
        navigationView.setCheckedItem(R.id.nav_library)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_library -> {
                // Already here
            }
            R.id.nav_playlists -> {
                startActivity(Intent(this, PlaylistsActivity::class.java))
            }
            R.id.nav_gdrive_library -> {
                startActivity(Intent(this, GDriveLibraryActivity::class.java))
            }
            R.id.nav_saved_queues -> {
                startActivity(Intent(this, SavedQueuesActivity::class.java))
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onResume() {
        super.onResume()

        // Register reload callback
        SettingsActivity.onLibraryReloadRequested = {
            reloadLibrary()
        }

        checkSpotifyToken()
    }

    private fun setupRecyclerView() {
        libraryRecyclerView = findViewById(R.id.libraryRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)
        libraryRecyclerView.layoutManager = LinearLayoutManager(this)

        libraryAdapter = ExpandableLibraryAdapter(
            onArtistClick = { artist, position ->
                loadArtistAlbums(artist, position)
            },
            onAlbumClick = { album, artistId, position ->
                loadAlbumTracks(album, position)
            },
            onTrackClick = { track, allTracks, trackPosition ->
                playTrackWithQueue(track, allTracks, trackPosition)
            }
        )

        libraryRecyclerView.adapter = libraryAdapter

        alphabetSidebar = findViewById(R.id.alphabetSidebar)
        alphabetSidebar.listener = object : AlphabetSidebarView.OnLetterSelectedListener {
            override fun onLetterSelected(letter: String) {
                val position = libraryAdapter.getLetterPosition(letter)
                if (position >= 0) {
                    (libraryRecyclerView.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(position, 0)
                }
            }
        }
    }

    private fun checkSpotifyToken() {
        if (!spotifyClient.hasAccessToken()) {
            if (hasLoadedContent) {
                libraryAdapter.setArtists(emptyList())
                showAlphabetSidebar(false)
                hasLoadedContent = false
            }
            Toast.makeText(this, "Please set your Spotify credentials in Settings", Toast.LENGTH_LONG).show()
        } else {
            if (!hasLoadedContent) {
                loadMyLibrary()
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showAlphabetSidebar(show: Boolean) {
        alphabetSidebar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadMyLibrary() {
        // Try to load from cache first
        if (libraryCache.hasCache()) {
            val cachedArtists = libraryCache.getArtists()
            val cachedSavedAlbumIds = libraryCache.getSavedAlbumIds()

            if (cachedArtists != null && cachedArtists.isNotEmpty()) {
                libraryAdapter.setSavedAlbumIds(cachedSavedAlbumIds)
                libraryAdapter.setArtists(cachedArtists)
                showAlphabetSidebar(true)
                hasLoadedContent = true
                return
            }
        }

        // No cache, load from API
        loadLibraryFromApi()
    }

    fun reloadLibrary() {
        libraryCache.clearCache()
        hasLoadedContent = false
        libraryAdapter.setArtists(emptyList())
        showAlphabetSidebar(false)
        loadLibraryFromApi()
    }

    private fun loadLibraryFromApi() {
        showLoading(true)
        scope.launch {
            try {
                // Load saved albums and all top artists in parallel
                val savedAlbumsDeferred = async(SupervisorJob()) {
                    try {
                        loadSavedAlbumIds()
                    } catch (e: Exception) {
                        emptySet()
                    }
                }

                // Load all followed artists (paginated)
                val allArtists = loadAllFollowedArtists()

                if (allArtists != null) {
                    val artists = allArtists.sortedBy { it.name }

                    // Set saved album IDs for categorization (wait for it now)
                    val savedAlbumIds = savedAlbumsDeferred.await()

                    // Save to cache
                    libraryCache.saveArtists(artists)
                    libraryCache.saveSavedAlbumIds(savedAlbumIds)

                    libraryAdapter.setSavedAlbumIds(savedAlbumIds)
                    libraryAdapter.setArtists(artists)
                    showAlphabetSidebar(artists.isNotEmpty())
                    hasLoadedContent = true

                    if (artists.isEmpty()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Your library is empty. Use Search to find music!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = when {
                    e.message?.contains("401") == true -> "Token expired. Please re-login in Settings."
                    e.message?.contains("403") == true -> "Insufficient permissions. Please re-login in Settings."
                    else -> "Error loading library: ${e.message}"
                }
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun loadAllFollowedArtists(): List<Artist>? {
        val allArtists = mutableListOf<Artist>()
        var after: String? = null

        do {
            val response = spotifyClient.api.getFollowedArtists(
                limit = 50,
                after = after
            )
            if (response.isSuccessful) {
                val artistsPage = response.body()?.artists
                val artists = artistsPage?.items ?: emptyList()
                allArtists.addAll(artists)
                after = artistsPage?.cursors?.after
            } else {
                handleApiError(response.code())
                return if (allArtists.isNotEmpty()) allArtists else null
            }
        } while (after != null)

        return allArtists
    }

    private suspend fun loadSavedAlbumIds(): Set<String> {
        val savedIds = mutableSetOf<String>()
        try {
            var offset = 0
            val limit = 50
            do {
                val response = spotifyClient.api.getMySavedAlbums(limit = limit, offset = offset)
                if (response.isSuccessful) {
                    val albums = response.body()?.items ?: emptyList()
                    savedIds.addAll(albums.map { it.album.id })
                    offset += limit
                    if (albums.size < limit) break
                } else {
                    break
                }
            } while (true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return savedIds
    }

    private fun loadArtistAlbums(artist: Artist, position: Int) {
        scope.launch {
            try {
                val response = spotifyClient.api.getArtistAlbums(artist.id, limit = 50)
                if (response.isSuccessful) {
                    val albums = response.body()?.items ?: emptyList()
                    libraryAdapter.setArtistAlbums(position, albums)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error loading albums: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAlbumTracks(album: Album, position: Int) {
        scope.launch {
            try {
                val response = spotifyClient.api.getAlbumTracks(album.id)
                if (response.isSuccessful) {
                    // Convert SimplifiedTrack to Track by adding album info
                    val tracks = response.body()?.items?.map { simplifiedTrack ->
                        Track(
                            id = simplifiedTrack.id,
                            name = simplifiedTrack.name,
                            artists = simplifiedTrack.artists,
                            album = album,
                            uri = simplifiedTrack.uri,
                            durationMs = simplifiedTrack.durationMs,
                            trackNumber = simplifiedTrack.trackNumber
                        )
                    } ?: emptyList()
                    libraryAdapter.setAlbumTracks(position, tracks)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error loading tracks: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playTrackWithQueue(track: Track, allTracks: List<Track>, trackPosition: Int) {
        // Queue all tracks from the clicked track onwards
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
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

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if (isSearchVisible) {
            hideSearchBar()
        } else {
            super.onBackPressed()
        }
    }

    private fun setupSearchBar() {
        searchBarContainer = findViewById(R.id.searchBarContainer)
        searchEditText = findViewById(R.id.searchEditText)
        searchCloseButton = findViewById(R.id.searchCloseButton)

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                libraryAdapter.filterArtists(s?.toString() ?: "")
                // Hide alphabet sidebar during search
                showAlphabetSidebar(s.isNullOrEmpty() && hasLoadedContent)
            }
        })

        searchCloseButton.setOnClickListener {
            hideSearchBar()
        }
    }

    private fun toggleSearchBar() {
        if (isSearchVisible) {
            hideSearchBar()
        } else {
            showSearchBar()
        }
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
        libraryAdapter.clearFilter()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
        showAlphabetSidebar(hasLoadedContent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
