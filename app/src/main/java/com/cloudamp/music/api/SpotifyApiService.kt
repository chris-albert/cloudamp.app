package com.cloudamp.music.api

import com.cloudamp.music.models.*
import retrofit2.Response
import retrofit2.http.*

interface SpotifyApiService {

    // User Library
    @GET("v1/me/tracks")
    suspend fun getMySavedTracks(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SavedTracksResponse>

    @GET("v1/me/albums")
    suspend fun getMySavedAlbums(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<SavedAlbumsResponse>

    @GET("v1/me/playlists")
    suspend fun getMyPlaylists(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<PlaylistsResponse>

    @GET("v1/me/top/artists")
    suspend fun getMyTopArtists(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("time_range") timeRange: String = "medium_term"
    ): Response<Paging<Artist>>

    @GET("v1/playlists/{playlist_id}/tracks")
    suspend fun getPlaylistTracks(
        @Path("playlist_id") playlistId: String,
        @Query("limit") limit: Int = 50
    ): Response<PlaylistTracksResponse>

    // Search
    @GET("v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String, // artist, album, track
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<SearchResponse>

    // Get Artist
    @GET("v1/artists/{id}")
    suspend fun getArtist(@Path("id") artistId: String): Response<Artist>

    // Get Artist's Albums
    @GET("v1/artists/{id}/albums")
    suspend fun getArtistAlbums(
        @Path("id") artistId: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): Response<ArtistAlbumsResponse>

    // Get Album
    @GET("v1/albums/{id}")
    suspend fun getAlbum(@Path("id") albumId: String): Response<Album>

    // Get Album Tracks
    @GET("v1/albums/{id}/tracks")
    suspend fun getAlbumTracks(
        @Path("id") albumId: String,
        @Query("limit") limit: Int = 50
    ): Response<AlbumTracksResponse>

    // Playback Control
    @PUT("v1/me/player/play")
    suspend fun play(
        @Body playRequest: PlayRequest
    ): Response<Unit>

    @PUT("v1/me/player/pause")
    suspend fun pause(): Response<Unit>

    @POST("v1/me/player/next")
    suspend fun next(): Response<Unit>

    @POST("v1/me/player/previous")
    suspend fun previous(): Response<Unit>

    @GET("v1/me/player")
    suspend fun getCurrentPlayback(): Response<CurrentPlayback>

    @PUT("v1/me/player/seek")
    suspend fun seek(@Query("position_ms") positionMs: Long): Response<Unit>

    // User Profile (for token validation)
    @GET("v1/me")
    suspend fun getCurrentUserProfile(): Response<UserProfile>
}

// Request bodies
data class PlayRequest(
    val uris: List<String>? = null,
    val context_uri: String? = null,
    val offset: PlayOffset? = null,
    val position_ms: Long? = null
)

data class PlayOffset(
    val position: Int? = null,
    val uri: String? = null
)

// User Profile Model (for token validation)
data class UserProfile(
    val id: String,
    val display_name: String?,
    val email: String?
)

// Library Response Models
data class SavedTrack(
    val added_at: String,
    val track: Track
)

data class SavedTracksResponse(
    val items: List<SavedTrack>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class SavedAlbum(
    val added_at: String,
    val album: Album
)

data class SavedAlbumsResponse(
    val items: List<SavedAlbum>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class Playlist(
    val id: String,
    val name: String,
    val description: String?,
    val images: List<Image>?,
    val uri: String,
    val tracks: PlaylistTracksInfo
)

data class PlaylistTracksInfo(
    val total: Int
)

data class PlaylistsResponse(
    val items: List<Playlist>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class PlaylistTrack(
    val added_at: String,
    val track: Track?
)

data class PlaylistTracksResponse(
    val items: List<PlaylistTrack>,
    val total: Int
)
