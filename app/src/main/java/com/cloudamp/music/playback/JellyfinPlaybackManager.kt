package com.cloudamp.music.playback

import android.content.Context
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.cloudamp.music.api.JellyfinApiClient
import com.cloudamp.music.api.JellyfinItem
import com.cloudamp.music.models.Album
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Track
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
 * Manages Jellyfin audio playback using ExoPlayer.
 * Maintains its own queue of JellyfinItem objects and streams audio
 * directly from the Jellyfin server via authenticated HTTP requests.
 */
class JellyfinPlaybackManager private constructor(
    private val context: Context
) : PlaybackProvider {

    companion object {
        private const val TAG = "JellyfinPlayback"

        @Volatile
        private var instance: JellyfinPlaybackManager? = null

        fun getInstance(context: Context): JellyfinPlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: JellyfinPlaybackManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var dataSourceFactory: DataSource.Factory? = null
    private var service: CloudAmpService? = null

    private val queue = mutableListOf<JellyfinItem>()
    private var currentIndex = 0

    override val providerName: String = "Jellyfin"

    fun getQueue(): List<JellyfinItem> = queue.toList()
    override fun getCurrentIndex(): Int = currentIndex

    fun setService(cloudAmpService: CloudAmpService) {
        service = cloudAmpService
    }

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

    private fun getDataSourceFactory(): DataSource.Factory {
        if (dataSourceFactory == null) {
            val jellyfinClient = JellyfinApiClient.getInstance(context)
            val streamingClient = jellyfinClient.getStreamingHttpClient()
            dataSourceFactory = OkHttpDataSource.Factory(streamingClient)
        }
        return dataSourceFactory!!
    }

    /**
     * Build a streaming URI for a Jellyfin audio item.
     * Authentication is handled via the Authorization header in OkHttpDataSource,
     * so no api_key query param is needed.
     */
    private fun buildStreamUri(itemId: String): Uri {
        val client = JellyfinApiClient.getInstance(context)
        val serverUrl = client.getServerUrl()?.trimEnd('/') ?: return Uri.EMPTY
        return Uri.parse("$serverUrl/Audio/$itemId/universal?audioCodec=aac&container=mp3,aac,opus,flac|aac,flac")
    }

    /**
     * Play a list of Jellyfin audio items starting from the given index.
     */
    fun playItems(items: List<JellyfinItem>, startIndex: Int = 0) {
        Log.d(TAG, "playItems called: ${items.size} items, startIndex=$startIndex")

        ActivePlayback.activate(this)
        queue.clear()
        queue.addAll(items)
        currentIndex = startIndex

        val player = getPlayer()
        player.stop()
        player.clearMediaItems()

        items.forEach { item ->
            val uri = buildStreamUri(item.Id)
            Log.d(TAG, "Adding media item: ${item.Name} -> $uri")
            player.addMediaItem(MediaItem.fromUri(uri))
        }

        player.seekTo(startIndex, 0)
        player.prepare()
        player.playWhenReady = true

        updateServiceMetadata()
        updateServiceQueue()
        service?.updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
    }

    override suspend fun play() {
        getPlayer().play()
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

    /**
     * Convert a JellyfinItem to a Track model for display.
     */
    fun jellyfinItemToTrack(item: JellyfinItem): Track {
        val client = JellyfinApiClient.getInstance(context)
        val serverUrl = client.getServerUrl()?.trimEnd('/') ?: ""
        val apiKey = client.getApiKey()
        val apiKeySuffix = if (apiKey != null) "&api_key=$apiKey" else ""

        val artistName = item.getArtistDisplay()
        val imageUrl = item.getPrimaryImageUrl(serverUrl, apiKey)

        // Build album art from the album ID if the track itself has no image
        val albumImageUrl = if (imageUrl == null && item.AlbumId != null) {
            "$serverUrl/Items/${item.AlbumId}/Images/Primary?maxWidth=300$apiKeySuffix"
        } else {
            imageUrl
        }

        val albumImages = if (albumImageUrl != null) {
            listOf(com.cloudamp.music.models.Image(url = albumImageUrl, height = 300, width = 300))
        } else {
            null
        }

        return Track(
            id = item.Id,
            name = item.Name,
            artists = listOf(Artist(id = "jellyfin", name = artistName, uri = "jellyfin:artist")),
            album = Album(
                id = item.AlbumId ?: "",
                name = item.Album ?: "",
                artists = listOf(Artist(id = "jellyfin", name = artistName, uri = "jellyfin:artist")),
                images = albumImages,
                uri = "jellyfin:album:${item.AlbumId ?: ""}"
            ),
            uri = "jellyfin:track:${item.Id}",
            durationMs = item.getDurationMs().toInt()
        )
    }

    override fun getQueueAsTracks(): List<Track> {
        return queue.map { jellyfinItemToTrack(it) }
    }

    override fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    override fun getDuration(): Long {
        val duration = exoPlayer?.duration ?: 0
        return if (duration > 0) duration else 0
    }

    override fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

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
            val item = queue[currentIndex]
            val track = jellyfinItemToTrack(item)
            val duration = getDuration()
            val trackWithDuration = if (duration > 0) {
                track.copy(durationMs = duration.toInt())
            } else {
                track
            }
            service?.updateMetadata(trackWithDuration, track.album?.images?.firstOrNull()?.url)
        }
    }

    private fun updateServiceQueue() {
        val tracks = getQueueAsTracks()
        service?.updateQueue(tracks, currentIndex)
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "onPlaybackStateChanged: $playbackState")
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
        }
    }
}
