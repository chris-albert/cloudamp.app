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
    @SerializedName("total_tracks") val totalTracks: Int? = null
)

// Track Model
data class Track(
    val id: String,
    val name: String,
    val artists: List<Artist>,
    val album: Album,
    val uri: String,
    @SerializedName("duration_ms") val durationMs: Long,
    @SerializedName("track_number") val trackNumber: Int? = null
)

// Simplified Track Model (used in album tracks, playlists, etc.)
data class SimplifiedTrack(
    val id: String,
    val name: String,
    val artists: List<Artist>,
    val uri: String,
    @SerializedName("duration_ms") val durationMs: Long,
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
    @SerializedName("progress_ms") val progressMs: Long?
)

// Playback State for local tracking
data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val queue: List<Track> = emptyList(),
    val currentPosition: Int = 0
)
