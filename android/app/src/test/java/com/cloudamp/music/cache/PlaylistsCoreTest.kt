package com.cloudamp.music.cache

import com.cloudamp.music.cache.PlaylistsCore.Playlist
import com.cloudamp.music.cache.PlaylistsCore.PlaylistOp
import com.cloudamp.music.cache.PlaylistsCore.PlaylistTrack
import com.cloudamp.music.cache.PlaylistsCore.PlaylistsFile
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic tests for the Playlists File. Mirrors web/src/lib/playlists-core.test.ts
 * so both platforms agree on parsing, op semantics, and serialization.
 */
class PlaylistsCoreTest {

    private fun track(id: String, addedAt: String = "2026-08-01T00:00:00Z") =
        PlaylistTrack(fileId = id, name = "$id.mp3", mimeType = "audio/mpeg", parentId = "album-1", addedAt = addedAt)

    private fun playlist(id: String, vararg trackIds: String) = Playlist(
        id = id, name = "Playlist $id", createdAt = "2026-08-01T00:00:00Z",
        updatedAt = "2026-08-01T00:00:00Z", tracks = trackIds.map { track(it) }
    )

    private fun file(vararg playlists: Playlist) = PlaylistsFile(PlaylistsCore.SCHEMA_VERSION, playlists.toList())

    // ── parse ───────────────────────────────────────────────────────────

    @Test
    fun `empty or malformed content bootstraps an empty file`() {
        for (text in listOf("", "not json", "[]", "42")) {
            val result = PlaylistsCore.parse(text) as PlaylistsCore.ParseResult.Ok
            assertTrue(result.file.playlists.isEmpty())
        }
    }

    @Test
    fun `unknown schemaVersion is refused`() {
        val result = PlaylistsCore.parse("""{"schemaVersion":2,"playlists":[]}""")
        assertTrue(result is PlaylistsCore.ParseResult.UnknownVersion)
        assertTrue(PlaylistsCore.parse("""{"playlists":[]}""") is PlaylistsCore.ParseResult.UnknownVersion)
    }

    @Test
    fun `parse drops entries missing required ids and coerces missing strings`() {
        val text = """
            {"schemaVersion":1,"playlists":[
              {"id":"p1","name":"Mix","tracks":[
                {"fileId":"f1","name":"one.mp3","parentId":"a1","addedAt":"t"},
                {"name":"no-id.mp3"},
                {"fileId":"f2"}
              ]},
              {"name":"no id"},
              {"id":"p2","name":"Empty"}
            ]}
        """.trimIndent()
        val parsed = (PlaylistsCore.parse(text) as PlaylistsCore.ParseResult.Ok).file
        assertEquals(listOf("p1", "p2"), parsed.playlists.map { it.id })
        val p1 = parsed.playlists[0]
        assertEquals("", p1.createdAt)
        assertEquals(listOf("f1", "f2"), p1.tracks.map { it.fileId })
        assertEquals("a1", p1.tracks[0].parentId)
        assertEquals("", p1.tracks[1].name)
        assertNull(p1.tracks[1].parentId)
        assertTrue(parsed.playlists[1].tracks.isEmpty())
    }

    @Test
    fun `serialize round-trips through parse and matches the documented shape`() {
        val original = file(playlist("p1", "f1", "f2"))
        val json = PlaylistsCore.serialize(original)
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals(1, obj.get("schemaVersion").asInt)
        val t = obj.getAsJsonArray("playlists")[0].asJsonObject.getAsJsonArray("tracks")[0].asJsonObject
        assertEquals(setOf("fileId", "name", "mimeType", "parentId", "addedAt"), t.keySet())
        assertEquals(original, (PlaylistsCore.parse(json) as PlaylistsCore.ParseResult.Ok).file)
    }

    // ── applyOp ─────────────────────────────────────────────────────────

    @Test
    fun `create appends and is idempotent on retry`() {
        val once = PlaylistsCore.applyOp(file(playlist("p1")), PlaylistOp.Create(playlist("p2")))
        assertEquals(listOf("p1", "p2"), once.playlists.map { it.id })
        val twice = PlaylistsCore.applyOp(once, PlaylistOp.Create(playlist("p2")))
        assertEquals(once, twice)
    }

