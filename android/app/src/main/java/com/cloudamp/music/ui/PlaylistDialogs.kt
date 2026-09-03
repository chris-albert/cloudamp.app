package com.cloudamp.music.ui

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.cloudamp.music.R
import com.cloudamp.music.ThemeManager
import com.cloudamp.music.api.DriveFile
import com.cloudamp.music.cache.PlaylistsRepository

/**
 * Shared dialogs for playlist management: name entry and "add to playlist".
 */
object PlaylistDialogs {

    /** Prompt for a playlist name; [onName] is only called with a non-blank name. */
    fun promptName(
        context: Context,
        title: String,
        positiveLabel: String,
        initialName: String = "",
        onName: (String) -> Unit
    ) {
        val editText = EditText(context).apply {
            setText(initialName)
            hint = "Playlist name"
            setTextColor(ThemeManager.resolveColor(context, R.attr.caText))
            setBackgroundColor(ThemeManager.resolveColor(context, R.attr.caBackground))
            setPadding(48, 32, 48, 32)
            setSelection(text.length)
        }

        AlertDialog.Builder(context, R.style.Theme_CloudAmp_Dialog)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(positiveLabel) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Let the user pick an existing playlist (or create a new one) and add
     * [files] to it. Files already in the chosen playlist are skipped. The
     * change is applied locally at once and pushed to Drive in the background.
     */
    fun showAddToPlaylist(context: Context, files: List<DriveFile>) {
        if (files.isEmpty()) return
        val repository = PlaylistsRepository.getInstance(context)
        val playlists = repository.listPlaylists()
        val labels = arrayOf("+ New playlist…") + playlists.map { it.name }.toTypedArray()

        AlertDialog.Builder(context, R.style.Theme_CloudAmp_Dialog)
            .setTitle("Add to playlist")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    promptName(context, "New Playlist", "Create") { name ->
                        val playlist = repository.createPlaylist(name, files)
                        repository.syncInBackground()
                        toastAdded(context, files.size, playlist.name)
                    }
                } else {
                    val playlist = playlists[which - 1]
                    val added = repository.addFiles(playlist.id, files)
                    if (added > 0) repository.syncInBackground()
                    toastAdded(context, added, playlist.name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toastAdded(context: Context, count: Int, playlistName: String) {
        val message = when (count) {
            0 -> "Already in $playlistName"
            1 -> "Added to $playlistName"
            else -> "Added $count tracks to $playlistName"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
