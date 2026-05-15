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
import com.cloudamp.music.api.GDriveAlbum
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.auth.GoogleDriveAuthManager
import com.cloudamp.music.cache.GDriveLibraryCache
import com.cloudamp.music.cache.GDriveLibraryScanner
import com.cloudamp.music.cache.LibraryScanManager
import com.cloudamp.music.playback.CloudAmpService
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.ui.AlphabetSidebarView
import com.cloudamp.music.ui.GDriveStructuredLibraryAdapter
import com.cloudamp.music.ui.MiniPlayerBar
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class GDriveStructuredLibraryActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var driveClient: GoogleDriveApiClient
    private lateinit var authManager: GoogleDriveAuthManager
    private lateinit var libraryCache: GDriveLibraryCache
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: GDriveStructuredLibraryAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingTextView: TextView
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
    private lateinit var miniPlayerBar: MiniPlayerBar
    private lateinit var appliedThemeId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeId = ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gdrive_structured_library)

        driveClient = GoogleDriveApiClient.getInstance(this)
        authManager = GoogleDriveAuthManager(this)
        libraryCache = GDriveLibraryCache.getInstance(this)

        setupDrawer()
        setupRecyclerView()
        setupEmptyState()
        setupSearchBar()
        observeScanState()

        miniPlayerBar = MiniPlayerBar(this, scope)
        miniPlayerBar.attach()
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
        navigationView.setCheckedItem(R.id.nav_gdrive_music_library)
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.gdriveRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)
        loadingTextView = findViewById(R.id.loadingTextView)
        pathTextView = findViewById(R.id.pathTextView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(LibraryDividerDecoration(this))

        adapter = GDriveStructuredLibraryAdapter(
            onArtistExpand = { artist, position ->
                loadArtistAlbums(artist.id, position)
            },
            onAlbumExpand = { album, position ->
                loadAlbumTracks(album, position)
            },
            onTrackClick = { track, allTracks, position ->
                onTrackClicked(track, allTracks, position)
            }
        )

        recyclerView.adapter = adapter

        alphabetSidebar = findViewById(R.id.alphabetSidebar)
        alphabetSidebar.listener = object : AlphabetSidebarView.OnLetterSelectedListener {
            override fun onLetterSelected(letter: String) {
                val pos = adapter.getLetterPosition(letter)
                if (pos >= 0) {
                    (recyclerView.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(pos, 0)
                }
            }
        }
    }

    private fun setupEmptyState() {
        emptyContainer = findViewById(R.id.emptyContainer)
        emptyTextView = findViewById(R.id.emptyTextView)
        settingsButton = findViewById(R.id.gdriveSettingsButton)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.recreateIfThemeChanged(this, appliedThemeId)
        SettingsActivity.onGDriveLibraryReloadRequested = {
            reloadLibrary()
        }
        checkAuthAndLoad()
        // Phone sleep / app suspension can interrupt the album-art prefetch.
        // Kick a prefetch-only job here so any covers still missing get
        // filled in when the user returns to the library.
        LibraryScanManager.resumePrefetchIfNeeded(this)
        miniPlayerBar.startUpdates()
    }

    override fun onPause() {
        miniPlayerBar.stopUpdates()
        super.onPause()
    }

    private fun checkAuthAndLoad() {
        if (!authManager.hasAccessToken()) {
            showEmptyState("Connect Google Drive in Settings\nto browse your music library")
        } else if (libraryCache.getRootFolderId() == null) {
            showEmptyState("Configure GDrive Music Root\nFolder in Settings")
        } else {
            hideEmptyState()
            if (!hasLoadedContent) {
                loadMyLibrary()
            }
        }
    }

    private fun loadMyLibrary() {
        // If a scan is already running, the state collector below will drive
        // the UI; just make sure we render whatever is in cache so far.
        if (LibraryScanManager.isScanning) {
            showCachedArtistsIfAny()
            return
        }

        if (libraryCache.hasFullCache()) {
            val cachedArtists = libraryCache.getArtists()
            if (cachedArtists != null && cachedArtists.isNotEmpty()) {
                adapter.setArtists(cachedArtists)
                showAlphabetSidebar(true)
                hasLoadedContent = true
                scrollToRandomArtist()
                preloadCachedAlbums()
                // Trigger background incremental sync (falls back to
                // full scan if no change token / raw cache exists)
                LibraryScanManager.syncLibrary(this)
                return
            }
        }

        // No cache yet — kick off the app-scoped scan job.
        LibraryScanManager.startScan(this, clearFirst = false)
    }

    private fun showCachedArtistsIfAny() {
        val cached = libraryCache.getArtists().orEmpty()
        if (cached.isNotEmpty()) {
            adapter.setArtists(cached)
            showAlphabetSidebar(true)
            hasLoadedContent = true
            preloadCachedAlbums()
        }
    }

    private fun observeScanState() {
        scope.launch {
            var wasSyncing = false
            LibraryScanManager.state.collect { state ->
                when (state) {
                    is LibraryScanManager.State.Idle -> {
                        showLoading(false)
                        pathTextView.text = "GDRIVE MUSIC"
                        // Scan/sync just finished — refresh adapter from
                        // cache in case metadata changed.
                        if (wasSyncing || !hasLoadedContent) {
                            showCachedArtistsIfAny()
                            if (hasLoadedContent && !wasSyncing) scrollToRandomArtist()
                            if (wasSyncing) preloadCachedAlbums()
                        }
                        wasSyncing = false
                    }
                    is LibraryScanManager.State.Active -> {
                        wasSyncing = false
                        val text = formatScanProgress(state.progress)
                        if (!state.metadataReady) {
                            // Fresh scan in progress — drop any stale view
                            // so the loading overlay is what the user sees.
                            if (hasLoadedContent) {
                                hasLoadedContent = false
                                adapter.setArtists(emptyList())
                                showAlphabetSidebar(false)
                            }
                            showLoading(true, text)
                            pathTextView.text = "GDRIVE MUSIC / $text"
                        } else {
                            // Metadata is in cache — show library, keep
                            // progress in the breadcrumb while art prefetches.
                            if (!hasLoadedContent) {
                                showCachedArtistsIfAny()
                                if (hasLoadedContent) scrollToRandomArtist()
                            }
                            showLoading(false)
                            pathTextView.text = "GDRIVE MUSIC / $text"
                        }
                    }
                    is LibraryScanManager.State.Syncing -> {
                        wasSyncing = true
                        // Show cached data while syncing, display
                        // sync status in the breadcrumb.
                        if (!hasLoadedContent) showCachedArtistsIfAny()
                        showLoading(false)
                        pathTextView.text = "GDRIVE MUSIC / ${state.message}"
                    }
                    is LibraryScanManager.State.Error -> {
                        wasSyncing = false
                        showLoading(false)
                        pathTextView.text = "GDRIVE MUSIC"
                        if (!hasLoadedContent) {
                            if (libraryCache.getRootFolderId() == null) {
                                showEmptyState("Configure GDrive Music Root\nFolder in Settings")
                            } else {
                                Toast.makeText(
                                    this@GDriveStructuredLibraryActivity,
                                    "Error scanning library: ${state.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        LibraryScanManager.acknowledgeError()
                    }
                }
            }
        }
    }

    private fun formatScanProgress(p: GDriveLibraryScanner.ScanProgress): String =
        buildString {
            append(p.message)
            when {
                p.totalArtists > 0 && p.artists < p.totalArtists ->
                    append(" · ${p.artists}/${p.totalArtists} artists")
                p.artists > 0 ->
                    append(" · ${p.artists} artist${if (p.artists != 1) "s" else ""}")
            }
            if (p.albums > 0) append(" · ${p.albums} albums")
            if (p.tracks > 0) append(" · ${p.tracks} tracks")
            if (p.totalAlbumArt > 0) {
                append(" · art ${p.albumArtFetched}/${p.totalAlbumArt}")
            }
        }

    private fun preloadCachedAlbums() {
        val artists = libraryCache.getArtists() ?: return
        for (artist in artists) {
            val albums = libraryCache.getArtistAlbums(artist.id)
            if (albums != null && albums.isNotEmpty()) {
                adapter.preloadArtistAlbums(artist.id, albums)
            }
        }
    }

    fun reloadLibrary() {
        hasLoadedContent = false
        adapter.setArtists(emptyList())
        showAlphabetSidebar(false)
        LibraryScanManager.startScan(this, clearFirst = true)
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

    private fun showLoading(show: Boolean, message: String = "Loading...") {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
        loadingTextView.text = message
    }

    private fun showAlphabetSidebar(show: Boolean) {
        alphabetSidebar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun scrollToRandomArtist() {
        val artistCount = adapter.getArtistCount()
        if (artistCount <= 0) return
        val randomIndex = (0 until artistCount).random()
        val position = adapter.getArtistPosition(randomIndex)
        if (position >= 0) {
            (recyclerView.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(position, 0)
        }
    }

    // ── Data Loading ────────────────────────────────────────────────────

    private fun loadArtistAlbums(artistId: String, position: Int) {
        val cachedAlbums = libraryCache.getArtistAlbums(artistId)
        if (cachedAlbums != null) {
            adapter.setArtistAlbums(position, cachedAlbums)
            return
        }
        // If not cached (shouldn't happen after full scan), show empty
        adapter.setArtistAlbums(position, emptyList())
    }

    private fun loadAlbumTracks(album: GDriveAlbum, position: Int) {
        val cachedTracks = libraryCache.getAlbumTracks(album.id)
        if (cachedTracks != null) {
            adapter.setAlbumTracks(position, cachedTracks)
            return
        }
        adapter.setAlbumTracks(position, emptyList())
    }

    // ── Track Playback ──────────────────────────────────────────────────

    private fun onTrackClicked(track: GDriveTrack, allTracks: List<GDriveTrack>, position: Int) {
        val gdrivePlayback = GDrivePlaybackManager.getInstance(this)

        // Build queue from clicked position (with rich metadata)
        val queueTracks = allTracks.subList(position, allTracks.size) +
                allTracks.subList(0, position)

        CloudAmpService.ensureForeground(this)
        gdrivePlayback.playGDriveTracks(queueTracks, 0)

        Toast.makeText(this, "Playing: ${track.trackName}", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    // ── Navigation Drawer ───────────────────────────────────────────────

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_gdrive_library -> {
                startActivity(Intent(this, GDriveLibraryActivity::class.java))
                finish()
            }
            R.id.nav_gdrive_music_library -> {
                // Already here
            }
            R.id.nav_gdrive_home -> {
                startActivity(Intent(this, GDriveHomeActivity::class.java))
                finish()
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
        adapter.clearFilter()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
        showAlphabetSidebar(hasLoadedContent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private class LibraryDividerDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val paint = Paint().apply {
            color = ThemeManager.resolveColor(context, R.attr.caSectionHeader)
            strokeWidth = (context.resources.displayMetrics.density * 1f)
        }

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter as? GDriveStructuredLibraryAdapter ?: return
            val childCount = parent.childCount

            for (i in 0 until childCount - 1) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION || pos + 1 >= adapter.itemCount) continue

                val currentType = adapter.getItemViewType(pos)
                val nextType = adapter.getItemViewType(pos + 1)

                val contentTypes = setOf(1, 2, 3) // ARTIST, ALBUM, TRACK
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
