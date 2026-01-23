package com.cloudamp.music.playback

import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.cloudamp.music.api.PlayRequest
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Track
import kotlinx.coroutines.*

class PlaybackManager(
    private val context: Context,
    private val spotifyClient: SpotifyApiClient
) {
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentQueue = mutableListOf<String>() // Track URIs
    private var currentIndex = 0
    
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
    
    fun addToQueue(trackUri: String) {
        currentQueue.add(trackUri)
    }
    
    fun setQueue(trackUris: List<String>) {
        currentQueue.clear()
        currentQueue.addAll(trackUris)
        currentIndex = 0
    }
    
    fun playTrack(trackUri: String) {
        scope.launch {
            try {
                val request = PlayRequest(
                    uris = listOf(trackUri)
                )
                spotifyClient.api.play(request)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun playTracks(trackUris: List<String>, startIndex: Int = 0) {
        scope.launch {
            try {
                setQueue(trackUris)
                currentIndex = startIndex
                
                val request = PlayRequest(
                    uris = trackUris,
                    offset = com.cloudamp.music.api.PlayOffset(position = startIndex)
                )
                spotifyClient.api.play(request)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private suspend fun playTrackAtIndex(index: Int) {
        if (index in currentQueue.indices) {
            val trackUri = currentQueue[index]
            playTrack(trackUri)
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
