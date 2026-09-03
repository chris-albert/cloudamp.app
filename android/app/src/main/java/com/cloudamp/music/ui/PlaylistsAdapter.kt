package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.R
import com.cloudamp.music.cache.PlaylistsCore.Playlist

class PlaylistsAdapter(
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onRenameClick: (Playlist) -> Unit,
    private val onDeleteClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistsAdapter.ViewHolder>() {

    private var playlists: List<Playlist> = emptyList()

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.playlistNameTextView)
        val detailsTextView: TextView = itemView.findViewById(R.id.playlistDetailsTextView)
        val renameButton: ImageButton = itemView.findViewById(R.id.renameButton)
        val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]
        val count = playlist.tracks.size
        holder.nameTextView.text = playlist.name
        holder.detailsTextView.text = "$count track${if (count != 1) "s" else ""}"

        holder.itemView.setOnClickListener { onPlaylistClick(playlist) }
        holder.renameButton.setOnClickListener { onRenameClick(playlist) }
        holder.deleteButton.setOnClickListener { onDeleteClick(playlist) }
    }

    override fun getItemCount(): Int = playlists.size

    fun setPlaylists(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}
