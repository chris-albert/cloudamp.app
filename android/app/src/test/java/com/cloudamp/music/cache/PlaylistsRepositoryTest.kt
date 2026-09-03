package com.cloudamp.music.cache

import com.cloudamp.music.api.DriveAboutResponse
import com.cloudamp.music.api.DriveChangeListResponse
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.api.DriveFileListResponse
import com.cloudamp.music.api.GoogleDriveApiService
import com.cloudamp.music.api.StartPageTokenResponse
import com.cloudamp.music.cache.PlaylistsCore.PlaylistOp
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Repository tests against a fake Drive client: GET→replay→PUT sequencing,
 * find-or-create of the .cloudamp folder and Playlists File, never
 * overwriting an unknown schemaVersion, cache hydration, and offline retry
 * of queued ops. Mirrors web/src/lib/playlists-store.test.ts.
 */
class PlaylistsRepositoryTest {

    private companion object {
        const val ROOT_ID = "root-id"
        const val FOLDER_ID = "folder-id"
        const val FILE_ID = "file-id"

        fun remoteFile(vararg playlists: String): String =
            """{"schemaVersion":1,"playlists":[${playlists.joinToString(",")}]}"""

        fun remotePlaylist(id: String, name: String, vararg fileIds: String): String {
            val tracks = fileIds.joinToString(",") { """{"fileId":"$it","name":"$it.mp3","addedAt":"t"}""" }
            return """{"id":"$id","name":"$name","createdAt":"t","updatedAt":"t","tracks":[$tracks]}"""
        }

        fun writtenPlaylists(json: String): Map<String, List<String>> =
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("playlists").associate { p ->
                val obj = p.asJsonObject
                obj.get("id").asString to obj.getAsJsonArray("tracks").map { it.asJsonObject.get("fileId").asString }
            }

        fun driveFile(id: String) = DriveFile(id, "$id.mp3", "audio/mpeg", parents = listOf("album-1"))
    }

    private class FakeCacheStore : PlaylistsCacheStore {
        var persisted: PersistedPlaylists? = null
        override fun load(): PersistedPlaylists? = persisted
        override fun save(persisted: PersistedPlaylists) { this.persisted = persisted }
    }

    private class FakeDriveApi : GoogleDriveApiService {
        var folderId: String? = null
        var fileId: String? = null
        var fileContent: String = ""
        var failUpdate: Boolean = false
        var offline: Boolean = false

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
            if (offline) throw IOException("offline")
            val files = when {
                query.contains("'.cloudamp'") -> listOfNotNull(
                    folderId?.let { DriveFile(it, ".cloudamp", "application/vnd.google-apps.folder") }
                )
                query.contains("'playlists.json'") -> listOfNotNull(
                    fileId?.let { DriveFile(it, "playlists.json", "application/json") }
                )
                else -> emptyList()
            }
            return Response.success(DriveFileListResponse(files))
        }

        override suspend fun downloadFile(fileId: String, alt: String): Response<okhttp3.ResponseBody> {
            calls.add("downloadFile")
            if (offline) throw IOException("offline")
            return Response.success(fileContent.toResponseBody("application/json".toMediaType()))
        }

