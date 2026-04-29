package com.cloudamp.music.cache

import android.content.Context
import android.util.Log
import com.cloudamp.music.api.GDriveAlbum
import com.cloudamp.music.api.GoogleDriveApiClient
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
                    withContext(Dispatchers.IO) { cache.clearCache() }
                }

                val scanner = GDriveLibraryScanner(driveClient.api, cache)
                scanner.onProgress = { progress ->
                    val current = _state.value
                    val metadataReady = current is State.Active && current.metadataReady
                    _state.value = State.Active(progress, metadataReady)
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
                _state.value = State.Active(
                    GDriveLibraryScanner.ScanProgress(
                        message = "Saving library...",
                        artists = result.artists.size,
                        albumArtFetched = 0,
                        totalAlbumArt = totalArt
                    ),
                    metadataReady = false
                )

                withContext(Dispatchers.IO) { scanner.saveToCache(result) }

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
                val anyMissing = referencedIds.any {
                    !GDriveImageProvider.isCached(appContext, it)
                }
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
