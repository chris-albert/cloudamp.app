package com.cloudamp.music.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import com.cloudamp.music.api.PlayRequest
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Track
import kotlinx.coroutines.*

class PlaybackManager(
    private val context: Context,
    private val spotifyClient: SpotifyApiClient
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentQueue = mutableListOf<Track>() // Full Track objects
    private var currentIndex = 0

    // Expose queue for UI
    fun getCurrentQueue(): List<Track> = currentQueue.toList()
    fun getCurrentIndex(): Int = currentIndex
    
    val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        
        override fun onPlay() {
            scope.launch {
                try {
                    spotifyClient.api.play(PlayRequest())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        override fun onPause() {
            scope.launch {
                try {
                    spotifyClient.api.pause()
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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        override fun onPlayFromMediaId(mediaId: String, extras: android.os.Bundle?) {
            scope.launch {
                try {
                    // Check if it's a Spotify URI
                    if (mediaId.startsWith("spotify:track:")) {
                        playTrack(mediaId)
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
    }
    
    fun setQueue(tracks: List<Track>) {
        currentQueue.clear()
        currentQueue.addAll(tracks)
        currentIndex = 0
    }

    fun playTrack(trackUri: String) {
        scope.launch {
            try {
                val request = PlayRequest(
                    uris = listOf(trackUri)
                )
                val response = spotifyClient.api.play(request)

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

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        scope.launch {
            try {
                setQueue(tracks)
                currentIndex = startIndex

                val trackUris = tracks.map { it.uri }
                val request = PlayRequest(
                    uris = trackUris,
                    offset = com.cloudamp.music.api.PlayOffset(position = startIndex)
                )
                val response = spotifyClient.api.play(request)

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
            playTrack(track.uri)
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
