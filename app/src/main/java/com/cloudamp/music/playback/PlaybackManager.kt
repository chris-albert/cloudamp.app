package com.cloudamp.music.playback

import android.content.Context
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.*

/**
 * Coordinates media session callbacks and dispatches transport controls
 * to the currently active playback provider (GDrive or Jellyfin).
 */
class PlaybackManager private constructor(
    private val context: Context
) {

    companion object {
        @Volatile
        private var instance: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackManager(
                    context.applicationContext
                ).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSessionCompat? = null
    private var service: CloudAmpService? = null

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
                    val active = ActivePlayback.provider ?: return@launch
                    active.play()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, active.getCurrentPosition())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPause() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.pause()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, active.getCurrentPosition())
                    service?.notifyPaused()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSkipToNext() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.skipToNext()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSkipToPrevious() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.skipToPrevious()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            scope.launch {
                try {
                    if (mediaId.startsWith("saved_queue_")) {
                        val queueId = mediaId.removePrefix("saved_queue_")
                        service?.playSavedQueue(queueId)
                    } else if (mediaId.startsWith("gdrive_music_track_")) {
                        val fileId = mediaId.removePrefix("gdrive_music_track_")
                        val albumId = extras?.getString("gdrive_music_album_id")
                        service?.playGDriveMusicFromMediaId(fileId, albumId)
                    } else if (mediaId.startsWith("gdrive_file_")) {
                        val fileId = mediaId.removePrefix("gdrive_file_")
                        val parentId = extras?.getString("gdrive_parent_id")
                        service?.playGDriveFromMediaId(fileId, parentId)
                    } else if (mediaId.startsWith("jellyfin_track_")) {
                        val trackId = mediaId.removePrefix("jellyfin_track_")
                        val parentId = extras?.getString("jellyfin_parent_id")
                        service?.playJellyfinFromMediaId(trackId, parentId)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) {
                onPlay()
            }
        }

        override fun onStop() {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.stop()
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    service?.notifyStopped()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onSeekTo(pos: Long) {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.seekTo(pos)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                "previous" -> onSkipToPrevious()
                "next" -> onSkipToNext()
                CloudAmpService.CUSTOM_ACTION_SAVE_QUEUE -> {
                    val saved = service?.saveCurrentQueue() ?: false
                    if (saved) {
                        service?.updateStatusMetadata("Queue saved!")
                        scope.launch {
                            delay(2000)
                            val active = ActivePlayback.provider ?: return@launch
                            val queue = active.getQueueAsTracks()
                            val idx = active.getCurrentIndex()
                            if (idx in queue.indices) {
                                val track = queue[idx]
                                service?.updateMetadata(track, track.album?.images?.firstOrNull()?.url)
                            }
                        }
                    }
                }
            }
        }

        override fun onSkipToQueueItem(id: Long) {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.skipToQueueItem(id.toInt())
                    service?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
