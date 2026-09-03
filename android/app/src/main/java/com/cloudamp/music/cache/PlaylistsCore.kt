package com.cloudamp.music.cache

import com.cloudamp.music.api.DriveFile
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure parse/merge/serialize logic for the Playlists File
 * (.cloudamp/playlists.json) — no I/O, no Android dependencies.
 *
 * Mirrors web/src/lib/playlists-core.ts so both platforms read and write
 * the same snapshot:
 *   { "schemaVersion": 1,
 *     "playlists": [{ "id", "name", "createdAt", "updatedAt",
 *                     "tracks": [{ "fileId", "name", "mimeType"?, "size"?, "parentId"?, "addedAt" }] }] }
 *
 * Edits are expressed as [PlaylistOp]s that are replayed onto a freshly
 * fetched file (see docs/adr/0002-playlists-json-op-merge.md).
 */
object PlaylistsCore {

    const val SCHEMA_VERSION = 1

    data class PlaylistTrack(
        val fileId: String,
        val name: String,
        val mimeType: String? = null,
        val size: String? = null,
        val parentId: String? = null,
        val addedAt: String = ""
    ) {
        /** Rebuild the DriveFile shape the playback layer expects. `parents` drives library metadata lookup. */
        fun toDriveFile(): DriveFile = DriveFile(
            id = fileId,
            name = name,
            mimeType = mimeType ?: "application/octet-stream",
            size = size,
            parents = parentId?.let { listOf(it) }
        )

        companion object {
            fun fromDriveFile(file: DriveFile, addedAt: String) = PlaylistTrack(
                fileId = file.id,
                name = file.name,
                mimeType = file.mimeType,
                size = file.size,
                parentId = file.parents?.firstOrNull(),
                addedAt = addedAt
            )
        }
    }

    data class Playlist(
        val id: String,
        val name: String,
        val createdAt: String,
        val updatedAt: String,
        val tracks: List<PlaylistTrack>
    )

    data class PlaylistsFile(val schemaVersion: Int, val playlists: List<Playlist>)

    sealed class PlaylistOp {
        data class Create(val playlist: Playlist) : PlaylistOp()
        data class Rename(val playlistId: String, val name: String, val at: String) : PlaylistOp()
        data class Delete(val playlistId: String) : PlaylistOp()
        data class AddTracks(val playlistId: String, val tracks: List<PlaylistTrack>, val at: String) : PlaylistOp()
        data class RemoveTrack(val playlistId: String, val fileId: String, val at: String) : PlaylistOp()
        data class SetOrder(val playlistId: String, val fileIds: List<String>, val at: String) : PlaylistOp()
    }

    sealed class ParseResult {
        data class Ok(val file: PlaylistsFile) : ParseResult()

        /** Unknown schemaVersion — callers must never overwrite the file. */
        data class UnknownVersion(val schemaVersion: String) : ParseResult()
    }

    fun emptyFile(): PlaylistsFile = PlaylistsFile(SCHEMA_VERSION, emptyList())

    // ── Parse ───────────────────────────────────────────────────────────

    /**
     * Empty, malformed, or non-object content bootstraps an empty file (the
     * first-write path). A schemaVersion other than [SCHEMA_VERSION] is
     * refused so a file written by a newer client is never destroyed.
     * Playlists without a string id/name and tracks without a string fileId
     * are dropped; other missing strings are coerced to "".
     */
    fun parse(text: String): ParseResult {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return ParseResult.Ok(emptyFile())
        }
        if (!root.isJsonObject) return ParseResult.Ok(emptyFile())
        val obj = root.asJsonObject

        val version = obj.get("schemaVersion")
        val isCurrentVersion = version != null && version.isJsonPrimitive &&
            version.asJsonPrimitive.isNumber && version.asJsonPrimitive.asDouble == SCHEMA_VERSION.toDouble()
        if (!isCurrentVersion) {
            return ParseResult.UnknownVersion(version?.toString() ?: "missing")
        }

