package com.cloudamp.music.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.api.GoogleDriveApiClient
import kotlinx.coroutines.*
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
 * - On init: download from Drive and merge (take longer file)
 */
class GDrivePlaybackHistory private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GDrivePlayHistory"
        private const val PREFS_NAME = "gdrive_playback_history"
        private const val KEY_DRIVE_FILE_ID = "history_drive_file_id"
        private const val KEY_DRIVE_FOLDER_ID = "history_drive_folder_id"
        private const val KEY_LAST_UPLOAD = "last_upload_timestamp"
        private const val HISTORY_FILENAME = "playback_history.ndjson"
        private const val DRIVE_FOLDER_NAME = ".cloudamp"
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
     * Sync with Drive: download remote, merge, upload.
     * Call on app start.
     */
    fun syncWithDrive() {
        scope.launch {
            try {
                downloadFromDrive()
                uploadToDrive()
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

    private suspend fun ensureDriveFolder(): String? {
        var folderId = prefs.getString(KEY_DRIVE_FOLDER_ID, null)
        if (folderId != null) return folderId

        val client = GoogleDriveApiClient.getInstance(context)
        if (!client.hasAccessToken()) return null

        try {
            // Search for existing .cloudamp folder in root
            val searchResponse = client.api.listFiles(
                query = "name = '$DRIVE_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false",
                fields = "files(id,name)",
                pageSize = 1
            )
            if (searchResponse.isSuccessful) {
                val existing = searchResponse.body()?.files?.firstOrNull()
                if (existing != null) {
                    folderId = existing.id
                    prefs.edit().putString(KEY_DRIVE_FOLDER_ID, folderId).apply()
                    return folderId
                }
            }

            // Create the folder
            val metadata = JSONObject().apply {
                put("name", DRIVE_FOLDER_NAME)
                put("mimeType", "application/vnd.google-apps.folder")
            }.toString()

            val response = client.api.createFolder(
                metadata = metadata.toRequestBody("application/json".toMediaType())
            )
            if (response.isSuccessful) {
                folderId = response.body()?.id
                prefs.edit().putString(KEY_DRIVE_FOLDER_ID, folderId).apply()
                return folderId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring Drive folder: ${e.message}")
        }
        return null
    }

    private suspend fun uploadToDrive() {
        if (!localFile.exists() || localFile.length() == 0L) return

        val client = GoogleDriveApiClient.getInstance(context)
        if (!client.hasAccessToken()) return

        try {
            val folderId = ensureDriveFolder() ?: return
            val content = localFile.readText()
            val mediaBody = content.toRequestBody("application/x-ndjson".toMediaType())

            var fileId = prefs.getString(KEY_DRIVE_FILE_ID, null)

            if (fileId != null) {
                // Update existing file
                val response = client.api.updateFileContent(fileId, mediaBody)
                if (response.isSuccessful) {
                    prefs.edit().putLong(KEY_LAST_UPLOAD, System.currentTimeMillis()).apply()
                    Log.d(TAG, "History uploaded to Drive (updated)")
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

    private suspend fun downloadFromDrive() {
        val client = GoogleDriveApiClient.getInstance(context)
        if (!client.hasAccessToken()) return

        try {
            val folderId = ensureDriveFolder() ?: return

            // Find existing history file
            var fileId = prefs.getString(KEY_DRIVE_FILE_ID, null)
            if (fileId == null) {
                val searchResponse = client.api.listFiles(
                    query = "name = '$HISTORY_FILENAME' and '$folderId' in parents and trashed = false",
                    fields = "files(id,name)",
                    pageSize = 1
                )
                if (searchResponse.isSuccessful) {
                    fileId = searchResponse.body()?.files?.firstOrNull()?.id
                    if (fileId != null) {
                        prefs.edit().putString(KEY_DRIVE_FILE_ID, fileId).apply()
                    }
                }
            }

            if (fileId == null) return // No remote file yet

            val response = client.api.downloadFile(fileId)
            if (!response.isSuccessful) return

            val remoteContent = response.body()?.string() ?: return
            val remoteLines = remoteContent.lines().filter { it.isNotBlank() }

            // Merge: take the longer set, then append any unique lines from the shorter
            val localLines = if (localFile.exists()) {
                localFile.readLines().filter { it.isNotBlank() }
            } else {
                emptyList()
            }

            if (remoteLines.size > localLines.size) {
                // Remote has more — use remote as base, append local-only lines
                val remoteSet = remoteLines.toSet()
                val localOnly = localLines.filter { it !in remoteSet }
                val merged = remoteLines + localOnly
                localFile.writeText(merged.joinToString("\n") + "\n")
                Log.d(TAG, "Merged history: ${remoteLines.size} remote + ${localOnly.size} local-only = ${merged.size} total")
            } else if (localLines.size < remoteLines.size) {
                // Shouldn't happen after the above, but handle edge case
                localFile.writeText(remoteLines.joinToString("\n") + "\n")
            }
            // If local >= remote, local is already up to date
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
        }
    }
}
