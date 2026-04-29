package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.api.GoogleDriveApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages playback history as an NDJSON file, synced to Google Drive.
 *
 * Each line is a JSON object recording a play event:
 *   {"type":"play","trackId":"...","trackName":"...","albumId":"...","albumName":"...","artistName":"...","playedAt":"2026-03-31T10:30:00Z"}
 *
 * Also records album-added events from library scans:
 *   {"type":"album_added","albumId":"...","albumName":"...","artistName":"...","addedAt":"2026-03-31T10:30:00Z"}
 *
 * Write strategy:
 * - Append locally (instant)
 * - Upload full local file to Drive periodically (throttled, max once per 5 min)
 * - On init: consolidate (merge any duplicate folders/files left over from
 *   races, trash extras, push merged content)
 *
 * All Drive operations are serialized through `driveMutex` so concurrent
 * uploads cannot each create their own folder/file.
 */
class GDrivePlaybackHistory private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GDrivePlayHistory"
        private const val PREFS_NAME = "gdrive_playback_history"
        private const val KEY_DRIVE_FILE_ID = "history_drive_file_id"
        private const val KEY_DRIVE_FOLDER_ID = "history_drive_folder_id"
        private const val KEY_LAST_UPLOAD = "last_upload_timestamp"
        // Bumped when the storage location for the history file changes.
        // v1 = file lives in <library root>/.cloudamp/ (was previously a
        // ".cloudamp" / "CloudAmp" folder at Drive root).
        private const val KEY_STORAGE_VERSION = "storage_version"
        private const val CURRENT_STORAGE_VERSION = 1
        private const val HISTORY_FILENAME = "playback_history.ndjson"
        private const val SUBFOLDER_NAME = ".cloudamp"
        private const val UPLOAD_THROTTLE_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        private var instance: GDrivePlaybackHistory? = null

        fun getInstance(context: Context): GDrivePlaybackHistory {
            return instance ?: synchronized(this) {
                instance ?: GDrivePlaybackHistory(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val localFile: File = File(context.filesDir, HISTORY_FILENAME)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Serializes all Drive operations so concurrent uploads cannot each create
    // a new folder/file and leave duplicates behind.
    private val driveMutex = Mutex()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Record a track play event. Appends locally and triggers async Drive upload.
     */
    fun recordPlay(track: GDriveTrack) {
        recordPlay(
            trackId = track.file.id,
            trackName = track.trackName,
            albumId = track.albumId,
            albumName = track.albumName,
            artistName = track.artistName
        )
    }

    /**
     * Record a track play event with explicit fields (for when full GDriveTrack metadata
     * is not available, e.g. playing from saved queue or raw file list).
     */
    fun recordPlay(
        trackId: String,
        trackName: String,
        albumId: String?,
        albumName: String?,
        artistName: String?
    ) {
        val line = JSONObject().apply {
            put("type", "play")
            put("trackId", trackId)
            put("trackName", trackName)
            put("albumId", albumId ?: "")
            put("albumName", albumName ?: "")
            put("artistName", artistName ?: "")
            put("playedAt", dateFormat.format(Date()))
        }.toString()

        appendLocal(line)
        scheduleUpload()
    }

    /**
     * Get all history lines, most recent first.
     */
    fun getHistory(): List<String> {
        if (!localFile.exists()) return emptyList()
        return try {
            localFile.readLines().filter { it.isNotBlank() }.reversed()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading history: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get recently played tracks (unique by albumId, most recent first).
     */
    fun getRecentlyPlayedAlbumIds(limit: Int = 20): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (line in getHistory()) {
            try {
                val json = JSONObject(line)
                if (json.optString("type") != "play") continue
                val albumId = json.optString("albumId", "")
                if (albumId.isNotEmpty() && seen.add(albumId)) {
                    result.add(albumId)
                    if (result.size >= limit) break
                }
            } catch (_: Exception) {}
        }
        return result
    }

    private fun appendLocal(line: String) {
        try {
            localFile.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error appending to local history: ${e.message}")
        }
    }

    private fun scheduleUpload() {
        val lastUpload = prefs.getLong(KEY_LAST_UPLOAD, 0)
        if (System.currentTimeMillis() - lastUpload < UPLOAD_THROTTLE_MS) return

        scope.launch {
            uploadToDrive()
        }
    }

    /**
     * Sync with Drive: consolidate any duplicate folders/files left over from
     * older racy code paths, merge their contents into one canonical file, and
     * push that merged content. Call on app start.
     */
    fun syncWithDrive() {
        scope.launch {
            try {
                consolidateOnDrive()
            } catch (e: Exception) {
                Log.e(TAG, "Drive sync error: ${e.message}")
            }
        }
    }

    /**
     * Force upload to Drive (e.g. on app pause).
     */
    fun forceUpload() {
        scope.launch {
            uploadToDrive()
        }
    }

    /**
     * Returns the parent folder ID for the history file: a ".cloudamp"
     * subfolder inside the user's configured music library root. Creates the
     * subfolder on Drive if missing. Returns null if the library root has not
     * been set or Drive is unavailable.
     *
     * Also performs a one-time migration on first call: clears the cached
     * file/folder IDs from the previous storage layout (where history lived in
     * a ".cloudamp" folder at Drive root) so the next upload re-resolves a
     * fresh subfolder inside the library root. The local NDJSON file persists,
     * so historical play events are preserved and re-uploaded.
     */
    private suspend fun resolveParentFolderId(): String? {
        migrateStorageVersionIfNeeded()

        val libraryRootId = GDriveLibraryCache.getInstance(context).getRootFolderId() ?: return null

        val cached = prefs.getString(KEY_DRIVE_FOLDER_ID, null)
        if (cached != null) return cached

        val client = GoogleDriveApiClient.getInstance(context)
        if (!client.hasAccessToken()) return null

        try {
            // Look for an existing .cloudamp subfolder inside the library root.
            val searchResponse = client.api.listFiles(
                query = "name = '$SUBFOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and '$libraryRootId' in parents and trashed = false",
                fields = "files(id,name)",
                pageSize = 1
            )
            if (searchResponse.isSuccessful) {
                val existing = searchResponse.body()?.files?.firstOrNull()
                if (existing != null) {
                    prefs.edit().putString(KEY_DRIVE_FOLDER_ID, existing.id).apply()
                    return existing.id
                }
            }

            // Otherwise create it.
            val metadata = JSONObject().apply {
                put("name", SUBFOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", org.json.JSONArray().put(libraryRootId))
            }.toString()

            val response = client.api.createFolder(
                metadata = metadata.toRequestBody("application/json".toMediaType())
            )
            if (response.isSuccessful) {
                val newId = response.body()?.id
                if (newId != null) {
                    prefs.edit().putString(KEY_DRIVE_FOLDER_ID, newId).apply()
                    return newId
                }
            } else {
                Log.e(TAG, "Failed to create .cloudamp subfolder: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving .cloudamp subfolder: ${e.message}")
        }
        return null
    }

    private fun migrateStorageVersionIfNeeded() {
        val current = prefs.getInt(KEY_STORAGE_VERSION, 0)
        if (current >= CURRENT_STORAGE_VERSION) return
        Log.d(TAG, "Migrating playback history storage v$current → v$CURRENT_STORAGE_VERSION")
        prefs.edit()
            .remove(KEY_DRIVE_FILE_ID)
            .remove(KEY_DRIVE_FOLDER_ID)
            .putInt(KEY_STORAGE_VERSION, CURRENT_STORAGE_VERSION)
            .apply()
    }

    private suspend fun uploadToDrive() = driveMutex.withLock {
        if (!localFile.exists() || localFile.length() == 0L) return@withLock

        val client = GoogleDriveApiClient.getInstance(context)
        if (!client.hasAccessToken()) return@withLock

        try {
            val folderId = resolveParentFolderId() ?: return@withLock
            val content = localFile.readText()
            val mediaBody = content.toRequestBody("application/x-ndjson".toMediaType())

            var fileId = prefs.getString(KEY_DRIVE_FILE_ID, null)

            if (fileId != null) {
                // Update existing file
                val response = client.api.updateFileContent(fileId, mediaBody)
                if (response.isSuccessful) {
                    prefs.edit().putLong(KEY_LAST_UPLOAD, System.currentTimeMillis()).apply()
                    Log.d(TAG, "History uploaded to Drive (updated)")
                    return@withLock
                } else if (response.code() == 404) {
                    // File was deleted, clear ID and retry
                    prefs.edit().remove(KEY_DRIVE_FILE_ID).apply()
                    fileId = null
                }
            }

            if (fileId == null) {
                // Create new file
                val metadata = JSONObject().apply {
                    put("name", HISTORY_FILENAME)
                    put("parents", org.json.JSONArray().put(folderId))
                }.toString()

                val metadataPart = metadata.toRequestBody("application/json".toMediaType())
                val mediaPart = MultipartBody.Part.createFormData("media", HISTORY_FILENAME, mediaBody)

                val response = client.api.createFile(metadataPart, mediaPart)
                if (response.isSuccessful) {
                    val newId = response.body()?.id
                    prefs.edit()
                        .putString(KEY_DRIVE_FILE_ID, newId)
                        .putLong(KEY_LAST_UPLOAD, System.currentTimeMillis())
                        .apply()
                    Log.d(TAG, "History uploaded to Drive (created, id=$newId)")
                } else {
                    Log.e(TAG, "Failed to create history file: ${response.code()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}")
        }
    }

    /**
     * Reconcile the state on Drive: find every ".cloudamp" folder under the
     * library root and every "playback_history.ndjson" inside any of them,
     * merge all their contents (line-deduped) with the local file, write the
     * merged content to a single canonical folder + file, and trash the rest.
     *
     * Idempotent: with no duplicates this performs the same work as a normal
     * download-then-upload sync.
     */
    private suspend fun consolidateOnDrive() = driveMutex.withLock {
        val libraryRootId = GDriveLibraryCache.getInstance(context).getRootFolderId() ?: return@withLock
        val client = GoogleDriveApiClient.getInstance(context)
        if (!client.hasAccessToken()) return@withLock

        migrateStorageVersionIfNeeded()

        try {
            // 1. Find all .cloudamp subfolders inside the library root.
            val folders = listAll { pageToken ->
                client.api.listFiles(
                    query = "name = '$SUBFOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and '$libraryRootId' in parents and trashed = false",
                    fields = "files(id,name),nextPageToken",
                    pageSize = 100,
                    pageToken = pageToken
                )
            } ?: return@withLock

            // 2. Find all playback_history.ndjson files in any of those folders.
            val allFiles = mutableListOf<DriveFile>()
            for (folder in folders) {
                val files = listAll { pageToken ->
                    client.api.listFiles(
                        query = "name = '$HISTORY_FILENAME' and '${folder.id}' in parents and trashed = false",
                        fields = "files(id,name,parents),nextPageToken",
                        pageSize = 100,
                        pageToken = pageToken
                    )
                } ?: continue
                allFiles.addAll(files)
            }

            // 3. Merge local + every remote file's lines, dedupe by exact match.
            val merged = LinkedHashSet<String>()
            if (localFile.exists()) {
                for (line in localFile.readLines()) {
                    if (line.isNotBlank()) merged.add(line)
                }
            }
            for (file in allFiles) {
                try {
                    val resp = client.api.downloadFile(file.id)
                    if (!resp.isSuccessful) continue
                    val text = resp.body()?.string() ?: continue
                    for (line in text.split("\n")) {
                        if (line.isNotBlank()) merged.add(line)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading ${file.id} during consolidation: ${e.message}")
                }
            }

            val mergedContent = if (merged.isEmpty()) "" else merged.joinToString("\n") + "\n"

            // 4. Pick / create the canonical folder.
            val canonicalFolderId = if (folders.isNotEmpty()) {
                folders.minByOrNull { it.id }!!.id
            } else {
                createSubfolder(client, libraryRootId) ?: return@withLock
            }

            // 5. Pick / create the canonical file inside the canonical folder.
            val filesInCanonical = allFiles.filter { it.parents?.contains(canonicalFolderId) == true }
            var canonicalFileId: String? = filesInCanonical.minByOrNull { it.id }?.id

            val mediaBody = mergedContent.toRequestBody("application/x-ndjson".toMediaType())
            if (canonicalFileId != null) {
                if (mergedContent.isNotEmpty()) {
                    val resp = client.api.updateFileContent(canonicalFileId, mediaBody)
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Consolidation: update canonical failed ${resp.code()}")
                    }
                }
            } else if (mergedContent.isNotEmpty()) {
                val metadata = JSONObject().apply {
                    put("name", HISTORY_FILENAME)
                    put("parents", org.json.JSONArray().put(canonicalFolderId))
                }.toString()
                val metadataPart = metadata.toRequestBody("application/json".toMediaType())
                val mediaPart = MultipartBody.Part.createFormData("media", HISTORY_FILENAME, mediaBody)
                val resp = client.api.createFile(metadataPart, mediaPart)
                if (resp.isSuccessful) {
                    canonicalFileId = resp.body()?.id
                } else {
                    Log.e(TAG, "Consolidation: create canonical failed ${resp.code()}")
                }
            }

            // 6. Update local file with merged content.
            if (mergedContent.isNotEmpty()) {
                localFile.writeText(mergedContent)
            }

            // 7. Cache canonical IDs.
            prefs.edit()
                .putString(KEY_DRIVE_FOLDER_ID, canonicalFolderId)
                .also { ed ->
                    if (canonicalFileId != null) ed.putString(KEY_DRIVE_FILE_ID, canonicalFileId)
                    else ed.remove(KEY_DRIVE_FILE_ID)
                }
                .putLong(KEY_LAST_UPLOAD, System.currentTimeMillis())
                .apply()

            // 8. Trash duplicate files (everything except the canonical).
            val trashBody = JSONObject().apply { put("trashed", true) }.toString()
                .toRequestBody("application/json".toMediaType())
            var trashedFiles = 0
            for (file in allFiles) {
                if (file.id == canonicalFileId) continue
                try {
                    val resp = client.api.trashFile(file.id, trashBody)
                    if (resp.isSuccessful) trashedFiles++
                    else Log.e(TAG, "Failed to trash file ${file.id}: ${resp.code()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error trashing file ${file.id}: ${e.message}")
                }
            }

            // 9. Trash duplicate folders.
            var trashedFolders = 0
            for (folder in folders) {
                if (folder.id == canonicalFolderId) continue
                try {
                    val resp = client.api.trashFile(folder.id, trashBody)
                    if (resp.isSuccessful) trashedFolders++
                    else Log.e(TAG, "Failed to trash folder ${folder.id}: ${resp.code()}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error trashing folder ${folder.id}: ${e.message}")
                }
            }

            Log.d(TAG, "Consolidation done: kept folder=$canonicalFolderId file=$canonicalFileId, trashed $trashedFolders folders + $trashedFiles files, ${merged.size} unique lines")
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation error: ${e.message}")
        }
    }

    private suspend fun listAll(
        page: suspend (pageToken: String?) -> retrofit2.Response<com.cloudamp.music.api.DriveFileListResponse>
    ): List<DriveFile>? {
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val resp = page(pageToken)
            if (!resp.isSuccessful) {
                Log.e(TAG, "listFiles failed: ${resp.code()}")
                return null
            }
            val body = resp.body() ?: break
            out.addAll(body.files)
            pageToken = body.nextPageToken
        } while (pageToken != null)
        return out
    }

    private suspend fun createSubfolder(client: GoogleDriveApiClient, libraryRootId: String): String? {
        val metadata = JSONObject().apply {
            put("name", SUBFOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
            put("parents", org.json.JSONArray().put(libraryRootId))
        }.toString()
        val resp = client.api.createFolder(
            metadata = metadata.toRequestBody("application/json".toMediaType())
        )
        if (resp.isSuccessful) return resp.body()?.id
        Log.e(TAG, "Failed to create .cloudamp subfolder: ${resp.code()}")
        return null
    }

}
