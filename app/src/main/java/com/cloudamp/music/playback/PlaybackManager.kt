package com.cloudamp.music.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.widget.Toast
import com.cloudamp.music.api.PlayRequest
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.api.TransferPlaybackRequest
import com.cloudamp.music.auth.SpotifyAuthManager
import com.cloudamp.music.models.Track
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import retrofit2.Response
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * Represents a playback request that is pending while Spotify wakes up.
 * Stored so the user can retry via the play button if the initial wake fails.
 */
private sealed class PendingPlayback {
    data class SingleTrack(val trackUri: String) : PendingPlayback()
    data class TrackList(val tracks: List<Track>, val startIndex: Int) : PendingPlayback()
}

class PlaybackManager private constructor(
    private val context: Context,
    private val spotifyClient: SpotifyApiClient
) : PlaybackProvider {

    companion object {
        @Volatile
        private var instance: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackManager(
                    context.applicationContext,
                    SpotifyApiClient.getInstance(context)
                ).also { instance = it }
            }
        }
    }

    override val providerName: String = "Spotify"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentQueue = mutableListOf<Track>()
    private var currentIndex = 0
    private var mediaSession: MediaSessionCompat? = null
    private var service: CloudAmpService? = null

    // Pending playback request for retry after failed Spotify wake
    private var pendingPlayback: PendingPlayback? = null

    // State tracking for PlaybackProvider interface — updated by CloudAmpService polling
    @Volatile var lastKnownPosition: Long = 0L
    @Volatile var lastKnownDuration: Long = 0L
    @Volatile var lastKnownIsPlaying: Boolean = false
    @Volatile private var lastPositionTimestamp: Long = 0L

    /** Last track reported by Spotify polling — used by NowPlayingActivity to detect track changes. */
    @Volatile var lastKnownTrack: Track? = null

    /** Called from CloudAmpService polling loop to feed Spotify state. */
    fun updateSpotifyState(position: Long, duration: Long, isPlaying: Boolean) {
        lastKnownPosition = position
        lastKnownDuration = duration
        lastKnownIsPlaying = isPlaying
        lastPositionTimestamp = System.currentTimeMillis()
    }

    /** Updates currentIndex to match the track Spotify is actually playing. */
    fun updateCurrentIndexFromTrack(trackId: String) {
        val idx = currentQueue.indexOfFirst { it.id == trackId }
        if (idx >= 0) {
            currentIndex = idx
        }
    }

    // Expose queue for UI
    fun getCurrentQueue(): List<Track> = currentQueue.toList()
    override fun getCurrentIndex(): Int = currentIndex

    // PlaybackProvider state queries
    override fun getQueueAsTracks(): List<Track> = currentQueue.toList()
    override fun getCurrentPosition(): Long {
        // Interpolate: estimate real position from elapsed time since last update.
        // This keeps the position accurate between polling intervals and when
        // the CloudAmpService polling loop isn't running (mobile-only usage).
        if (lastKnownIsPlaying && lastPositionTimestamp > 0) {
            val elapsed = System.currentTimeMillis() - lastPositionTimestamp
            val estimated = lastKnownPosition + elapsed
            return if (lastKnownDuration > 0) minOf(estimated, lastKnownDuration) else estimated
        }
        return lastKnownPosition
    }
    override fun getDuration(): Long = lastKnownDuration
    override fun isPlaying(): Boolean = lastKnownIsPlaying

    fun setMediaSession(session: MediaSessionCompat) {
        mediaSession = session
    }

    fun setService(cloudAmpService: CloudAmpService) {
        service = cloudAmpService
    }

    // ── Device Activation ──────────────────────────────────────────────

    /**
     * Checks for an active Spotify device without any wake-up logic.
     * Returns a device ID if one is already active or available, null otherwise.
     */
    private suspend fun findActiveDevice(): String? {
        try {
            // Check current playback for an active device
            val playbackResponse = spotifyClient.api.getCurrentPlayback()
            if (playbackResponse.isSuccessful) {
                val playback = playbackResponse.body()
                if (playback?.device != null && playback.device.is_active) {
                    return playback.device.id
                }
            }

            // Check available (but inactive) devices
            val devices = spotifyClient.api.getAvailableDevices()
                .body()?.devices?.filter { it.id != null } ?: emptyList()

            if (devices.isEmpty()) return null

            val targetDevice = devices.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) }
                ?: devices.first()

            val deviceId = targetDevice.id ?: return null

            // Transfer playback to activate the device
            spotifyClient.api.transferPlayback(
                TransferPlaybackRequest(device_ids = listOf(deviceId), play = false)
            )
            delay(500)
            return deviceId
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Full wake flow: shows "Waking Spotify..." in Android Auto, launches Spotify,
     * polls for a device, pauses Spotify's old queue, and returns the device ID or null.
     */
    private suspend fun wakeSpotifyWithUI(): String? {
        // Suppress polling so it doesn't overwrite our UI states
        service?.suppressPollingUpdates = true

        // Show buffering state with "Waking Spotify..." message
        service?.updateStatusMetadata("Waking Spotify...")
        service?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)

        try {
            // Use Spotify App Remote SDK to wake Spotify — works even for force-stopped apps.
            // The SDK uses a content provider mechanism that bypasses Android's background
            // activity restrictions and can launch Spotify from any state.
            val connected = withTimeoutOrNull(10_000) {
                connectSpotifyAppRemote()
            } ?: false

            if (!connected) {
                // Fallback to legacy wake strategies (broadcasts, MediaBrowser, launch intent)
                wakeUpSpotifyLegacy()
            }

            // Poll for a Web API device. If App Remote connected, Spotify is alive
            // and the device should appear quickly.
            val maxPolls = if (connected) 20 else 30  // 10s or 15s
            val devices = pollForDevices(maxPolls)

            if (devices.isEmpty()) {
                return null
            }

            val targetDevice = devices.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) }
                ?: devices.first()

            val deviceId = targetDevice.id ?: return null

            // The legacy MEDIA_PLAY fallback may have started Spotify's old queue.
            // Pause it immediately so our play command starts clean.
            try {
                spotifyClient.api.pause()
            } catch (_: Exception) { }

            // Transfer playback to activate
            spotifyClient.api.transferPlayback(
                TransferPlaybackRequest(device_ids = listOf(deviceId), play = false)
            )
            delay(500)

            service?.updateStatusMetadata("Spotify connected")
            return deviceId
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ── Spotify App Remote SDK ─────────────────────────────────────────

    /**
     * Connects to Spotify via the App Remote SDK, which can launch Spotify even from
     * a force-stopped state. Returns true if the connection succeeded (Spotify is alive).
     * Disconnects immediately after — we only need this to wake Spotify, not for playback.
     */
    private suspend fun connectSpotifyAppRemote(): Boolean {
        val clientId = SpotifyAuthManager(context).getClientId() ?: return false

        return suspendCancellableCoroutine { continuation ->
            val connectionParams = ConnectionParams.Builder(clientId)
                .setRedirectUri("cloudamp://callback")
                .showAuthView(true)
                .build()

            SpotifyAppRemote.connect(context, connectionParams,
                object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) {
                        // Spotify is alive — disconnect immediately since we use Web API for playback
                        SpotifyAppRemote.disconnect(appRemote)
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onFailure(error: Throwable) {
                        error.printStackTrace()
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                })
        }
    }

    // ── Legacy wake strategies (fallback) ──────────────────────────────

    /**
     * Fallback: wakes Spotify using broadcasts, MediaBrowser, and launch intent.
     * Used when the App Remote SDK fails (e.g., first-time auth not yet completed).
     */
    private fun wakeUpSpotifyLegacy() {
        // Strategy 1: Send a MEDIA_PLAY key event to Spotify's package.
        try {
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                `package` = "com.spotify.music"
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                `package` = "com.spotify.music"
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            context.sendBroadcast(downIntent)
            context.sendBroadcast(upIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Strategy 2: Connect to Spotify's MediaBrowserService
        try {
            val browserComponent = ComponentName(
                "com.spotify.music",
                "com.spotify.music.internal.mediabrowser.MediaBrowserService"
            )
            val browser = MediaBrowserCompat(
                context,
                browserComponent,
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() { }
                    override fun onConnectionFailed() { }
                },
                null
            )
            browser.connect()
            scope.launch {
                delay(15_000)
                try { browser.disconnect() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Strategy 3: Launch intent
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Polls the Spotify devices endpoint until at least one device appears.
     * Polls every 500ms for up to [maxPolls] attempts (default 30 = 15 seconds).
     */
    private suspend fun pollForDevices(maxPolls: Int = 30): List<com.cloudamp.music.api.SpotifyDevice> {
        repeat(maxPolls) {
            delay(500)
            try {
                val response = spotifyClient.api.getAvailableDevices()
                val devices = response.body()?.devices?.filter { it.id != null } ?: emptyList()
                if (devices.isNotEmpty()) {
                    return devices
                }
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    // ── Play with device activation ────────────────────────────────────

    /**
     * Attempts to play via the Spotify API. If no device is active, shows the
     * "Waking Spotify..." UI and attempts to wake it. On failure, sets an error
     * state and stores the request for retry.
     */
    private suspend fun playWithDeviceActivation(
        request: PlayRequest,
        pending: PendingPlayback
    ): Response<Unit> {
        // Fast path: check if a device is already active
        val existingDevice = findActiveDevice()
        if (existingDevice != null) {
            val response = spotifyClient.api.play(request, existingDevice)
            if (response.isSuccessful) {
                pendingPlayback = null
                return response
            }
        }

        // No active device — store pending and start the visible wake flow.
        pendingPlayback = pending

        val deviceId = wakeSpotifyWithUI()

        if (deviceId != null) {
            // Spotify woke up — try to play
            val response = spotifyClient.api.play(request, deviceId)
            if (response.isSuccessful) {
                pendingPlayback = null
                service?.suppressPollingUpdates = false
                return response
            }

            // Try once more without device_id
            try {
                val fallbackResponse = spotifyClient.api.play(request)
                if (fallbackResponse.isSuccessful) {
                    pendingPlayback = null
                    service?.suppressPollingUpdates = false
                    return fallbackResponse
                }
            } catch (_: Exception) { }
        }

        // Wake failed — show error with retry hint
        service?.updateStatusMetadata("Could not connect to Spotify. Tap play to retry.")
        service?.updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        service?.suppressPollingUpdates = false
        // Return a failed response so callers know it didn't work
        return spotifyClient.api.play(request)
    }

    // ── MediaSession Callback ──────────────────────────────────────────

    val mediaSessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider

                    // Spotify-specific: check for a pending playback to retry
                    if (active == null || active is PlaybackManager) {
                        val pending = pendingPlayback
                        if (pending != null) {
                            when (pending) {
                                is PendingPlayback.SingleTrack -> playTrackFromMediaId(pending.trackUri)
                                is PendingPlayback.TrackList -> playTracks(pending.tracks, pending.startIndex)
                            }
                            return@launch
                        }
                    }

                    if (active != null) {
                        active.play()
                        service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, active.getCurrentPosition())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPause() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.pause()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, active.getCurrentPosition())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSkipToNext() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.skipToNext()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSkipToPrevious() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.skipToPrevious()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            scope.launch {
                try {
                    if (mediaId.startsWith("saved_queue_")) {
                        val queueId = mediaId.removePrefix("saved_queue_")
                        service?.playSavedQueue(queueId)
                    } else if (mediaId.startsWith("gdrive_file_")) {
                        val fileId = mediaId.removePrefix("gdrive_file_")
                        val parentId = extras?.getString("gdrive_parent_id")
                        service?.playGDriveFromMediaId(fileId, parentId)
                    } else if (mediaId.startsWith("jellyfin_track_")) {
                        val trackId = mediaId.removePrefix("jellyfin_track_")
                        val parentId = extras?.getString("jellyfin_parent_id")
                        service?.playJellyfinFromMediaId(trackId, parentId)
                    } else if (mediaId.startsWith("spotify:track:")) {
                        val playlistId = extras?.getString("playlist_id")
                        val albumId = extras?.getString("album_id")
                        if (playlistId != null) {
                            service?.playPlaylistFromMediaId(playlistId, mediaId)
                        } else if (albumId != null) {
                            playAlbumFromTrack(albumId, mediaId)
                        } else {
                            playTrackFromMediaId(mediaId)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) {
                onPlay()
                return
            }

            scope.launch {
                try {
                    val response = spotifyClient.api.search(query, "track", limit = 1)
                    if (response.isSuccessful) {
                        val tracks = response.body()?.tracks?.items
                        if (!tracks.isNullOrEmpty()) {
                            val track = tracks.first()
                            playTrackWithMetadata(track)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onStop() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.stop()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSeekTo(pos: Long) {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.seekTo(pos)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                "previous" -> onSkipToPrevious()
                "next" -> onSkipToNext()
                CloudAmpService.CUSTOM_ACTION_SAVE_QUEUE -> {
                    val saved = service?.saveCurrentQueue() ?: false
                    if (saved) {
                        service?.updateStatusMetadata("Queue saved!")
                        scope.launch {
                            delay(2000)
                            // Restore current track metadata
                            val active = ActivePlayback.provider ?: return@launch
                            val queue = active.getQueueAsTracks()
                            val idx = active.getCurrentIndex()
                            if (idx in queue.indices) {
                                val track = queue[idx]
                                service?.updateMetadata(track, track.album?.images?.firstOrNull()?.url)
                            }
                        }
                    }
                }
            }
        }

        override fun onSkipToQueueItem(id: Long) {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.skipToQueueItem(id.toInt())
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // ── Queue Management ───────────────────────────────────────────────

    fun setQueue(tracks: List<Track>) {
        currentQueue.clear()
        currentQueue.addAll(tracks)
        currentIndex = 0
    }

    // ── Playback Methods ───────────────────────────────────────────────

    private suspend fun playAlbumFromTrack(albumId: String, trackUri: String) {
        try {
            val albumResponse = spotifyClient.api.getAlbum(albumId)
            val album = albumResponse.body() ?: return playTrackFromMediaId(trackUri)

            val tracksResponse = spotifyClient.api.getAlbumTracks(albumId)
            val albumTracks = tracksResponse.body()?.items ?: return playTrackFromMediaId(trackUri)

            val tracks = albumTracks.map { track ->
                Track(
                    id = track.id,
                    name = track.name,
                    uri = track.uri,
                    artists = track.artists,
                    album = album,
                    durationMs = track.durationMs
                )
            }

            val startIndex = tracks.indexOfFirst { it.uri == trackUri }.takeIf { it >= 0 } ?: 0
            playTracks(tracks, startIndex)
        } catch (e: Exception) {
            e.printStackTrace()
            playTrackFromMediaId(trackUri)
        }
    }

    fun playTrack(trackUri: String) {
        scope.launch {
            playTrackFromMediaId(trackUri)
        }
    }

    private suspend fun playTrackFromMediaId(trackUri: String) {
        try {
            ActivePlayback.activate(this)
            val request = PlayRequest(uris = listOf(trackUri))
            val pending = PendingPlayback.SingleTrack(trackUri)
            val response = playWithDeviceActivation(request, pending)

            if (response.isSuccessful) {
                service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
            // Error state is handled inside playWithDeviceActivation
        } catch (e: Exception) {
            e.printStackTrace()
            service?.updatePlaybackStateError(
                PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                "Could not connect to Spotify. Tap play to retry."
            )
        }
    }

    private fun playTrackWithMetadata(track: Track) {
        scope.launch {
            try {
                val request = PlayRequest(uris = listOf(track.uri))
                val pending = PendingPlayback.SingleTrack(track.uri)
                val response = playWithDeviceActivation(request, pending)

                if (response.isSuccessful) {
                    service?.updateMetadata(track, track.album?.images?.firstOrNull()?.url)
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                service?.updatePlaybackStateError(
                    PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                    "Could not connect to Spotify. Tap play to retry."
                )
            }
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        scope.launch {
            try {
                ActivePlayback.activate(this@PlaybackManager)
                setQueue(tracks)
                currentIndex = startIndex
                service?.updateQueue(tracks, currentIndex)

                val trackUris = tracks.map { it.uri }
                val request = PlayRequest(
                    uris = trackUris,
                    offset = com.cloudamp.music.api.PlayOffset(position = startIndex)
                )
                val pending = PendingPlayback.TrackList(tracks, startIndex)
                val response = playWithDeviceActivation(request, pending)

                if (response.isSuccessful) {
                    lastKnownPosition = 0
                    lastKnownIsPlaying = true
                    lastPositionTimestamp = System.currentTimeMillis()
                    if (startIndex in tracks.indices) {
                        val currentTrack = tracks[startIndex]
                        service?.updateMetadata(currentTrack, currentTrack.album?.images?.firstOrNull()?.url)
                    }
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                service?.updatePlaybackStateError(
                    PlaybackStateCompat.ERROR_CODE_APP_ERROR,
                    "Could not connect to Spotify. Tap play to retry."
                )
            }
        }
    }

    private fun openSpotifyApp(trackUri: String?) {
        try {
            trackUri?.let { uri ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(
                    context,
                    "Please install Spotify app or connect a device",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun playTrackAtIndex(index: Int) {
        if (index in currentQueue.indices) {
            val track = currentQueue[index]
            playTrackWithMetadata(track)
        }
    }

    // ── PlaybackProvider transport controls ─────────────────────────────

    override suspend fun play() {
        val deviceId = findActiveDevice()
        val response = if (deviceId != null) {
            spotifyClient.api.play(PlayRequest(), deviceId)
        } else {
            spotifyClient.api.play(PlayRequest())
        }
        if (response.isSuccessful) {
            lastKnownIsPlaying = true
            lastPositionTimestamp = System.currentTimeMillis()
        }
    }

    override suspend fun pause() {
        // Freeze the interpolated position before marking as paused
        lastKnownPosition = getCurrentPosition()
        spotifyClient.api.pause()
        lastKnownIsPlaying = false
        lastPositionTimestamp = System.currentTimeMillis()
    }

    override suspend fun stop() {
        spotifyClient.api.pause()
        lastKnownPosition = 0
        lastKnownIsPlaying = false
        lastPositionTimestamp = 0
    }

    override suspend fun seekTo(positionMs: Long) {
        spotifyClient.api.seek(positionMs)
        lastKnownPosition = positionMs
        lastPositionTimestamp = System.currentTimeMillis()
    }

    override suspend fun skipToNext() {
        if (currentIndex < currentQueue.size - 1) {
            currentIndex++
            lastKnownPosition = 0
            lastPositionTimestamp = System.currentTimeMillis()
            playTrackAtIndex(currentIndex)
        } else {
            spotifyClient.api.next()
        }
    }

    override suspend fun skipToPrevious() {
        if (currentIndex > 0) {
            currentIndex--
            lastKnownPosition = 0
            lastPositionTimestamp = System.currentTimeMillis()
            playTrackAtIndex(currentIndex)
        } else {
            spotifyClient.api.previous()
        }
    }

    override suspend fun skipToQueueItem(index: Int) {
        if (index in currentQueue.indices) {
            currentIndex = index
            lastKnownPosition = 0
            lastPositionTimestamp = System.currentTimeMillis()
            playTrackAtIndex(index)
        }
    }

    fun getCurrentPlayback(callback: (String?, Boolean) -> Unit) {
        scope.launch {
            try {
                val response = spotifyClient.api.getCurrentPlayback()
                if (response.isSuccessful) {
                    val playback = response.body()
                    callback(playback?.item?.name, playback?.isPlaying ?: false)
                } else {
                    callback(null, false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null, false)
            }
        }
    }
}
