package com.cloudamp.music.cache

import android.content.Context
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.api.GoogleDriveApiService
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Unlike a network failure, an unknown schemaVersion is a deliberate refusal
 * (a newer client owns the file) — retrying later can't help, so it must not
 * go on the dirty queue.
 */
class UnsupportedSchemaException(message: String) : IOException(message)

/**
 * Drive I/O around [FavoritesCore]: find-or-create of the `.cloudamp` folder
 * and the Favorites File under the library root (same location and pattern as
 * [GDrivePlaybackHistory]), plus read-merge-write toggles.
 *
 * Every write GETs the file fresh, applies only the pending toggles on top of
 * the remote list, and PUTs the result — never a blind write of in-memory
 * state (ADR-0001). Drive folder/file ids are cached for the session; content
 * is not.
 *
 * State is also persisted to a [FavoritesCacheStore] so hearts render at app
 * launch without waiting on Drive; [load] then reconciles against Drive.
 * Toggles whose write fails (offline) are kept locally, marked dirty, and
 * pushed through the same read-merge-write flow at the next launch or next
 * successful toggle.
 */
class FavoritesRepository(
    private val api: GoogleDriveApiService,
    private val cacheStore: FavoritesCacheStore,
    private val rootFolderIdProvider: () -> String?
) {

    companion object {
        private const val SUBFOLDER_NAME = ".cloudamp"
        private const val FAVORITES_FILENAME = "favorites.json"

        @Volatile
        private var instance: FavoritesRepository? = null

        fun getInstance(context: Context): FavoritesRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    FavoritesRepository(
                        api = GoogleDriveApiClient.getInstance(appContext).api,
                        cacheStore = SharedPrefsFavoritesCacheStore(appContext),
                        rootFolderIdProvider = {
                            GDriveLibraryCache.getInstance(appContext).getRootFolderId()
                        }
                    ).also { instance = it }
                }
            }
        }
    }

    // Serializes toggles so concurrent taps cannot each create a folder/file
    // or interleave their read-merge-write cycles.
    private val driveMutex = Mutex()

    private var cachedFolderId: String? = null
    private var cachedFileId: String? = null

    @Volatile
    private var loadedFavorites: List<FavoritesCore.FavoriteEntry> = emptyList()

    // Toggles whose Drive write failed, keyed by albumId so the latest toggle
    // per album wins. Mutated under driveMutex.
    private val dirtyToggles = LinkedHashMap<String, PendingToggle>()

    private var hydrated = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun isFavorite(albumId: String): Boolean = loadedFavorites.any { it.albumId == albumId }

    fun listFavorites(): List<FavoritesCore.FavoriteEntry> = loadedFavorites

    /**
     * Populate in-memory state from the local cache — no Drive I/O — so
     * hearts and the Favorites row render immediately at launch. Idempotent;
     * [load] and [toggle] also hydrate first so dirty toggles from a previous
     * session are never lost.
     */
    @Synchronized
    fun hydrateFromCache(): List<FavoritesCore.FavoriteEntry> {
        if (!hydrated) {
            hydrated = true
            cacheStore.load()?.let { cached ->
                loadedFavorites = cached.favorites
                for (toggle in cached.pending) {
                    dirtyToggles[toggle.albumId] = toggle
                }
            }
        }
        return loadedFavorites
    }

    /**
     * Reconcile against Drive: push dirty toggles from a previous session via
     * read-merge-write when there are any, otherwise read the remote Favorites
     * File fresh. A missing folder or file yields an empty list — nothing is
     * created on the read path. Throws on a Drive failure; the hydrated state
     * (and any dirty toggles) stay intact for the next attempt.
     */
    suspend fun load(): List<FavoritesCore.FavoriteEntry> {
        hydrateFromCache()
        return driveMutex.withLock {
            if (dirtyToggles.isNotEmpty()) {
                val merged = writeToggles(dirtyToggles.values.toList())
                dirtyToggles.clear()
                loadedFavorites = merged.favorites
                persistCache()
                return@withLock loadedFavorites
            }

            val rootId = rootFolderIdProvider() ?: return@withLock loadedFavorites
            val folderId = findFolderId(rootId)
            val fileId = folderId?.let { findFileId(it) }
            val file = if (fileId != null) fetchRemote(fileId) else FavoritesCore.emptyFile()
            loadedFavorites = file.favorites
            persistCache()
            loadedFavorites
        }
    }

    /**
     * Toggle one album via read-merge-write and return the merged list; dirty
     * toggles from earlier failures ride along in the same write. If the write
     * fails the toggled state is kept locally and marked dirty for retry at
     * the next launch/toggle — except on an unknown schemaVersion, which is
     * rethrown so callers revert their optimistic state.
     */
    suspend fun toggle(albumId: String, favorite: Boolean): List<FavoritesCore.FavoriteEntry> {
        hydrateFromCache()
        return driveMutex.withLock {
            val toggleEntry = PendingToggle(albumId, favorite, dateFormat.format(Date()))
            val pending = dirtyToggles.values.filter { it.albumId != albumId }
            try {
                val merged = writeToggles(pending + toggleEntry)
                dirtyToggles.clear()
                loadedFavorites = merged.favorites
            } catch (e: UnsupportedSchemaException) {
                throw e
            } catch (e: Exception) {
                // Offline or Drive error: keep the toggled state and retry later
                dirtyToggles[albumId] = toggleEntry
                loadedFavorites = FavoritesCore.applyToggle(
                    FavoritesCore.FavoritesFile(FavoritesCore.SCHEMA_VERSION, loadedFavorites),
                    albumId,
                    favorite,
                    toggleEntry.favoritedAt
                ).favorites
            }
            persistCache()
            loadedFavorites
        }
    }

    private fun persistCache() {
        cacheStore.save(PersistedFavorites(loadedFavorites, dirtyToggles.values.toList()))
    }

    /**
     * Read-merge-write a batch of toggles: GET the file fresh, apply only
     * these toggles on top of the remote list, PUT the result. Creates the
     * folder and file on first write. Returns the merged file that was written.
     */
    private suspend fun writeToggles(toggles: List<PendingToggle>): FavoritesCore.FavoritesFile {
        val rootId = rootFolderIdProvider()
            ?: throw IllegalStateException("Music root folder not configured")
        val folderId = findOrCreateFolderId(rootId)
        val fileId = findFileId(folderId)

        val remote = if (fileId != null) fetchRemote(fileId) else FavoritesCore.emptyFile()
        var merged = remote
        for (toggle in toggles) {
            merged = FavoritesCore.applyToggle(merged, toggle.albumId, toggle.favorite, toggle.favoritedAt)
        }
        val content = FavoritesCore.serialize(merged)

        if (fileId != null) {
            val response = api.updateFileContent(
                fileId,
                content.toRequestBody("application/json".toMediaType())
            )
            if (!response.isSuccessful) {
                throw IOException("Failed to update favorites file: HTTP ${response.code()}")
            }
        } else {
            val metadata = JsonObject().apply {
                addProperty("name", FAVORITES_FILENAME)
                add("parents", JsonArray().apply { add(folderId) })
            }.toString()
            val response = api.createFile(
                metadata = metadata.toRequestBody("application/json".toMediaType()),
                media = MultipartBody.Part.createFormData(
                    "media",
                    FAVORITES_FILENAME,
                    content.toRequestBody("application/json".toMediaType())
                )
            )
            if (!response.isSuccessful) {
                throw IOException("Failed to create favorites file: HTTP ${response.code()}")
            }
            cachedFileId = response.body()?.id
        }

        return merged
    }

    private suspend fun fetchRemote(fileId: String): FavoritesCore.FavoritesFile {
        val response = api.downloadFile(fileId)
        if (!response.isSuccessful) {
            throw IOException("Failed to download favorites file: HTTP ${response.code()}")
        }
        val text = response.body()?.string() ?: ""
        return when (val parsed = FavoritesCore.parse(text)) {
            is FavoritesCore.ParseResult.Ok -> parsed.file
            is FavoritesCore.ParseResult.UnknownVersion -> throw UnsupportedSchemaException(
                "Favorites file has unsupported schemaVersion ${parsed.schemaVersion}; not overwriting it"
            )
        }
    }

    private suspend fun findFolderId(rootId: String): String? {
        cachedFolderId?.let { return it }
        val folders = listAll(
            "name = '$SUBFOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' " +
                "and '$rootId' in parents and trashed = false"
        )
        cachedFolderId = folders.minByOrNull { it.id }?.id
        return cachedFolderId
    }

    private suspend fun findOrCreateFolderId(rootId: String): String {
        findFolderId(rootId)?.let { return it }
        val metadata = JsonObject().apply {
            addProperty("name", SUBFOLDER_NAME)
            addProperty("mimeType", "application/vnd.google-apps.folder")
            add("parents", JsonArray().apply { add(rootId) })
        }.toString()
        val response = api.createFolder(metadata.toRequestBody("application/json".toMediaType()))
        val id = if (response.isSuccessful) response.body()?.id else null
        cachedFolderId = id ?: throw IOException("Failed to create $SUBFOLDER_NAME folder: HTTP ${response.code()}")
        return id
    }

    private suspend fun findFileId(folderId: String): String? {
        cachedFileId?.let { return it }
        val files = listAll(
            "name = '$FAVORITES_FILENAME' and '$folderId' in parents and trashed = false"
        )
        cachedFileId = files.minByOrNull { it.id }?.id
        return cachedFileId
    }

    private suspend fun listAll(query: String): List<DriveFile> {
        val results = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val response = api.listFiles(
                query = query,
                fields = "files(id,name),nextPageToken",
                pageToken = pageToken
            )
            if (!response.isSuccessful) {
                throw IOException("Drive list failed: HTTP ${response.code()}")
            }
            val body = response.body() ?: break
            results.addAll(body.files)
            pageToken = body.nextPageToken
        } while (pageToken != null)
        return results
    }
}
