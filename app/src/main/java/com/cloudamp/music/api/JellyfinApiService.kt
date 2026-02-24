package com.cloudamp.music.api

import retrofit2.Response
import retrofit2.http.*

interface JellyfinApiService {

    /** Authenticate with username and password, returns user info + access token. */
    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(
        @Body request: JellyfinAuthRequest
    ): Response<JellyfinAuthResult>

    /** Validate credentials and retrieve the authenticated user. */
    @GET("Users/Me")
    suspend fun getCurrentUser(): Response<JellyfinUser>

    /** Get album artists in the library (excludes featured/track-only artists). */
    @GET("Artists/AlbumArtists")
    suspend fun getArtists(
        @Query("UserId") userId: String,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Recursive") recursive: Boolean = true,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,SortName,ChildCount,Path",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 500
    ): Response<JellyfinItemsResponse>

    /** Get albums in an artist's folder (uses folder structure as source of truth). */
    @GET("Users/{userId}/Items")
    suspend fun getArtistAlbums(
        @Path("userId") userId: String,
        @Query("ParentId") artistId: String,
        @Query("IncludeItemTypes") types: String = "MusicAlbum",
        @Query("SortBy") sortBy: String = "ProductionYear,SortName",
        @Query("SortOrder") sortOrder: String = "Descending",
        @Query("Recursive") recursive: Boolean = true,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,ProductionYear"
    ): Response<JellyfinItemsResponse>

    /** Get tracks in an album. */
    @GET("Users/{userId}/Items")
    suspend fun getAlbumTracks(
        @Path("userId") userId: String,
        @Query("ParentId") albumId: String,
        @Query("IncludeItemTypes") types: String = "Audio",
        @Query("SortBy") sortBy: String = "ParentIndexNumber,IndexNumber",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,Artists"
    ): Response<JellyfinItemsResponse>

    /** Get all playlists. */
    @GET("Users/{userId}/Items")
    suspend fun getPlaylists(
        @Path("userId") userId: String,
        @Query("IncludeItemTypes") types: String = "Playlist",
        @Query("SortBy") sortBy: String = "SortName",
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Recursive") recursive: Boolean = true,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,ChildCount"
    ): Response<JellyfinItemsResponse>

    /** Get items in a playlist. */
    @GET("Playlists/{playlistId}/Items")
    suspend fun getPlaylistItems(
        @Path("playlistId") playlistId: String,
        @Query("UserId") userId: String,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,Artists"
    ): Response<JellyfinItemsResponse>

    /** Search across items. */
    @GET("Users/{userId}/Items")
    suspend fun searchItems(
        @Path("userId") userId: String,
        @Query("SearchTerm") searchTerm: String,
        @Query("IncludeItemTypes") types: String = "MusicArtist,MusicAlbum,Audio",
        @Query("Recursive") recursive: Boolean = true,
        @Query("Limit") limit: Int = 50,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,Artists"
    ): Response<JellyfinItemsResponse>
}
