package com.cloudamp.music.models

/**
 * A user-saved Google Drive folder location.
 * The path holds the full folder chain from the Drive root down to (and
 * including) the saved folder, so the browser can restore back-navigation.
 */
data class SavedLocation(
    val folderId: String,
    val name: String,
    val path: List<PathSegment>,
    val savedAt: Long
) {
    data class PathSegment(val id: String, val name: String)
}
