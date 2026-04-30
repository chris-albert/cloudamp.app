package com.cloudamp.music.cache

import android.content.Context
import android.util.Log
import com.cloudamp.music.api.GDriveAlbum
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.api.PageTokenExpiredException
import com.cloudamp.music.playback.GDriveImageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-lifetime owner of the GDrive library scan + album-art prefetch job.
 *
 * Lives in a process-wide [CoroutineScope] so navigating between activities
 * (Library ↔ Settings ↔ NowPlaying) does not cancel the in-flight scan. Both
 * screens subscribe to [state] to render the same progress UI.
 */
object LibraryScanManager {

    private const val TAG = "LibraryScanManager"

    sealed class State {
        object Idle : State()

        /**
         * @param progress raw progress emitted by the scanner
         * @param metadataReady true once metadata has been written to cache
         *   and the library can be shown; the scan is still running while
         *   album art prefetches.
         */
        data class Active(
            val progress: GDriveLibraryScanner.ScanProgress,
            val metadataReady: Boolean
        ) : State()

        data class Syncing(val message: String) : State()

        data class Error(val message: String) : State()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var currentJob: Job? = null

    /** True while a full scan is running (not just a background prefetch). */
    @Volatile
    private var isFullScan: Boolean = false

    val isScanning: Boolean get() = currentJob?.isActive == true

    /**
     * True while an explicit full library scan (initiated by [startScan]) is
     * in progress. Distinct from [isScanning], which is also true while a
     * background resume-prefetch is filling in missing covers.
     */
    val isFullScanRunning: Boolean get() = isFullScan

    /**
     * Kick off a full library scan + album-art prefetch. No-op if a full
     * scan is already running. If only a background resume-prefetch is
     * active, it is cancelled so the explicit reload wins. The job
     * survives activity destruction.
     *
     * @param clearFirst when true, wipe the metadata cache before scanning
     *   (used by an explicit "Reload library" action). For first-time scans
     *   the cache is already empty so the flag has no effect.
     */
    fun startScan(context: Context, clearFirst: Boolean) {
        if (isFullScan) {
            Log.d(TAG, "startScan ignored — full scan already in progress")
            return
        }
        // Preempt any in-flight resume-prefetch so the user's explicit
        // reload isn't blocked behind it.
        currentJob?.cancel()

        val appContext = context.applicationContext
        isFullScan = true
        currentJob = scope.launch {
            try {
                val driveClient = GoogleDriveApiClient.getInstance(appContext)
                val cache = GDriveLibraryCache.getInstance(appContext)

                if (clearFirst) {
                    // Emit immediately so the user sees something before
                    // clearCache walks albumsDir/tracksDir; on libraries
                    // with thousands of cached JSON files this can take
                    // several seconds.
                    _state.value = State.Active(
                        GDriveLibraryScanner.ScanProgress(
                            message = "Clearing old library..."
                        ),
                        metadataReady = false
                    )
                    withContext(Dispatchers.IO) { cache.clearCache() }
                }

                val scanner = GDriveLibraryScanner(driveClient.api, cache)
                scanner.onProgress = { progress ->
                    val current = _state.value
                    val metadataReady = current is State.Active && current.metadataReady
                    // Preserve totalAlbumArt once it's been computed so the
                    // progress bar stays visible across phases that don't
                    // know the cover total (e.g. saveToCache reports).
                    val preservedTotalArt = if (current is State.Active) {
                        current.progress.totalAlbumArt
                    } else 0
                    val merged = if (progress.totalAlbumArt == 0 && preservedTotalArt > 0) {
                        progress.copy(totalAlbumArt = preservedTotalArt)
                    } else {
                        progress
                    }
                    _state.value = State.Active(merged, metadataReady)
                }

                // Get change token BEFORE scanning so changes during
                // the scan aren't lost on the next incremental sync.
                val tokenBeforeScan = try {
                    val resp = withContext(Dispatchers.IO) {
                        driveClient.api.getChangesStartPageToken()
                    }
                    resp.body()?.startPageToken
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get start page token", e)
                    null
                }

                val result = withContext(Dispatchers.IO) { scanner.scan() }
                if (result == null) {
                    _state.value = State.Error("Configure GDrive Music Root in Settings")
                    return@launch
                }

                // Compute the prefetch total up front so the progress bar
                // appears immediately while saveToCache writes JSON. Without
                // this, the UI sits on "Scan complete" with no totalArt for
                // the seconds it takes to flush thousands of cached entries.
                val totalArt = countReferencedCovers(result)
                val totalAlbums = result.albumsByArtist.values.sumOf { it.size }
                val totalTracks = result.tracksByAlbum.values.sumOf { it.size }
                _state.value = State.Active(
                    GDriveLibraryScanner.ScanProgress(
                        message = "Saving library to device...",
                        artists = result.artists.size,
                        totalArtists = result.artists.size,
                        albums = totalAlbums,
                        tracks = totalTracks,
                        albumArtFetched = 0,
                        totalAlbumArt = totalArt
                    ),
                    metadataReady = false
                )

                withContext(Dispatchers.IO) {
                    scanner.saveToCache(result)
                    // Persist raw file lists + change token for incremental sync
                    cache.saveRawFiles(result.allFolders, result.allAudioFiles, result.allCoverFiles)
                    if (tokenBeforeScan != null) {
                        cache.saveChangePageToken(tokenBeforeScan)
                    }
                }

                // Metadata is now persisted — library UI can render artists.
                val current = _state.value
                if (current is State.Active) {
                    _state.value = current.copy(metadataReady = true)
                }

                withContext(Dispatchers.IO) {
                    scanner.prefetchAlbumArt(appContext, result)
                }

                _state.value = State.Idle
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Scan failed", e)
                _state.value = State.Error(e.message ?: "Scan failed")
            } finally {
                isFullScan = false
            }
        }
    }

