package com.cloudamp.music.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import com.cloudamp.music.api.PlayRequest
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Track
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

    val mediaSessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() {
            scope.launch {
                try {
                    spotifyClient.api.play(PlayRequest())
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
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
                            playTrack(mediaId)
                        }
                        service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
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
            try {
                val request = PlayRequest(
                    uris = listOf(trackUri)
                )
                val response = spotifyClient.api.play(request)

                service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)

                // If API call fails, open Spotify app
                if (!response.isSuccessful) {
                    openSpotifyApp(trackUri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                openSpotifyApp(trackUri)
            }
        }
    }

    private fun playTrackWithMetadata(track: Track) {
        scope.launch {
            try {
                val request = PlayRequest(
                    uris = listOf(track.uri)
                )
                val response = spotifyClient.api.play(request)

                // Update metadata
                service?.updateMetadata(track, track.album?.images?.firstOrNull()?.url)
                service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)

                // If API call fails, open Spotify app
                if (!response.isSuccessful) {
                    openSpotifyApp(track.uri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                val response = spotifyClient.api.play(request)

                // Update metadata for current track
                if (startIndex in tracks.indices) {
                    val currentTrack = tracks[startIndex]
                    service?.updateMetadata(currentTrack, currentTrack.album?.images?.firstOrNull()?.url)
                }
                service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)

                // If API call fails (no active device or not premium), open Spotify app
                if (!response.isSuccessful) {
                    val errorCode = response.code()
                    if (errorCode == 404 || errorCode == 403) {
                        // No active device or forbidden - open Spotify app
                        openSpotifyApp(tracks.getOrNull(startIndex)?.uri)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // On error, try opening Spotify app
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
