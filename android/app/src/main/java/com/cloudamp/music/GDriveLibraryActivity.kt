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
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.auth.GoogleDriveAuthManager
import com.cloudamp.music.playback.CloudAmpService
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.ui.GDriveAdapter
import com.cloudamp.music.ui.MiniPlayerBar
import com.cloudamp.music.util.MusicFilenameParser
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class GDriveLibraryActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var driveClient: GoogleDriveApiClient
    private lateinit var authManager: GoogleDriveAuthManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var gdriveRecyclerView: RecyclerView
    private lateinit var gdriveAdapter: GDriveAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var emptyContainer: LinearLayout
    private lateinit var emptyTextView: TextView
    private lateinit var pathTextView: TextView
    private lateinit var settingsButton: Button

    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchCloseButton: ImageView
    private var isSearchVisible = false

    // Navigation stack: pairs of (folderId, folderName)
    private val folderStack = mutableListOf<Pair<String, String>>()
    private lateinit var miniPlayerBar: MiniPlayerBar
    private lateinit var appliedThemeId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeId = ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gdrive_library)

        driveClient = GoogleDriveApiClient.getInstance(this)
        authManager = GoogleDriveAuthManager(this)

        setupDrawer()
        setupRecyclerView()
        setupEmptyState()
        setupSearchBar()

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
        navigationView.setCheckedItem(R.id.nav_gdrive_library)
    }

    private fun setupRecyclerView() {
        gdriveRecyclerView = findViewById(R.id.gdriveRecyclerView)
        loadingContainer = findViewById(R.id.loadingContainer)
        pathTextView = findViewById(R.id.pathTextView)

        gdriveRecyclerView.layoutManager = LinearLayoutManager(this)

        gdriveAdapter = GDriveAdapter(
            onFolderClick = { folder ->
                navigateToFolder(folder.id, folder.name)
            },
            onAudioClick = { file, allAudioFiles, position ->
                onAudioFileClicked(file, allAudioFiles, position)
            },
            onBackClick = {
                navigateBack()
            }
        )

        gdriveRecyclerView.adapter = gdriveAdapter
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
        checkAuthAndLoad()
        miniPlayerBar.startUpdates()
    }

    override fun onPause() {
        miniPlayerBar.stopUpdates()
        super.onPause()
    }

    private fun checkAuthAndLoad() {
        if (!authManager.hasAccessToken()) {
            showEmptyState("Connect Google Drive in Settings\nto browse your music files")
        } else {
            hideEmptyState()
            if (folderStack.isEmpty()) {
                loadRootFolder()
            }
        }
    }

    private fun showEmptyState(message: String) {
        emptyContainer.visibility = View.VISIBLE
        emptyTextView.text = message
        gdriveRecyclerView.visibility = View.GONE
    }

    private fun hideEmptyState() {
        emptyContainer.visibility = View.GONE
        gdriveRecyclerView.visibility = View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        loadingContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updatePath() {
        if (folderStack.isEmpty()) {
            pathTextView.text = "GDRIVE / My Drive"
        } else {
            val path = "GDRIVE / " + folderStack.joinToString(" / ") { it.second }
            pathTextView.text = path
        }
    }

    private fun loadRootFolder() {
        loadFolder("root")
    }

    private fun navigateToFolder(folderId: String, folderName: String) {
        if (isSearchVisible) hideSearchBar()
        folderStack.add(Pair(folderId, folderName))
        updatePath()
        loadFolder(folderId)
    }

    private fun navigateBack() {
        if (isSearchVisible) hideSearchBar()
        if (folderStack.isNotEmpty()) {
            folderStack.removeAt(folderStack.size - 1)
            updatePath()

            val parentId = if (folderStack.isNotEmpty()) {
                folderStack.last().first
            } else {
                "root"
            }
            loadFolder(parentId)
        }
    }

    private fun loadFolder(folderId: String) {
        showLoading(true)
        scope.launch {
            try {
                val allFiles = mutableListOf<DriveFile>()
                var pageToken: String? = null

                do {
                    val query = "'$folderId' in parents and trashed = false and " +
                            "(mimeType contains 'audio/' or mimeType = 'application/vnd.google-apps.folder'" +
                            " or name contains '.flac' or name contains '.m4a'" +
                            " or name contains '.ogg' or name contains '.opus'" +
                            " or name contains '.wav' or name contains '.aac')"

                    val response = driveClient.api.listFiles(
                        query = query,
                        pageToken = pageToken
                    )

                    if (response.isSuccessful) {
                        val result = response.body()
                        allFiles.addAll(result?.files ?: emptyList())
                        pageToken = result?.nextPageToken
                    } else {
                        handleApiError(response.code())
                        break
                    }
                } while (pageToken != null)

                val hasParent = folderStack.isNotEmpty()
                gdriveAdapter.setFiles(allFiles, hasParent)

                if (allFiles.isEmpty() && !hasParent) {
                    showEmptyState("No audio files or folders found\nin your Google Drive")
                } else if (allFiles.isEmpty()) {
                    // Empty folder but we have navigation, show empty list with back
                    gdriveAdapter.setFiles(emptyList(), true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@GDriveLibraryActivity,
                    "Error loading files: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun onAudioFileClicked(file: DriveFile, allAudioFiles: List<DriveFile>, position: Int) {
        val gdrivePlayback = GDrivePlaybackManager.getInstance(this)

        // Build queue: all audio files from clicked position onwards
        val queueFiles = allAudioFiles.subList(position, allAudioFiles.size) +
                allAudioFiles.subList(0, position)

        CloudAmpService.ensureForeground(this)
        gdrivePlayback.playFiles(queueFiles, 0)

        Toast.makeText(
            this,
            "Playing: ${MusicFilenameParser.parseTrackFilename(file.name).title}",
            Toast.LENGTH_SHORT
        ).show()

        // Open Now Playing
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun handleApiError(code: Int) {
        val errorMsg = when (code) {
            401 -> "Google Drive token expired. Please re-login in Settings."
            403 -> "Insufficient permissions. Please re-login in Settings."
            404 -> "Content not found."
            else -> "Error loading content (code: $code)"
        }
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_gdrive_library -> {
                // Already here
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
        } else if (folderStack.isNotEmpty()) {
            navigateBack()
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
                gdriveAdapter.filterFiles(s?.toString() ?: "")
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
    }

    private fun hideSearchBar() {
        isSearchVisible = false
        searchBarContainer.visibility = View.GONE
        searchEditText.setText("")
        gdriveAdapter.clearFilter()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
