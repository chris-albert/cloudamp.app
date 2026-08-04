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
 * Drive I/O around [FavoritesCore]: find-or-create of the `.cloudamp` folder
 * and the Favorites File under the library root (same location and pattern as
 * [GDrivePlaybackHistory]), plus read-merge-write toggles.
 *
 * Every toggle GETs the file fresh, applies only that toggle on top of the
 * remote list, and PUTs the result — never a blind write of in-memory state
 * (ADR-0001). Drive folder/file ids are cached for the session; content is not.
 */
class FavoritesRepository(
    private val api: GoogleDriveApiService,
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

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun isFavorite(albumId: String): Boolean = loadedFavorites.any { it.albumId == albumId }

    fun listFavorites(): List<FavoritesCore.FavoriteEntry> = loadedFavorites

    /**
     * Load the remote Favorites File. A missing folder or file yields an
     * empty list — nothing is created on the read path.
     */
    suspend fun load(): List<FavoritesCore.FavoriteEntry> = driveMutex.withLock {
        val rootId = rootFolderIdProvider() ?: return@withLock emptyList()
        val folderId = findFolderId(rootId) ?: return@withLock emptyList<FavoritesCore.FavoriteEntry>().also {
            loadedFavorites = it
        }
        val fileId = findFileId(folderId) ?: return@withLock emptyList<FavoritesCore.FavoriteEntry>().also {
            loadedFavorites = it
        }
        val file = fetchRemote(fileId)
        loadedFavorites = file.favorites
        loadedFavorites
    }

    /**
     * Toggle one album via read-merge-write and return the merged list.
     * Throws on any Drive failure or on an unknown schemaVersion — callers
     * revert their optimistic state.
     */
    suspend fun toggle(albumId: String, favorite: Boolean): List<FavoritesCore.FavoriteEntry> =
        driveMutex.withLock {
            val rootId = rootFolderIdProvider()
                ?: throw IllegalStateException("Music root folder not configured")
            val folderId = findOrCreateFolderId(rootId)
            val fileId = findFileId(folderId)

            val remote = if (fileId != null) fetchRemote(fileId) else FavoritesCore.emptyFile()
            val merged = FavoritesCore.applyToggle(remote, albumId, favorite, dateFormat.format(Date()))
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

            loadedFavorites = merged.favorites
            merged.favorites
        }

    private suspend fun fetchRemote(fileId: String): FavoritesCore.FavoritesFile {
        val response = api.downloadFile(fileId)
        if (!response.isSuccessful) {
            throw IOException("Failed to download favorites file: HTTP ${response.code()}")
        }
        val text = response.body()?.string() ?: ""
        return when (val parsed = FavoritesCore.parse(text)) {
            is FavoritesCore.ParseResult.Ok -> parsed.file
            is FavoritesCore.ParseResult.UnknownVersion -> throw IOException(
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
