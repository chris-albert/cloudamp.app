package com.cloudamp.music.cache

import android.content.Context
import android.util.Log
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GoogleDriveApiClient
import com.cloudamp.music.api.GoogleDriveApiService
import com.cloudamp.music.cache.PlaylistsCore.Playlist
import com.cloudamp.music.cache.PlaylistsCore.PlaylistOp
import com.cloudamp.music.cache.PlaylistsCore.PlaylistsFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import java.util.UUID

/**
 * Drive I/O around [PlaylistsCore]: find-or-create of the `.cloudamp` folder
 * and the Playlists File under the library root (same location and pattern as
 * [FavoritesRepository]), plus read-merge-write of playlist ops.
 *
 * [apply] mutates the in-memory list immediately and queues the op; [sync]
 * GETs the file fresh, replays every queued op on top of the remote content,
 * and PUTs the result — never a blind write of in-memory state
 * (ADR-0002). Ops that fail to reach Drive (offline) stay queued and are
 * replayed at the next [sync]. State is persisted to a [PlaylistsCacheStore]
 * so playlists render at launch without waiting on Drive.
 */
class PlaylistsRepository(
    private val api: GoogleDriveApiService,
    private val cacheStore: PlaylistsCacheStore,
    private val rootFolderIdProvider: () -> String?
) {

    companion object {
        private const val TAG = "PlaylistsRepository"
        private const val SUBFOLDER_NAME = ".cloudamp"
        private const val PLAYLISTS_FILENAME = "playlists.json"

        @Volatile
        private var instance: PlaylistsRepository? = null

        fun getInstance(context: Context): PlaylistsRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    PlaylistsRepository(
                        api = GoogleDriveApiClient.getInstance(appContext).api,
                        cacheStore = SharedPrefsPlaylistsCacheStore(appContext),
                        rootFolderIdProvider = {
                            GDriveLibraryCache.getInstance(appContext).getRootFolderId()
                        }
                    ).also { instance = it }
                }
            }
        }
    }

    // Serializes Drive round trips so two syncs cannot interleave their
    // read-merge-write cycles or each create a folder/file.
    private val driveMutex = Mutex()
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var cachedFolderId: String? = null
    private var cachedFileId: String? = null

    // Guards loaded/pendingOps/hydrated; held only briefly, never across I/O.
    private val lock = Any()
    private var loaded: PlaylistsFile = PlaylistsCore.emptyFile()
    private val pendingOps = mutableListOf<PlaylistOp>()
    private var hydrated = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun now(): String = dateFormat.format(Date())

    fun listPlaylists(): List<Playlist> = synchronized(lock) { hydrateLocked(); loaded.playlists }

    fun getPlaylist(playlistId: String): Playlist? = listPlaylists().firstOrNull { it.id == playlistId }

    /** Populate in-memory state from the local cache — no Drive I/O. Idempotent. */
    fun hydrateFromCache(): List<Playlist> = synchronized(lock) { hydrateLocked(); loaded.playlists }

    private fun hydrateLocked() {
        if (hydrated) return
        hydrated = true
        val cached = cacheStore.load() ?: return
        loaded = when (val parsed = PlaylistsCore.parse(cached.fileJson)) {
            is PlaylistsCore.ParseResult.Ok -> parsed.file
            is PlaylistsCore.ParseResult.UnknownVersion -> PlaylistsCore.emptyFile()
        }
        pendingOps.addAll(cached.pendingOps.mapNotNull { PlaylistsCore.parseOp(it) })
    }

    // ── Mutations ───────────────────────────────────────────────────────

    /**
     * Apply an op locally and queue it for Drive. Returns the updated list
     * synchronously so the UI can re-render at once; call [syncInBackground]
     * (or [sync]) to push it.
     */
    fun apply(op: PlaylistOp): List<Playlist> = synchronized(lock) {
        hydrateLocked()
        loaded = PlaylistsCore.applyOp(loaded, op)
        pendingOps.add(op)
        persistCacheLocked()
        loaded.playlists
    }

    fun createPlaylist(name: String, files: List<DriveFile> = emptyList()): Playlist {
        val at = now()
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = at,
            updatedAt = at,
            tracks = files.map { PlaylistsCore.PlaylistTrack.fromDriveFile(it, at) }
        )
        apply(PlaylistOp.Create(playlist))
        return playlist
    }

    /** @return the number of files that weren't already in the playlist */
    fun addFiles(playlistId: String, files: List<DriveFile>): Int {
        val existing = getPlaylist(playlistId)?.tracks?.map { it.fileId }?.toSet() ?: return 0
        val at = now()
        val tracks = files.filter { it.id !in existing }.distinctBy { it.id }
            .map { PlaylistsCore.PlaylistTrack.fromDriveFile(it, at) }
        if (tracks.isEmpty()) return 0
        apply(PlaylistOp.AddTracks(playlistId, tracks, at))
        return tracks.size
    }

    /** Push queued ops (or refresh from Drive) without blocking the caller; failures are kept for next time. */
    fun syncInBackground() {
        backgroundScope.launch {
            try {
                sync()
            } catch (e: Exception) {
                Log.w(TAG, "Background playlist sync failed: ${e.message}")
            }
        }
    }

    // ── Sync ────────────────────────────────────────────────────────────

    /**
     * Reconcile against Drive: replay queued ops onto the freshly fetched
     * file and PUT it when there are any, otherwise read the remote file
     * fresh. A missing folder or file yields an empty list — nothing is
     * created on the read path. Throws on a Drive failure; local state and
     * queued ops stay intact for the next attempt. An unknown schemaVersion
     * drops the queued ops (a newer client owns the file) and rethrows.
     */
    suspend fun sync(): List<Playlist> = driveMutex.withLock {
        val batch = synchronized(lock) { hydrateLocked(); pendingOps.toList() }

        val remote = if (batch.isNotEmpty()) {
            try {
                writeOps(batch)
            } catch (e: UnsupportedSchemaException) {
                synchronized(lock) { pendingOps.subList(0, batch.size).clear(); persistCacheLocked() }
                throw e
            }
        } else {
            val rootId = rootFolderIdProvider() ?: return@withLock listPlaylists()
            val folderId = findFolderId(rootId)
            val fileId = folderId?.let { findFileId(it) }
            if (fileId != null) fetchRemote(fileId) else PlaylistsCore.emptyFile()
        }

        synchronized(lock) {
            pendingOps.subList(0, batch.size).clear()
            // Ops applied while the write was in flight ride on top of the fresh remote state
            loaded = pendingOps.fold(remote) { file, op -> PlaylistsCore.applyOp(file, op) }
            persistCacheLocked()
            loaded.playlists
        }
    }

    private fun persistCacheLocked() {
        cacheStore.save(
            PersistedPlaylists(
                fileJson = PlaylistsCore.serialize(loaded),
                pendingOps = pendingOps.map { PlaylistsCore.serializeOp(it) }
            )
        )
    }

    /**
     * Read-merge-write a batch of ops: GET the file fresh, replay the ops on
     * top of the remote content, PUT the result. Creates the folder and file
     * on first write. Returns the merged file that was written.
     */
    private suspend fun writeOps(ops: List<PlaylistOp>): PlaylistsFile {
        val rootId = rootFolderIdProvider()
            ?: throw IllegalStateException("Music root folder not configured")
        val folderId = findOrCreateFolderId(rootId)
        val fileId = findFileId(folderId)

        val remote = if (fileId != null) fetchRemote(fileId) else PlaylistsCore.emptyFile()
        val merged = ops.fold(remote) { file, op -> PlaylistsCore.applyOp(file, op) }
        val content = PlaylistsCore.serialize(merged)

        if (fileId != null) {
            val response = api.updateFileContent(
                fileId,
                content.toRequestBody("application/json".toMediaType())
            )
            if (!response.isSuccessful) {
                throw IOException("Failed to update playlists file: HTTP ${response.code()}")
            }
        } else {
            val metadata = JsonObject().apply {
                addProperty("name", PLAYLISTS_FILENAME)
                add("parents", JsonArray().apply { add(folderId) })
            }.toString()
            val response = api.createFile(
                metadata = metadata.toRequestBody("application/json".toMediaType()),
                media = MultipartBody.Part.createFormData(
                    "media",
                    PLAYLISTS_FILENAME,
                    content.toRequestBody("application/json".toMediaType())
                )
            )
            if (!response.isSuccessful) {
                throw IOException("Failed to create playlists file: HTTP ${response.code()}")
            }
            cachedFileId = response.body()?.id
        }

        return merged
    }

    private suspend fun fetchRemote(fileId: String): PlaylistsFile {
        val response = api.downloadFile(fileId)
        if (!response.isSuccessful) {
            throw IOException("Failed to download playlists file: HTTP ${response.code()}")
        }
        val text = response.body()?.string() ?: ""
        return when (val parsed = PlaylistsCore.parse(text)) {
            is PlaylistsCore.ParseResult.Ok -> parsed.file
            is PlaylistsCore.ParseResult.UnknownVersion -> throw UnsupportedSchemaException(
                "Playlists file has unsupported schemaVersion ${parsed.schemaVersion}; not overwriting it"
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
            "name = '$PLAYLISTS_FILENAME' and '$folderId' in parents and trashed = false"
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
