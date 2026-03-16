package com.cloudamp.music.playback

import android.content.Context
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.cache.GDriveLibraryCache
import com.cloudamp.music.models.Album
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Image
import com.cloudamp.music.models.Track
import com.cloudamp.music.util.MusicFilenameParser
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DataSource

/**
 * Manages Google Drive audio playback using ExoPlayer.
 * Maintains its own queue of DriveFile objects and streams audio
 * directly from Drive via authenticated HTTP requests.
 */
class GDrivePlaybackManager private constructor(
    private val context: Context
) : PlaybackProvider {

    companion object {
        private const val TAG = "GDrivePlayback"

        @Volatile
        private var instance: GDrivePlaybackManager? = null

        fun getInstance(context: Context): GDrivePlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: GDrivePlaybackManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var dataSourceFactory: DataSource.Factory? = null
    private var service: CloudAmpService? = null

    private val queue = mutableListOf<DriveFile>()
    private var currentIndex = 0

    override val providerName: String = "Google Drive"

    fun getQueue(): List<DriveFile> = queue.toList()
    override fun getCurrentIndex(): Int = currentIndex

    fun setService(cloudAmpService: CloudAmpService) {
        service = cloudAmpService
    }

    /**
     * Get or create the ExoPlayer instance.
     * Uses OkHttpDataSource with Google Drive auth headers so all media
     * requests include the Bearer token automatically.
     */
    private fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            val dsFactory: DataSource.Factory = getDataSourceFactory()
            val mediaSourceFactory = DefaultMediaSourceFactory(dsFactory)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            exoPlayer = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, true)
                .build()
                .apply {
                    addListener(playerListener)
                }
        }
        return exoPlayer!!
    }

    /**
     * Get or create the DataSource.Factory with Google Drive auth headers.
     * Uses a streaming-specific HTTP client WITHOUT body-level logging
     * (which would buffer entire audio files into memory).
     */
    private fun getDataSourceFactory(): DataSource.Factory {
        if (dataSourceFactory == null) {
            val driveClient = GoogleDriveApiClient.getInstance(context)
            val hasToken = driveClient.hasAccessToken()
            Log.d(TAG, "Creating DataSource.Factory, hasAccessToken=$hasToken")
            val streamingClient = driveClient.getStreamingHttpClient()
            dataSourceFactory = OkHttpDataSource.Factory(streamingClient)
        }
        return dataSourceFactory!!
    }

    /**
     * Build a streaming URI for a Google Drive file.
     */
    private fun buildStreamUri(fileId: String): Uri {
        return Uri.parse("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
    }

    /**
     * Play a list of Drive audio files starting from the given index.
     * This sets GDrive as the active provider.
     */
    fun playFiles(files: List<DriveFile>, startIndex: Int = 0) {
        Log.d(TAG, "playFiles called: ${files.size} files, startIndex=$startIndex")

        // Pre-check: verify we have an access token before attempting playback
        val driveClient = GoogleDriveApiClient.getInstance(context)
        if (!driveClient.hasAccessToken()) {
            Log.e(TAG, "playFiles: NO ACCESS TOKEN! Playback will fail. User needs to re-authenticate.")
        }

        ActivePlayback.activate(this)
        queue.clear()
        queue.addAll(files)
        currentIndex = startIndex

        val player = getPlayer()
        Log.d(TAG, "ExoPlayer obtained, state=${player.playbackState}")

        // Clear and set the new playlist.
        // ExoPlayer is configured with our OkHttpDataSource.Factory (via
        // DefaultMediaSourceFactory), so all HTTP requests for these URIs
        // will include the Google Drive Authorization header automatically.
        player.stop()
        player.clearMediaItems()

        files.forEach { file ->
            val uri = buildStreamUri(file.id)
            Log.d(TAG, "Adding media item: ${file.name} -> $uri")
            player.addMediaItem(MediaItem.fromUri(uri))
        }

        player.seekTo(startIndex, 0)
        Log.d(TAG, "Calling prepare()")
        player.prepare()
        player.playWhenReady = true
        Log.d(TAG, "playWhenReady=true, playbackState=${player.playbackState}")

        // Update media session metadata and queue
        updateServiceMetadata()
        updateServiceQueue()
        service?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
    }

    /**
     * Restore a queue without starting playback. Prepares ExoPlayer in paused
     * state and seeks to the given position so the user can press play to resume.
     */
    fun restoreFiles(files: List<DriveFile>, startIndex: Int, positionMs: Long) {
        Log.d(TAG, "restoreFiles: ${files.size} files, index=$startIndex, pos=${positionMs}ms")

        val driveClient = GoogleDriveApiClient.getInstance(context)
        if (!driveClient.hasAccessToken()) {
            Log.e(TAG, "restoreFiles: NO ACCESS TOKEN! Playback will fail.")
        }

        ActivePlayback.activate(this)
        queue.clear()
        queue.addAll(files)
        currentIndex = startIndex

        val player = getPlayer()
        player.stop()
        player.clearMediaItems()

        files.forEach { file ->
            player.addMediaItem(MediaItem.fromUri(buildStreamUri(file.id)))
        }

        player.playWhenReady = false
        player.seekTo(startIndex, positionMs)
        player.prepare()

        updateServiceMetadata()
        updateServiceQueue()
        service?.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, positionMs)
    }

    override suspend fun play() {
        val player = getPlayer()
        // Re-prepare if player is idle (e.g. after an error during restore)
        if (player.playbackState == Player.STATE_IDLE && queue.isNotEmpty()) {
            Log.d(TAG, "play(): player idle, re-preparing")
            player.prepare()
        }
        player.play()
        updateServiceMetadata()
        updateServiceQueue()
    }

    override suspend fun pause() {
        getPlayer().pause()
    }

    override suspend fun stop() {
        getPlayer().stop()
    }

    override suspend fun seekTo(positionMs: Long) {
        getPlayer().seekTo(positionMs)
    }

    override suspend fun skipToNext() {
        val player = getPlayer()
        if (currentIndex < queue.size - 1) {
            currentIndex++
            player.seekTo(currentIndex, 0)
            updateServiceMetadata()
        }
    }

    override suspend fun skipToPrevious() {
        val player = getPlayer()
        if (currentIndex > 0) {
            currentIndex--
            player.seekTo(currentIndex, 0)
            updateServiceMetadata()
        }
    }

    override suspend fun skipToQueueItem(index: Int) {
        if (index in queue.indices) {
            currentIndex = index
            getPlayer().seekTo(index, 0)
            updateServiceMetadata()
        }
    }

    // Metadata lookup cache: file ID -> GDriveTrack (populated when playing from structured library)
    private val trackMetadataCache = mutableMapOf<String, GDriveTrack>()

    /**
     * Play a list of GDriveTrack objects (from structured library).
     * Caches metadata so driveFileToTrack can produce rich metadata.
     */
    fun playGDriveTracks(tracks: List<GDriveTrack>, startIndex: Int = 0) {
        for (track in tracks) {
            trackMetadataCache[track.file.id] = track
        }
        playFiles(tracks.map { it.file }, startIndex)
    }

    /**
     * Convert a DriveFile to a Track model for display in the media session,
     * notifications, and NowPlayingActivity.
     *
     * Looks up GDriveLibraryCache for rich metadata (artist name, album name,
     * track number, cover art) instead of using generic "Google Drive" artist.
     */
    fun driveFileToTrack(file: DriveFile): Track {
        // Check metadata cache first (populated by playGDriveTracks or from library cache)
        val cachedMeta = trackMetadataCache[file.id]
        if (cachedMeta != null) {
            val coverUri = cachedMeta.coverFileId?.let {
                GDriveImageProvider.buildUri(it).toString()
            }
            return Track(
                id = file.id,
                name = cachedMeta.trackName,
                artists = listOf(Artist(
                    id = cachedMeta.artistId,
                    name = cachedMeta.artistName,
                    uri = "gdrive:artist:${cachedMeta.artistId}"
                )),
                album = Album(
                    id = cachedMeta.albumId,
                    name = cachedMeta.albumName,
                    artists = listOf(Artist(
                        id = cachedMeta.artistId,
                        name = cachedMeta.artistName,
                        uri = "gdrive:artist:${cachedMeta.artistId}"
                    )),
                    images = if (coverUri != null) listOf(Image(url = coverUri, height = null, width = null)) else null,
                    uri = "gdrive:album:${cachedMeta.albumId}"
                ),
                uri = "gdrive:file:${file.id}",
                durationMs = 0,
                trackNumber = cachedMeta.trackNumber
            )
        }

        // Fallback: try to look up from library cache
        val libraryCache = GDriveLibraryCache.getInstance(context)
        val parentFolderId = file.parents?.firstOrNull()
        if (parentFolderId != null) {
            val tracks = libraryCache.getAlbumTracks(parentFolderId)
            val found = tracks?.find { it.file.id == file.id }
            if (found != null) {
                trackMetadataCache[file.id] = found
                return driveFileToTrack(file) // Recurse now that it's cached
            }
        }

        // Last resort: parse filename
        val parsed = MusicFilenameParser.parseTrackFilename(file.name)
        return Track(
            id = file.id,
            name = parsed.title,
            artists = listOf(Artist(id = "gdrive", name = "Google Drive", uri = "gdrive:artist:gdrive")),
            album = null,
            uri = "gdrive:file:${file.id}",
            durationMs = 0,
            trackNumber = parsed.trackNumber
        )
    }

    /**
     * Get the current queue as Track objects for NowPlayingActivity / QueueAdapter.
     */
    override fun getQueueAsTracks(): List<Track> {
        return queue.map { driveFileToTrack(it) }
    }

    /**
     * Get the current position in milliseconds from ExoPlayer.
     */
    override fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    /**
     * Get the current track duration in milliseconds from ExoPlayer.
     */
    override fun getDuration(): Long {
        val duration = exoPlayer?.duration ?: 0
        return if (duration > 0) duration else 0
    }

    override fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    /**
     * Deactivates GDrive as the provider and releases the player.
     */
    fun deactivate() {
        exoPlayer?.stop()
    }

    fun release() {
        if (ActivePlayback.provider === this) {
            ActivePlayback.clear()
        }
        exoPlayer?.release()
        exoPlayer = null
        dataSourceFactory = null
        queue.clear()
        currentIndex = 0
    }

    /** Push current metadata and queue onto the MediaSession (e.g. after resume). */
    fun refreshSessionMetadata() {
        updateServiceMetadata()
        updateServiceQueue()
    }

    private fun updateServiceMetadata() {
        if (currentIndex in queue.indices) {
            val file = queue[currentIndex]
            val track = driveFileToTrack(file)
            // Include actual duration from ExoPlayer when available
            val duration = getDuration()
            val trackWithDuration = if (duration > 0) {
                track.copy(durationMs = duration.toInt())
            } else {
                track
            }
            // Pass album art URL from the track's album images
            val albumArtUrl = trackWithDuration.album?.images?.firstOrNull()?.url
            service?.updateMetadata(trackWithDuration, albumArtUrl)
        }
    }

    private fun updateServiceQueue() {
        val tracks = getQueueAsTracks()
        service?.updateQueue(tracks, currentIndex)
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "onPlaybackStateChanged: $playbackState (IDLE=1, BUFFERING=2, READY=3, ENDED=4)")
            when (playbackState) {
                Player.STATE_READY -> {
                    updateServiceMetadata()
                    val state = if (getPlayer().isPlaying) {
                        PlaybackStateCompat.STATE_PLAYING
                    } else {
                        PlaybackStateCompat.STATE_PAUSED
                    }
                    service?.updatePlaybackState(state, getCurrentPosition())
                }
                Player.STATE_BUFFERING -> {
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, getCurrentPosition())
                }
                Player.STATE_ENDED -> {
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    service?.notifyStopped()
                }
                Player.STATE_IDLE -> { }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            val state = if (isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            service?.updatePlaybackState(state, getCurrentPosition())
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName} (${error.errorCode})", error)
            Log.e(TAG, "  cause: ${error.cause?.message}")
            // Walk the cause chain for more details (e.g., HTTP status codes)
            var cause: Throwable? = error.cause
            var depth = 1
            while (cause != null) {
                Log.e(TAG, "  cause[$depth]: ${cause::class.java.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            service?.updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
        }

        override fun onMediaItemTransition(mediaItem: com.google.android.exoplayer2.MediaItem?, reason: Int) {
            val player = exoPlayer ?: return
            currentIndex = player.currentMediaItemIndex
            Log.d(TAG, "onMediaItemTransition: index=$currentIndex, reason=$reason")
            updateServiceMetadata()
            updateServiceQueue()
            service?.notifyTrackTransition()
        }
    }
}
