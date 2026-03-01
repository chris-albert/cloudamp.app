package com.cloudamp.music.models

import com.google.gson.annotations.SerializedName

data class Artist(
    val id: String,
    val name: String,
    val images: List<Image>? = null,
    val uri: String
)

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
    fun getAlbumCategory(): String {
        val nameUpper = name.uppercase()
        if (nameUpper.contains(" EP") || nameUpper.endsWith(" EP") ||
            nameUpper.contains("(EP)") || nameUpper.contains("- EP")) {
            return "ep"
        }
        return when (albumType?.lowercase()) {
            "single" -> "single"
            "compilation" -> "album"
            else -> {
                val tracks = totalTracks ?: 0
                if (tracks in 4..6) "ep" else "album"
            }
        }
    }
}

data class Track(
    val id: String,
    val name: String,
    val artists: List<Artist>,
    val album: Album? = null,
    val uri: String,
    @SerializedName("duration_ms") val durationMs: Int = 0,
    @SerializedName("track_number") val trackNumber: Int? = null
)

data class Image(
    val url: String,
    val height: Int?,
    val width: Int?
)