        override suspend fun updateFileContent(
            fileId: String, media: RequestBody, uploadType: String
        ): Response<DriveFile> {
            calls.add("updateFileContent")
            if (offline) throw IOException("offline")
            if (failUpdate) {
                return Response.error(500, "server error".toResponseBody("text/plain".toMediaType()))
            }
            updatedContent = media.readUtf8()
            fileContent = updatedContent!!
            return Response.success(DriveFile(fileId, "playlists.json", "application/json"))
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
            fileContent = createdFileContent!!
            return Response.success(DriveFile("new-file-id", "playlists.json", "application/json"))
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

    private fun repository(api: FakeDriveApi, store: PlaylistsCacheStore = FakeCacheStore()) =
        PlaylistsRepository(api, store) { ROOT_ID }

    private fun existingFile(api: FakeDriveApi, content: String) {
        api.folderId = FOLDER_ID
        api.fileId = FILE_ID
        api.fileContent = content
    }

    // ── read-merge-write ────────────────────────────────────────────────

    @Test
    fun `sync GETs the file fresh, replays queued ops onto it, then PUTs`() = runTest {
        val api = FakeDriveApi()
        existingFile(api, remoteFile(remotePlaylist("p-remote", "Theirs", "r1")))
        val repo = repository(api)

        val created = repo.createPlaylist("Mine", listOf(driveFile("m1")))
        // Local state reflects the op before any Drive I/O
        assertEquals(listOf("Mine"), repo.listPlaylists().map { it.name })

        repo.sync()

        assertEquals(listOf("listFiles", "listFiles", "downloadFile", "updateFileContent"), api.calls)
        val written = writtenPlaylists(api.updatedContent!!)
        assertEquals(mapOf("p-remote" to listOf("r1"), created.id to listOf("m1")), written)
        // Merged remote state (including the other client's playlist) is now local
        assertEquals(listOf("Theirs", "Mine"), repo.listPlaylists().map { it.name })
    }

    @Test
    fun `an edit made by another client between load and write is preserved`() = runTest {
        val api = FakeDriveApi()
        existingFile(api, remoteFile(remotePlaylist("p1", "Mix", "f1")))
        val repo = repository(api)
        repo.sync()

        // Other client adds f2 after our load
        api.fileContent = remoteFile(remotePlaylist("p1", "Mix", "f1", "f2"))

        assertEquals(1, repo.addFiles("p1", listOf(driveFile("f3"))))
        repo.sync()

        assertEquals(listOf("f1", "f2", "f3"), writtenPlaylists(api.updatedContent!!)["p1"])
        assertEquals(listOf("f1", "f2", "f3"), repo.getPlaylist("p1")!!.tracks.map { it.fileId })
    }

    @Test
    fun `addFiles skips files already in the playlist and queues nothing`() = runTest {
        val api = FakeDriveApi()
        existingFile(api, remoteFile(remotePlaylist("p1", "Mix", "f1")))
        val repo = repository(api)
        repo.sync()
        api.calls.clear()

        assertEquals(0, repo.addFiles("p1", listOf(driveFile("f1"))))
        repo.sync()
        // Nothing queued, so the sync is a plain read — no PUT
        assertEquals(listOf("downloadFile"), api.calls)
    }

    @Test
    fun `first write creates the cloudamp folder and the Playlists File under the root`() = runTest {
        val api = FakeDriveApi()
        val repo = repository(api)

        val created = repo.createPlaylist("Mine")
        repo.sync()

        assertTrue(api.calls.contains("createFolder"))
        assertTrue(api.calls.contains("createFile"))
        assertNull(api.updatedContent)
        assertTrue(api.createdFolderMetadata!!.contains("\"$ROOT_ID\""))
        assertTrue(api.createdFileMetadata!!.contains("playlists.json"))
        assertTrue(api.createdFileMetadata!!.contains("new-folder-id"))
        assertEquals(mapOf(created.id to emptyList<String>()), writtenPlaylists(api.createdFileContent!!))
    }

    @Test
    fun `plain sync with nothing queued reads without creating anything`() = runTest {
        val api = FakeDriveApi()
        val repo = repository(api)
        assertTrue(repo.sync().isEmpty())
        assertEquals(listOf("listFiles"), api.calls)
    }

    @Test
    fun `refuses to write over an unknown schemaVersion and drops the queued ops`() = runTest {
        val api = FakeDriveApi()
        existingFile(api, """{"schemaVersion":2,"playlists":[]}""")
        val store = FakeCacheStore()
        val repo = repository(api, store)

        repo.createPlaylist("Mine")
        try {
            repo.sync()
            fail("expected UnsupportedSchemaException")
        } catch (e: UnsupportedSchemaException) {
            // expected
        }
        assertNull(api.updatedContent)
        assertTrue(store.persisted!!.pendingOps.isEmpty())
    }

    // ── cache + offline retry ───────────────────────────────────────────

    @Test
    fun `queued ops survive a failed write and are replayed on the next sync`() = runTest {
        val api = FakeDriveApi()
        existingFile(api, remoteFile(remotePlaylist("p1", "Mix", "f1")))
        val store = FakeCacheStore()
        val repo = repository(api, store)
        repo.sync()

        api.offline = true
        repo.apply(PlaylistOp.Rename("p1", "Renamed", "t2"))
        try {
            repo.sync()
            fail("expected IOException")
        } catch (e: IOException) {
            // offline
        }
        // Optimistic state and the queued op are both persisted
        assertEquals("Renamed", repo.getPlaylist("p1")!!.name)
        assertEquals(1, store.persisted!!.pendingOps.size)

        // A fresh repository (new process) hydrates the cache and pushes the op
        api.offline = false
        api.calls.clear()
        val restarted = repository(api, store)
        assertEquals("Renamed", restarted.hydrateFromCache().single().name)
        restarted.sync()

        assertNotNull(api.updatedContent)
        assertTrue(api.updatedContent!!.contains("\"Renamed\""))
        assertTrue(store.persisted!!.pendingOps.isEmpty())
    }

    @Test
    fun `an HTTP failure on PUT keeps the op queued`() = runTest {
        val api = FakeDriveApi()
        existingFile(api, remoteFile())
        val store = FakeCacheStore()
        val repo = repository(api, store)

        api.failUpdate = true
        repo.createPlaylist("Mine")
        try {
            repo.sync()
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(1, store.persisted!!.pendingOps.size)
        assertEquals(listOf("Mine"), repo.listPlaylists().map { it.name })
    }

    @Test
    fun `a playlist deleted on another device disappears after sync and its queued edits are dropped`() = runTest {
        val api = FakeDriveApi()
        existingFile(api, remoteFile(remotePlaylist("p1", "Mix", "f1")))
        val repo = repository(api)
        repo.sync()

        api.fileContent = remoteFile() // deleted elsewhere
        repo.apply(PlaylistOp.AddTracks("p1", listOf(PlaylistsCore.PlaylistTrack("f2", "f2.mp3")), "t"))
        repo.sync()

        assertTrue(repo.listPlaylists().isEmpty())
        assertTrue(writtenPlaylists(api.updatedContent!!).isEmpty())
    }
}
