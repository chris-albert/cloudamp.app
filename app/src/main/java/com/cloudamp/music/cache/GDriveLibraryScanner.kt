package com.cloudamp.music.cache

import android.util.Log
import com.cloudamp.music.api.*
import com.cloudamp.music.util.MusicFilenameParser

/**
 * Scans a Google Drive folder tree (Music/Artist/Album/Track.ext) and
 * builds a structured library of GDriveArtist, GDriveAlbum, GDriveTrack.
 *
 * Uses bulk queries to minimize API calls (~15 calls instead of ~1200):
 * 1. Fetch ALL folders under root recursively
 * 2. Fetch ALL audio files under root recursively
 * 3. Fetch cover art files
 * 4. Reconstruct tree client-side by matching parent IDs
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
    }

    data class ScanResult(
        val artists: List<GDriveArtist>,
        val albumsByArtist: Map<String, List<GDriveAlbum>>,
        val tracksByAlbum: Map<String, List<GDriveTrack>>
    )

    var onProgress: ((String) -> Unit)? = null

    /**
     * Perform a full library scan from the configured root folder.
     * Returns null if root folder is not configured.
     */
    suspend fun scan(): ScanResult? {
        val rootId = cache.getRootFolderId() ?: return null
        return scanFolder(rootId)
    }

    private suspend fun scanFolder(rootId: String): ScanResult {
        // 1. Fetch ALL folders under root recursively
        onProgress?.invoke("Scanning folders...")
        val allFolders = fetchAllPaginated { pageToken ->
            api.listFiles(
                query = "'$rootId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                fields = "files(id,name,parents,modifiedTime),nextPageToken",
                orderBy = "name",
                pageSize = 1000,
                pageToken = pageToken
            )
        }

        // These are artist folders (direct children of root)
        val artistFolders = allFolders.filter { it.parents?.contains(rootId) == true }
            .sortedBy { it.name.lowercase() }
        Log.d(TAG, "Found ${artistFolders.size} artist folders")

        // Now fetch album folders (children of artist folders)
        val artistIds = artistFolders.map { it.id }.toSet()
        onProgress?.invoke("Scanning album folders...")
        val allSubfolders = mutableListOf<DriveFile>()
        for (artistFolder in artistFolders) {
            val albumFolders = fetchAllPaginated { pageToken ->
                api.listFiles(
                    query = "'${artistFolder.id}' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                    fields = "files(id,name,parents,modifiedTime),nextPageToken",
                    orderBy = "name",
                    pageSize = 1000,
                    pageToken = pageToken
                )
            }
            allSubfolders.addAll(albumFolders)
        }
        val albumFolders = allSubfolders.filter { folder ->
            folder.parents?.any { it in artistIds } == true
        }
        val albumIds = albumFolders.map { it.id }.toSet()
        Log.d(TAG, "Found ${albumFolders.size} album folders")

        // 2. Fetch ALL audio files under album folders
        onProgress?.invoke("Scanning audio files...")
        val allAudioFiles = mutableListOf<DriveFile>()
        for (albumFolder in albumFolders) {
            val audioFiles = fetchAllPaginated { pageToken ->
                api.listFiles(
                    query = "'${albumFolder.id}' in parents and mimeType contains 'audio/' and trashed = false",
                    fields = "files(id,name,mimeType,size,parents,modifiedTime),nextPageToken",
                    orderBy = "name",
                    pageSize = 1000,
                    pageToken = pageToken
                )
            }
            allAudioFiles.addAll(audioFiles)
        }
        Log.d(TAG, "Found ${allAudioFiles.size} audio files")

        // 3. Fetch cover art files
        onProgress?.invoke("Scanning cover art...")
        val allCoverFiles = mutableListOf<DriveFile>()
        for (albumFolder in albumFolders) {
            val coverFiles = fetchAllPaginated { pageToken ->
                api.listFiles(
                    query = "'${albumFolder.id}' in parents and mimeType contains 'image/' and trashed = false",
                    fields = "files(id,name,parents),nextPageToken",
                    orderBy = "name",
                    pageSize = 100,
                    pageToken = pageToken
                )
            }
            allCoverFiles.addAll(coverFiles)
        }

        // Build cover map: album folder ID → cover file ID
        val coverByAlbum = mutableMapOf<String, String>()
        for (cover in allCoverFiles) {
            val parentId = cover.parents?.firstOrNull() ?: continue
            if (parentId in albumIds && cover.name.lowercase() in COVER_NAMES) {
                coverByAlbum[parentId] = cover.id
            }
        }
        Log.d(TAG, "Found ${coverByAlbum.size} album covers")

        // 4. Build the library tree
        onProgress?.invoke("Building library...")

        // Build artist folder lookup
        val artistFolderById = artistFolders.associateBy { it.id }

        // Build album folder lookup
        val albumFolderById = albumFolders.associateBy { it.id }

        // Group album folders by artist
        val albumFoldersByArtist = albumFolders.groupBy { folder ->
            folder.parents?.firstOrNull { it in artistIds } ?: ""
        }.filterKeys { it.isNotEmpty() }

        // Group audio files by album
        val audioFilesByAlbum = allAudioFiles.groupBy { file ->
            file.parents?.firstOrNull { it in albumIds } ?: ""
        }.filterKeys { it.isNotEmpty() }

        // Build structured data
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

                // Build tracks for this album
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

            // Sort albums by year then name
            val sortedAlbums = albums.sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { it.name.lowercase() }))

            artists.add(GDriveArtist(
                id = artistFolder.id,
                name = artistFolder.name,
                albumCount = sortedAlbums.size
            ))
            albumsByArtistMap[artistFolder.id] = sortedAlbums
        }

        val sortedArtists = artists.sortedBy { it.name.lowercase() }
        Log.d(TAG, "Scan complete: ${sortedArtists.size} artists, ${albumsByArtistMap.values.sumOf { it.size }} albums, ${tracksByAlbumMap.values.sumOf { it.size }} tracks")

        return ScanResult(sortedArtists, albumsByArtistMap, tracksByAlbumMap)
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
        cache.markCacheComplete()
    }

    private suspend fun fetchAllPaginated(
        fetcher: suspend (pageToken: String?) -> retrofit2.Response<DriveFileListResponse>
    ): List<DriveFile> {
        val allFiles = mutableListOf<DriveFile>()
        var pageToken: String? = null

        do {
            val response = fetcher(pageToken)
            if (response.isSuccessful) {
                val body = response.body()
                allFiles.addAll(body?.files ?: emptyList())
                pageToken = body?.nextPageToken
            } else {
                Log.e(TAG, "API error: ${response.code()}")
                break
            }
        } while (pageToken != null)

        return allFiles
    }
}