        val rawList = obj.get("playlists")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val playlists = rawList.mapNotNull { parsePlaylist(it) }
        return ParseResult.Ok(PlaylistsFile(SCHEMA_VERSION, playlists))
    }

    private fun parsePlaylist(element: JsonElement): Playlist? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val id = obj.string("id") ?: return null
        val name = obj.string("name") ?: return null
        val rawTracks = obj.get("tracks")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        return Playlist(
            id = id,
            name = name,
            createdAt = obj.string("createdAt") ?: "",
            updatedAt = obj.string("updatedAt") ?: "",
            tracks = rawTracks.mapNotNull { parseTrack(it) }
        )
    }

    private fun parseTrack(element: JsonElement): PlaylistTrack? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val fileId = obj.string("fileId") ?: return null
        return PlaylistTrack(
            fileId = fileId,
            name = obj.string("name") ?: "",
            mimeType = obj.string("mimeType"),
            size = obj.string("size"),
            parentId = obj.string("parentId"),
            addedAt = obj.string("addedAt") ?: ""
        )
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    // ── Apply ───────────────────────────────────────────────────────────

    /**
     * Apply one op on top of a (freshly fetched) remote file. Ops targeting
     * a playlist that no longer exists remotely are dropped; a Create whose
     * id already exists is a no-op (so retries are idempotent). All other
     * playlists pass through untouched.
     */
    fun applyOp(file: PlaylistsFile, op: PlaylistOp): PlaylistsFile = when (op) {
        is PlaylistOp.Create ->
            if (file.playlists.any { it.id == op.playlist.id }) file
            else file.copy(playlists = file.playlists + op.playlist)

        is PlaylistOp.Delete ->
            file.copy(playlists = file.playlists.filter { it.id != op.playlistId })

        is PlaylistOp.Rename -> update(file, op.playlistId) { it.copy(name = op.name, updatedAt = op.at) }

        is PlaylistOp.AddTracks -> update(file, op.playlistId) { playlist ->
            val existing = playlist.tracks.map { it.fileId }.toMutableSet()
            val added = op.tracks.filter { existing.add(it.fileId) }
            if (added.isEmpty()) playlist
            else playlist.copy(tracks = playlist.tracks + added, updatedAt = op.at)
        }

        is PlaylistOp.RemoveTrack -> update(file, op.playlistId) { playlist ->
            playlist.copy(tracks = playlist.tracks.filter { it.fileId != op.fileId }, updatedAt = op.at)
        }

        is PlaylistOp.SetOrder -> update(file, op.playlistId) { playlist ->
            val byId = playlist.tracks.associateBy { it.fileId }
            val ordered = op.fileIds.mapNotNull { byId[it] }
            val orderedIds = ordered.map { it.fileId }.toSet()
            // Tracks added by another client since this order was captured keep their place at the end
            val remaining = playlist.tracks.filter { it.fileId !in orderedIds }
            playlist.copy(tracks = ordered + remaining, updatedAt = op.at)
        }
    }

    private fun update(file: PlaylistsFile, playlistId: String, transform: (Playlist) -> Playlist): PlaylistsFile {
        if (file.playlists.none { it.id == playlistId }) return file
        return file.copy(playlists = file.playlists.map { if (it.id == playlistId) transform(it) else it })
    }

    // ── Serialize ───────────────────────────────────────────────────────

    /**
     * Serialize the full loaded file with exactly the documented fields,
     * 2-space pretty-printed like the web client's JSON.stringify(obj, null, 2).
     */
    fun serialize(file: PlaylistsFile): String {
        val obj = JsonObject().apply {
            addProperty("schemaVersion", file.schemaVersion)
            add("playlists", JsonArray().apply { file.playlists.forEach { add(playlistToJson(it)) } })
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(obj)
    }

    private fun playlistToJson(playlist: Playlist): JsonObject = JsonObject().apply {
        addProperty("id", playlist.id)
        addProperty("name", playlist.name)
        addProperty("createdAt", playlist.createdAt)
        addProperty("updatedAt", playlist.updatedAt)
        add("tracks", JsonArray().apply { playlist.tracks.forEach { add(trackToJson(it)) } })
    }

    private fun trackToJson(track: PlaylistTrack): JsonObject = JsonObject().apply {
        addProperty("fileId", track.fileId)
        addProperty("name", track.name)
        track.mimeType?.let { addProperty("mimeType", it) }
        track.size?.let { addProperty("size", it) }
        track.parentId?.let { addProperty("parentId", it) }
        addProperty("addedAt", track.addedAt)
    }

    // ── Op serialization (local pending-op cache only) ──────────────────

    fun serializeOp(op: PlaylistOp): String {
        val obj = JsonObject()
        when (op) {
            is PlaylistOp.Create -> {
                obj.addProperty("op", "create")
                obj.add("playlist", playlistToJson(op.playlist))
            }
            is PlaylistOp.Rename -> {
                obj.addProperty("op", "rename")
                obj.addProperty("playlistId", op.playlistId)
                obj.addProperty("name", op.name)
                obj.addProperty("at", op.at)
            }
            is PlaylistOp.Delete -> {
                obj.addProperty("op", "delete")
                obj.addProperty("playlistId", op.playlistId)
            }
            is PlaylistOp.AddTracks -> {
                obj.addProperty("op", "add-tracks")
                obj.addProperty("playlistId", op.playlistId)
                obj.add("tracks", JsonArray().apply { op.tracks.forEach { add(trackToJson(it)) } })
                obj.addProperty("at", op.at)
            }
            is PlaylistOp.RemoveTrack -> {
                obj.addProperty("op", "remove-track")
                obj.addProperty("playlistId", op.playlistId)
                obj.addProperty("fileId", op.fileId)
                obj.addProperty("at", op.at)
            }
            is PlaylistOp.SetOrder -> {
                obj.addProperty("op", "set-order")
                obj.addProperty("playlistId", op.playlistId)
                obj.add("fileIds", JsonArray().apply { op.fileIds.forEach { add(it) } })
                obj.addProperty("at", op.at)
            }
        }
        return obj.toString()
    }

    /** Returns null for anything unrecognised so a corrupt cache entry is simply skipped. */
    fun parseOp(json: String): PlaylistOp? {
        val obj = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        } catch (e: Exception) {
            return null
        }
        val at = obj.string("at") ?: ""
        return when (obj.string("op")) {
            "create" -> obj.get("playlist")?.let { parsePlaylist(it) }?.let { PlaylistOp.Create(it) }
            "rename" -> PlaylistOp.Rename(obj.string("playlistId") ?: return null, obj.string("name") ?: return null, at)
            "delete" -> PlaylistOp.Delete(obj.string("playlistId") ?: return null)
            "add-tracks" -> {
                val tracks = obj.get("tracks")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { parseTrack(it) }
                    ?: return null
                PlaylistOp.AddTracks(obj.string("playlistId") ?: return null, tracks, at)
            }
            "remove-track" -> PlaylistOp.RemoveTrack(
                obj.string("playlistId") ?: return null, obj.string("fileId") ?: return null, at
            )
            "set-order" -> {
                val ids = obj.get("fileIds")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: return null
                PlaylistOp.SetOrder(obj.string("playlistId") ?: return null, ids, at)
            }
            else -> null
        }
    }
}