    /** Reset to Idle after the UI has consumed an Error state. */
    fun acknowledgeError() {
        if (_state.value is State.Error) {
            _state.value = State.Idle
        }
    }

    /**
     * Attempt incremental sync using the Drive Changes API. Falls back to
     * a full scan if the change token is missing/expired or the raw file
     * cache doesn't exist. No-op if a scan is already running.
     *
     * The cached library is displayed immediately while sync runs in the
     * background; the UI only refreshes if changes are found.
     */
    fun syncLibrary(context: Context) {
        if (isScanning) {
            Log.d(TAG, "syncLibrary ignored — scan already in progress")
            return
        }
        currentJob?.cancel()

        val appContext = context.applicationContext
        currentJob = scope.launch {
            var needsFullScan = false
            try {
                val cache = GDriveLibraryCache.getInstance(appContext)
                val token = cache.getChangePageToken()

                // No token or no raw file cache → full scan
                if (token == null || !cache.hasRawFileCache()) {
                    Log.d(TAG, "No change token or raw cache — falling back to full scan")
                    needsFullScan = true
                    return@launch
                }

                _state.value = State.Syncing("Checking for changes...")

                val driveClient = GoogleDriveApiClient.getInstance(appContext)
                val scanner = GDriveLibraryScanner(driveClient.api, cache)

                val folders = withContext(Dispatchers.IO) { cache.getRawFolders() }
                val audio = withContext(Dispatchers.IO) { cache.getRawAudioFiles() }
                val covers = withContext(Dispatchers.IO) { cache.getRawCoverFiles() }

                if (folders == null || audio == null || covers == null) {
                    Log.d(TAG, "Raw file cache unreadable — falling back to full scan")
                    needsFullScan = true
                    return@launch
                }

                val syncResult = withContext(Dispatchers.IO) {
                    scanner.syncChanges(token, folders, audio, covers)
                }

                if (syncResult.changesApplied == 0) {
                    Log.d(TAG, "No changes since last sync")
                    cache.saveChangePageToken(syncResult.newPageToken)
                    _state.value = State.Idle
                    return@launch
                }

                Log.d(TAG, "Applied ${syncResult.changesApplied} changes")
                _state.value = State.Syncing("Saving updated library...")

                withContext(Dispatchers.IO) {
                    scanner.saveToCache(syncResult.scanResult)
                    cache.saveRawFiles(
                        syncResult.scanResult.allFolders,
                        syncResult.scanResult.allAudioFiles,
                        syncResult.scanResult.allCoverFiles
                    )
                    cache.saveChangePageToken(syncResult.newPageToken)
                }

                // Prefetch any new album art
                scanner.onProgress = { progress ->
                    _state.value = State.Active(progress, metadataReady = true)
                }
                withContext(Dispatchers.IO) {
                    scanner.prefetchAlbumArt(appContext, syncResult.scanResult)
                }

                _state.value = State.Idle
            } catch (e: PageTokenExpiredException) {
                Log.w(TAG, "Change token expired — falling back to full scan")
                _state.value = State.Syncing("Token expired, rescanning...")
                needsFullScan = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Sync failed, falling back to cached data", e)
                _state.value = State.Idle
            }

            // Launch full scan outside the try/catch so this coroutine
            // finishes cleanly before startScan cancels currentJob.
            if (needsFullScan) {
                startScan(appContext, clearFirst = false)
            }
        }
    }

