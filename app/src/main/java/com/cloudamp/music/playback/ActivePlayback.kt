package com.cloudamp.music.playback

/**
 * Singleton that tracks which PlaybackProvider is currently active.
 * Consumers use [provider] instead of branching on provider type.
 */
object ActivePlayback {
    @Volatile
    var provider: PlaybackProvider? = null
        private set

    fun activate(newProvider: PlaybackProvider) {
        val old = provider
        if (old !== newProvider) {
            if (old is GDrivePlaybackManager) old.deactivate()
        }
        provider = newProvider
    }

    fun clear() {
        provider = null
    }
}
