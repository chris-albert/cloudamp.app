package com.cloudamp.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.auth.GoogleDriveAuthManager
import com.cloudamp.music.cache.GDriveLibraryCache
import com.cloudamp.music.cache.GDriveLibraryScanner
import com.cloudamp.music.cache.LibraryScanManager
import com.cloudamp.music.cache.MediaCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class SettingsActivity : AppCompatActivity() {

    // Google Drive fields
    private lateinit var gdriveAuthManager: GoogleDriveAuthManager
    private lateinit var gdriveClientIdEditText: EditText
    private lateinit var gdriveClientSecretEditText: EditText
    private lateinit var saveGdriveCredentialsButton: Button
    private lateinit var loginWithGdriveButton: Button
    private lateinit var clearGdriveButton: Button
    private lateinit var gdriveValidationStatus: TextView

    // GDrive Library fields
    private lateinit var gdriveLibraryCache: GDriveLibraryCache
    private lateinit var gdriveMusicRootText: TextView
    private lateinit var gdriveBrowseRootButton: Button
    private lateinit var gdriveLastLoadedText: TextView
    private lateinit var reloadGdriveLibraryButton: Button
    private lateinit var gdriveScanStatusText: TextView
    private lateinit var gdriveScanProgressBar: ProgressBar
    private lateinit var gdriveLibraryStatsText: TextView

    // Streaming cache fields
    private lateinit var mediaCacheUsageText: TextView
    private lateinit var mediaCacheCountText: TextView
    private lateinit var mediaCacheLimitText: TextView
    private lateinit var mediaCacheProgressBar: ProgressBar
    private lateinit var mediaCacheTracksContainer: LinearLayout
    private lateinit var clearMediaCacheButton: Button
    private lateinit var changeMediaCacheLimitButton: Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        var onGDriveLibraryReloadRequested: (() -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        gdriveAuthManager = GoogleDriveAuthManager(this)

        // Google Drive views
        setupGoogleDrive()

        // GDrive Library views
        setupGDriveLibrary()

        // Streaming cache views
        setupMediaCache()
    }

    private fun setupGoogleDrive() {
        gdriveClientIdEditText = findViewById(R.id.gdriveClientIdEditText)
        gdriveClientSecretEditText = findViewById(R.id.gdriveClientSecretEditText)
        saveGdriveCredentialsButton = findViewById(R.id.saveGdriveCredentialsButton)
        loginWithGdriveButton = findViewById(R.id.loginWithGdriveButton)
        clearGdriveButton = findViewById(R.id.clearGdriveButton)
        gdriveValidationStatus = findViewById(R.id.gdriveValidationStatus)

        // Load existing credentials
        gdriveAuthManager.getClientId()?.let { gdriveClientIdEditText.setText(it) }
        gdriveAuthManager.getClientSecret()?.let { gdriveClientSecretEditText.setText(it) }

        // Show current status
        if (gdriveAuthManager.hasAccessToken()) {
            updateGdriveStatus("Connected to Google Drive", true)
            validateGdriveToken()
        }

        loginWithGdriveButton.isEnabled = gdriveAuthManager.hasClientCredentials()

        saveGdriveCredentialsButton.setOnClickListener {
            val clientId = gdriveClientIdEditText.text.toString().trim()
            val clientSecret = gdriveClientSecretEditText.text.toString().trim()
            if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                gdriveAuthManager.saveClientCredentials(clientId, clientSecret)
                loginWithGdriveButton.isEnabled = true
                Toast.makeText(this, "Google Drive credentials saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter both Client ID and Secret", Toast.LENGTH_SHORT).show()
            }
        }

        loginWithGdriveButton.setOnClickListener {
            startGoogleDriveLogin()
        }

        clearGdriveButton.setOnClickListener {
            gdriveAuthManager.clearCredentials()
            gdriveClientIdEditText.setText("")
            gdriveClientSecretEditText.setText("")
            loginWithGdriveButton.isEnabled = false
            updateGdriveStatus("", false)
            Toast.makeText(this, "Google Drive credentials cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGoogleDriveLogin() {
        try {
            val authUrl = gdriveAuthManager.getAuthorizationUrl()

            loginWithGdriveButton.isEnabled = false
            updateGdriveStatus("Waiting for authorization...", false)

            // Start listening for the loopback callback in background
            scope.launch {
                val codeResult = withContext(Dispatchers.IO) {
                    gdriveAuthManager.waitForAuthorizationCode()
                }

                codeResult.onSuccess { code ->
                    updateGdriveStatus("Exchanging token...", false)
                    val tokenResult = gdriveAuthManager.exchangeCodeForToken(code)
                    tokenResult.onSuccess {
                        updateGdriveStatus("Connected to Google Drive!", true)
                        validateGdriveToken()
                    }.onFailure { error ->
                        updateGdriveStatus("Token exchange failed: ${error.message}", false)
                    }
                }.onFailure { error ->
                    updateGdriveStatus("Authorization failed: ${error.message}", false)
                }

                loginWithGdriveButton.isEnabled = true
            }

            // Open browser for authorization
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            startActivity(intent)
        } catch (e: Exception) {
            loginWithGdriveButton.isEnabled = true
            updateGdriveStatus("Error: ${e.message}", false)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun validateGdriveToken() {
        scope.launch {
            try {
                val driveClient = GoogleDriveApiClient.getInstance(this@SettingsActivity)
                val response = driveClient.api.getAbout()
                if (response.isSuccessful) {
                    val displayName = response.body()?.user?.displayName ?: "User"
                    val email = response.body()?.user?.emailAddress ?: ""
                    updateGdriveStatus("Connected: $displayName ($email)", true)
                } else {
                    updateGdriveStatus("Token expired - please re-login", false)
                }
            } catch (e: Exception) {
                updateGdriveStatus("Connection error", false)
            }
        }
    }

    private fun updateGdriveStatus(message: String, isValid: Boolean) {
        gdriveValidationStatus.text = message
        gdriveValidationStatus.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        gdriveValidationStatus.setTextColor(
            if (isValid) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
    }

    private fun setupGDriveLibrary() {
        gdriveLibraryCache = GDriveLibraryCache.getInstance(this)
        gdriveMusicRootText = findViewById(R.id.gdriveMusicRootText)
        gdriveBrowseRootButton = findViewById(R.id.gdriveBrowseRootButton)
        gdriveLastLoadedText = findViewById(R.id.gdriveLastLoadedText)
        reloadGdriveLibraryButton = findViewById(R.id.reloadGdriveLibraryButton)
        gdriveScanStatusText = findViewById(R.id.gdriveScanStatusText)
        gdriveScanProgressBar = findViewById(R.id.gdriveScanProgressBar)
        gdriveLibraryStatsText = findViewById(R.id.gdriveLibraryStatsText)

        updateGDriveLibraryDisplay()

        gdriveBrowseRootButton.setOnClickListener {
            showFolderPicker()
        }

        reloadGdriveLibraryButton.setOnClickListener {
            if (LibraryScanManager.isFullScanRunning) {
                Toast.makeText(this, "Scan already in progress", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Notify the library activity so it drops its current view; the
            // scan itself runs in LibraryScanManager (app-scoped) and is
            // not bound to this Settings activity's lifecycle. A background
            // resume-prefetch (if any) is preempted inside startScan.
            onGDriveLibraryReloadRequested?.invoke()
            LibraryScanManager.startScan(this, clearFirst = true)
        }

        observeScanState()
    }

    private fun observeScanState() {
        scope.launch {
            LibraryScanManager.state.collect { state ->
                when (state) {
                    is LibraryScanManager.State.Idle -> {
                        gdriveScanStatusText.visibility = View.GONE
                        gdriveScanProgressBar.visibility = View.GONE
                        reloadGdriveLibraryButton.isEnabled = true
                        reloadGdriveLibraryButton.text = "RELOAD GDRIVE LIBRARY"
                        updateGDriveLibraryDisplay()
                    }
                    is LibraryScanManager.State.Active -> {
                        gdriveScanStatusText.visibility = View.VISIBLE
                        gdriveScanStatusText.text = formatScanStatus(state.progress)

                        val total = state.progress.totalAlbumArt
                        if (total > 0) {
                            gdriveScanProgressBar.visibility = View.VISIBLE
                            gdriveScanProgressBar.max = total
                            gdriveScanProgressBar.progress = state.progress.albumArtFetched
                        } else {
                            gdriveScanProgressBar.visibility = View.GONE
                        }

                        reloadGdriveLibraryButton.isEnabled = false
                        reloadGdriveLibraryButton.text = "SCAN IN PROGRESS..."

                        // Once metadata is persisted, stats are known and we
                        // can show artist/album/track totals plus a live
                        // cached-cover count from the prefetch progress.
                        if (state.metadataReady) {
                            renderLibraryStats(liveCachedCovers = state.progress.albumArtFetched)
                        }
                    }
                    is LibraryScanManager.State.Syncing -> {
                        gdriveScanStatusText.visibility = View.VISIBLE
                        gdriveScanStatusText.text = state.message
                        gdriveScanProgressBar.visibility = View.GONE
                        reloadGdriveLibraryButton.isEnabled = false
                        reloadGdriveLibraryButton.text = "SYNCING..."
                    }
                    is LibraryScanManager.State.Error -> {
                        gdriveScanStatusText.visibility = View.VISIBLE
                        gdriveScanStatusText.text = "Error: ${state.message}"
                        gdriveScanProgressBar.visibility = View.GONE
                        reloadGdriveLibraryButton.isEnabled = true
                        reloadGdriveLibraryButton.text = "RELOAD GDRIVE LIBRARY"
                    }
                }
            }
        }
    }

    private fun renderLibraryStats(liveCachedCovers: Int? = null) {
        val stats = gdriveLibraryCache.getStats()
        if (stats == null) {
            gdriveLibraryStatsText.visibility = View.GONE
            return
        }
        val cached = liveCachedCovers ?: stats.cachedCovers
        gdriveLibraryStatsText.visibility = View.VISIBLE
        gdriveLibraryStatsText.text =
            "Library: ${stats.artists} artists · ${stats.albums} albums · " +
                "${stats.tracks} tracks · $cached/${stats.totalCovers} covers cached"
    }

    private fun formatScanStatus(p: GDriveLibraryScanner.ScanProgress): String =
        buildString {
            append(p.message)
            val artistsLine = when {
                p.totalArtists > 0 && p.artists < p.totalArtists ->
                    "${p.artists}/${p.totalArtists} artists"
                p.artists > 0 ->
                    "${p.artists} artist${if (p.artists != 1) "s" else ""}"
                else -> null
            }
            if (artistsLine != null) {
                append("\n$artistsLine")
                if (p.albums > 0) append(" · ${p.albums} albums")
                if (p.tracks > 0) append(" · ${p.tracks} tracks")
            }
            if (p.totalAlbumArt > 0) {
                append("\n${p.albumArtFetched}/${p.totalAlbumArt} covers cached")
            }
        }

    private fun updateGDriveLibraryDisplay() {
        val rootName = gdriveLibraryCache.getRootFolderName()
        gdriveMusicRootText.text = if (rootName != null) {
            "Music root: $rootName"
        } else {
            "Music root: Not set"
        }

        val lastLoaded = gdriveLibraryCache.getLastLoadedFormatted()
        gdriveLastLoadedText.text = if (lastLoaded != null) {
            "Last loaded: $lastLoaded"
        } else {
            "Last loaded: Never"
        }

        renderLibraryStats()
    }

    private fun showFolderPicker() {
        if (!gdriveAuthManager.hasAccessToken()) {
            Toast.makeText(this, "Please login to Google Drive first", Toast.LENGTH_SHORT).show()
            return
        }

        // Show choice: Browse My Drive or Search All Drive
        android.app.AlertDialog.Builder(this)
            .setTitle("Find music folder")
            .setItems(arrayOf("Browse My Drive", "Search All Drive")) { _, which ->
                when (which) {
                    0 -> showBrowseFolderPicker("root")
                    1 -> showSearchFolderPicker()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    /**
     * Browse folders starting from a given parent (My Drive root or any subfolder).
     */
    private fun showBrowseFolderPicker(startFolderId: String) {
        val driveClient = GoogleDriveApiClient.getInstance(this)
        val folderStack = mutableListOf<Pair<String, String>>() // id, name

        fun loadAndShowFolders(folderId: String) {
            scope.launch {
                try {
                    val response = driveClient.api.listFiles(
                        query = "'$folderId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                        orderBy = "name",
                        pageSize = 100
                    )
                    if (!response.isSuccessful) {
                        Toast.makeText(this@SettingsActivity, "Error loading folders", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val folders = response.body()?.files ?: emptyList()
                    val names = mutableListOf<String>()
                    val ids = mutableListOf<String>()

                    // Add "Select this folder" option
                    if (folderStack.isNotEmpty()) {
                        names.add("[ SELECT THIS FOLDER ]")
                        ids.add("__select__")
                        names.add("[ .. BACK ]")
                        ids.add("__back__")
                    }

                    for (folder in folders) {
                        names.add(folder.name)
                        ids.add(folder.id)
                    }

                    val currentName = if (folderStack.isNotEmpty()) folderStack.last().second else "My Drive"
                    val dialog = android.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Select folder: $currentName")
                        .setItems(names.toTypedArray()) { _, which ->
                            val selectedId = ids[which]
                            when (selectedId) {
                                "__select__" -> {
                                    val selected = folderStack.last()
                                    gdriveLibraryCache.setRootFolder(selected.first, selected.second)
                                    updateGDriveLibraryDisplay()
                                    Toast.makeText(this@SettingsActivity, "Music root set to: ${selected.second}", Toast.LENGTH_SHORT).show()
                                }
                                "__back__" -> {
                                    folderStack.removeAt(folderStack.size - 1)
                                    val parentId = if (folderStack.isNotEmpty()) folderStack.last().first else startFolderId
                                    loadAndShowFolders(parentId)
                                }
                                else -> {
                                    folderStack.add(Pair(selectedId, names[which]))
                                    loadAndShowFolders(selectedId)
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
                    dialog.show()

                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        loadAndShowFolders(startFolderId)
    }

    /**
     * Search for folders by name across all of Drive (My Drive, Computers, Shared drives).
     * Shows results with their parent path for disambiguation.
     */
    private fun showSearchFolderPicker() {
        val input = EditText(this).apply {
            hint = "e.g. Music"
            setSingleLine(true)
            setPadding(48, 32, 48, 16)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Search for folder")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchFolders(query)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    private fun searchFolders(query: String) {
        val driveClient = GoogleDriveApiClient.getInstance(this)

        scope.launch {
            try {
                // Search all folders matching the name across all Drive locations
                val response = driveClient.api.listFiles(
                    query = "name contains '$query' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                    fields = "files(id,name,parents),nextPageToken",
                    orderBy = "name",
                    pageSize = 50
                )
                if (!response.isSuccessful) {
                    Toast.makeText(this@SettingsActivity, "Search failed", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val folders = response.body()?.files ?: emptyList()
                if (folders.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "No folders found matching '$query'", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Build path labels by resolving parent folder names
                val parentIds = folders.mapNotNull { it.parents?.firstOrNull() }.distinct()
                val parentNames = mutableMapOf<String, String>()
                for (parentId in parentIds) {
                    try {
                        val parentResponse = driveClient.api.getFile(parentId, "id,name")
                        if (parentResponse.isSuccessful) {
                            parentNames[parentId] = parentResponse.body()?.name ?: parentId
                        }
                    } catch (_: Exception) {
                        // Parent may not be accessible (e.g. Computers root)
                    }
                }

                val displayNames = folders.map { folder ->
                    val parentId = folder.parents?.firstOrNull()
                    val parentName = parentNames[parentId]
                    if (parentName != null) {
                        "${folder.name}  (in $parentName)"
                    } else {
                        folder.name
                    }
                }

                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select folder")
                    .setItems(displayNames.toTypedArray()) { _, which ->
                        val selected = folders[which]
                        gdriveLibraryCache.setRootFolder(selected.id, selected.name)
                        updateGDriveLibraryDisplay()
                        Toast.makeText(this@SettingsActivity, "Music root set to: ${selected.name}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                    .show()

            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupMediaCache() {
        mediaCacheUsageText = findViewById(R.id.mediaCacheUsageText)
        mediaCacheCountText = findViewById(R.id.mediaCacheCountText)
        mediaCacheLimitText = findViewById(R.id.mediaCacheLimitText)
        mediaCacheProgressBar = findViewById(R.id.mediaCacheProgressBar)
        mediaCacheTracksContainer = findViewById(R.id.mediaCacheTracksContainer)
        clearMediaCacheButton = findViewById(R.id.clearMediaCacheButton)
        changeMediaCacheLimitButton = findViewById(R.id.changeMediaCacheLimitButton)

        changeMediaCacheLimitButton.setOnClickListener { showMediaCacheLimitPicker() }

        clearMediaCacheButton.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Clear streaming cache?")
                .setMessage("Cached audio for all queued tracks will be deleted. They'll re-download on next play.")
                .setPositiveButton("CLEAR") { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) { MediaCache.getInstance(this@SettingsActivity).clear() }
                        refreshMediaCacheDisplay()
                        Toast.makeText(this@SettingsActivity, "Streaming cache cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshMediaCacheDisplay()
    }

    private fun refreshMediaCacheDisplay() {
        scope.launch {
            val stats = withContext(Dispatchers.IO) {
                MediaCache.getInstance(this@SettingsActivity).stats()
            }

            mediaCacheUsageText.text =
                "${MediaCache.formatBytes(stats.totalBytes)} / ${MediaCache.formatBytes(stats.maxBytes)}"

            val pct = if (stats.maxBytes > 0) {
                ((stats.totalBytes.toDouble() / stats.maxBytes) * 1000).toInt().coerceIn(0, 1000)
            } else 0
            mediaCacheProgressBar.progress = pct

            mediaCacheCountText.text = when (stats.tracks.size) {
                0 -> "No tracks cached"
                1 -> "1 track cached"
                else -> "${stats.tracks.size} tracks cached"
            }

            val cache = MediaCache.getInstance(this@SettingsActivity)
            val limitLabel = MediaCache.SIZE_PRESETS.firstOrNull { it.first == cache.maxBytes }?.second
                ?: MediaCache.formatBytes(cache.maxBytes)
            mediaCacheLimitText.text = if (cache.isRestartRequired()) {
                "Max size: $limitLabel  (restart to apply)"
            } else {
                "Max size: $limitLabel"
            }

            mediaCacheTracksContainer.removeAllViews()
            for (track in stats.tracks) {
                mediaCacheTracksContainer.addView(buildCachedTrackRow(track))
            }
        }
    }

    private fun buildCachedTrackRow(track: MediaCache.CachedTrack): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
        }
        val name = TextView(this).apply {
            text = track.name
            setTextColor(getColor(R.color.winamp_text))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            ellipsize = android.text.TextUtils.TruncateAt.END
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sizeLabel = if (track.totalBytes != null && track.totalBytes > track.cachedBytes) {
            "${MediaCache.formatBytes(track.cachedBytes)} / ${MediaCache.formatBytes(track.totalBytes)}"
        } else {
            MediaCache.formatBytes(track.cachedBytes)
        }
        val size = TextView(this).apply {
            text = sizeLabel
            setTextColor(getColor(R.color.winamp_display_text))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 0, 0, 0)
        }
        row.addView(name)
        row.addView(size)
        return row
    }

    private fun showMediaCacheLimitPicker() {
        val cache = MediaCache.getInstance(this)
        val current = cache.maxBytes
        val labels = MediaCache.SIZE_PRESETS.map { (bytes, label) ->
            if (bytes == current) "$label  ✓" else label
        }.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Max streaming cache size")
            .setItems(labels) { _, which ->
                val (bytes, label) = MediaCache.SIZE_PRESETS[which]
                if (bytes == current) return@setItems
                cache.setMaxBytes(bytes)
                refreshMediaCacheDisplay()
                val msg = if (cache.isRestartRequired()) {
                    "Max size set to $label. Restart the app to apply."
                } else {
                    "Max size set to $label."
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::mediaCacheUsageText.isInitialized) refreshMediaCacheDisplay()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
