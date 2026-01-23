package com.cloudamp.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.cloudamp.music.MainActivity
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Track
import kotlinx.coroutines.*

class CloudAmpService : MediaBrowserServiceCompat() {
    
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        const val ROOT_ID = "root"
        const val ARTISTS_ID = "artists"
        const val ALBUMS_ID = "albums"
        const val TRACKS_ID = "tracks"
        const val SEARCH_ID = "search"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager(this, spotifyClient)
        
        // Create MediaSession
        mediaSession = MediaSessionCompat(this, "CloudAmpService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            
            setCallback(playbackManager.mediaSessionCallback)
            
            val sessionIntent = packageManager.getLaunchIntentForPackage(packageName)
            val pendingIntent = PendingIntent.getActivity(
                this@CloudAmpService,
                0,
                sessionIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            setSessionActivity(pendingIntent)
            
            isActive = true
        }
        
        sessionToken = mediaSession.sessionToken
        
        // Update playback state
        updatePlaybackState(PlaybackStateCompat.STATE_NONE)
    }
    
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }
    
    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        
        serviceScope.launch {
            val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()
            
            when (parentId) {
                ROOT_ID -> {
                    // Root menu
                    mediaItems.add(createBrowsableItem(ARTISTS_ID, "Artists", "Browse by artist"))
                    mediaItems.add(createBrowsableItem(SEARCH_ID, "Search", "Search for music"))
                }
                
                ARTISTS_ID -> {
                    // Load artists from search/cache
                    // For now, showing search prompt
                    mediaItems.add(createPlayableItem(
                        "search_artists",
                        "Search for Artists",
                        "Use voice search to find artists"
                    ))
                }
                
                else -> {
                    // Handle dynamic IDs
                    when {
                        parentId.startsWith("artist_") -> {
                            val artistId = parentId.removePrefix("artist_")
                            loadArtistAlbums(artistId, mediaItems)
                        }
                        parentId.startsWith("album_") -> {
                            val albumId = parentId.removePrefix("album_")
                            loadAlbumTracks(albumId, mediaItems)
                        }
                    }
                }
            }
            
            result.sendResult(mediaItems)
        }
    }
    
    private suspend fun loadArtistAlbums(artistId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val response = spotifyClient.api.getArtistAlbums(artistId)
            if (response.isSuccessful) {
                response.body()?.items?.forEach { album ->
                    items.add(createBrowsableItem(
                        "album_${album.id}",
                        album.name,
                        album.artists.joinToString(", ") { it.name },
                        album.images?.firstOrNull()?.url
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun loadAlbumTracks(albumId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val response = spotifyClient.api.getAlbumTracks(albumId)
            if (response.isSuccessful) {
                response.body()?.items?.forEach { track ->
                    items.add(createPlayableItem(
                        track.uri,
                        track.name,
                        track.artists.joinToString(", ") { it.name },
                        track.album.images?.firstOrNull()?.url
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        
        serviceScope.launch {
            val mediaItems = mutableListOf<MediaBrowserCompat.MediaItem>()
            
            try {
                val response = spotifyClient.api.search(query, "artist,album,track")
                if (response.isSuccessful) {
                    val searchResult = response.body()
                    
                    // Add artists
                    searchResult?.artists?.items?.forEach { artist ->
                        mediaItems.add(createBrowsableItem(
                            "artist_${artist.id}",
                            artist.name,
                            "Artist",
                            artist.images?.firstOrNull()?.url
                        ))
                    }
                    
                    // Add albums
                    searchResult?.albums?.items?.forEach { album ->
                        mediaItems.add(createBrowsableItem(
                            "album_${album.id}",
                            album.name,
                            album.artists.joinToString(", ") { it.name },
                            album.images?.firstOrNull()?.url
                        ))
                    }
                    
                    // Add tracks
                    searchResult?.tracks?.items?.forEach { track ->
                        mediaItems.add(createPlayableItem(
                            track.uri,
                            track.name,
                            track.artists.joinToString(", ") { it.name },
                            track.album.images?.firstOrNull()?.url
                        ))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            result.sendResult(mediaItems)
        }
    }
    
    private fun createBrowsableItem(
        id: String,
        title: String,
        subtitle: String,
        iconUri: String? = null
    ): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply {
                iconUri?.let { setIconUri(android.net.Uri.parse(it)) }
            }
            .build()
        
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }
    
    private fun createPlayableItem(
        id: String,
        title: String,
        subtitle: String,
        iconUri: String? = null
    ): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply {
                iconUri?.let { setIconUri(android.net.Uri.parse(it)) }
            }
            .build()
        
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }
    
    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, 0, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()
        
        mediaSession.setPlaybackState(playbackState)
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }
}
