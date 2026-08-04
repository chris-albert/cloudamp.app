package com.cloudamp.music.cache

import com.cloudamp.music.api.DriveAboutResponse
import com.cloudamp.music.api.DriveChangeListResponse
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.DriveFileListResponse
import com.cloudamp.music.api.GoogleDriveApiService
import com.cloudamp.music.api.StartPageTokenResponse
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Repository tests against a fake Drive client: GET→merge→PUT sequencing on
 * toggle, find-or-create of the .cloudamp folder and Favorites File, and
 * never overwriting an unknown schemaVersion. Mirrors
 * web/src/lib/favorites-store.test.ts.
 */
class FavoritesRepositoryTest {

    private companion object {
        const val ROOT_ID = "root-id"
        const val FOLDER_ID = "folder-id"
        const val FILE_ID = "file-id"

        fun remoteFile(vararg albumIds: String): String {
            val entries = albumIds.joinToString(",") {
                """{"albumId":"$it","favoritedAt":"2026-07-01T00:00:00Z"}"""
            }
            return """{"schemaVersion":1,"favorites":[$entries]}"""
        }

        fun writtenAlbumIds(json: String): List<String> =
            JsonParser.parseString(json).asJsonObject
                .getAsJsonArray("favorites")
                .map { it.asJsonObject.get("albumId").asString }
    }

    private class FakeDriveApi : GoogleDriveApiService {
        var folderId: String? = null
        var fileId: String? = null
        var fileContent: String = ""
        var failUpdate: Boolean = false

        val calls = mutableListOf<String>()
        var createdFolderMetadata: String? = null
        var createdFileMetadata: String? = null
        var createdFileContent: String? = null
        var updatedContent: String? = null

        private fun RequestBody.readUtf8(): String {
            val buffer = Buffer()
            writeTo(buffer)
            return buffer.readUtf8()
        }

        override suspend fun listFiles(
            query: String, fields: String, orderBy: String, pageSize: Int, pageToken: String?
        ): Response<DriveFileListResponse> {
            calls.add("listFiles")
            val files = when {
                query.contains("'.cloudamp'") -> listOfNotNull(
                    folderId?.let { DriveFile(it, ".cloudamp", "application/vnd.google-apps.folder") }
                )
                query.contains("'favorites.json'") -> listOfNotNull(
                    fileId?.let { DriveFile(it, "favorites.json", "application/json") }
                )
                else -> emptyList()
            }
            return Response.success(DriveFileListResponse(files))
        }

        override suspend fun downloadFile(fileId: String, alt: String): Response<okhttp3.ResponseBody> {
            calls.add("downloadFile")
            return Response.success(fileContent.toResponseBody("application/json".toMediaType()))
        }

        override suspend fun updateFileContent(
            fileId: String, media: RequestBody, uploadType: String
        ): Response<DriveFile> {
            calls.add("updateFileContent")
            if (failUpdate) {
                return Response.error(500, "server error".toResponseBody("text/plain".toMediaType()))
            }
            updatedContent = media.readUtf8()
            return Response.success(DriveFile(fileId, "favorites.json", "application/json"))
        }

        override suspend fun createFolder(metadata: RequestBody, fields: String): Response<DriveFile> {
            calls.add("createFolder")
            createdFolderMetadata = metadata.readUtf8()
            folderId = "new-folder-id"
            return Response.success(DriveFile("new-folder-id", ".cloudamp", "application/vnd.google-apps.folder"))
        }

        override suspend fun createFile(
            metadata: RequestBody, media: MultipartBody.Part, uploadType: String, fields: String
        ): Response<DriveFile> {
            calls.add("createFile")
            createdFileMetadata = metadata.readUtf8()
            createdFileContent = media.body.readUtf8()
            fileId = "new-file-id"
            return Response.success(DriveFile("new-file-id", "favorites.json", "application/json"))
        }

        override suspend fun getFile(fileId: String, fields: String): Response<DriveFile> =
            throw UnsupportedOperationException()

        override suspend fun getAbout(fields: String): Response<DriveAboutResponse> =
            throw UnsupportedOperationException()

        override suspend fun trashFile(fileId: String, body: RequestBody): Response<DriveFile> =
            throw UnsupportedOperationException()

        override suspend fun getChangesStartPageToken(): Response<StartPageTokenResponse> =
            throw UnsupportedOperationException()

        override suspend fun listChanges(
            pageToken: String, fields: String, pageSize: Int, spaces: String
        ): Response<DriveChangeListResponse> = throw UnsupportedOperationException()
    }

    private fun repository(api: FakeDriveApi) = FavoritesRepository(api) { ROOT_ID }

    // ── toggle with an existing Favorites File ──────────────────────────

