package com.cloudamp.music.api

import retrofit2.Response
import retrofit2.http.*

interface GoogleDriveApiService {

    /**
     * List files in Google Drive.
     * Use q parameter to filter by folder, MIME type, etc.
     * Examples:
     *   - Files in a folder: "'folderId' in parents"
     *   - Audio files: "mimeType contains 'audio/'"
     *   - Folders: "mimeType = 'application/vnd.google-apps.folder'"
     *   - Combined: "'folderId' in parents and (mimeType contains 'audio/' or mimeType = 'application/vnd.google-apps.folder')"
     */
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Query("q") query: String,
        @Query("fields") fields: String = "files(id,name,mimeType,size,parents,modifiedTime,webContentLink),nextPageToken",
        @Query("orderBy") orderBy: String = "folder,name",
        @Query("pageSize") pageSize: Int = 100,
        @Query("pageToken") pageToken: String? = null
    ): Response<DriveFileListResponse>

    /**
     * Get file metadata.
     */
    @GET("drive/v3/files/{fileId}")
    suspend fun getFile(
        @Path("fileId") fileId: String,
        @Query("fields") fields: String = "id,name,mimeType,size,parents,modifiedTime,webContentLink"
    ): Response<DriveFile>

    /**
     * Get user info (for token validation).
     */
    @GET("drive/v3/about")
    suspend fun getAbout(
        @Query("fields") fields: String = "user(displayName,emailAddress)"
    ): Response<DriveAboutResponse>
}

// Google Drive response models

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: String? = null,
    val parents: List<String>? = null,
    val modifiedTime: String? = null,
    val webContentLink: String? = null
) {
    fun isFolder(): Boolean = mimeType == "application/vnd.google-apps.folder"

    fun isAudioFile(): Boolean {
        if (mimeType.startsWith("audio/")) return true
        // Google Drive may not assign audio/ MIME types to all formats
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "aiff", "ape", "wv"
        )
    }

    fun getFileSizeFormatted(): String {
        val bytes = size?.toLongOrNull() ?: return ""
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun getFileExtension(): String {
        val dotIndex = name.lastIndexOf('.')
        return if (dotIndex >= 0) name.substring(dotIndex + 1).uppercase() else ""
    }
}

data class DriveFileListResponse(
    val files: List<DriveFile>,
    val nextPageToken: String? = null
)

data class DriveAboutResponse(
    val user: DriveUser?
)

data class DriveUser(
    val displayName: String?,
    val emailAddress: String?
)
