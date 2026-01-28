package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cloudamp.music.R
import com.cloudamp.music.api.Playlist
import com.cloudamp.music.models.Track

sealed class PlaylistItem {
    data class PlaylistHeader(
        val playlist: Playlist,
        var isExpanded: Boolean = false,
        var tracks: List<Track> = emptyList(),
        var isLoadingTracks: Boolean = false
    ) : PlaylistItem()

    data class TrackItem(
        val track: Track,
        val parentPlaylistId: String,
        val trackIndex: Int
    ) : PlaylistItem()

    data class FooterItem(
        val parentPlaylistId: String
    ) : PlaylistItem()
}

class PlaylistsAdapter(
    private val onPlaylistClick: (Playlist, Int) -> Unit,
    private val onTrackClick: (Track, List<Track>, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<PlaylistItem>()

    companion object {
        private const val TYPE_PLAYLIST = 0
        private const val TYPE_TRACK = 1
        private const val TYPE_FOOTER = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PlaylistItem.PlaylistHeader -> TYPE_PLAYLIST
            is PlaylistItem.TrackItem -> TYPE_TRACK
            is PlaylistItem.FooterItem -> TYPE_FOOTER
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
            is PlaylistItem.PlaylistHeader -> {
                (holder as PlaylistViewHolder).bind(item)
            }
            is PlaylistItem.TrackItem -> {
                (holder as TrackViewHolder).bind(item)
            }
            is PlaylistItem.FooterItem -> {
                // Footer doesn't need binding
            }
        }
    }

    override fun getItemCount() = items.size

    fun setPlaylists(playlists: List<Playlist>) {
        items.clear()
        items.addAll(playlists.map { PlaylistItem.PlaylistHeader(it) })
        notifyDataSetChanged()
    }

    fun togglePlaylist(position: Int) {
        val item = items[position] as? PlaylistItem.PlaylistHeader ?: return

        if (item.isExpanded) {
            // Collapse: remove tracks and footer
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is PlaylistItem.TrackItem && it.parentPlaylistId == item.playlist.id ||
                it is PlaylistItem.FooterItem && it.parentPlaylistId == item.playlist.id
            }.size
            repeat(itemsToRemove) {
                items.removeAt(position + 1)
            }
            item.isExpanded = false
            notifyItemChanged(position)
            notifyItemRangeRemoved(position + 1, itemsToRemove)
        } else {
            // Expand: trigger loading
            item.isExpanded = true
            notifyItemChanged(position)
            onPlaylistClick(item.playlist, position)
        }
    }

    fun setPlaylistTracks(position: Int, tracks: List<Track>) {
        val item = items[position] as? PlaylistItem.PlaylistHeader ?: return
        item.tracks = tracks
        item.isLoadingTracks = false

        if (item.isExpanded && tracks.isNotEmpty()) {
            val itemsToAdd = mutableListOf<PlaylistItem>()
            itemsToAdd.addAll(tracks.mapIndexed { index, track ->
                PlaylistItem.TrackItem(track, item.playlist.id, index)
            })
            itemsToAdd.add(PlaylistItem.FooterItem(item.playlist.id))
            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
    }

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.playlistNameTextView)
        private val tracksTextView: TextView = itemView.findViewById(R.id.playlistTracksTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.playlistImageView)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: PlaylistItem.PlaylistHeader) {
            nameTextView.text = item.playlist.name
            tracksTextView.text = "${item.playlist.tracks.total} tracks"
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            item.playlist.images?.firstOrNull()?.url?.let { imageUrl ->
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_playlist)
                    .into(imageView)
            } ?: run {
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

        fun bind(item: PlaylistItem.TrackItem) {
            trackNumberTextView.text = (item.trackIndex + 1).toString()
            nameTextView.text = item.track.name
            artistTextView.text = item.track.artists.joinToString(", ") { it.name }

            val minutes = item.track.durationMs / 60000
            val seconds = (item.track.durationMs % 60000) / 1000
            durationTextView.text = String.format("%d:%02d", minutes, seconds)

            itemView.setOnClickListener {
                // Find all tracks from the same playlist
                val playlistTracks = mutableListOf<Track>()
                var trackPosition = 0

                for (i in items.indices) {
                    val currentItem = items[i]
                    if (currentItem is PlaylistItem.TrackItem && currentItem.parentPlaylistId == item.parentPlaylistId) {
                        playlistTracks.add(currentItem.track)
                        if (currentItem.track.id == item.track.id) {
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
