package com.cloudamp.music.cache

import com.cloudamp.music.cache.FavoritesCore.FavoriteEntry
import com.cloudamp.music.cache.FavoritesCore.FavoritesFile
import com.cloudamp.music.cache.FavoritesCore.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the web invariant suite (web/src/lib/favorites-core.test.ts) so
 * both platforms enforce identical Favorites File semantics.
 */
class FavoritesCoreTest {

    // ── applyToggle ─────────────────────────────────────────────────────

    @Test
    fun `favoriting adds an entry with favoritedAt`() {
        val result = FavoritesCore.applyToggle(
            FavoritesCore.emptyFile(), "album-1", true, "2026-07-21T10:00:00Z"
        )
        assertEquals(listOf(FavoriteEntry("album-1", "2026-07-21T10:00:00Z")), result.favorites)
        assertTrue(FavoritesCore.isFavorite(result, "album-1"))
    }

    @Test
    fun `unfavoriting removes the entry`() {
        val file = FavoritesFile(1, listOf(FavoriteEntry("album-1", "2026-07-21T10:00:00Z")))
        val result = FavoritesCore.applyToggle(file, "album-1", false, "2026-07-22T10:00:00Z")
        assertEquals(emptyList<FavoriteEntry>(), result.favorites)
        assertFalse(FavoritesCore.isFavorite(result, "album-1"))
    }

    @Test
    fun `favoriting an already-favorited album keeps the original favoritedAt`() {
        val file = FavoritesFile(1, listOf(FavoriteEntry("album-1", "2026-07-21T10:00:00Z")))
        val result = FavoritesCore.applyToggle(file, "album-1", true, "2026-07-22T10:00:00Z")
        assertEquals(file.favorites, result.favorites)
    }

    @Test
    fun `toggle over a stale remote list keeps remote-added entries`() {
        val remote = FavoritesFile(
            1,
            listOf(
                FavoriteEntry("album-1", "2026-07-01T00:00:00Z"),
                FavoriteEntry("album-2", "2026-07-02T00:00:00Z")
            )
        )
        val result = FavoritesCore.applyToggle(remote, "album-3", true, "2026-07-03T00:00:00Z")
        assertEquals(listOf("album-1", "album-2", "album-3"), result.favorites.map { it.albumId })
    }

    // ── orphan preservation ─────────────────────────────────────────────

    @Test
    fun `entries not in any library survive toggle and serialize`() {
        val remote = FavoritesFile(
            1,
            listOf(
                FavoriteEntry("orphaned-album", "2026-01-01T00:00:00Z"),
                FavoriteEntry("album-1", "2026-07-01T00:00:00Z")
            )
        )
        val result = FavoritesCore.applyToggle(remote, "album-1", false, "2026-07-02T00:00:00Z")
        val roundTripped = FavoritesCore.parse(FavoritesCore.serialize(result))
        assertTrue(roundTripped is ParseResult.Ok)
        assertEquals(
            listOf(FavoriteEntry("orphaned-album", "2026-01-01T00:00:00Z")),
            (roundTripped as ParseResult.Ok).file.favorites
        )
    }

    // ── parse ───────────────────────────────────────────────────────────

    @Test
    fun `empty content bootstraps an empty file`() {
        val result = FavoritesCore.parse("")
        assertEquals(ParseResult.Ok(FavoritesCore.emptyFile()), result)
    }

    @Test
    fun `malformed JSON bootstraps an empty file`() {
        val result = FavoritesCore.parse("not json {")
        assertEquals(ParseResult.Ok(FavoritesCore.emptyFile()), result)
    }

    @Test
    fun `parses a valid schemaVersion 1 file`() {
        val text = """{"schemaVersion":1,"favorites":[{"albumId":"album-1","favoritedAt":"2026-07-21T10:00:00Z"}]}"""
        val result = FavoritesCore.parse(text)
        assertEquals(
            ParseResult.Ok(FavoritesFile(1, listOf(FavoriteEntry("album-1", "2026-07-21T10:00:00Z")))),
            result
        )
    }

    @Test
    fun `refuses an unknown schemaVersion so callers cannot overwrite it`() {
        val result = FavoritesCore.parse("""{"schemaVersion":2,"favorites":[],"somethingNew":true}""")
        assertTrue(result is ParseResult.UnknownVersion)
        assertEquals("2", (result as ParseResult.UnknownVersion).schemaVersion)
    }

    @Test
    fun `skips malformed entries but keeps well-formed ones`() {
        val text = """
            {"schemaVersion":1,"favorites":[
                {"albumId":"album-1","favoritedAt":"2026-07-21T10:00:00Z"},
                {"notAnAlbum":true},
                "junk"
            ]}
        """.trimIndent()
        val result = FavoritesCore.parse(text)
        assertEquals(
            ParseResult.Ok(FavoritesFile(1, listOf(FavoriteEntry("album-1", "2026-07-21T10:00:00Z")))),
            result
        )
    }

    // ── resolveFavoriteAlbums ───────────────────────────────────────────

    private val albums = mapOf(
        "album-1" to "First",
        "album-2" to "Second"
    )

    @Test
    fun `returns favorite albums most recently favorited first`() {
        val result = FavoritesCore.resolveFavoriteAlbums(
            listOf(
                FavoriteEntry("album-1", "2026-07-01T00:00:00Z"),
                FavoriteEntry("album-2", "2026-07-20T00:00:00Z")
            ),
            albums
        )
        assertEquals(listOf("Second", "First"), result)
    }

    @Test
    fun `hides entries whose album is missing from the library`() {
        val result = FavoritesCore.resolveFavoriteAlbums(
            listOf(
                FavoriteEntry("gone-album", "2026-07-21T00:00:00Z"),
                FavoriteEntry("album-1", "2026-07-01T00:00:00Z")
            ),
            albums
        )
        assertEquals(listOf("First"), result)
    }

    @Test
    fun `resolves a previously hidden entry once its album returns to the library`() {
        val favorites = listOf(FavoriteEntry("gone-album", "2026-07-21T00:00:00Z"))
        assertEquals(emptyList<String>(), FavoritesCore.resolveFavoriteAlbums(favorites, albums))
        val restored = albums + ("gone-album" to "Back")
        assertEquals(listOf("Back"), FavoritesCore.resolveFavoriteAlbums(favorites, restored))
    }

    // ── serialize ───────────────────────────────────────────────────────

    @Test
    fun `serializes the documented schema shape`() {
        val file = FavoritesFile(1, listOf(FavoriteEntry("album-1", "2026-07-21T10:00:00Z")))
        val parsed = com.google.gson.JsonParser.parseString(FavoritesCore.serialize(file)).asJsonObject
        assertEquals(setOf("schemaVersion", "favorites"), parsed.keySet())
        assertEquals(1, parsed.get("schemaVersion").asInt)
        val entry = parsed.getAsJsonArray("favorites").get(0).asJsonObject
        assertEquals(setOf("albumId", "favoritedAt"), entry.keySet())
        assertEquals("album-1", entry.get("albumId").asString)
        assertEquals("2026-07-21T10:00:00Z", entry.get("favoritedAt").asString)
    }
}
