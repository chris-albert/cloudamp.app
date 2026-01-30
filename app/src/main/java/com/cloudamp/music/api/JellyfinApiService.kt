package com.cloudamp.music.api

import retrofit2.Response
import retrofit2.http.*

interface JellyfinApiService {

    /**
     * Get all media library views (Music, Movies, etc.) for the user.
     */
    @GET("Users/{userId}/Views")
    suspend fun getViews(
        @Path("userId") userId: String
    ): Response<JellyfinItemsResponse>

    /**
     * Get items from a user's library with various filters.
     * Used for browsing artists, albums, tracks, and searching.
     */
    @GET("Users/{userId}/Items")
    suspend fun getItems(
        @Path("userId") userId: String,
        @Query("ParentId") parentId: String? = null,
        @Query("IncludeItemTypes") includeItemTypes: String? = null,
        @Query("ArtistIds") artistIds: String? = null,
        @Query("AlbumIds") albumIds: String? = null,
        @Query("SortBy") sortBy: String? = null,
        @Query("SortOrder") sortOrder: String? = null,
        @Query("Fields") fields: String? = "PrimaryImageTag,Overview,Genres,DateCreated,ProductionYear",
        @Query("Recursive") recursive: Boolean = true,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        @Query("ImageTypeLimit") imageTypeLimit: Int? = 1
    ): Response<JellyfinItemsResponse>

    /**
     * Get a single item by ID.
     */
    @GET("Users/{userId}/Items/{itemId}")
    suspend fun getItem(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String
    ): Response<JellyfinItem>
}

// ── Jellyfin response models ───────────────────────────────────────

data class JellyfinItemsResponse(
    val Items: List<JellyfinItem>,
    val TotalRecordCount: Int = 0
)

data class JellyfinItem(
    val Id: String,
    val Name: String,
    val Type: String? = null,
    val CollectionType: String? = null,
    val AlbumId: String? = null,
    val AlbumArtist: String? = null,
    val AlbumArtists: List<JellyfinNameId>? = null,
    val ArtistItems: List<JellyfinNameId>? = null,
    val Artists: List<String>? = null,
    val Album: String? = null,
    val RunTimeTicks: Long? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val ProductionYear: Int? = null,
    val ImageTags: Map<String, String>? = null,
    val ChildCount: Int? = null,
    val Overview: String? = null,
    val Genres: List<String>? = null,
    val ServerId: String? = null,
    val MediaType: String? = null
) {
    fun hasPrimaryImage(): Boolean = ImageTags?.containsKey("Primary") == true

    fun getRunTimeMs(): Int {
        val ticks = RunTimeTicks ?: return 0
        return (ticks / 10_000).toInt() // Jellyfin uses ticks (100-nanosecond units)
    }
}

data class JellyfinNameId(
    val Name: String,
    val Id: String
)
