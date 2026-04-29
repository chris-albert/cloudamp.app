package com.cloudamp.music.cache

import android.content.Context
import android.util.Log
import com.cloudamp.music.api.GoogleDriveApiClient
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

    val isScanning: Boolean get() = currentJob?.isActive == true

    /**
     * Kick off a full library scan + album-art prefetch. No-op if a scan is
     * already running. The job survives activity destruction.
     *
     * @param clearFirst when true, wipe the metadata cache before scanning
     *   (used by an explicit "Reload library" action). For first-time scans
     *   the cache is already empty so the flag has no effect.
     */
    fun startScan(context: Context, clearFirst: Boolean) {
        if (isScanning) {
            Log.d(TAG, "startScan ignored — scan already in progress")
            return
        }

        val appContext = context.applicationContext
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
            }
        }
    }

    /** Reset to Idle after the UI has consumed an Error state. */
    fun acknowledgeError() {
        if (_state.value is State.Error) {
            _state.value = State.Idle
        }
    }
}
