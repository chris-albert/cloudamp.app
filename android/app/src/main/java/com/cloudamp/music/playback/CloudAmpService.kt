package com.cloudamp.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
import androidx.media.utils.MediaConstants
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.cloudamp.music.MainActivity
import com.cloudamp.music.R
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.cache.GDriveLibraryCache
import com.cloudamp.music.cache.GDrivePlaybackHistory
import com.cloudamp.music.cache.MediaCache
import com.cloudamp.music.cache.PlaybackStateStore
import com.cloudamp.music.cache.SavedQueuesManager
import com.cloudamp.music.models.Track
import com.cloudamp.music.util.MusicFilenameParser
import android.util.Log
import kotlinx.coroutines.*

class CloudAmpService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var playbackManager: PlaybackManager
    private lateinit var gdrivePlaybackManager: GDrivePlaybackManager
    private lateinit var gdriveClient: GoogleDriveApiClient
    private lateinit var savedQueuesManager: SavedQueuesManager
    private lateinit var playbackStateStore: PlaybackStateStore
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentAlbumArt: Bitmap? = null
    private var playbackPollingJob: Job? = null

    private lateinit var gdriveLibraryCache: GDriveLibraryCache

    // Cache of audio files per GDrive folder for building playback queues
    private val gdriveAudioFilesByFolder = mutableMapOf<String, List<DriveFile>>()

    // Cache of GDrive tracks by album for structured browsing playback
    private val gdriveTracksByAlbum = mutableMapOf<String, List<GDriveTrack>>()


    companion object {
        private const val TAG = "CloudAmpService"
        const val ROOT_ID = "root"
        const val GDRIVE_ID = "gdrive"

        const val GDRIVE_MUSIC_ID = "gdrive_music"
        const val GDRIVE_MUSIC_HOME_ID = "gdrive_music_home"
        const val GDRIVE_LIBRARY_ID = "gdrive_library"
        const val GDRIVE_PLAYLISTS_ID = "gdrive_playlists"
        const val SAVED_QUEUES_ID = "saved_queues"
        const val SEARCH_ID = "search"
        const val CUSTOM_ACTION_SAVE_QUEUE = "save_queue"

        private const val CHANNEL_ID = "cloudamp_playback"
        private const val NOTIFICATION_ID = 1

        /** Start the service as a foreground service so playback survives backgrounding. */
        fun ensureForeground(context: Context) {
            val intent = Intent(context, CloudAmpService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // When started via startForegroundService(), immediately promote to foreground
        // so Android doesn't kill the service after ~5 seconds.
        try {
            updateNotification(ActivePlayback.provider?.isPlaying() ?: false)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground notification: ${e.message}")
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        playbackManager = PlaybackManager.getInstance(this)
        gdrivePlaybackManager = GDrivePlaybackManager.getInstance(this)
        gdriveClient = GoogleDriveApiClient.getInstance(this)
        savedQueuesManager = SavedQueuesManager.getInstance(this)
        playbackStateStore = PlaybackStateStore.getInstance(this)
        gdriveLibraryCache = GDriveLibraryCache.getInstance(this)

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

        // Start polling for playback state
        startPlaybackPolling()

        // Sync playback history with Drive
        GDrivePlaybackHistory.getInstance(this).syncWithDrive()

        // Auto-restore last playback state if available
        restorePlaybackState()
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

            // Also load bitmap for notification (Glide can handle HTTP URLs directly)
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
                    // Refresh notification with album art — may fail when service
                    // is only bound (not started via startForegroundService)
                    try {
                        val state = mediaSession.controller.playbackState?.state
                        val isPlaying = state == PlaybackStateCompat.STATE_PLAYING
                        updateNotification(isPlaying)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not update notification with album art: ${e.message}")
                    }
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
        val active = ActivePlayback.provider
        val queue: List<Track> = active?.getQueueAsTracks() ?: emptyList()
        val currentIndex: Int = active?.getCurrentIndex() ?: 0

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

        // Add save queue action when there's an active queue
        if (queue.isNotEmpty()) {
            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    CUSTOM_ACTION_SAVE_QUEUE,
                    "Save Queue",
                    R.drawable.ic_save
                ).build()
            )
        }

        mediaSession.setPlaybackState(stateBuilder.build())

        // Update notification — may fail when service is only bound (not started
        // via startForegroundService), e.g., when Android Auto binds to us before
        // the user has actively started playback through CloudAmp.
        try {
            val isPlaying = state == PlaybackStateCompat.STATE_PLAYING
            updateNotification(isPlaying)
        } catch (e: Exception) {
            Log.w(TAG, "Could not update foreground notification: ${e.message}")
        }
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
        try {
            updateNotification(false)
        } catch (e: Exception) {
            Log.w(TAG, "Could not update foreground notification: ${e.message}")
        }
    }

    /**
     * Sets metadata to show a status message during loading.
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
            var detectedPlayback = false

            while (isActive) {
                try {
                    val active = ActivePlayback.provider
                    if (active is GDrivePlaybackManager) {
                        if (!detectedPlayback) {
                            Log.d(TAG, "Detected active GDrive playback, pushing metadata")
                            active.refreshSessionMetadata()
                        }
                        detectedPlayback = true
                        val position = active.getCurrentPosition()
                        val state = if (active.isPlaying()) {
                            PlaybackStateCompat.STATE_PLAYING
                        } else {
                            PlaybackStateCompat.STATE_PAUSED
                        }
                        updatePlaybackState(state, position, 1.0f)
                    }
                    // Auto-save playback state (throttled internally to every ~10s)
                    playbackStateStore.save()
                } catch (e: Exception) {
                    Log.w(TAG, "Playback polling error: ${e.message}")
                }
                delay(1000L)
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
                    mediaItems.add(createGridBrowsableItem(GDRIVE_MUSIC_ID, "Home", "Recently played & discover"))
                    mediaItems.add(createBrowsableItem(GDRIVE_LIBRARY_ID, "Library", "Browse artists & albums"))
                    mediaItems.add(createBrowsableItem(GDRIVE_PLAYLISTS_ID, "Playlists", "Your playlists"))
                    mediaItems.add(createBrowsableItem(GDRIVE_ID, "Drive", "Browse your Drive folders"))
                }

                GDRIVE_MUSIC_ID, GDRIVE_MUSIC_HOME_ID -> {
                    loadGDriveMusicHome(mediaItems)
                }

                GDRIVE_LIBRARY_ID -> {
                    loadGDriveMusicLibrary(mediaItems)
                }

                GDRIVE_PLAYLISTS_ID -> {
                    // Placeholder — playlists not yet implemented for GDrive
                    mediaItems.add(createBrowsableItem(
                        "gdrive_playlists_empty",
                        "Coming Soon",
                        "Playlists are not yet available"
                    ))
                }

                GDRIVE_ID -> {
                    loadGDriveFolder("root", mediaItems)
                }

                SAVED_QUEUES_ID -> {
                    loadSavedQueues(mediaItems)
                }

                else -> {
                    // Handle dynamic IDs
                    when {
                        parentId == "gdrive_no_auth" || parentId == "saved_queues_empty" || parentId == "gdrive_playlists_empty" -> {
                            // No-op: placeholder items
                        }
                        parentId.startsWith("gdrive_music_artist_") -> {
                            val artistId = parentId.removePrefix("gdrive_music_artist_")
                            loadGDriveMusicArtistAlbums(artistId, mediaItems)
                        }
                        parentId.startsWith("gdrive_music_album_") -> {
                            val albumId = parentId.removePrefix("gdrive_music_album_")
                            loadGDriveMusicAlbumTracks(albumId, mediaItems)
                        }
                        parentId.startsWith("gdrive_folder_") -> {
                            val folderId = parentId.removePrefix("gdrive_folder_")
                            loadGDriveFolder(folderId, mediaItems)
                        }
                    }
                }
            }

            result.sendResult(mediaItems)
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
                    "(mimeType contains 'audio/' or mimeType = 'application/vnd.google-apps.folder'" +
                    " or name contains '.flac' or name contains '.m4a'" +
                    " or name contains '.ogg' or name contains '.opus'" +
                    " or name contains '.wav' or name contains '.aac')"

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
                val parsed = MusicFilenameParser.parseTrackFilename(file.name)
                items.add(createGDrivePlayableItem(
                    "gdrive_file_${file.id}",
                    parsed.title,
                    "${file.getFileExtension()} · ${file.getFileSizeFormatted()}",
                    folderId
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    // ── Google Drive structured music browsing ──────────────────────────

    private fun loadGDriveMusicHome(items: MutableList<MediaBrowserCompat.MediaItem>) {
        if (!gdriveLibraryCache.hasFullCache()) {
            items.add(createBrowsableItem(
                "gdrive_no_auth",
                "Scan Library First",
                "Open CloudAmp app to scan your music"
            ))
            return
        }

        val artists = gdriveLibraryCache.getArtists() ?: emptyList()
        val placeholderUri = "android.resource://${packageName}/${R.drawable.ic_gdrive}"

        val allAlbums = artists.flatMap { artist ->
            gdriveLibraryCache.getArtistAlbums(artist.id) ?: emptyList()
        }

        // Discover: random 9 (re-randomised each time the user navigates here)
        val discover = allAlbums.shuffled().take(9)
        for (album in discover) {
            val imageUrl = album.coverFileId?.let { GDriveImageProvider.buildUri(it).toString() }
            items.add(createBrowsableItemWithGroup(
                "gdrive_music_album_${album.id}",
                album.name,
                album.artistName,
                "Discover",
                imageUrl ?: placeholderUri
            ))
        }

        // Recently Played: from NDJSON history (falls back to SharedPreferences)
        val history = GDrivePlaybackHistory.getInstance(this)
        val recentlyPlayedIds = history.getRecentlyPlayedAlbumIds(9).ifEmpty {
            gdriveLibraryCache.getRecentlyPlayedIds().take(9)
        }
        val albumById = allAlbums.associateBy { it.id }
        val recentlyPlayed = recentlyPlayedIds.mapNotNull { albumById[it] }
        for (album in recentlyPlayed) {
            val imageUrl = album.coverFileId?.let { GDriveImageProvider.buildUri(it).toString() }
            items.add(createBrowsableItemWithGroup(
                "gdrive_music_album_${album.id}",
                album.name,
                album.artistName,
                "Recently Played",
                imageUrl ?: placeholderUri
            ))
        }

        // Recently Added albums
        val recentlyAdded = allAlbums.sortedByDescending { it.modifiedTime ?: "" }.take(9)
        for (album in recentlyAdded) {
            val imageUrl = album.coverFileId?.let { GDriveImageProvider.buildUri(it).toString() }
            items.add(createBrowsableItemWithGroup(
                "gdrive_music_album_${album.id}",
                album.name,
                album.artistName,
                "Recently Added",
                imageUrl ?: placeholderUri
            ))
        }

        // Cached: albums with at least one track in the media cache,
        // sorted by most recently cached track first
        val mediaCache = MediaCache.getInstance(this)
        val cachedTracks = mediaCache.stats().tracks
        if (cachedTracks.isNotEmpty()) {
            val cachedFileIds = cachedTracks.map { it.fileId }.toSet()
            val addedAtByFileId = cachedTracks.associate { it.fileId to it.addedAt }
            val cachedAlbums = allAlbums.mapNotNull { album ->
                val tracks = gdriveLibraryCache.getAlbumTracks(album.id) ?: emptyList()
                val maxAddedAt = tracks
                    .mapNotNull { addedAtByFileId[it.file.id] }
                    .maxOrNull()
                if (maxAddedAt != null) album to maxAddedAt else null
            }.sortedByDescending { it.second }.map { it.first }
            for (album in cachedAlbums) {
                val imageUrl = album.coverFileId?.let { GDriveImageProvider.buildUri(it).toString() }
                items.add(createBrowsableItemWithGroup(
                    "gdrive_music_album_${album.id}",
                    album.name,
                    album.artistName,
                    "Cached",
                    imageUrl ?: placeholderUri
                ))
            }
        }
    }

    private fun loadGDriveMusicArtistAlbums(artistId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        val albums = gdriveLibraryCache.getArtistAlbums(artistId) ?: return
        val placeholderUri = "android.resource://${packageName}/${R.drawable.ic_album_placeholder}"

        for (album in albums) {
            val yearStr = album.year?.toString() ?: ""
            val imageUrl = album.coverFileId?.let { GDriveImageProvider.buildUri(it).toString() }
            items.add(createBrowsableItem(
                "gdrive_music_album_${album.id}",
                album.name,
                yearStr,
                imageUrl ?: placeholderUri
            ))
        }
    }

    private fun loadGDriveMusicAlbumTracks(albumId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        val tracks = gdriveLibraryCache.getAlbumTracks(albumId) ?: return

        // Cache tracks for playback queue building
        gdriveTracksByAlbum[albumId] = tracks

        for (track in tracks) {
            val subtitle = "${track.artistName} · ${track.file.getFileExtension()}"
            val imageUrl = track.coverFileId?.let { GDriveImageProvider.buildUri(it).toString() }
            val extras = Bundle().apply {
                putString("gdrive_music_album_id", albumId)
            }

            val description = MediaDescriptionCompat.Builder()
                .setMediaId("gdrive_music_track_${track.file.id}")
                .setTitle(track.trackName)
                .setSubtitle(subtitle)
                .setExtras(extras)
                .apply {
                    imageUrl?.let { setIconUri(android.net.Uri.parse(it)) }
                }
                .build()

            items.add(MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }
    }

    // ── Google Drive Library browsing (Artists list) ──────────────────────

    private fun loadGDriveMusicLibrary(items: MutableList<MediaBrowserCompat.MediaItem>) {
        if (!gdriveLibraryCache.hasFullCache()) {
            items.add(createBrowsableItem(
                "gdrive_no_auth",
                "Scan Library First",
                "Open CloudAmp app to scan your music"
            ))
            return
        }

        val artists = gdriveLibraryCache.getArtists() ?: emptyList()
        val placeholderUri = "android.resource://${packageName}/${R.drawable.ic_gdrive}"

        for (artist in artists) {
            val artistImageId = artist.imageFileId
                ?: gdriveLibraryCache.getArtistAlbums(artist.id)?.firstOrNull()?.coverFileId
            val imageUrl = artistImageId?.let { GDriveImageProvider.buildUri(it).toString() }
            items.add(createBrowsableItem(
                "gdrive_music_artist_${artist.id}",
                artist.name,
                "${artist.albumCount} album${if (artist.albumCount != 1) "s" else ""}",
                imageUrl ?: placeholderUri
            ))
        }
    }

    /**
     * Called by PlaybackManager when a GDrive Music track is selected from Android Auto.
     * Looks up the cached album tracks to build the playback queue with rich metadata.
     */
    fun playGDriveMusicFromMediaId(trackFileId: String, albumId: String?) {
        val tracks = if (albumId != null) {
            gdriveTracksByAlbum[albumId] ?: gdriveLibraryCache.getAlbumTracks(albumId)
        } else {
            gdriveTracksByAlbum.values.firstOrNull { container ->
                container.any { it.file.id == trackFileId }
            }
        }

        if (tracks.isNullOrEmpty()) return

        val index = tracks.indexOfFirst { it.file.id == trackFileId }.takeIf { it >= 0 } ?: 0
        gdrivePlaybackManager.playGDriveTracks(tracks, index)
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

    // ── Playback state persistence ────────────────────────────────────

    /**
     * Restores the last auto-saved playback state (if any).
     * Called during onCreate to resume where the user left off.
     * Always restores paused — the user presses play when ready.
     */
    private fun restorePlaybackState() {
        val state = playbackStateStore.load() ?: return
        Log.d(TAG, "Restoring playback state: ${state.provider} index=${state.currentIndex} pos=${state.currentPositionMs}ms")

        when (state.provider) {
            com.cloudamp.music.models.SavedQueue.PROVIDER_GDRIVE -> {
                if (state.driveFiles.isEmpty()) return
                gdrivePlaybackManager.restoreFiles(state.driveFiles, state.currentIndex, state.currentPositionMs)
            }
        }

        // Clear persisted state after restore to avoid double-restores
        playbackStateStore.clear()
    }

    /** Force-save on pause — user might not come back for a while. */
    fun notifyPaused() {
        playbackStateStore.save(force = true)
    }

    /** Force-save on track transition — index changed. */
    fun notifyTrackTransition() {
        playbackStateStore.save(force = true)
    }

    /** Clear persisted state on explicit stop / queue end. */
    fun notifyStopped() {
        playbackStateStore.clear()
    }

    // ── Saved Queues browsing ──────────────────────────────────────────

    private fun loadSavedQueues(items: MutableList<MediaBrowserCompat.MediaItem>) {
        // Persist live playback position so the list shows the current track
        savedQueuesManager.saveActiveQueuePosition()
        val queues = savedQueuesManager.getSavedQueues()

        if (queues.isEmpty()) {
            items.add(createBrowsableItem(
                "saved_queues_empty",
                "No Saved Queues",
                "Save a queue from Now Playing"
            ))
            return
        }

        for (queue in queues) {
            val trackCount = queue.getTrackCount()
            val currentTrackName = queue.getCurrentTrackName() ?: ""
            val subtitle = "${queue.getDisplayProvider()} · $trackCount tracks · $currentTrackName"

            items.add(createPlayableItem(
                "saved_queue_${queue.id}",
                queue.name,
                subtitle
            ))
        }
    }

    /**
     * Called by PlaybackManager when a saved queue is selected from Android Auto.
     * Loads the queue into the appropriate provider and resumes from the saved position.
     */
    fun playSavedQueue(queueId: String) {
        val queue = savedQueuesManager.getQueue(queueId) ?: return

        // Save position of the currently active queue before switching
        savedQueuesManager.saveActiveQueuePosition()

        when (queue.provider) {
            com.cloudamp.music.models.SavedQueue.PROVIDER_GDRIVE -> {
                if (queue.driveFiles.isEmpty()) return
                gdrivePlaybackManager.playFiles(queue.driveFiles, queue.currentIndex)
                // Seek to saved position within the track after a brief delay for buffering
                if (queue.currentPositionMs > 0) {
                    serviceScope.launch {
                        delay(1500)
                        gdrivePlaybackManager.seekTo(queue.currentPositionMs)
                    }
                }
            }
        }

        // Mark this queue as active and update last played time
        savedQueuesManager.setActiveQueue(queueId)
        savedQueuesManager.updateQueuePosition(
            queueId, queue.currentIndex, queue.currentPositionMs
        )
    }

    /**
     * Saves the current playback queue with an auto-generated name.
     * Called from the save_queue custom action.
     */
    fun saveCurrentQueue(): Boolean {
        val name = buildAutoSaveName()
        val positionMs = ActivePlayback.provider?.getCurrentPosition() ?: 0L
        return savedQueuesManager.saveCurrentQueue(name, positionMs) != null
    }

    private fun buildAutoSaveName(): String {
        val active = ActivePlayback.provider ?: return "Queue"
        val queue = active.getQueueAsTracks()
        val idx = active.getCurrentIndex()
        if (idx !in queue.indices) return "Queue"
        val track = queue[idx]
        return track.album?.name ?: track.name
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
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

    /** Browsable item whose children render as list items on Android Auto. */
    private fun createListBrowsableItem(
        id: String,
        title: String,
        subtitle: String,
        iconUri: String? = null
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                   MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
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

    /** Browsable item whose children render as grid tiles on Android Auto. */
    private fun createGridBrowsableItem(
        id: String,
        title: String,
        subtitle: String,
        iconUri: String? = null
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                   MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
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

    private fun createPlayableItem(
        id: String,
        title: String,
        subtitle: String,
        iconUri: String? = null,
        albumId: String? = null,
        playlistId: String? = null
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            albumId?.let { putString("album_id", it) }
            playlistId?.let { putString("playlist_id", it) }
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
        // Force-save playback state before teardown
        playbackStateStore.save(force = true)
        playbackPollingJob?.cancel()
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }
}
