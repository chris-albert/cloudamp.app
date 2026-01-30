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
import com.cloudamp.music.models.Album
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Image
import com.cloudamp.music.models.Track
import com.cloudamp.music.playback.JellyfinPlaybackManager
import com.cloudamp.music.ui.AlphabetSidebarView
import com.cloudamp.music.ui.ExpandableLibraryAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class JellyfinLibraryActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var jellyfinClient: JellyfinApiClient
    private lateinit var authManager: JellyfinAuthManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var libraryRecyclerView: RecyclerView
    private lateinit var libraryAdapter: ExpandableLibraryAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var emptyContainer: LinearLayout
    private lateinit var emptyTextView: TextView
    private lateinit var headerTextView: TextView
    private lateinit var settingsButton: Button
    private lateinit var alphabetSidebar: AlphabetSidebarView

    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchCloseButton: ImageView
    private var isSearchVisible = false

    // Jellyfin music library ID (discovered from /Views)
    private var musicLibraryId: String? = null

    private var hasLoadedContent = false

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
        libraryRecyclerView = findViewById(R.id.libraryRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)
        headerTextView = findViewById(R.id.headerTextView)
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
        if (!authManager.hasAccessToken()) {
            showEmptyState("Connect Jellyfin in Settings\nto browse your music library")
        } else {
            hideEmptyState()
            if (!hasLoadedContent) {
                loadLibrary()
            }
        }
    }

    private fun showEmptyState(message: String) {
        emptyContainer.visibility = View.VISIBLE
        emptyTextView.text = message
        libraryRecyclerView.visibility = View.GONE
    }

    private fun hideEmptyState() {
        emptyContainer.visibility = View.GONE
        libraryRecyclerView.visibility = View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showAlphabetSidebar(show: Boolean) {
        alphabetSidebar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadLibrary() {
        showLoading(true)
        scope.launch {
            try {
                val api = jellyfinClient.api ?: run {
                    showEmptyState("Jellyfin server not configured")
                    return@launch
                }
                val userId = jellyfinClient.getUserId() ?: run {
                    showEmptyState("Not logged in to Jellyfin")
                    return@launch
                }

                // First, find the music library
                if (musicLibraryId == null) {
                    val viewsResponse = api.getViews(userId)
                    if (viewsResponse.isSuccessful) {
                        val views = viewsResponse.body()?.Items ?: emptyList()
                        val musicView = views.firstOrNull {
                            it.CollectionType?.equals("music", ignoreCase = true) == true
                        }
                        musicLibraryId = musicView?.Id
                    }
                }

                val libId = musicLibraryId
                if (libId == null) {
                    showEmptyState("No music library found on this Jellyfin server")
                    return@launch
                }

                // Load all artists from the music library
                val artistsResponse = api.getItems(
                    userId = userId,
                    parentId = libId,
                    includeItemTypes = "MusicArtist",
                    sortBy = "SortName",
                    sortOrder = "Ascending"
                )

                if (artistsResponse.isSuccessful) {
                    val jellyfinArtists = artistsResponse.body()?.Items ?: emptyList()
                    val artists = jellyfinArtists.map { item ->
                        jellyfinItemToArtist(item)
                    }

                    libraryAdapter.setSavedAlbumIds(emptySet())
                    libraryAdapter.setArtists(artists)
                    showAlphabetSidebar(artists.isNotEmpty())
                    hasLoadedContent = true

                    headerTextView.text = "JELLYFIN / Artists (${artists.size})"

                    if (artists.isEmpty()) {
                        showEmptyState("No artists found in your Jellyfin music library")
                    }
                } else {
                    handleApiError(artistsResponse.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@JellyfinLibraryActivity,
                    "Error loading library: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun loadArtistAlbums(artist: Artist, position: Int) {
        scope.launch {
            try {
                val api = jellyfinClient.api ?: return@launch
                val userId = jellyfinClient.getUserId() ?: return@launch

                val response = api.getItems(
                    userId = userId,
                    includeItemTypes = "MusicAlbum",
                    artistIds = artist.id,
                    sortBy = "ProductionYear,SortName",
                    sortOrder = "Descending"
                )

                if (response.isSuccessful) {
                    val jellyfinAlbums = response.body()?.Items ?: emptyList()
                    val albums = jellyfinAlbums.map { item ->
                        jellyfinItemToAlbum(item)
                    }
                    libraryAdapter.setArtistAlbums(position, albums)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@JellyfinLibraryActivity, "Error loading albums: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAlbumTracks(album: Album, position: Int) {
        scope.launch {
            try {
                val api = jellyfinClient.api ?: return@launch
                val userId = jellyfinClient.getUserId() ?: return@launch

                val response = api.getItems(
                    userId = userId,
                    parentId = album.id,
                    includeItemTypes = "Audio",
                    sortBy = "ParentIndexNumber,IndexNumber,SortName",
                    sortOrder = "Ascending"
                )

                if (response.isSuccessful) {
                    val jellyfinTracks = response.body()?.Items ?: emptyList()
                    val tracks = jellyfinTracks.map { item ->
                        jellyfinItemToTrack(item, album)
                    }
                    libraryAdapter.setAlbumTracks(position, tracks)
                } else {
                    handleApiError(response.code())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@JellyfinLibraryActivity, "Error loading tracks: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playTrackWithQueue(track: Track, allTracks: List<Track>, trackPosition: Int) {
        val jellyfinPlayback = JellyfinPlaybackManager.getInstance(this)

        // Convert Track models back to JellyfinItem for the playback manager queue
        val items = allTracks.map { t ->
            JellyfinItem(
                Id = t.id,
                Name = t.name,
                Artists = t.artists.map { it.name },
                ArtistItems = t.artists.map { com.cloudamp.music.api.JellyfinNameId(Name = it.name, Id = it.id) },
                Album = t.album?.name,
                AlbumId = t.album?.id,
                AlbumArtist = t.artists.firstOrNull()?.name,
                RunTimeTicks = t.durationMs.toLong() * 10_000,
                IndexNumber = t.trackNumber,
                Type = "Audio"
            )
        }

        val albumArtUrl = track.album?.images?.firstOrNull()?.url
        val tracksToPlay = items.drop(trackPosition)
        jellyfinPlayback.playItems(tracksToPlay, 0, albumArtUrl)

        Toast.makeText(
            this,
            "Playing: ${track.name} (+${tracksToPlay.size - 1} in queue)",
            Toast.LENGTH_SHORT
        ).show()

        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    // ── Jellyfin → Model Conversion ─────────────────────────────────

    private fun jellyfinItemToArtist(item: JellyfinItem): Artist {
        val imageUrl = if (item.hasPrimaryImage()) {
            jellyfinClient.getImageUrl(item.Id)
        } else null

        return Artist(
            id = item.Id,
            name = item.Name,
            images = imageUrl?.let { listOf(Image(url = it, height = 300, width = 300)) },
            uri = "jellyfin:artist:${item.Id}"
        )
    }

    private fun jellyfinItemToAlbum(item: JellyfinItem): Album {
        val imageUrl = if (item.hasPrimaryImage()) {
            jellyfinClient.getImageUrl(item.Id)
        } else null

        val artistName = item.AlbumArtist
            ?: item.ArtistItems?.firstOrNull()?.Name
            ?: "Unknown Artist"
        val artistId = item.ArtistItems?.firstOrNull()?.Id ?: "unknown"

        return Album(
            id = item.Id,
            name = item.Name,
            artists = listOf(Artist(id = artistId, name = artistName, uri = "jellyfin:artist:$artistId")),
            images = imageUrl?.let { listOf(Image(url = it, height = 300, width = 300)) },
            uri = "jellyfin:album:${item.Id}",
            releaseDate = item.ProductionYear?.toString(),
            totalTracks = item.ChildCount
        )
    }

    private fun jellyfinItemToTrack(item: JellyfinItem, album: Album): Track {
        val artistName = item.ArtistItems?.firstOrNull()?.Name
            ?: item.Artists?.firstOrNull()
            ?: item.AlbumArtist
            ?: "Unknown Artist"
        val artistId = item.ArtistItems?.firstOrNull()?.Id ?: "unknown"

        return Track(
            id = item.Id,
            name = item.Name,
            artists = listOf(Artist(id = artistId, name = artistName, uri = "jellyfin:artist:$artistId")),
            album = album,
            uri = "jellyfin:track:${item.Id}",
            durationMs = item.getRunTimeMs(),
            trackNumber = item.IndexNumber
        )
    }

    // ── Error handling ──────────────────────────────────────────────

    private fun handleApiError(code: Int) {
        val errorMsg = when (code) {
            401 -> "Jellyfin session expired. Please re-login in Settings."
            403 -> "Insufficient permissions."
            404 -> "Content not found."
            else -> "Error loading content (code: $code)"
        }
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
    }

    // ── Navigation ──────────────────────────────────────────────────

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
        } else {
            super.onBackPressed()
        }
    }

    // ── Search ──────────────────────────────────────────────────────

    private fun setupSearchBar() {
        searchBarContainer = findViewById(R.id.searchBarContainer)
        searchEditText = findViewById(R.id.searchEditText)
        searchCloseButton = findViewById(R.id.searchCloseButton)

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                libraryAdapter.filterArtists(s?.toString() ?: "")
                showAlphabetSidebar(s.isNullOrEmpty() && hasLoadedContent)
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
