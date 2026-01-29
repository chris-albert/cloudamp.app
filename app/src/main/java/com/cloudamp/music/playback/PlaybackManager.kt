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
import com.cloudamp.music.models.Track
import retrofit2.Response
import kotlinx.coroutines.*

class PlaybackManager private constructor(
    private val context: Context,
    private val spotifyClient: SpotifyApiClient
) {

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

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentQueue = mutableListOf<Track>()
    private var currentIndex = 0
    private var mediaSession: MediaSessionCompat? = null
    private var service: CloudAmpService? = null

    // Expose queue for UI
    fun getCurrentQueue(): List<Track> = currentQueue.toList()
    fun getCurrentIndex(): Int = currentIndex

    fun setMediaSession(session: MediaSessionCompat) {
        mediaSession = session
    }

    fun setService(cloudAmpService: CloudAmpService) {
        service = cloudAmpService
    }

    /**
     * Best-effort attempt to ensure there's an active Spotify device for playback.
     * Returns a device ID if one was found/activated, or null if we couldn't determine one.
     * Callers should still attempt playback even if this returns null.
     */
    private suspend fun ensureActiveDevice(): String? {
        try {
            // First check current playback to see if there's an active device
            val playbackResponse = spotifyClient.api.getCurrentPlayback()
            if (playbackResponse.isSuccessful) {
                val playback = playbackResponse.body()
                if (playback?.device != null && playback.device.is_active) {
                    return playback.device.id
                }
            }

            // No active device — check for available (but inactive) devices
            var devices = spotifyClient.api.getAvailableDevices()
                .body()?.devices?.filter { it.id != null } ?: emptyList()

            // If no devices at all, Spotify is likely not running — wake it up and poll
            if (devices.isEmpty()) {
                wakeUpSpotify()
                devices = pollForDevices()
                if (devices.isEmpty()) {
                    return null
                }
            }

            // Pick the best device to activate
            val targetDevice = devices.firstOrNull { it.type.equals("Smartphone", ignoreCase = true) }
                ?: devices.first()

            val deviceId = targetDevice.id ?: return null

            // Transfer playback to activate the device
            val transferRequest = TransferPlaybackRequest(
                device_ids = listOf(deviceId),
                play = false
            )
            val transferResponse = spotifyClient.api.transferPlayback(transferRequest)

            if (transferResponse.isSuccessful) {
                delay(500)
                return deviceId
            }

            // Transfer failed, but still return the device ID — the play call
            // with device_id might still work on an already-available device
            return deviceId
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Wakes up the Spotify app using multiple strategies so it registers as a Connect device.
     * Tries: media button broadcast, MediaBrowser connection, and launch intent.
     */
    private suspend fun wakeUpSpotify() {
        // Strategy 1: Send a media button broadcast to Spotify's package.
        // Broadcasts aren't blocked by background activity restrictions.
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

        // Strategy 2: Connect to Spotify's MediaBrowserService.
        // This forces Android to start the Spotify service process.
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
            // Disconnect after a delay to avoid leaking the connection
            scope.launch {
                delay(10_000)
                try { browser.disconnect() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Strategy 3: Launch intent (may work if foreground service conditions are met)
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
     * Returns the list of devices found, or empty if none appeared within the timeout.
     */
    private suspend fun pollForDevices(): List<com.cloudamp.music.api.SpotifyDevice> {
        // Poll every second for up to 10 seconds — cold-starting Spotify takes time
        repeat(10) {
            delay(1_000)
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

    /**
     * Attempts to play via the Spotify API, first trying with a device_id if available,
     * then falling back to playing without one. Returns the Response from the play call.
     */
    private suspend fun playWithDeviceActivation(request: PlayRequest): Response<Unit> {
        val deviceId = ensureActiveDevice()

        // Try playing with the device_id if we have one
        if (deviceId != null) {
            val response = spotifyClient.api.play(request, deviceId)
            if (response.isSuccessful) {
                return response
            }
        }

        // Fall back to playing without a device_id - Spotify may still find an active device
        return spotifyClient.api.play(request)
    }

    val mediaSessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() {
            scope.launch {
                try {
                    val response = playWithDeviceActivation(PlayRequest())
                    if (response.isSuccessful) {
                        service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPause() {
            scope.launch {
                try {
                    spotifyClient.api.pause()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSkipToNext() {
            scope.launch {
                try {
                    if (currentIndex < currentQueue.size - 1) {
                        currentIndex++
                        playTrackAtIndex(currentIndex)
                    } else {
                        spotifyClient.api.next()
                    }
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSkipToPrevious() {
            scope.launch {
                try {
                    if (currentIndex > 0) {
                        currentIndex--
                        playTrackAtIndex(currentIndex)
                    } else {
                        spotifyClient.api.previous()
                    }
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            scope.launch {
                try {
                    // Check if it's a Spotify URI
                    if (mediaId.startsWith("spotify:track:")) {
                        // Check if we have album context to queue the whole album
                        val albumId = extras?.getString("album_id")
                        if (albumId != null) {
                            playAlbumFromTrack(albumId, mediaId)
                        } else {
                            playTrackFromMediaId(mediaId)
                        }
                        // Note: playback state is updated by the play methods after API success
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) {
                // Resume playback
                onPlay()
                return
            }

            scope.launch {
                try {
                    // Search for the query and play the first result
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
                    spotifyClient.api.pause()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSeekTo(pos: Long) {
            scope.launch {
                try {
                    spotifyClient.api.seek(pos)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                "previous" -> onSkipToPrevious()
                "next" -> onSkipToNext()
            }
        }

        override fun onSkipToQueueItem(id: Long) {
            scope.launch {
                try {
                    val index = id.toInt()
                    if (index in currentQueue.indices) {
                        currentIndex = index
                        playTrackAtIndex(index)
                        service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun setQueue(tracks: List<Track>) {
        currentQueue.clear()
        currentQueue.addAll(tracks)
        currentIndex = 0
    }

    private suspend fun playAlbumFromTrack(albumId: String, trackUri: String) {
        try {
            // Fetch album details and tracks
            val albumResponse = spotifyClient.api.getAlbum(albumId)
            val album = albumResponse.body() ?: return playTrack(trackUri)

            val tracksResponse = spotifyClient.api.getAlbumTracks(albumId)
            val albumTracks = tracksResponse.body()?.items ?: return playTrack(trackUri)

            // Convert to Track objects with album info
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

            // Find the position of the clicked track
            val startIndex = tracks.indexOfFirst { it.uri == trackUri }.takeIf { it >= 0 } ?: 0

            // Play all tracks starting from the selected one
            playTracks(tracks, startIndex)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to single track playback
            playTrack(trackUri)
        }
    }

    fun playTrack(trackUri: String) {
        scope.launch {
            playTrackFromMediaId(trackUri)
        }
    }

    /**
     * Suspend function to play a track by URI, ensuring device is active first.
     * Used internally from coroutines (e.g., onPlayFromMediaId callback).
     */
    private suspend fun playTrackFromMediaId(trackUri: String) {
        try {
            val request = PlayRequest(uris = listOf(trackUri))
            val response = playWithDeviceActivation(request)

            if (response.isSuccessful) {
                service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            } else {
                service?.updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                openSpotifyApp(trackUri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            service?.updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
            openSpotifyApp(trackUri)
        }
    }

    private fun playTrackWithMetadata(track: Track) {
        scope.launch {
            try {
                val request = PlayRequest(uris = listOf(track.uri))
                val response = playWithDeviceActivation(request)

                if (response.isSuccessful) {
                    service?.updateMetadata(track, track.album?.images?.firstOrNull()?.url)
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                } else {
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                    openSpotifyApp(track.uri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                service?.updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                openSpotifyApp(track.uri)
            }
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        scope.launch {
            try {
                setQueue(tracks)
                currentIndex = startIndex

                // Update MediaSession queue for Android Auto
                service?.updateQueue(tracks, currentIndex)

                val trackUris = tracks.map { it.uri }
                val request = PlayRequest(
                    uris = trackUris,
                    offset = com.cloudamp.music.api.PlayOffset(position = startIndex)
                )
                val response = playWithDeviceActivation(request)

                if (response.isSuccessful) {
                    // Update metadata for current track
                    if (startIndex in tracks.indices) {
                        val currentTrack = tracks[startIndex]
                        service?.updateMetadata(currentTrack, currentTrack.album?.images?.firstOrNull()?.url)
                    }
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                } else {
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                    openSpotifyApp(tracks.getOrNull(startIndex)?.uri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                service?.updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
                openSpotifyApp(tracks.getOrNull(startIndex)?.uri)
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
            // Show toast on main thread
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