    /**
     * If we have library metadata cached but some referenced cover images
     * are missing from the album-art cache (e.g. an earlier prefetch was
     * cut short by sleep, network loss, or app death), kick off a
     * prefetch-only job so they get filled in. No-op if a scan is already
     * running, the library hasn't been scanned yet, or every cover is
     * already on disk.
     */
    fun resumePrefetchIfNeeded(context: Context) {
        if (isScanning) return
        val appContext = context.applicationContext
        val cache = GDriveLibraryCache.getInstance(appContext)
        if (!cache.hasFullCache()) return

        currentJob = scope.launch {
            try {
                val artists = withContext(Dispatchers.IO) { cache.getArtists() }
                if (artists.isNullOrEmpty()) return@launch

                val albumsByArtist = withContext(Dispatchers.IO) {
                    buildMap<String, List<GDriveAlbum>> {
                        for (artist in artists) {
                            cache.getArtistAlbums(artist.id)?.let { put(artist.id, it) }
                        }
                    }
                }

                val referencedIds = buildSet {
                    for (artist in artists) artist.imageFileId?.let { add(it) }
                    for (albums in albumsByArtist.values) {
                        for (album in albums) album.coverFileId?.let { add(it) }
                    }
                }
                val cachedIds = GDriveImageProvider.cachedFileIds(appContext)
                val anyMissing = referencedIds.any { it !in cachedIds }
                if (!anyMissing) return@launch

                val driveClient = GoogleDriveApiClient.getInstance(appContext)
                val scanner = GDriveLibraryScanner(driveClient.api, cache)
                scanner.onProgress = { progress ->
                    _state.value = State.Active(progress, metadataReady = true)
                }

                val result = GDriveLibraryScanner.ScanResult(
                    artists = artists,
                    albumsByArtist = albumsByArtist,
                    tracksByAlbum = emptyMap()
                )
                withContext(Dispatchers.IO) {
                    scanner.prefetchAlbumArt(appContext, result)
                }

                _state.value = State.Idle
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Resume prefetch failed", e)
                _state.value = State.Idle
            }
        }
    }

    private fun countReferencedCovers(result: GDriveLibraryScanner.ScanResult): Int {
        val ids = HashSet<String>()
        for (artist in result.artists) artist.imageFileId?.let { ids.add(it) }
        for (albums in result.albumsByArtist.values) {
            for (album in albums) album.coverFileId?.let { ids.add(it) }
        }
        return ids.size
    }
}
