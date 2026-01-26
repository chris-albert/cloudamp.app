package com.cloudamp.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.cloudamp.music.MainActivity
import com.cloudamp.music.R
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Track
import kotlinx.coroutines.*

class CloudAmpService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentAlbumArt: Bitmap? = null
    private var playbackPollingJob: Job? = null

    companion object {
        const val ROOT_ID = "root"
        const val ARTISTS_ID = "artists"
        const val ALBUMS_ID = "albums"
        const val TRACKS_ID = "tracks"
        const val TOP_ARTISTS_ID = "top_artists"
        const val TOP_TRACKS_ID = "top_tracks"
        const val SAVED_ALBUMS_ID = "saved_albums"
        const val SEARCH_ID = "search"

        private const val CHANNEL_ID = "cloudamp_playback"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)

        // Create MediaSession
        mediaSession = MediaSessionCompat(this, "CloudAmpService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS
            )

            // Set available actions
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
                    .setActions(getAvailableActions())
                    .build()
            )

            val sessionIntent = packageManager.getLaunchIntentForPackage(packageName)
            val pendingIntent = PendingIntent.getActivity(
                this@CloudAmpService,
                0,
                sessionIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            setSessionActivity(pendingIntent)

            isActive = true
        }

        // Set the callback on MediaSession and pass reference to PlaybackManager
        playbackManager.setMediaSession(mediaSession)
        playbackManager.setService(this)
        mediaSession.setCallback(playbackManager.mediaSessionCallback)

        sessionToken = mediaSession.sessionToken

        // Start polling for playback state from Spotify
        startPlaybackPolling()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CloudAmp playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getAvailableActions(): Long {
        return PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
    }

    fun updateNotification(isPlaying: Boolean) {
        val notification = buildNotification(isPlaying)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val controller = mediaSession.controller
        val mediaMetadata = controller.metadata
        val description = mediaMetadata?.description

        val builder = NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setContentTitle(description?.title ?: "CloudAmp")
            setContentText(description?.subtitle ?: "Ready to play")
            setSubText(description?.description)
            setSmallIcon(R.drawable.ic_notification)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setOnlyAlertOnce(true)
            setShowWhen(false)

            // Set album art
            currentAlbumArt?.let { setLargeIcon(it) }

            // Launch app when notification is tapped
            setContentIntent(controller.sessionActivity)

            // Stop service when notification is dismissed
            setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this@CloudAmpService,
                    PlaybackStateCompat.ACTION_STOP
                )
            )

            // Add transport controls
            addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_previous,
                    "Previous",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this@CloudAmpService,
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            )

            if (isPlaying) {
                addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_pause,
                        "Pause",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this@CloudAmpService,
                            PlaybackStateCompat.ACTION_PAUSE
                        )
                    )
                )
            } else {
                addAction(
                    NotificationCompat.Action(
                        R.drawable.ic_play,
                        "Play",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this@CloudAmpService,
                            PlaybackStateCompat.ACTION_PLAY
                        )
                    )
                )
            }

            addAction(
                NotificationCompat.Action(
                    R.drawable.ic_skip_next,
                    "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this@CloudAmpService,
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                )
            )

            // Media style with transport controls
            setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this@CloudAmpService,
                            PlaybackStateCompat.ACTION_STOP
                        )
                    )
            )
        }

        return builder.build()
    }

    fun updateMetadata(track: Track?, albumArtUrl: String?) {
        if (track == null) {
            mediaSession.setMetadata(null)
            return
        }

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.uri)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artists.joinToString(", ") { it.name })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album?.name ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs.toLong())

        // Set album art URI for Android Auto
        albumArtUrl?.let { url ->
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, url)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, url)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, url)

            // Also load bitmap for notification
            loadAlbumArt(url)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun loadAlbumArt(url: String) {
        Glide.with(this)
            .asBitmap()
            .load(url)
            .into(object : CustomTarget<Bitmap>(300, 300) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    currentAlbumArt = resource
                    // Refresh notification with album art
                    val state = mediaSession.controller.playbackState?.state
                    val isPlaying = state == PlaybackStateCompat.STATE_PLAYING
                    updateNotification(isPlaying)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    currentAlbumArt = null
                }
            })
    }

    fun updatePlaybackState(state: Int, position: Long = 0, playbackSpeed: Float = 1.0f) {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(state, position, playbackSpeed)
            .setActions(getAvailableActions())

        // Add queue navigation
        val queue = playbackManager.getCurrentQueue()
        val currentIndex = playbackManager.getCurrentIndex()

        if (currentIndex > 0) {
            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    "previous",
                    "Previous",
                    R.drawable.ic_skip_previous
                ).build()
            )
        }

        if (currentIndex < queue.size - 1) {
            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    "next",
                    "Next",
                    R.drawable.ic_skip_next
                ).build()
            )
        }

        mediaSession.setPlaybackState(stateBuilder.build())

        // Update notification
        val isPlaying = state == PlaybackStateCompat.STATE_PLAYING
        updateNotification(isPlaying)
    }

    private fun startPlaybackPolling() {
        playbackPollingJob?.cancel()
        playbackPollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    val response = spotifyClient.api.getCurrentPlayback()
                    if (response.isSuccessful) {
                        val playback = response.body()
                        if (playback != null) {
                            val state = if (playback.isPlaying) {
                                PlaybackStateCompat.STATE_PLAYING
                            } else {
                                PlaybackStateCompat.STATE_PAUSED
                            }

                            updatePlaybackState(
                                state,
                                playback.progressMs.toLong(),
                                1.0f
                            )

                            // Update metadata if track info available
                            playback.item?.let { track ->
                                updateMetadata(track, track.album?.images?.firstOrNull()?.url)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore polling errors
                }
                delay(3000) // Poll every 3 seconds
            }
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        // Allow all clients (Android Auto, etc.)
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
                    // Root menu - main categories
                    mediaItems.add(createBrowsableItem(TOP_TRACKS_ID, "Top Tracks", "Your most played tracks"))
                    mediaItems.add(createBrowsableItem(TOP_ARTISTS_ID, "Top Artists", "Your favorite artists"))
                    mediaItems.add(createBrowsableItem(SAVED_ALBUMS_ID, "Saved Albums", "Albums in your library"))
                }

                TOP_TRACKS_ID -> {
                    loadTopTracks(mediaItems)
                }

                TOP_ARTISTS_ID -> {
                    loadTopArtists(mediaItems)
                }

                SAVED_ALBUMS_ID -> {
                    loadSavedAlbums(mediaItems)
                }

                ARTISTS_ID -> {
                    loadTopArtists(mediaItems)
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

    private suspend fun loadTopTracks(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val response = spotifyClient.api.getTopTracks(limit = 50)
            if (response.isSuccessful) {
                response.body()?.items?.forEach { track ->
                    items.add(createPlayableItem(
                        track.uri,
                        track.name,
                        track.artists.joinToString(", ") { it.name },
                        track.album?.images?.firstOrNull()?.url
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadTopArtists(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val response = spotifyClient.api.getTopArtists(limit = 50)
            if (response.isSuccessful) {
                response.body()?.items?.forEach { artist ->
                    items.add(createBrowsableItem(
                        "artist_${artist.id}",
                        artist.name,
                        "Artist",
                        artist.images?.firstOrNull()?.url
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadSavedAlbums(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val response = spotifyClient.api.getSavedAlbums(limit = 50)
            if (response.isSuccessful) {
                response.body()?.items?.forEach { savedAlbum ->
                    val album = savedAlbum.album
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
            // First fetch album info to get artwork
            val albumResponse = spotifyClient.api.getAlbum(albumId)
            val album = albumResponse.body()
            val albumArtUrl = album?.images?.firstOrNull()?.url

            // Then fetch tracks
            val tracksResponse = spotifyClient.api.getAlbumTracks(albumId)
            if (tracksResponse.isSuccessful) {
                tracksResponse.body()?.items?.forEach { track ->
                    items.add(createPlayableItem(
                        track.uri,
                        track.name,
                        track.artists.joinToString(", ") { it.name },
                        albumArtUrl
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

                    // Add tracks first (most likely what user wants)
                    searchResult?.tracks?.items?.forEach { track ->
                        mediaItems.add(createPlayableItem(
                            track.uri,
                            track.name,
                            track.artists.joinToString(", ") { it.name },
                            track.album.images?.firstOrNull()?.url
                        ))
                    }

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

    override fun onDestroy() {
        playbackPollingJob?.cancel()
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }
}
