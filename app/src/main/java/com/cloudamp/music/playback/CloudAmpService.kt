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
import android.support.v4.media.session.MediaSessionCompat.QueueItem
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
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.cache.LibraryCache
import com.cloudamp.music.api.Playlist
import com.cloudamp.music.models.Album
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Track
import kotlinx.coroutines.*

class CloudAmpService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private lateinit var gdrivePlaybackManager: GDrivePlaybackManager
    private lateinit var gdriveClient: GoogleDriveApiClient
    private lateinit var libraryCache: LibraryCache
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentAlbumArt: Bitmap? = null
    private var playbackPollingJob: Job? = null

    // Cache of audio files per GDrive folder for building playback queues
    private val gdriveAudioFilesByFolder = mutableMapOf<String, List<DriveFile>>()

    // When true, playback polling skips state updates (e.g., during Spotify wake flow)
    var suppressPollingUpdates = false

    companion object {
        const val ROOT_ID = "root"
        const val ARTISTS_ID = "artists"
        const val ALBUMS_ID = "albums"
        const val TRACKS_ID = "tracks"
        const val TOP_ARTISTS_ID = "top_artists"
        const val TOP_TRACKS_ID = "top_tracks"
        const val SAVED_ALBUMS_ID = "saved_albums"
        const val LIBRARY_ID = "library"
        const val PLAYLISTS_ID = "playlists"
        const val GDRIVE_ID = "gdrive"
        const val SEARCH_ID = "search"

        private const val CHANNEL_ID = "cloudamp_playback"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)
        gdrivePlaybackManager = GDrivePlaybackManager.getInstance(this)
        gdriveClient = GoogleDriveApiClient.getInstance(this)
        libraryCache = LibraryCache.getInstance(this)

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
        gdrivePlaybackManager.setService(this)
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
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
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

        // Add queue navigation - use the correct provider's queue
        val queue: List<Track>
        val currentIndex: Int
        if (GDrivePlaybackManager.isActiveProvider) {
            queue = gdrivePlaybackManager.getQueueAsTracks()
            currentIndex = gdrivePlaybackManager.getCurrentIndex()
        } else {
            queue = playbackManager.getCurrentQueue()
            currentIndex = playbackManager.getCurrentIndex()
        }

        // Set active queue item ID for Android Auto to highlight current track
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            stateBuilder.setActiveQueueItemId(currentIndex.toLong())
        }

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

    /**
     * Sets the playback state to STATE_ERROR with an error message shown in Android Auto.
     */
    fun updatePlaybackStateError(errorCode: Int, message: String) {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
            .setActions(getAvailableActions())
            .setErrorMessage(errorCode, message)

        mediaSession.setPlaybackState(stateBuilder.build())
        updateNotification(false)
    }

    /**
     * Sets metadata to show a status message (e.g., "Waking Spotify...") during loading.
     */
    fun updateStatusMetadata(message: String) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, message)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "CloudAmp")
        mediaSession.setMetadata(metadataBuilder.build())
    }

    fun updateQueue(tracks: List<Track>, currentIndex: Int) {
        val queueItems = tracks.mapIndexed { index, track ->
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(track.uri)
                .setTitle(track.name)
                .setSubtitle(track.artists.joinToString(", ") { it.name })
                .apply {
                    track.album?.images?.firstOrNull()?.url?.let { url ->
                        setIconUri(android.net.Uri.parse(url))
                    }
                }
                .build()
            QueueItem(description, index.toLong())
        }
        mediaSession.setQueue(queueItems)
        mediaSession.setQueueTitle("Now Playing")
    }

    private fun startPlaybackPolling() {
        playbackPollingJob?.cancel()
        playbackPollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (!suppressPollingUpdates) {
                        if (GDrivePlaybackManager.isActiveProvider) {
                            // Poll ExoPlayer position for Google Drive playback
                            val position = gdrivePlaybackManager.getCurrentPosition()
                            val state = if (gdrivePlaybackManager.isPlaying()) {
                                PlaybackStateCompat.STATE_PLAYING
                            } else {
                                PlaybackStateCompat.STATE_PAUSED
                            }
                            updatePlaybackState(state, position, 1.0f)
                        } else {
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
                        }
                    }
                } catch (e: Exception) {
                    // Ignore polling errors
                }
                // Poll every 1s for GDrive (cheap local call), 3s for Spotify (network API)
                delay(if (GDrivePlaybackManager.isActiveProvider) 1000 else 3000)
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
                    mediaItems.add(createBrowsableItem(LIBRARY_ID, "Library", "Your followed artists"))
                    mediaItems.add(createBrowsableItem(PLAYLISTS_ID, "Playlists", "Your playlists"))
                    mediaItems.add(createBrowsableItem(GDRIVE_ID, "Google Drive", "Browse your Drive music"))
                }

                TOP_TRACKS_ID -> {
                    loadTopTracks(mediaItems)
                }

                TOP_ARTISTS_ID -> {
                    loadTopArtists(mediaItems)
                }

                LIBRARY_ID -> {
                    loadTopArtists(mediaItems)
                }

                PLAYLISTS_ID -> {
                    loadPlaylists(mediaItems)
                }

                SAVED_ALBUMS_ID -> {
                    loadSavedAlbums(mediaItems)
                }

                GDRIVE_ID -> {
                    loadGDriveFolder("root", mediaItems)
                }

                ARTISTS_ID -> {
                    loadTopArtists(mediaItems)
                }

                else -> {
                    // Handle dynamic IDs
                    when {
                        parentId == "gdrive_no_auth" -> {
                            // No-op: placeholder item when GDrive is not connected
                        }
                        parentId.startsWith("gdrive_folder_") -> {
                            val folderId = parentId.removePrefix("gdrive_folder_")
                            loadGDriveFolder(folderId, mediaItems)
                        }
                        parentId.startsWith("section_letter_artist_") -> {
                            // Show artists for this letter
                            val letter = parentId.removePrefix("section_letter_artist_")
                            loadArtistsForLetter(letter, mediaItems)
                        }
                        parentId.startsWith("section_letter_album_") -> {
                            // Show albums for this letter
                            val letter = parentId.removePrefix("section_letter_album_")
                            loadAlbumsForLetter(letter, mediaItems)
                        }
                        parentId.startsWith("section_letter_playlist_") -> {
                            // Show playlists for this letter
                            val letter = parentId.removePrefix("section_letter_playlist_")
                            loadPlaylistsForLetter(letter, mediaItems)
                        }
                        parentId.startsWith("section_type_") -> {
                            // Type headers don't navigate - return empty
                        }
                        parentId.startsWith("playlist_") -> {
                            val playlistId = parentId.removePrefix("playlist_")
                            loadPlaylistTracks(playlistId, mediaItems)
                        }
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
            // Try to use cached artists first
            val artists = libraryCache.getArtists()?.sortedBy { it.name }
                ?: loadFollowedArtistsFromApi()?.sortedBy { it.name }
                ?: emptyList()

            for (artist in artists) {
                items.add(createBrowsableItem(
                    "artist_${artist.id}",
                    artist.name,
                    "Artist",
                    artist.images?.firstOrNull()?.url
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadArtistsForLetter(letter: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val artists = libraryCache.getArtists()?.sortedBy { it.name }
                ?: loadFollowedArtistsFromApi()?.sortedBy { it.name }
                ?: emptyList()

            val targetLetter = letter.firstOrNull()?.uppercaseChar() ?: '#'

            for (artist in artists) {
                val firstLetter = artist.name.firstOrNull()?.uppercaseChar() ?: '#'
                val artistLetter = if (firstLetter.isLetter()) firstLetter else '#'

                if (artistLetter == targetLetter) {
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

    private suspend fun loadAlbumsForLetter(letter: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val allAlbums = loadAllSavedAlbumsFromApi()
            val sortedAlbums = allAlbums.sortedBy { it.name }
            val targetLetter = letter.firstOrNull()?.uppercaseChar() ?: '#'

            for (album in sortedAlbums) {
                val firstLetter = album.name.firstOrNull()?.uppercaseChar() ?: '#'
                val albumLetter = if (firstLetter.isLetter()) firstLetter else '#'

                if (albumLetter == targetLetter) {
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

    private suspend fun loadPlaylists(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val allPlaylists = loadAllPlaylistsFromApi()

            // Sort by playlist name and group by first letter using group title hint
            val sortedPlaylists = allPlaylists.sortedBy { it.name }

            for (playlist in sortedPlaylists) {
                val firstLetter = playlist.name.firstOrNull()?.uppercaseChar() ?: '#'
                val letter = if (firstLetter.isLetter()) firstLetter else '#'

                items.add(createBrowsableItemWithGroup(
                    "playlist_${playlist.id}",
                    playlist.name,
                    "${playlist.tracks.total} tracks",
                    letter.toString(),
                    playlist.images?.firstOrNull()?.url
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadPlaylistsForLetter(letter: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val allPlaylists = loadAllPlaylistsFromApi()
            val sortedPlaylists = allPlaylists.sortedBy { it.name }
            val targetLetter = letter.firstOrNull()?.uppercaseChar() ?: '#'

            for (playlist in sortedPlaylists) {
                val firstLetter = playlist.name.firstOrNull()?.uppercaseChar() ?: '#'
                val playlistLetter = if (firstLetter.isLetter()) firstLetter else '#'

                if (playlistLetter == targetLetter) {
                    items.add(createBrowsableItem(
                        "playlist_${playlist.id}",
                        playlist.name,
                        "${playlist.tracks.total} tracks",
                        playlist.images?.firstOrNull()?.url
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadAllPlaylistsFromApi(): List<Playlist> {
        val allPlaylists = mutableListOf<Playlist>()
        var offset = 0
        val limit = 50

        do {
            val response = spotifyClient.api.getMyPlaylists(limit = limit, offset = offset)
            if (response.isSuccessful) {
                val playlists = response.body()?.items ?: emptyList()
                allPlaylists.addAll(playlists)
                offset += limit
                if (playlists.size < limit) break
            } else {
                break
            }
        } while (true)

        return allPlaylists
    }

    private suspend fun loadPlaylistTracks(playlistId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val response = spotifyClient.api.getPlaylistTracks(playlistId)
            if (response.isSuccessful) {
                response.body()?.items?.forEach { playlistTrack ->
                    val track = playlistTrack.track
                    if (track != null) {
                        items.add(createPlayableItem(
                            track.uri,
                            track.name,
                            track.artists.joinToString(", ") { it.name },
                            track.album?.images?.firstOrNull()?.url,
                            playlistId
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadFollowedArtistsFromApi(): List<Artist>? {
        val allArtists = mutableListOf<Artist>()
        var after: String? = null

        do {
            val response = spotifyClient.api.getFollowedArtists(limit = 50, after = after)
            if (response.isSuccessful) {
                val artistsPage = response.body()?.artists
                val artists = artistsPage?.items ?: emptyList()
                allArtists.addAll(artists)
                after = artistsPage?.cursors?.after
            } else {
                return if (allArtists.isNotEmpty()) allArtists else null
            }
        } while (after != null)

        return allArtists
    }

    private suspend fun loadSavedAlbums(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val allAlbums = loadAllSavedAlbumsFromApi()

            // Sort by album name and group by first letter using group title hint
            val sortedAlbums = allAlbums.sortedBy { it.name }

            for (album in sortedAlbums) {
                val firstLetter = album.name.firstOrNull()?.uppercaseChar() ?: '#'
                val letter = if (firstLetter.isLetter()) firstLetter else '#'

                items.add(createBrowsableItemWithGroup(
                    "album_${album.id}",
                    album.name,
                    album.artists.joinToString(", ") { it.name },
                    letter.toString(),
                    album.images?.firstOrNull()?.url
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadAllSavedAlbumsFromApi(): List<Album> {
        val allAlbums = mutableListOf<Album>()
        var offset = 0
        val limit = 50

        do {
            val response = spotifyClient.api.getSavedAlbums(limit = limit, offset = offset)
            if (response.isSuccessful) {
                val albums = response.body()?.items?.map { it.album } ?: emptyList()
                allAlbums.addAll(albums)
                offset += limit
                if (albums.size < limit) break
            } else {
                break
            }
        } while (true)

        return allAlbums
    }

    private suspend fun loadArtistAlbums(artistId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val response = spotifyClient.api.getArtistAlbums(artistId)
            if (response.isSuccessful) {
                val albums = response.body()?.items ?: emptyList()

                // Get saved album IDs for categorization
                val savedAlbumIds = libraryCache.getSavedAlbumIds()

                // Group albums into categories
                val savedAlbums = mutableListOf<Album>()
                val lps = mutableListOf<Album>()
                val eps = mutableListOf<Album>()
                val singles = mutableListOf<Album>()

                for (album in albums) {
                    when {
                        savedAlbumIds.contains(album.id) -> savedAlbums.add(album)
                        album.getAlbumCategory() == "single" -> singles.add(album)
                        album.getAlbumCategory() == "ep" -> eps.add(album)
                        else -> lps.add(album)
                    }
                }

                // Sort each group by release date (newest first)
                val sortByReleaseDate: (Album) -> String = { it.releaseDate ?: "" }

                // Add Saved Albums section using group title hint
                savedAlbums.sortedByDescending(sortByReleaseDate).forEach { album ->
                    items.add(createBrowsableItemWithGroup(
                        "album_${album.id}",
                        album.name,
                        album.releaseDate?.take(4) ?: "",
                        "♥ SAVED",
                        album.images?.firstOrNull()?.url
                    ))
                }

                // Add LP's section
                lps.sortedByDescending(sortByReleaseDate).forEach { album ->
                    items.add(createBrowsableItemWithGroup(
                        "album_${album.id}",
                        album.name,
                        album.releaseDate?.take(4) ?: "",
                        "LP's",
                        album.images?.firstOrNull()?.url
                    ))
                }

                // Add EP's section
                eps.sortedByDescending(sortByReleaseDate).forEach { album ->
                    items.add(createBrowsableItemWithGroup(
                        "album_${album.id}",
                        album.name,
                        album.releaseDate?.take(4) ?: "",
                        "EP's",
                        album.images?.firstOrNull()?.url
                    ))
                }

                // Add Singles section
                singles.sortedByDescending(sortByReleaseDate).forEach { album ->
                    items.add(createBrowsableItemWithGroup(
                        "album_${album.id}",
                        album.name,
                        album.releaseDate?.take(4) ?: "",
                        "SINGLES",
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
                        albumArtUrl,
                        albumId
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Google Drive browsing ─────────────────────────────────────────

    private suspend fun loadGDriveFolder(folderId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            if (!gdriveClient.hasAccessToken()) {
                // No auth — show a hint to open the app
                items.add(createBrowsableItem(
                    "gdrive_no_auth",
                    "Connect Google Drive",
                    "Open CloudAmp app to sign in"
                ))
                return
            }

            val allFiles = mutableListOf<DriveFile>()
            var pageToken: String? = null
            val query = "'$folderId' in parents and trashed = false and " +
                    "(mimeType contains 'audio/' or mimeType = 'application/vnd.google-apps.folder')"

            do {
                val response = gdriveClient.api.listFiles(
                    query = query,
                    pageToken = pageToken
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    allFiles.addAll(body?.files ?: emptyList())
                    pageToken = body?.nextPageToken
                } else {
                    break
                }
            } while (pageToken != null)

            val folders = allFiles.filter { it.isFolder() }.sortedBy { it.name }
            val audioFiles = allFiles.filter { it.isAudioFile() }.sortedBy { it.name }

            // Cache audio files so playback can build the full queue
            gdriveAudioFilesByFolder[folderId] = audioFiles

            // Add folders as browsable items
            for (folder in folders) {
                items.add(createBrowsableItem(
                    "gdrive_folder_${folder.id}",
                    folder.name,
                    "Folder"
                ))
            }

            // Add audio files as playable items
            for (file in audioFiles) {
                val nameWithoutExtension = file.name.substringBeforeLast('.')
                items.add(createGDrivePlayableItem(
                    "gdrive_file_${file.id}",
                    nameWithoutExtension,
                    "${file.getFileExtension()} · ${file.getFileSizeFormatted()}",
                    folderId
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Called by PlaybackManager when a Google Drive file is selected from Android Auto.
     * Looks up the cached folder contents to build the playback queue.
     */
    fun playGDriveFromMediaId(fileId: String, parentFolderId: String?) {
        val files = if (parentFolderId != null) {
            gdriveAudioFilesByFolder[parentFolderId]
        } else {
            // Fallback: find any cached folder containing this file
            gdriveAudioFilesByFolder.values.firstOrNull { folder ->
                folder.any { it.id == fileId }
            }
        }

        if (files.isNullOrEmpty()) return

        val index = files.indexOfFirst { it.id == fileId }.takeIf { it >= 0 } ?: 0
        gdrivePlaybackManager.playFiles(files, index)
    }

    private fun createGDrivePlayableItem(
        id: String,
        title: String,
        subtitle: String,
        gdriveParentId: String
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putString("gdrive_parent_id", gdriveParentId)
        }

        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setExtras(extras)
            .build()

        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
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
                            track.album?.images?.firstOrNull()?.url
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

    private fun createBrowsableItemWithGroup(
        id: String,
        title: String,
        subtitle: String,
        groupTitle: String,
        iconUri: String? = null
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", groupTitle)
        }

        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setExtras(extras)
            .apply {
                iconUri?.let { setIconUri(android.net.Uri.parse(it)) }
            }
            .build()

        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
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
        iconUri: String? = null,
        albumId: String? = null
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            albumId?.let { putString("album_id", it) }
        }

        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setExtras(extras)
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
