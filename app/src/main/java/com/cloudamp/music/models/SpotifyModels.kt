package com.cloudamp.music.models

import com.google.gson.annotations.SerializedName

// Artist Model
data class Artist(
    val id: String,
    val name: String,
    val images: List<Image>? = null,
    val uri: String
)

// Album Model
data class Album(
    val id: String,
    val name: String,
    val artists: List<Artist>,
    val images: List<Image>? = null,
    val uri: String,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("total_tracks") val totalTracks: Int? = null,
    @SerializedName("album_type") val albumType: String? = null,
    @SerializedName("album_group") val albumGroup: String? = null
) {
    /**
     * Determines the album category for display purposes.
     * Returns: "ep" for EPs, "single" for singles, "album" for LPs
     */
    fun getAlbumCategory(): String {
        // Check if name explicitly contains EP indicator
        val nameUpper = name.uppercase()
        if (nameUpper.contains(" EP") || nameUpper.endsWith(" EP") ||
            nameUpper.contains("(EP)") || nameUpper.contains("- EP")) {
            return "ep"
        }

        // Use album_type from Spotify if available
        return when (albumType?.lowercase()) {
            "single" -> "single"
            "compilation" -> "album" // Treat compilations as albums
            else -> {
                // Check if it's likely an EP based on track count (4-6 tracks)
                val tracks = totalTracks ?: 0
                if (tracks in 4..6) "ep" else "album"
            }
        }
    }
}

// Track Model
data class Track(
    val id: String,
    val name: String,
    val artists: List<Artist>,
    val album: Album? = null,
    val uri: String,
    @SerializedName("duration_ms") val durationMs: Int = 0,
    @SerializedName("track_number") val trackNumber: Int? = null
)

// Simplified Track Model (used in album tracks, playlists, etc.)
data class SimplifiedTrack(
    val id: String,
    val name: String,
    val artists: List<Artist>,
    val uri: String,
    @SerializedName("duration_ms") val durationMs: Int = 0,
    @SerializedName("track_number") val trackNumber: Int? = null
)

// Image Model
data class Image(
    val url: String,
    val height: Int?,
    val width: Int?
)

// Paging Response
data class Paging<T>(
    val items: List<T>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val next: String?,
    val previous: String?
)

// Search Response
data class SearchResponse(
    val artists: Paging<Artist>? = null,
    val albums: Paging<Album>? = null,
    val tracks: Paging<Track>? = null
)

// Artist Albums Response
data class ArtistAlbumsResponse(
    val items: List<Album>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

// Album Tracks Response
data class AlbumTracksResponse(
    val items: List<SimplifiedTrack>,
    val total: Int
)

// Current Playback
data class CurrentPlayback(
    @SerializedName("is_playing") val isPlaying: Boolean,
    val item: Track?,
    @SerializedName("progress_ms") val progressMs: Int = 0
)

// Playback State for local tracking
data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val queue: List<Track> = emptyList(),
    val currentPosition: Int = 0
)
