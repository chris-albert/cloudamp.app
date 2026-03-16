package com.cloudamp.music.api

data class GDriveArtist(
    val id: String,
    val name: String,
    val albumCount: Int = 0
)

data class GDriveAlbum(
    val id: String,
    val name: String,
    val artistId: String,
    val artistName: String,
    val year: Int? = null,
    val trackCount: Int = 0,
    val coverFileId: String? = null,
    val modifiedTime: String? = null
)

data class GDriveTrack(
    val file: DriveFile,
    val artistId: String,
    val artistName: String,
    val albumId: String,
    val albumName: String,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val trackName: String,
    val year: Int? = null,
    val coverFileId: String? = null
)
