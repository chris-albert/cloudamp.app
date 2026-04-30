package com.cloudamp.music.cache

import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Walks the upcoming queue and pulls each track's bytes into the shared
 * MediaCache so playback is hot from disk before ExoPlayer advances.
 * Stops short of the configured cap to leave room for the active track
 * and avoid the LRU evictor immediately reclaiming what we just wrote.
 *
 * One run at a time: starting a new prefetch cancels any in-flight one.
 */
class MediaCachePrefetcher(private val mediaCache: MediaCache) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    data class Item(val fileId: String, val uri: Uri)

    fun prefetch(items: List<Item>, upstream: DataSource.Factory) {
        cancel()
        if (items.isEmpty()) return
        job = scope.launch {
            // Give the active player a head start before we contend on the
            // network — first-track buffer matters more than queue depth.
            delay(START_DELAY_MS)
            val cap = mediaCache.maxBytes
            val softCeiling = (cap * SOFT_CEILING).toLong()
            for (item in items) {
                if (!isActive) return@launch
                if (mediaCache.cacheStateFor(item.fileId) == MediaCache.CacheState.FULL) continue
                val used = mediaCache.cache.cacheSpace
                if (used >= softCeiling) {
                    Log.d(TAG, "Prefetch stopping: cache near cap ($used/$cap)")
                    return@launch
                }

                val dataSpec = DataSpec.Builder()
                    .setUri(item.uri)
                    .setKey(item.fileId)
                    .build()
                val ds = mediaCache.cacheDataSourceFactory(upstream)
                    .createDataSource() as CacheDataSource
                val writer = CacheWriter(ds, dataSpec, null, null)

                // Bridge coroutine cancellation into the writer so a torn-down
                // queue stops downloading immediately instead of finishing the
                // current track first.
                val cancelHandle = coroutineContext[Job]!!.invokeOnCompletion { writer.cancel() }
                try {
                    writer.cache()
                    Log.d(TAG, "Prefetched ${item.fileId}")
                } catch (e: Exception) {
                    if (!isActive) return@launch
                    Log.w(TAG, "Prefetch failed for ${item.fileId}: ${e.message}")
                } finally {
                    cancelHandle.dispose()
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "MediaCachePrefetcher"
        private const val START_DELAY_MS = 3_000L
        private const val SOFT_CEILING = 0.95
    }
}