    @Test
    fun `toggle GETs the file fresh, merges onto the remote list, then PUTs`() = runTest {
        val api = FakeDriveApi().apply {
            folderId = FOLDER_ID
            fileId = FILE_ID
            fileContent = remoteFile("album-a")
        }
        val repo = repository(api)

        repo.toggle("album-b", favorite = true)

        assertFalse(api.calls.contains("createFolder"))
        assertFalse(api.calls.contains("createFile"))
        assertTrue(api.calls.indexOf("downloadFile") < api.calls.indexOf("updateFileContent"))
        assertEquals(listOf("album-a", "album-b"), writtenAlbumIds(api.updatedContent!!))
        assertTrue(repo.isFavorite("album-b"))
    }

    @Test
    fun `toggle preserves a favorite added by another client after load`() = runTest {
        val api = FakeDriveApi().apply {
            folderId = FOLDER_ID
            fileId = FILE_ID
            fileContent = remoteFile("album-a")
        }
        val repo = repository(api)
        repo.load()

        // Another client favorites album-b between our load and our toggle
        api.fileContent = remoteFile("album-a", "album-b")
        repo.toggle("album-c", favorite = true)

        assertEquals(listOf("album-a", "album-b", "album-c"), writtenAlbumIds(api.updatedContent!!))
        assertEquals(
            listOf("album-a", "album-b", "album-c"),
            repo.listFavorites().map { it.albumId }
        )
    }

    @Test
    fun `toggle refuses to write over an unknown schemaVersion`() = runTest {
        val api = FakeDriveApi().apply {
            folderId = FOLDER_ID
            fileId = FILE_ID
            fileContent = """{"schemaVersion":2,"favorites":[]}"""
        }
        val repo = repository(api)

        try {
            repo.toggle("album-a", favorite = true)
            fail("Expected toggle to throw on unknown schemaVersion")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("schemaVersion"))
        }
        assertNull(api.updatedContent)
        assertNull(api.createdFileContent)
        assertFalse(repo.isFavorite("album-a"))
    }

    @Test
    fun `toggle throws on a failed PUT and keeps prior state`() = runTest {
        val api = FakeDriveApi().apply {
            folderId = FOLDER_ID
            fileId = FILE_ID
            fileContent = remoteFile("album-a")
            failUpdate = true
        }
        val repo = repository(api)
        repo.load()

        try {
            repo.toggle("album-b", favorite = true)
            fail("Expected toggle to throw on failed PUT")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(listOf("album-a"), repo.listFavorites().map { it.albumId })
        assertFalse(repo.isFavorite("album-b"))
    }

    // ── find-or-create on first write ───────────────────────────────────

    @Test
    fun `first toggle creates the folder and file with the bootstrapped list`() = runTest {
        val api = FakeDriveApi()
        val repo = repository(api)

        repo.toggle("album-a", favorite = true)

        assertTrue(api.calls.contains("createFolder"))
        assertTrue(api.createdFolderMetadata!!.contains("\".cloudamp\""))
        assertTrue(api.createdFolderMetadata!!.contains("\"$ROOT_ID\""))
        assertFalse(api.calls.contains("downloadFile"))
        assertFalse(api.calls.contains("updateFileContent"))
        assertTrue(api.createdFileMetadata!!.contains("\"favorites.json\""))
        assertTrue(api.createdFileMetadata!!.contains("\"new-folder-id\""))
        assertEquals(listOf("album-a"), writtenAlbumIds(api.createdFileContent!!))
    }

    @Test
    fun `first toggle reuses the existing folder when only the file is missing`() = runTest {
        val api = FakeDriveApi().apply { folderId = FOLDER_ID }
        val repo = repository(api)

        repo.toggle("album-a", favorite = true)

        assertFalse(api.calls.contains("createFolder"))
        assertTrue(api.createdFileMetadata!!.contains("\"$FOLDER_ID\""))
        assertEquals(listOf("album-a"), writtenAlbumIds(api.createdFileContent!!))
    }

    // ── load ────────────────────────────────────────────────────────────

    @Test
    fun `load resolves to an empty list when the file does not exist yet`() = runTest {
        val api = FakeDriveApi().apply { folderId = FOLDER_ID }
        val repo = repository(api)

        assertEquals(emptyList<FavoritesCore.FavoriteEntry>(), repo.load())
        assertFalse(api.calls.contains("createFolder"))
        assertFalse(api.calls.contains("createFile"))
    }

    @Test
    fun `load reflects the remote list so hearts survive a relaunch`() = runTest {
        val api = FakeDriveApi().apply {
            folderId = FOLDER_ID
            fileId = FILE_ID
            fileContent = remoteFile("album-a")
        }
        val repo = repository(api)
        repo.load()

        assertTrue(repo.isFavorite("album-a"))
        assertFalse(repo.isFavorite("album-b"))
    }
}
