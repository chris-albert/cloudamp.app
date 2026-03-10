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
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.api.JellyfinApiClient
import com.cloudamp.music.api.JellyfinItem
import com.cloudamp.music.auth.JellyfinAuthManager
import com.cloudamp.music.cache.JellyfinLibraryCache
import com.cloudamp.music.cache.PlaybackStateStore
import com.cloudamp.music.cache.SavedQueuesManager
import com.cloudamp.music.models.Track
import android.util.Log
import kotlinx.coroutines.*

class CloudAmpService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var playbackManager: PlaybackManager
    private lateinit var gdrivePlaybackManager: GDrivePlaybackManager
    private lateinit var gdriveClient: GoogleDriveApiClient
    private lateinit var jellyfinPlaybackManager: JellyfinPlaybackManager
    private lateinit var jellyfinClient: JellyfinApiClient
    private lateinit var jellyfinAuthManager: JellyfinAuthManager
    private lateinit var jellyfinLibraryCache: JellyfinLibraryCache
    private lateinit var savedQueuesManager: SavedQueuesManager
    private lateinit var playbackStateStore: PlaybackStateStore
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentAlbumArt: Bitmap? = null
    private var playbackPollingJob: Job? = null

    // Cache of audio files per GDrive folder for building playback queues
    private val gdriveAudioFilesByFolder = mutableMapOf<String, List<DriveFile>>()

    // Cache of Jellyfin tracks by album/playlist for building playback queues
    private val jellyfinTracksByAlbum = mutableMapOf<String, List<JellyfinItem>>()

    // In-memory cache of built Jellyfin artist MediaItems for Android Auto browsing
    private var jellyfinArtistMediaItems: List<MediaBrowserCompat.MediaItem>? = null
    private var jellyfinArtistCacheTimestamp: Long = 0


    companion object {
        private const val TAG = "CloudAmpService"
        const val ROOT_ID = "root"
        const val GDRIVE_ID = "gdrive"
        const val JELLYFIN_ID = "jellyfin"
        const val JELLYFIN_HOME_ID = "jellyfin_home"
        const val JELLYFIN_LIBRARY_ID = "jellyfin_library"
        const val JELLYFIN_PLAYLISTS_ID = "jellyfin_playlists"
        const val JELLYFIN_RECENT_ID = "jellyfin_recent"
        const val JELLYFIN_RECENT_PLAYED_ID = "jellyfin_recent_played"
        const val JELLYFIN_RECENT_ADDED_ID = "jellyfin_recent_added"

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
        jellyfinPlaybackManager = JellyfinPlaybackManager.getInstance(this)
        jellyfinClient = JellyfinApiClient.getInstance(this)
        jellyfinAuthManager = JellyfinAuthManager(this)
        jellyfinLibraryCache = JellyfinLibraryCache.getInstance(this)
        savedQueuesManager = SavedQueuesManager.getInstance(this)
        playbackStateStore = PlaybackStateStore.getInstance(this)

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
        jellyfinPlaybackManager.setService(this)
        mediaSession.setCallback(playbackManager.mediaSessionCallback)

        sessionToken = mediaSession.sessionToken

        // Start polling for playback state
        startPlaybackPolling()

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
            // For Jellyfin, use content:// URI so Android Auto can load the image
            val artUri = if (track.uri.startsWith("jellyfin:")) {
                val itemId = track.uri.removePrefix("jellyfin:track:")
                jellyfinContentUri(itemId, url) ?: url
            } else {
                url
            }
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUri)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUri)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri)

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
                        // For Jellyfin, use content:// URI so Android Auto can load the image
                        val iconUrl = if (track.uri.startsWith("jellyfin:")) {
                            val itemId = track.uri.removePrefix("jellyfin:track:")
                            jellyfinContentUri(itemId, url) ?: url
                        } else {
                            url
                        }
                        setIconUri(android.net.Uri.parse(iconUrl))
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
                    } else if (active is JellyfinPlaybackManager) {
                        if (!detectedPlayback) {
                            Log.d(TAG, "Detected active Jellyfin playback, pushing metadata")
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
                    mediaItems.add(createGridBrowsableItem(JELLYFIN_ID, "Jellyfin", "Browse your Jellyfin library"))
                    mediaItems.add(createBrowsableItem(GDRIVE_ID, "Drive", "Browse your Drive music"))
                    mediaItems.add(createBrowsableItem(SAVED_QUEUES_ID, "Queues", "Resume where you left off"))
                }


                GDRIVE_ID -> {
                    loadGDriveFolder("root", mediaItems)
                }

                JELLYFIN_ID, JELLYFIN_HOME_ID -> {
                    loadJellyfinHome(mediaItems)
                }

                JELLYFIN_LIBRARY_ID -> {
                    loadJellyfinArtists(mediaItems)
                }

                JELLYFIN_PLAYLISTS_ID -> {
                    loadJellyfinPlaylists(mediaItems)
                }

                JELLYFIN_RECENT_ID -> {
                    val historyIcon = "android.resource://${packageName}/${R.drawable.ic_history}"
                    val newReleasesIcon = "android.resource://${packageName}/${R.drawable.ic_new_releases}"
                    mediaItems.add(createGridBrowsableItem(JELLYFIN_RECENT_PLAYED_ID, "Played", "Albums you've listened to", historyIcon))
                    mediaItems.add(createGridBrowsableItem(JELLYFIN_RECENT_ADDED_ID, "Added", "New albums in your library", newReleasesIcon))
                }

                JELLYFIN_RECENT_PLAYED_ID -> {
                    loadJellyfinRecentlyPlayed(mediaItems)
                }

                JELLYFIN_RECENT_ADDED_ID -> {
                    loadJellyfinRecentlyAdded(mediaItems)
                }

                SAVED_QUEUES_ID -> {
                    loadSavedQueues(mediaItems)
                }

                else -> {
                    // Handle dynamic IDs
                    when {
                        parentId == "gdrive_no_auth" || parentId == "jellyfin_no_auth" || parentId == "saved_queues_empty" -> {
                            // No-op: placeholder items
                        }
                        parentId.startsWith("gdrive_folder_") -> {
                            val folderId = parentId.removePrefix("gdrive_folder_")
                            loadGDriveFolder(folderId, mediaItems)
                        }
                        parentId.startsWith("jellyfin_artist_") -> {
                            val artistId = parentId.removePrefix("jellyfin_artist_")
                            loadJellyfinArtistAlbums(artistId, mediaItems)
                        }
                        parentId.startsWith("jellyfin_home_") && parentId.contains("_album_") -> {
                            val albumId = parentId.substringAfter("_album_")
                            loadJellyfinAlbumTracks(albumId, mediaItems)
                        }
                        parentId.startsWith("jellyfin_album_") -> {
                            val albumId = parentId.removePrefix("jellyfin_album_")
                            loadJellyfinAlbumTracks(albumId, mediaItems)
                        }
                        parentId.startsWith("jellyfin_playlist_") -> {
                            val playlistId = parentId.removePrefix("jellyfin_playlist_")
                            loadJellyfinPlaylistTracks(playlistId, mediaItems)
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

    // ── Jellyfin browsing ──────────────────────────────────────────────

    /** Convert a Jellyfin HTTP image URL to a content:// URI that Android Auto can load. */
    private fun jellyfinContentUri(itemId: String, httpUrl: String?): String? {
        if (httpUrl == null) return null
        return JellyfinImageProvider.buildUri(itemId, httpUrl).toString()
    }

    private suspend fun loadJellyfinArtists(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            if (!jellyfinAuthManager.isConfigured()) {
                items.add(createBrowsableItem(
                    "jellyfin_no_auth",
                    "Connect Jellyfin",
                    "Open CloudAmp app to configure"
                ))
                return
            }

            // Return in-memory cached list if available and not stale
            val currentTimestamp = jellyfinLibraryCache.getLastLoadedTimestamp()
            if (jellyfinArtistMediaItems != null && jellyfinArtistCacheTimestamp == currentTimestamp) {
                items.addAll(jellyfinArtistMediaItems!!)
                return
            }

            // Use cache first, fall back to API
            var artists = jellyfinLibraryCache.getArtists()
            if (artists.isNullOrEmpty()) {
                val userId = jellyfinAuthManager.getUserId() ?: return
                val response = jellyfinClient.api.getArtists(userId)
                if (response.isSuccessful) {
                    val allArtists = response.body()?.Items ?: emptyList()
                    // Filter out /config/metadata/ ghost artists only when a real
                    // /media/ artist with the same normalized name already exists.
                    val mediaNames = allArtists
                        .filter { it.Path?.startsWith("/media/") == true }
                        .map { it.Name.lowercase().trim() }
                        .toSet()
                    artists = allArtists.filter {
                        it.Path == null ||
                        !it.Path.startsWith("/config/metadata/") ||
                        it.Name.lowercase().trim() !in mediaNames
                    }
                    jellyfinLibraryCache.saveArtists(artists)
                }
            }

            val serverUrl = jellyfinAuthManager.getServerUrl()?.trimEnd('/') ?: ""
            val apiKey = jellyfinAuthManager.getApiKey()
            val placeholderUri = "android.resource://${packageName}/${R.drawable.ic_artist_placeholder}"
            val fallbackImages = jellyfinLibraryCache.getArtistFallbackImages() ?: emptyMap()

            val built = mutableListOf<MediaBrowserCompat.MediaItem>()
            for (artist in artists ?: emptyList()) {
                var imageUrl = jellyfinContentUri(artist.Id, artist.getPrimaryImageUrl(serverUrl, apiKey))

                // Use cached fallback album image for artists without primary image
                if (imageUrl == null) {
                    val repId = jellyfinLibraryCache.getRepresentativeArtistId(artist.Id) ?: artist.Id
                    val fallbackAlbumId = fallbackImages[repId]
                    if (fallbackAlbumId != null) {
                        val fallbackUrl = "$serverUrl/Items/$fallbackAlbumId/Images/Primary?maxWidth=300" +
                            (if (apiKey != null) "&api_key=$apiKey" else "")
                        imageUrl = jellyfinContentUri(fallbackAlbumId, fallbackUrl)
                    }
                }

                built.add(createBrowsableItem(
                    "jellyfin_artist_${artist.Id}",
                    artist.Name,
                    "",
                    imageUrl ?: placeholderUri
                ))
            }

            jellyfinArtistMediaItems = built
            jellyfinArtistCacheTimestamp = currentTimestamp
            items.addAll(built)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadJellyfinPlaylists(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            if (!jellyfinAuthManager.isConfigured()) {
                items.add(createBrowsableItem(
                    "jellyfin_no_auth",
                    "Connect Jellyfin",
                    "Open CloudAmp app to configure"
                ))
                return
            }

            val userId = jellyfinAuthManager.getUserId() ?: return
            val serverUrl = jellyfinAuthManager.getServerUrl()?.trimEnd('/') ?: ""
            val apiKey = jellyfinAuthManager.getApiKey()
            val playlistsResponse = jellyfinClient.api.getPlaylists(userId)
            if (playlistsResponse.isSuccessful) {
                val playlists = playlistsResponse.body()?.Items ?: emptyList()
                for (playlist in playlists) {
                    val countStr = if (playlist.ChildCount != null) "${playlist.ChildCount} tracks" else "Playlist"
                    items.add(createBrowsableItem(
                        "jellyfin_playlist_${playlist.Id}",
                        playlist.Name,
                        countStr,
                        jellyfinContentUri(playlist.Id, playlist.getPrimaryImageUrl(serverUrl, apiKey))
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadJellyfinRecentlyPlayed(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            if (!jellyfinAuthManager.isConfigured()) {
                items.add(createBrowsableItem("jellyfin_no_auth", "Connect Jellyfin", "Open CloudAmp app to configure"))
                return
            }
            val userId = jellyfinAuthManager.getUserId() ?: return
            val serverUrl = jellyfinAuthManager.getServerUrl()?.trimEnd('/') ?: ""
            val apiKey = jellyfinAuthManager.getApiKey()
            val apiKeySuffix = if (apiKey != null) "&api_key=$apiKey" else ""
            val placeholderUri = "android.resource://${packageName}/${R.drawable.ic_album_placeholder}"

            // Determine currently playing album ID (if Jellyfin)
            val currentAlbumId = (ActivePlayback.provider as? JellyfinPlaybackManager)?.let { jfm ->
                val q = jfm.getQueue()
                val idx = jfm.getCurrentIndex()
                if (idx in q.indices) q[idx].AlbumId else null
            }

            // Query recently played tracks, then deduplicate by album
            val response = jellyfinClient.api.getRecentlyPlayedTracks(userId)
            if (response.isSuccessful) {
                val tracks = response.body()?.Items ?: emptyList()
                val seenAlbumIds = mutableSetOf<String>()
                for (track in tracks) {
                    val albumId = track.AlbumId ?: continue
                    if (!seenAlbumIds.add(albumId)) continue
                    val albumName = track.Album ?: continue
                    val artist = track.AlbumArtist ?: ""
                    val year = track.Year?.toString()
                    val isPlaying = albumId == currentAlbumId
                    val parts = mutableListOf<String>()
                    if (artist.isNotEmpty()) parts.add(artist)
                    if (year != null) parts.add(year)
                    parts.add(track.Name)
                    val subtitle = parts.joinToString(" \u00b7 ")
                    val displayName = if (isPlaying) "\u25b6 $albumName" else albumName
                    val imageUrl = jellyfinContentUri(albumId,
                        "$serverUrl/Items/$albumId/Images/Primary?maxWidth=300$apiKeySuffix")
                        ?: placeholderUri
                    items.add(createBrowsableItem("jellyfin_album_$albumId", displayName, subtitle, imageUrl))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadJellyfinHome(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            if (!jellyfinAuthManager.isConfigured()) {
                items.add(createBrowsableItem("jellyfin_no_auth", "Connect Jellyfin", "Open CloudAmp app to configure"))
                return
            }
            val userId = jellyfinAuthManager.getUserId() ?: return
            val serverUrl = jellyfinAuthManager.getServerUrl()?.trimEnd('/') ?: ""
            val apiKey = jellyfinAuthManager.getApiKey()
            val placeholderUri = "android.resource://${packageName}/${R.drawable.ic_album_placeholder}"

            // Top nav links (no icons → compact text-only items in Android Auto)
            items.add(createBrowsableItem(JELLYFIN_LIBRARY_ID, "Library", ""))
            items.add(createBrowsableItem(JELLYFIN_PLAYLISTS_ID, "Playlists", ""))
            items.add(createBrowsableItem(JELLYFIN_RECENT_ID, "Recently", ""))

            // Load all four sections in parallel
            val recentlyPlayedDeferred = serviceScope.async(Dispatchers.IO) {
                try {
                    val response = jellyfinClient.api.getRecentlyPlayedTracks(userId)
                    if (!response.isSuccessful) return@async emptyList<JellyfinItem>()
                    val tracks = response.body()?.Items ?: emptyList()
                    val seenAlbumIds = mutableSetOf<String>()
                    tracks.mapNotNull { track ->
                        val albumId = track.AlbumId ?: return@mapNotNull null
                        if (!seenAlbumIds.add(albumId)) return@mapNotNull null
                        track
                    }.take(9)
                } catch (e: Exception) { emptyList() }
            }

            val recentlyAddedDeferred = serviceScope.async(Dispatchers.IO) {
                try {
                    val response = jellyfinClient.api.getRecentlyAddedAlbums(userId)
                    if (!response.isSuccessful) return@async emptyList<JellyfinItem>()
                    (response.body()?.Items ?: emptyList()).take(9)
                } catch (e: Exception) { emptyList() }
            }
            val discoverDeferred = serviceScope.async(Dispatchers.IO) {
                try {
                    val response = jellyfinClient.api.getRandomAlbums(userId)
                    if (!response.isSuccessful) return@async emptyList<JellyfinItem>()
                    (response.body()?.Items ?: emptyList()).take(9)
                } catch (e: Exception) { emptyList() }
            }

            val recentlyPlayed = recentlyPlayedDeferred.await()
            val recentlyAdded = recentlyAddedDeferred.await()
            val discover = discoverDeferred.await()

            // Recently Played — derived from tracks, use track fields for album info
            if (recentlyPlayed.isNotEmpty()) {
                for (track in recentlyPlayed) {
                    val albumId = track.AlbumId ?: continue
                    val albumName = track.Album ?: continue
                    val artist = track.AlbumArtist ?: ""
                    val imageUrl = jellyfinContentUri(albumId,
                        "$serverUrl/Items/$albumId/Images/Primary?maxWidth=300${if (apiKey != null) "&api_key=$apiKey" else ""}")
                        ?: placeholderUri
                    items.add(createBrowsableItemWithGroup(
                        "jellyfin_home_played_album_$albumId", albumName, artist,
                        "Recently Played", imageUrl))
                }
            }

            // Discover
            if (discover.isNotEmpty()) {
                for (album in discover) {
                    val artist = album.AlbumArtist ?: ""
                    val imageUrl = jellyfinContentUri(album.Id, album.getPrimaryImageUrl(serverUrl, apiKey))
                        ?: placeholderUri
                    items.add(createBrowsableItemWithGroup(
                        "jellyfin_home_discover_album_${album.Id}", album.Name, artist,
                        "Discover", imageUrl))
                }
            }

            // Recently Added
            if (recentlyAdded.isNotEmpty()) {
                for (album in recentlyAdded) {
                    val artist = album.AlbumArtist ?: ""
                    val imageUrl = jellyfinContentUri(album.Id, album.getPrimaryImageUrl(serverUrl, apiKey))
                        ?: placeholderUri
                    items.add(createBrowsableItemWithGroup(
                        "jellyfin_home_added_album_${album.Id}", album.Name, artist,
                        "Recently Added", imageUrl))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadJellyfinRecentlyAdded(items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            if (!jellyfinAuthManager.isConfigured()) {
                items.add(createBrowsableItem("jellyfin_no_auth", "Connect Jellyfin", "Open CloudAmp app to configure"))
                return
            }
            val userId = jellyfinAuthManager.getUserId() ?: return
            val serverUrl = jellyfinAuthManager.getServerUrl()?.trimEnd('/') ?: ""
            val apiKey = jellyfinAuthManager.getApiKey()
            val placeholderUri = "android.resource://${packageName}/${R.drawable.ic_album_placeholder}"

            val response = jellyfinClient.api.getRecentlyAddedAlbums(userId)
            if (response.isSuccessful) {
                val albums = response.body()?.Items ?: emptyList()
                for (album in albums) {
                    val artist = album.AlbumArtist ?: ""
                    val year = album.Year?.toString()
                    val subtitle = if (year != null) "$artist \u00b7 $year" else artist
                    val imageUrl = jellyfinContentUri(album.Id, album.getPrimaryImageUrl(serverUrl, apiKey))
                        ?: placeholderUri
                    items.add(createBrowsableItem("jellyfin_album_${album.Id}", album.Name, subtitle, imageUrl))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadJellyfinArtistAlbums(artistId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val serverUrl = jellyfinAuthManager.getServerUrl()?.trimEnd('/') ?: ""
            val apiKey = jellyfinAuthManager.getApiKey()

            // Find representative ID and try cache first
            val repId = jellyfinLibraryCache.getRepresentativeArtistId(artistId) ?: artistId
            var albums = jellyfinLibraryCache.getArtistAlbums(repId)

            // Cache miss - fetch from API
            if (albums == null) {
                val userId = jellyfinAuthManager.getUserId() ?: return
                val response = jellyfinClient.api.getArtistAlbums(userId, repId)
                albums = if (response.isSuccessful) response.body()?.Items ?: emptyList() else null
                // Fallback for metadata-only artists (no folder children)
                if (albums != null && albums.isEmpty()) {
                    val fallbackResponse = jellyfinClient.api.getArtistAlbumsByArtistId(userId, repId)
                    if (fallbackResponse.isSuccessful) albums = fallbackResponse.body()?.Items ?: emptyList()
                }
                if (albums != null) jellyfinLibraryCache.saveArtistAlbums(repId, albums)
            }

            if (albums != null) {
                val fallbackImageUrl = albums
                    .sortedBy { it.Year ?: Int.MAX_VALUE }
                    .firstOrNull { it.hasPrimaryImage() }
                    ?.let { jellyfinContentUri(it.Id, it.getPrimaryImageUrl(serverUrl, apiKey)) }
                val placeholderUri = "android.resource://${packageName}/${R.drawable.ic_album_placeholder}"

                for (album in albums) {
                    val yearStr = album.Year?.toString() ?: ""
                    val imageUrl = jellyfinContentUri(album.Id, album.getPrimaryImageUrl(serverUrl, apiKey))
                        ?: fallbackImageUrl ?: placeholderUri
                    items.add(createBrowsableItem(
                        "jellyfin_album_${album.Id}",
                        album.Name,
                        yearStr,
                        imageUrl
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadJellyfinAlbumTracks(albumId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val serverUrl = jellyfinAuthManager.getServerUrl()?.trimEnd('/') ?: ""
            val apiKey = jellyfinAuthManager.getApiKey()
            val apiKeySuffix = if (apiKey != null) "&api_key=$apiKey" else ""

            // Try cache first
            var tracks = jellyfinLibraryCache.getAlbumTracks(albumId)

            // Cache miss - fetch from API
            if (tracks == null) {
                val userId = jellyfinAuthManager.getUserId() ?: return
                val response = jellyfinClient.api.getAlbumTracks(userId, albumId)
                if (response.isSuccessful) {
                    tracks = response.body()?.Items ?: emptyList()
                    jellyfinLibraryCache.saveAlbumTracks(albumId, tracks)
                }
            }

            if (tracks != null) {
                // Populate runtime map for playback queue building
                jellyfinTracksByAlbum[albumId] = tracks

                // Determine currently playing track ID (if Jellyfin)
                val currentTrackId = (ActivePlayback.provider as? JellyfinPlaybackManager)?.let { jfm ->
                    val q = jfm.getQueue()
                    val idx = jfm.getCurrentIndex()
                    if (idx in q.indices) q[idx].Id else null
                }

                for (track in tracks) {
                    val durationMs = track.getDurationMs()
                    val durationStr = if (durationMs > 0) {
                        val totalSeconds = durationMs / 1000
                        val minutes = totalSeconds / 60
                        val seconds = totalSeconds % 60
                        String.format("%d:%02d", minutes, seconds)
                    } else ""
                    val subtitle = "${track.getArtistDisplay()} \u00b7 $durationStr"
                    val isPlaying = track.Id == currentTrackId
                    val displayName = if (isPlaying) "\u25b6 ${track.Name}" else track.Name

                    // Use album art URL if track has no image
                    val rawImageUrl = track.getPrimaryImageUrl(serverUrl, apiKey)
                        ?: "$serverUrl/Items/$albumId/Images/Primary?maxWidth=300$apiKeySuffix"
                    val imageUrl = jellyfinContentUri(track.Id, rawImageUrl)

                    items.add(createJellyfinPlayableItem(
                        "jellyfin_track_${track.Id}",
                        displayName,
                        subtitle,
                        albumId,
                        imageUrl
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun loadJellyfinPlaylistTracks(playlistId: String, items: MutableList<MediaBrowserCompat.MediaItem>) {
        try {
            val userId = jellyfinAuthManager.getUserId() ?: return
            val serverUrl = jellyfinAuthManager.getServerUrl()?.trimEnd('/') ?: ""
            val apiKey = jellyfinAuthManager.getApiKey()
            val apiKeySuffix = if (apiKey != null) "&api_key=$apiKey" else ""
            val response = jellyfinClient.api.getPlaylistItems(playlistId, userId)
            if (response.isSuccessful) {
                val tracks = response.body()?.Items ?: emptyList()

                // Cache tracks for playback queue building
                jellyfinTracksByAlbum[playlistId] = tracks

                // Determine currently playing track ID (if Jellyfin)
                val currentTrackId = (ActivePlayback.provider as? JellyfinPlaybackManager)?.let { jfm ->
                    val q = jfm.getQueue()
                    val idx = jfm.getCurrentIndex()
                    if (idx in q.indices) q[idx].Id else null
                }

                for (track in tracks) {
                    val durationMs = track.getDurationMs()
                    val durationStr = if (durationMs > 0) {
                        val totalSeconds = durationMs / 1000
                        val minutes = totalSeconds / 60
                        val seconds = totalSeconds % 60
                        String.format("%d:%02d", minutes, seconds)
                    } else ""
                    val subtitle = "${track.getArtistDisplay()} \u00b7 $durationStr"
                    val isPlaying = track.Id == currentTrackId
                    val displayName = if (isPlaying) "\u25b6 ${track.Name}" else track.Name

                    val rawImageUrl = track.getPrimaryImageUrl(serverUrl, apiKey)
                        ?: if (track.AlbumId != null) "$serverUrl/Items/${track.AlbumId}/Images/Primary?maxWidth=300$apiKeySuffix" else null
                    val imageUrl = jellyfinContentUri(track.Id, rawImageUrl)

                    items.add(createJellyfinPlayableItem(
                        "jellyfin_track_${track.Id}",
                        displayName,
                        subtitle,
                        playlistId,
                        imageUrl
                    ))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Called by PlaybackManager when a Jellyfin track is selected from Android Auto.
     * Looks up the cached album/playlist contents to build the playback queue.
     */
    fun playJellyfinFromMediaId(trackId: String, parentId: String?) {
        val tracks = if (parentId != null) {
            jellyfinTracksByAlbum[parentId]
        } else {
            // Fallback: find any cached container with this track
            jellyfinTracksByAlbum.values.firstOrNull { container ->
                container.any { it.Id == trackId }
            }
        }

        if (tracks.isNullOrEmpty()) return

        val index = tracks.indexOfFirst { it.Id == trackId }.takeIf { it >= 0 } ?: 0
        jellyfinPlaybackManager.playItems(tracks, index)
    }

    private fun createJellyfinPlayableItem(
        id: String,
        title: String,
        subtitle: String,
        jellyfinParentId: String,
        iconUri: String? = null
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle().apply {
            putString("jellyfin_parent_id", jellyfinParentId)
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
                gdrivePlaybackManager.playFiles(state.driveFiles, state.currentIndex)
                serviceScope.launch {
                    delay(1500)
                    gdrivePlaybackManager.seekTo(state.currentPositionMs)
                    gdrivePlaybackManager.pause()
                }
            }
            com.cloudamp.music.models.SavedQueue.PROVIDER_JELLYFIN -> {
                val items = state.jellyfinItems.orEmpty()
                if (items.isEmpty()) return
                jellyfinPlaybackManager.playItems(items, state.currentIndex)
                serviceScope.launch {
                    delay(1500)
                    jellyfinPlaybackManager.seekTo(state.currentPositionMs)
                    jellyfinPlaybackManager.pause()
                }
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
            com.cloudamp.music.models.SavedQueue.PROVIDER_JELLYFIN -> {
                val items = queue.jellyfinItems.orEmpty()
                if (items.isEmpty()) return
                jellyfinPlaybackManager.playItems(items, queue.currentIndex)
                // Seek to saved position within the track after a brief delay for buffering
                if (queue.currentPositionMs > 0) {
                    serviceScope.launch {
                        delay(1500)
                        jellyfinPlaybackManager.seekTo(queue.currentPositionMs)
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
