package com.cloudamp.music.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ContentProvider that proxies Jellyfin image URLs for Android Auto.
 *
 * Android Auto cannot load images from arbitrary HTTP URLs via setIconUri().
 * This provider downloads Jellyfin images to a local cache and serves them
 * through content:// URIs that Android Auto can read.
 *
 * URI format: content://com.cloudamp.music.jellyfinimages/<itemId>
 * The actual HTTP URL is passed as query parameter "url".
 */
class JellyfinImageProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.cloudamp.music.jellyfinimages"
        private const val TAG = "JellyfinImageProvider"

        /**
         * Build a content:// URI for the given Jellyfin image URL.
         * @param itemId Jellyfin item ID (used as cache key)
         * @param httpUrl The full HTTP URL to the Jellyfin image
         */
        fun buildUri(itemId: String, httpUrl: String): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(itemId)
                .appendQueryParameter("url", httpUrl)
                .build()
        }
    }

    private val cacheDir: File by lazy {
        File(context!!.cacheDir, "jellyfin_images").also { it.mkdirs() }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val itemId = uri.pathSegments?.firstOrNull() ?: return null
        val httpUrl = uri.getQueryParameter("url") ?: return null

        val cacheFile = File(cacheDir, "$itemId.img")

        // Serve from cache if fresh (cache for 24 hours)
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 24 * 60 * 60 * 1000) {
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Download the image
        try {
            val connection = URL(httpUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doInput = true
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image for $itemId: ${e.message}")
        }

        return null
    }

    override fun getType(uri: Uri): String = "image/*"
    override fun query(uri: Uri, p: Array<String>?, s: String?, sa: Array<String>?, so: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
