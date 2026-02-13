package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cloudamp.music.R
import com.cloudamp.music.api.JellyfinItem

sealed class JellyfinPlaylistItem {
    data class PlaylistHeader(
        val playlist: JellyfinItem,
        var isExpanded: Boolean = false,
        var tracks: List<JellyfinItem> = emptyList(),
        var isLoadingTracks: Boolean = false
    ) : JellyfinPlaylistItem()

    data class TrackItem(
        val track: JellyfinItem,
        val parentPlaylistId: String,
        val trackIndex: Int
    ) : JellyfinPlaylistItem()

    data class FooterItem(
        val parentPlaylistId: String
    ) : JellyfinPlaylistItem()
}

class JellyfinPlaylistsAdapter(
    private val serverUrl: String,
    private val apiKey: String? = null,
    private val onPlaylistClick: (JellyfinItem, Int) -> Unit,
    private val onTrackClick: (JellyfinItem, List<JellyfinItem>, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<JellyfinPlaylistItem>()

    companion object {
        private const val TYPE_PLAYLIST = 0
        private const val TYPE_TRACK = 1
        private const val TYPE_FOOTER = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is JellyfinPlaylistItem.PlaylistHeader -> TYPE_PLAYLIST
            is JellyfinPlaylistItem.TrackItem -> TYPE_TRACK
            is JellyfinPlaylistItem.FooterItem -> TYPE_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PLAYLIST -> PlaylistViewHolder(inflater.inflate(R.layout.item_playlist, parent, false))
            TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_track, parent, false))
            TYPE_FOOTER -> FooterViewHolder(inflater.inflate(R.layout.item_section_footer, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is JellyfinPlaylistItem.PlaylistHeader -> {
                (holder as PlaylistViewHolder).bind(item)
            }
            is JellyfinPlaylistItem.TrackItem -> {
                (holder as TrackViewHolder).bind(item)
            }
            is JellyfinPlaylistItem.FooterItem -> {
                // Footer doesn't need binding
            }
        }
    }

    override fun getItemCount() = items.size

    fun setPlaylists(playlists: List<JellyfinItem>) {
        items.clear()
        items.addAll(playlists.map { JellyfinPlaylistItem.PlaylistHeader(it) })
        notifyDataSetChanged()
    }

    fun togglePlaylist(position: Int) {
        val item = items[position] as? JellyfinPlaylistItem.PlaylistHeader ?: return

        if (item.isExpanded) {
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is JellyfinPlaylistItem.TrackItem && it.parentPlaylistId == item.playlist.Id ||
                it is JellyfinPlaylistItem.FooterItem && it.parentPlaylistId == item.playlist.Id
            }.size
            repeat(itemsToRemove) {
                items.removeAt(position + 1)
            }
            item.isExpanded = false
            notifyItemChanged(position)
            notifyItemRangeRemoved(position + 1, itemsToRemove)
        } else {
            item.isExpanded = true
            notifyItemChanged(position)
            onPlaylistClick(item.playlist, position)
        }
    }

    fun setPlaylistTracks(position: Int, tracks: List<JellyfinItem>) {
        val item = items[position] as? JellyfinPlaylistItem.PlaylistHeader ?: return
        item.tracks = tracks
        item.isLoadingTracks = false

        if (item.isExpanded && tracks.isNotEmpty()) {
            val itemsToAdd = mutableListOf<JellyfinPlaylistItem>()
            itemsToAdd.addAll(tracks.mapIndexed { index, track ->
                JellyfinPlaylistItem.TrackItem(track, item.playlist.Id, index)
            })
            itemsToAdd.add(JellyfinPlaylistItem.FooterItem(item.playlist.Id))
            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.playlistNameTextView)
        private val tracksTextView: TextView = itemView.findViewById(R.id.playlistTracksTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.playlistImageView)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: JellyfinPlaylistItem.PlaylistHeader) {
            nameTextView.text = item.playlist.Name
            tracksTextView.text = if (item.playlist.ChildCount != null) {
                "${item.playlist.ChildCount} tracks"
            } else ""
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            val imageUrl = item.playlist.getPrimaryImageUrl(serverUrl, apiKey)
            if (imageUrl != null) {
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_playlist)
                    .into(imageView)
            } else {
                Glide.with(itemView.context).clear(imageView)
                imageView.setImageResource(R.drawable.ic_playlist)
            }

            itemView.setOnClickListener {
                togglePlaylist(bindingAdapterPosition)
            }
        }
    }

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val trackNumberTextView: TextView = itemView.findViewById(R.id.trackNumber)
        private val nameTextView: TextView = itemView.findViewById(R.id.trackNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.trackArtistTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.trackDurationTextView)

        fun bind(item: JellyfinPlaylistItem.TrackItem) {
            trackNumberTextView.text = (item.trackIndex + 1).toString()
            nameTextView.text = item.track.Name
            artistTextView.text = item.track.getArtistDisplay()
            durationTextView.text = formatDuration(item.track.getDurationMs())

            itemView.setOnClickListener {
                val playlistTracks = mutableListOf<JellyfinItem>()
                var trackPosition = 0

                for (i in items.indices) {
                    val currentItem = items[i]
                    if (currentItem is JellyfinPlaylistItem.TrackItem && currentItem.parentPlaylistId == item.parentPlaylistId) {
                        playlistTracks.add(currentItem.track)
                        if (currentItem.track.Id == item.track.Id) {
                            trackPosition = playlistTracks.size - 1
                        }
                    }
                }

                onTrackClick(item.track, playlistTracks, trackPosition)
            }
        }
    }

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
