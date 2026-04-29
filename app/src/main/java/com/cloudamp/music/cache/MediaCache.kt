package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import org.json.JSONObject
import java.io.File

/**
 * Disk-backed media cache shared between the ExoPlayer pipeline and the
 * Settings UI. Bytes streamed from Google Drive are persisted to
 * filesDir/media-cache (survives "Clear cache" in Android settings; only
 * goes away on uninstall, manual clear, or LRU eviction once we hit the
 * size cap).
 *
 * Cache keys are the Google Drive file ID (set via
 * MediaItem.Builder().setCustomCacheKey(...)), so a sidecar JSON index
 * maps file ID -> human-readable name/size for the Settings list.
 */
class MediaCache private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val cacheDir: File = File(appContext.filesDir, "media-cache")
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val databaseProvider by lazy { StandaloneDatabaseProvider(appContext) }

    /** Configured cap (persisted). Takes effect on next app launch. */
    val maxBytes: Long
        get() = prefs.getLong(KEY_MAX_BYTES, DEFAULT_MAX_BYTES)

    /** Cap the live SimpleCache was actually built with (-1 until first use). */
    @Volatile private var openedWithMaxBytes: Long = -1L

    @Volatile private var simpleCache: SimpleCache? = null

    val cache: SimpleCache
        get() = simpleCache ?: synchronized(this) {
            simpleCache ?: openCache().also {
                simpleCache = it
                openedWithMaxBytes = maxBytes
            }
        }

    private fun openCache(): SimpleCache {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
        return SimpleCache(cacheDir, evictor, databaseProvider)
    }

    /** Persist a new size cap. Fully applies on next app launch. */
    fun setMaxBytes(newBytes: Long) {
        if (newBytes <= 0 || newBytes == maxBytes) return
        prefs.edit().putLong(KEY_MAX_BYTES, newBytes).apply()
    }

    /** True if a SimpleCache is open and the configured cap differs from it. */
    fun isRestartRequired(): Boolean =
        openedWithMaxBytes > 0 && openedWithMaxBytes != maxBytes

    /** Wrap an upstream factory so reads are served from / written to disk. */
    fun cacheDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Record a track we just enqueued so the Settings UI can label cache entries. */
    fun registerTrack(fileId: String, name: String, totalBytes: Long?) {
        val index = readIndex()
        val entry = JSONObject().apply {
            put("name", name)
            if (totalBytes != null && totalBytes > 0) put("size", totalBytes)
        }
        index.put(fileId, entry)
        writeIndex(index)
    }

    data class CachedTrack(
        val fileId: String,
        val name: String,
        val cachedBytes: Long,
        val totalBytes: Long?,
    )

    data class Stats(
        val totalBytes: Long,
        val maxBytes: Long,
        val tracks: List<CachedTrack>,
    )

    fun stats(): Stats {
        val index = readIndex()
        val keys = cache.keys.toList()
        val list = mutableListOf<CachedTrack>()
        for (key in keys) {
            val spans = cache.getCachedSpans(key) ?: continue
            val bytes = spans.sumOf { it.length }
            if (bytes <= 0) continue
            val meta = index.optJSONObject(key)
            list.add(
                CachedTrack(
                    fileId = key,
                    name = meta?.optString("name").orEmpty().ifEmpty { key },
                    cachedBytes = bytes,
                    totalBytes = meta?.optLong("size", -1L)?.takeIf { it > 0 },
                )
            )
        }
        list.sortByDescending { it.cachedBytes }
        return Stats(cache.cacheSpace, maxBytes, list)
    }

    enum class CacheState { NONE, PARTIAL, FULL }

    /**
     * Cheap per-track lookup for UI indicators. If the cache hasn't been
     * opened yet this session, returns NONE without forcing it open.
     */
    fun cacheStateFor(fileId: String): CacheState {
        val live = simpleCache ?: return CacheState.NONE
        val spans = live.getCachedSpans(fileId) ?: return CacheState.NONE
        if (spans.isEmpty()) return CacheState.NONE
        val cached = spans.sumOf { it.length }
        if (cached <= 0) return CacheState.NONE
        val total = readIndex().optJSONObject(fileId)?.optLong("size", -1L) ?: -1L
        return if (total > 0 && cached >= total) CacheState.FULL else CacheState.PARTIAL
    }

    /** Snapshot states for a batch of file IDs in one pass (single index read). */
    fun cacheStatesFor(fileIds: Collection<String>): Map<String, CacheState> {
        val live = simpleCache ?: return emptyMap()
        val index = readIndex()
        val out = HashMap<String, CacheState>(fileIds.size)
        for (id in fileIds) {
            val spans = live.getCachedSpans(id) ?: continue
            if (spans.isEmpty()) continue
            val cached = spans.sumOf { it.length }
            if (cached <= 0) continue
            val total = index.optJSONObject(id)?.optLong("size", -1L) ?: -1L
            out[id] = if (total > 0 && cached >= total) CacheState.FULL else CacheState.PARTIAL
        }
        return out
    }

    /** Drop every cached byte and the sidecar index. */
    fun clear() {
        for (key in cache.keys.toList()) {
            cache.removeResource(key)
        }
        prefs.edit().remove(KEY_INDEX).apply()
    }

    private fun readIndex(): JSONObject = try {
        JSONObject(prefs.getString(KEY_INDEX, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun writeIndex(obj: JSONObject) {
        prefs.edit().putString(KEY_INDEX, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "media_cache_prefs"
        private const val KEY_INDEX = "index"
        private const val KEY_MAX_BYTES = "max_bytes"

        const val DEFAULT_MAX_BYTES: Long = 1L * 1024 * 1024 * 1024 // 1 GB

        /** Picker options surfaced in Settings. */
        val SIZE_PRESETS: List<Pair<Long, String>> = listOf(
            256L * 1024 * 1024 to "256 MB",
            512L * 1024 * 1024 to "512 MB",
            1L * 1024 * 1024 * 1024 to "1 GB",
            2L * 1024 * 1024 * 1024 to "2 GB",
            5L * 1024 * 1024 * 1024 to "5 GB",
            10L * 1024 * 1024 * 1024 to "10 GB",
        )

        @Volatile private var instance: MediaCache? = null

        fun getInstance(context: Context): MediaCache =
            instance ?: synchronized(this) {
                instance ?: MediaCache(context.applicationContext).also { instance = it }
            }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
