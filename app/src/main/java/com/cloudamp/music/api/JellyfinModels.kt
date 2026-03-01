package com.cloudamp.music.api

import com.google.gson.annotations.SerializedName

/**
 * Unified Jellyfin item model — used for artists, albums, tracks, and playlists.
 * The [Type] field distinguishes the kind of item.
 */
data class JellyfinItem(
    val Id: String,
    val Name: String,
    val Type: String, // "MusicArtist", "MusicAlbum", "Audio", "Playlist"
    val ServerId: String? = null,
    val AlbumId: String? = null,
    val AlbumArtist: String? = null,
    val Album: String? = null,
    val Artists: List<String>? = null,
    val RunTimeTicks: Long? = null,
    @SerializedName("IndexNumber") val TrackNumber: Int? = null,
    @SerializedName("ParentIndexNumber") val DiscNumber: Int? = null,
    val AlbumArtists: List<JellyfinNameIdPair>? = null,
    val ImageTags: Map<String, String>? = null,
    val Path: String? = null,
    val ParentId: String? = null,
    val ChildCount: Int? = null,
    @SerializedName("ProductionYear") val Year: Int? = null
) {
    /** Duration in milliseconds (Jellyfin uses ticks = 100-nanosecond units). */
    fun getDurationMs(): Long {
        val ticks = RunTimeTicks ?: return 0L
        return ticks / 10_000
    }

    fun hasPrimaryImage(): Boolean {
        return ImageTags?.containsKey("Primary") == true
    }

    /**
     * Build the URL for this item's primary image.
     * @param serverUrl Base server URL (no trailing slash)
     * @param apiKey Optional API key/access token to include as a query parameter.
     *              Required for Android Auto and Glide which cannot add auth headers.
     */
    fun getPrimaryImageUrl(serverUrl: String, apiKey: String? = null): String? {
        if (!hasPrimaryImage()) return null
        val base = "$serverUrl/Items/$Id/Images/Primary?maxWidth=300"
        return if (apiKey != null) "$base&api_key=$apiKey" else base
    }

    fun getArtistDisplay(): String {
        return Artists?.joinToString(", ") ?: AlbumArtist ?: ""
    }
}

data class JellyfinItemsResponse(
    val Items: List<JellyfinItem>,
    val TotalRecordCount: Int? = null
)

data class JellyfinUser(
    val Id: String,
    val Name: String,
    val ServerId: String? = null
)

data class JellyfinNameIdPair(
    val Name: String,
    val Id: String
)

/** Request body for authenticating by username/password. */
data class JellyfinAuthRequest(
    @SerializedName("Username") val username: String,
    @SerializedName("Pw") val password: String
)

/** Response from AuthenticateByName — contains user info and access token. */
data class JellyfinAuthResult(
    val User: JellyfinUser,
    val AccessToken: String,
    val ServerId: String? = null
)