    @Test
    fun `rename and delete only touch the targeted playlist`() {
        val start = file(playlist("p1"), playlist("p2"))
        val renamed = PlaylistsCore.applyOp(start, PlaylistOp.Rename("p2", "New", "t2"))
        assertEquals("Playlist p1", renamed.playlists[0].name)
        assertEquals("New", renamed.playlists[1].name)
        assertEquals("t2", renamed.playlists[1].updatedAt)

        val deleted = PlaylistsCore.applyOp(renamed, PlaylistOp.Delete("p1"))
        assertEquals(listOf("p2"), deleted.playlists.map { it.id })
    }

    @Test
    fun `ops targeting a playlist deleted elsewhere are dropped`() {
        val start = file(playlist("p1"))
        assertEquals(start, PlaylistsCore.applyOp(start, PlaylistOp.Rename("gone", "x", "t")))
        assertEquals(start, PlaylistsCore.applyOp(start, PlaylistOp.AddTracks("gone", listOf(track("f9")), "t")))
        assertEquals(start, PlaylistsCore.applyOp(start, PlaylistOp.RemoveTrack("gone", "f1", "t")))
        assertEquals(start, PlaylistsCore.applyOp(start, PlaylistOp.SetOrder("gone", listOf("f1"), "t")))
    }

    @Test
    fun `add-tracks skips files already present and keeps remote additions`() {
        // Remote gained f3 from another client; we add f2 (dup) and f4
        val remote = file(playlist("p1", "f1", "f2", "f3"))
        val merged = PlaylistsCore.applyOp(
            remote, PlaylistOp.AddTracks("p1", listOf(track("f2"), track("f4")), "t2")
        )
        assertEquals(listOf("f1", "f2", "f3", "f4"), merged.playlists[0].tracks.map { it.fileId })
        assertEquals("t2", merged.playlists[0].updatedAt)

        val noop = PlaylistsCore.applyOp(remote, PlaylistOp.AddTracks("p1", listOf(track("f1")), "t3"))
        assertEquals(remote, noop)
    }

    @Test
    fun `remove-track removes by file id`() {
        val merged = PlaylistsCore.applyOp(file(playlist("p1", "f1", "f2")), PlaylistOp.RemoveTrack("p1", "f1", "t"))
        assertEquals(listOf("f2"), merged.playlists[0].tracks.map { it.fileId })
    }

    @Test
    fun `set-order reorders known tracks and appends ones added elsewhere`() {
        val remote = file(playlist("p1", "f1", "f2", "f3", "f4"))
        // Our order was captured before f4 existed and after f2 was removed remotely... f9 never existed
        val merged = PlaylistsCore.applyOp(remote, PlaylistOp.SetOrder("p1", listOf("f3", "f9", "f1"), "t"))
        assertEquals(listOf("f3", "f1", "f2", "f4"), merged.playlists[0].tracks.map { it.fileId })
    }

    // ── op cache serialization ──────────────────────────────────────────

    @Test
    fun `every op round-trips through serializeOp and parseOp`() {
        val ops = listOf(
            PlaylistOp.Create(playlist("p1", "f1")),
            PlaylistOp.Rename("p1", "Renamed", "t1"),
            PlaylistOp.Delete("p1"),
            PlaylistOp.AddTracks("p1", listOf(track("f2"), track("f3")), "t2"),
            PlaylistOp.RemoveTrack("p1", "f2", "t3"),
            PlaylistOp.SetOrder("p1", listOf("f3", "f1"), "t4")
        )
        for (op in ops) {
            assertEquals(op, PlaylistsCore.parseOp(PlaylistsCore.serializeOp(op)))
        }
        assertNull(PlaylistsCore.parseOp("garbage"))
        assertNull(PlaylistsCore.parseOp("""{"op":"teleport"}"""))
    }

    @Test
    fun `toDriveFile carries the parent so library metadata can be resolved`() {
        val driveFile = track("f1").toDriveFile()
        assertEquals("f1", driveFile.id)
        assertEquals(listOf("album-1"), driveFile.parents)
        assertFalse(driveFile.isFolder())
    }
}
