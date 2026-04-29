package com.cloudamp.music.cache

import android.content.Context
import android.util.Log
import com.cloudamp.music.api.*
import com.cloudamp.music.playback.GDriveImageProvider
import com.cloudamp.music.util.MusicFilenameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans a Google Drive folder tree (Music/Artist/Album/Track.ext) and
 * builds a structured library of GDriveArtist, GDriveAlbum, GDriveTrack.
 *
 * Strategy: 3 bulk queries fetch ALL folders, audio files, and cover images
 * across the entire Drive (~10-15 paginated API calls total). The Artist→Album→Track
 * tree is then reconstructed client-side by walking parent IDs from the configured
 * root folder. This is orders of magnitude faster than per-folder queries.
 */
class GDriveLibraryScanner(
    private val api: GoogleDriveApiService,
    private val cache: GDriveLibraryCache
) {
    companion object {
        private const val TAG = "GDriveLibraryScanner"
        private val COVER_NAMES = setOf(
            "cover.jpg", "cover.png", "cover.jpeg",
            "folder.jpg", "folder.png", "folder.jpeg",
            "front.jpg", "front.png", "front.jpeg",
            "album.jpg", "album.png", "album.jpeg"
        )
        // Folders directly under the library root that should never be treated as artists.
        // Compared in lowercase.
        private val IGNORED_FOLDER_NAMES = setOf("music", ".cloudamp")
    }

    data class ScanResult(
        val artists: List<GDriveArtist>,
        val albumsByArtist: Map<String, List<GDriveAlbum>>,
        val tracksByAlbum: Map<String, List<GDriveTrack>>
    )

    /**
     * Snapshot of scan/prefetch progress reported via [onProgress].
     * @param message human-readable phase description
     * @param artists number of artists discovered (0 until tree is built)
     * @param albumArtFetched covers downloaded so far
     * @param totalAlbumArt total covers to download (0 until prefetch starts)
     */
    data class ScanProgress(
        val message: String,
        val artists: Int = 0,
        val albumArtFetched: Int = 0,
        val totalAlbumArt: Int = 0
    )

    var onProgress: ((ScanProgress) -> Unit)? = null

    private var artistsCount: Int = 0

    private fun report(message: String, artFetched: Int = 0, totalArt: Int = 0) {
        onProgress?.invoke(ScanProgress(message, artistsCount, artFetched, totalArt))
    }

    /**
     * Perform a full library scan from the configured root folder.
     * Returns null if root folder is not configured.
     */
    suspend fun scan(): ScanResult? {
        val rootId = cache.getRootFolderId() ?: return null
        return scanFolder(rootId)
    }

    private suspend fun scanFolder(rootId: String): ScanResult = coroutineScope {
        // ── Bulk fetch: 3 parallel queries across ALL of Drive ────────────

        report("Fetching folders...")
        val foldersDeferred = async {
            fetchAllPaginated { pageToken ->
                api.listFiles(
                    query = "mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                    fields = "files(id,name,parents,modifiedTime),nextPageToken",
                    orderBy = "name",
                    pageSize = 1000,
                    pageToken = pageToken
                )
            }
        }

        // Query audio files by MIME type AND by extension (Drive may assign
        // application/octet-stream to FLAC, OGG, etc. instead of audio/)
        report("Fetching audio files...")
        val audioQuery = "(mimeType contains 'audio/'" +
            " or name contains '.flac' or name contains '.m4a'" +
            " or name contains '.ogg' or name contains '.opus'" +
            " or name contains '.wav' or name contains '.aac'" +
            " or name contains '.wma' or name contains '.alac'" +
            " or name contains '.aiff' or name contains '.ape'" +
            ") and trashed = false"
        val audioDeferred = async {
            fetchAllPaginated { pageToken ->
                api.listFiles(
                    query = audioQuery,
                    fields = "files(id,name,mimeType,size,parents,modifiedTime),nextPageToken",
                    orderBy = "name",
                    pageSize = 1000,
                    pageToken = pageToken
                )
            }
        }

        report("Fetching cover art...")
        val coversDeferred = async {
            fetchAllPaginated { pageToken ->
                api.listFiles(
                    query = "(name='cover.jpg' or name='cover.png' or name='cover.jpeg' or name='folder.jpg' or name='folder.png' or name='folder.jpeg' or name='front.jpg' or name='front.png' or name='front.jpeg' or name='album.jpg' or name='album.png' or name='album.jpeg') and mimeType contains 'image/' and trashed = false",
                    fields = "files(id,name,parents),nextPageToken",
                    orderBy = "name",
                    pageSize = 1000,
                    pageToken = pageToken
                )
            }
        }

        val allFolders = foldersDeferred.await()
        val allAudioFiles = audioDeferred.await()
        val allCoverFiles = coversDeferred.await()

        Log.d(TAG, "Bulk fetch complete: ${allFolders.size} folders, ${allAudioFiles.size} audio files, ${allCoverFiles.size} cover images")

        // ── Reconstruct tree client-side ──────────────────────────────────

        report("Building library tree...")

        // Build folder lookup by ID
        val folderById = allFolders.associateBy { it.id }

        // Find artist folders: direct children of root (excluding metadata folders)
        val artistFolders = allFolders
            .filter {
                it.parents?.contains(rootId) == true &&
                    it.name.lowercase() !in IGNORED_FOLDER_NAMES
            }
            .sortedBy { it.name.lowercase() }
        val artistIds = artistFolders.map { it.id }.toSet()

        Log.d(TAG, "Found ${artistFolders.size} artist folders under root")

        // Find album folders: direct children of any artist folder
        val albumFolders = allFolders.filter { folder ->
            folder.parents?.any { it in artistIds } == true
        }
        val albumIds = albumFolders.map { it.id }.toSet()

        Log.d(TAG, "Found ${albumFolders.size} album folders")

        // Filter to actual audio files in album folders (the broad query may
        // match non-audio files whose names happen to contain e.g. ".flac")
        val musicAudioFiles = allAudioFiles.filter { file ->
            file.isAudioFile() && file.parents?.any { it in albumIds } == true
        }

        Log.d(TAG, "Found ${musicAudioFiles.size} tracks in library (${allAudioFiles.size} total in Drive)")

        // Build cover maps: folder ID → cover file ID (for both artist and album folders)
        val coverByAlbum = mutableMapOf<String, String>()
        val coverByArtist = mutableMapOf<String, String>()
        for (cover in allCoverFiles) {
            val parentId = cover.parents?.firstOrNull() ?: continue
            val lowerName = cover.name.lowercase()
            if (lowerName !in COVER_NAMES) continue
            if (parentId in albumIds) {
                coverByAlbum[parentId] = cover.id
            }
            if (parentId in artistIds) {
                coverByArtist[parentId] = cover.id
            }
        }
        Log.d(TAG, "Found ${coverByAlbum.size} album covers, ${coverByArtist.size} artist images")

        // ── Build structured data ─────────────────────────────────────────

        report("Organizing library...")

        // Group album folders by artist
        val albumFoldersByArtist = albumFolders.groupBy { folder ->
            folder.parents?.firstOrNull { it in artistIds } ?: ""
        }.filterKeys { it.isNotEmpty() }

        // Group audio files by album
        val audioFilesByAlbum = musicAudioFiles.groupBy { file ->
            file.parents?.firstOrNull { it in albumIds } ?: ""
        }.filterKeys { it.isNotEmpty() }

        val artists = mutableListOf<GDriveArtist>()
        val albumsByArtistMap = mutableMapOf<String, List<GDriveAlbum>>()
        val tracksByAlbumMap = mutableMapOf<String, List<GDriveTrack>>()

        for (artistFolder in artistFolders) {
            val artistAlbumFolders = albumFoldersByArtist[artistFolder.id] ?: emptyList()

            val albums = mutableListOf<GDriveAlbum>()
            for (albumFolder in artistAlbumFolders) {
                val parsed = MusicFilenameParser.parseAlbumFolderName(albumFolder.name)
                val audioFiles = audioFilesByAlbum[albumFolder.id] ?: emptyList()

                val album = GDriveAlbum(
                    id = albumFolder.id,
                    name = parsed.name,
                    artistId = artistFolder.id,
                    artistName = artistFolder.name,
                    year = parsed.year,
                    trackCount = audioFiles.size,
                    coverFileId = coverByAlbum[albumFolder.id],
                    modifiedTime = albumFolder.modifiedTime
                )
                albums.add(album)

                val tracks = audioFiles.map { file ->
                    val parsedTrack = MusicFilenameParser.parseTrackFilename(file.name)
                    GDriveTrack(
                        file = file,
                        artistId = artistFolder.id,
                        artistName = artistFolder.name,
                        albumId = albumFolder.id,
                        albumName = parsed.name,
                        trackNumber = parsedTrack.trackNumber,
                        discNumber = parsedTrack.discNumber,
                        trackName = parsedTrack.title,
                        year = parsed.year,
                        coverFileId = coverByAlbum[albumFolder.id]
                    )
                }.sortedWith(compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))

                tracksByAlbumMap[albumFolder.id] = tracks
            }

            val sortedAlbums = albums.sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { it.name.lowercase() }))

            artists.add(GDriveArtist(
                id = artistFolder.id,
                name = artistFolder.name,
                albumCount = sortedAlbums.size,
                imageFileId = coverByArtist[artistFolder.id]
            ))
            albumsByArtistMap[artistFolder.id] = sortedAlbums
        }

        val sortedArtists = artists.sortedBy { it.name.lowercase() }
        Log.d(TAG, "Scan complete: ${sortedArtists.size} artists, ${albumsByArtistMap.values.sumOf { it.size }} albums, ${tracksByAlbumMap.values.sumOf { it.size }} tracks")

        artistsCount = sortedArtists.size
        report("Scan complete")

        ScanResult(sortedArtists, albumsByArtistMap, tracksByAlbumMap)
    }

    /**
     * Download every cover image referenced by [result] into the on-disk
     * album-art cache, in parallel with limited concurrency. Reports
     * progress as covers complete. Failures are logged and counted as
     * unsuccessful but never thrown — a missing cover should not abort
     * the rest of the prefetch.
     */
    suspend fun prefetchAlbumArt(context: Context, result: ScanResult) {
        artistsCount = result.artists.size

        val fileIds = buildSet {
            for (artist in result.artists) artist.imageFileId?.let { add(it) }
            for (albums in result.albumsByArtist.values) {
                for (album in albums) album.coverFileId?.let { add(it) }
            }
        }.toList()

        val total = fileIds.size
        if (total == 0) {
            report("No album art to cache")
            return
        }

        report("Caching album art...", artFetched = 0, totalArt = total)

        val done = AtomicInteger(0)
        val semaphore = Semaphore(permits = 6)

        coroutineScope {
            fileIds.map { fileId ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        GDriveImageProvider.fetchToCache(context, fileId)
                        val n = done.incrementAndGet()
                        report("Caching album art...", artFetched = n, totalArt = total)
                    }
                }
            }.awaitAll()
        }

        Log.d(TAG, "Album art prefetch complete: ${done.get()}/$total cached")
        report("Album art cached", artFetched = done.get(), totalArt = total)
    }

    /**
     * Save scan results to cache.
     */
    fun saveToCache(result: ScanResult) {
        cache.saveArtists(result.artists)
        for ((artistId, albums) in result.albumsByArtist) {
            cache.saveArtistAlbums(artistId, albums)
        }
        for ((albumId, tracks) in result.tracksByAlbum) {
            cache.saveAlbumTracks(albumId, tracks)
        }

        val totalCovers = buildSet {
            for (artist in result.artists) artist.imageFileId?.let { add(it) }
            for (albums in result.albumsByArtist.values) {
                for (album in albums) album.coverFileId?.let { add(it) }
            }
        }.size
        cache.saveStats(
            artists = result.artists.size,
            albums = result.albumsByArtist.values.sumOf { it.size },
            tracks = result.tracksByAlbum.values.sumOf { it.size },
            totalCovers = totalCovers
        )

        cache.markCacheComplete()
        // A full scan is the only thing that invalidates the album art cache.
        cache.clearAlbumArtCache()
    }

    private suspend fun fetchAllPaginated(
        fetcher: suspend (pageToken: String?) -> retrofit2.Response<DriveFileListResponse>
    ): List<DriveFile> {
        val allFiles = mutableListOf<DriveFile>()
        var pageToken: String? = null
        var page = 0

        do {
            val response = fetcher(pageToken)
            if (response.isSuccessful) {
                val body = response.body()
                val files = body?.files ?: emptyList()
                allFiles.addAll(files)
                pageToken = body?.nextPageToken
                page++
                Log.d(TAG, "  page $page: ${files.size} items (${allFiles.size} total)")
            } else {
                Log.e(TAG, "API error: ${response.code()}")
                break
            }
        } while (pageToken != null)

        return allFiles
    }
}
