package com.cloudamp.music.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.cloudamp.music.auth.GoogleDriveAuthManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ContentProvider that proxies Google Drive image files for Android Auto.
 *
 * Android Auto cannot load images from arbitrary HTTP URLs via setIconUri().
 * This provider downloads Drive images to a local cache and serves them
 * through content:// URIs that Android Auto can read.
 *
 * URI format: content://com.cloudamp.music.gdriveimages/<fileId>
 *
 * Cached files live in filesDir (not cacheDir) so the system won't evict
 * them under low-storage pressure. The cache has no TTL — entries are only
 * cleared when a full library scan completes (see GDriveLibraryCache).
 */
class GDriveImageProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.cloudamp.music.gdriveimages"
        private const val TAG = "GDriveImageProvider"
        private const val CACHE_DIR_NAME = "album_art_cache"

        /**
         * Build a content:// URI for the given Google Drive file ID.
         * @param fileId Google Drive file ID for the cover image
         */
        fun buildUri(fileId: String): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(fileId)
                .build()
        }

        fun cacheDir(context: Context): File =
            File(context.filesDir, CACHE_DIR_NAME).also { it.mkdirs() }

        fun cacheFileFor(context: Context, fileId: String): File =
            File(cacheDir(context), "$fileId.img")

        fun isCached(context: Context, fileId: String): Boolean {
            val f = cacheFileFor(context, fileId)
            return f.exists() && f.length() > 0
        }

        /** Wipe every cached image. Called after a full library scan. */
        fun clearCache(context: Context) {
            val dir = File(context.filesDir, CACHE_DIR_NAME)
            if (!dir.exists()) return
            val count = dir.listFiles()?.count { it.delete() } ?: 0
            Log.d(TAG, "Cleared $count cached album art files")
        }

        /**
         * Download a Drive image into the on-disk cache if it isn't there
         * already. Safe to call from any background thread. Returns true if
         * the image is cached (already or freshly downloaded).
         */
        fun fetchToCache(context: Context, fileId: String): Boolean {
            val cacheFile = cacheFileFor(context, fileId)
            if (cacheFile.exists() && cacheFile.length() > 0) return true

            return try {
                val authManager = GoogleDriveAuthManager(context)
                val token = authManager.getAccessToken() ?: return false

                val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doInput = true
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val tmpFile = File(cacheDir(context), "$fileId.img.tmp")
                    connection.inputStream.use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tmpFile.length() > 0 && tmpFile.renameTo(cacheFile)) {
                        true
                    } else {
                        tmpFile.delete()
                        Log.w(TAG, "Failed to finalize cache file for $fileId")
                        false
                    }
                } else {
                    Log.w(TAG, "Failed to download image for $fileId: HTTP ${connection.responseCode}")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download image for $fileId: ${e.message}")
                false
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val fileId = uri.pathSegments?.firstOrNull() ?: return null
        val ctx = context ?: return null

        if (!fetchToCache(ctx, fileId)) return null

        return ParcelFileDescriptor.open(
            cacheFileFor(ctx, fileId),
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun getType(uri: Uri): String = "image/*"
    override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
