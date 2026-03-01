package com.cloudamp.music.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cloudamp.music.R
import com.cloudamp.music.api.JellyfinItem

sealed class JellyfinRecentAlbumItem {
    data class AlbumHeader(
        val album: JellyfinItem,
        val lastPlayedTrack: String? = null,
        var isExpanded: Boolean = false,
        var tracks: List<JellyfinItem> = emptyList(),
        var isLoadingTracks: Boolean = false
    ) : JellyfinRecentAlbumItem()

    data class TrackItem(
        val track: JellyfinItem,
        val parentAlbumId: String,
        val trackIndex: Int,
        val isLastPlayed: Boolean = false
    ) : JellyfinRecentAlbumItem()

    data class FooterItem(
        val parentAlbumId: String
    ) : JellyfinRecentAlbumItem()
}

class JellyfinRecentAlbumsAdapter(
    private val serverUrl: String,
    private val apiKey: String? = null,
    private val onAlbumClick: (JellyfinItem, Int) -> Unit,
    private val onTrackClick: (JellyfinItem, List<JellyfinItem>, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<JellyfinRecentAlbumItem>()

    companion object {
        private const val TYPE_ALBUM = 0
        private const val TYPE_TRACK = 1
        private const val TYPE_FOOTER = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is JellyfinRecentAlbumItem.AlbumHeader -> TYPE_ALBUM
            is JellyfinRecentAlbumItem.TrackItem -> TYPE_TRACK
            is JellyfinRecentAlbumItem.FooterItem -> TYPE_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_playlist, parent, false))
            TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_track, parent, false))
            TYPE_FOOTER -> FooterViewHolder(inflater.inflate(R.layout.item_section_footer, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is JellyfinRecentAlbumItem.AlbumHeader -> (holder as AlbumViewHolder).bind(item)
            is JellyfinRecentAlbumItem.TrackItem -> (holder as TrackViewHolder).bind(item)
            is JellyfinRecentAlbumItem.FooterItem -> { }
        }
    }

    override fun getItemCount() = items.size

    fun setAlbums(albums: List<JellyfinItem>, lastPlayedTracks: Map<String, String> = emptyMap()) {
        items.clear()
        items.addAll(albums.map {
            JellyfinRecentAlbumItem.AlbumHeader(it, lastPlayedTrack = lastPlayedTracks[it.Id])
        })
        notifyDataSetChanged()
    }

    fun toggleAlbum(position: Int) {
        val item = items[position] as? JellyfinRecentAlbumItem.AlbumHeader ?: return

        if (item.isExpanded) {
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is JellyfinRecentAlbumItem.TrackItem && it.parentAlbumId == item.album.Id ||
                it is JellyfinRecentAlbumItem.FooterItem && it.parentAlbumId == item.album.Id
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
            onAlbumClick(item.album, position)
        }
    }

    /**
     * @return the adapter position of the last-played track, or -1 if none
     */
    fun setAlbumTracks(position: Int, tracks: List<JellyfinItem>): Int {
        val item = items[position] as? JellyfinRecentAlbumItem.AlbumHeader ?: return -1
        item.tracks = tracks
        item.isLoadingTracks = false

        var lastPlayedAdapterPos = -1
        if (item.isExpanded && tracks.isNotEmpty()) {
            val itemsToAdd = mutableListOf<JellyfinRecentAlbumItem>()
            itemsToAdd.addAll(tracks.mapIndexed { index, track ->
                val isLast = item.lastPlayedTrack != null && track.Name == item.lastPlayedTrack
                if (isLast) lastPlayedAdapterPos = position + 1 + index
                JellyfinRecentAlbumItem.TrackItem(track, item.album.Id, index, isLast)
            })
            itemsToAdd.add(JellyfinRecentAlbumItem.FooterItem(item.album.Id))
            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
        return lastPlayedAdapterPos
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.playlistNameTextView)
        private val subtitleTextView: TextView = itemView.findViewById(R.id.playlistTracksTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.playlistImageView)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: JellyfinRecentAlbumItem.AlbumHeader) {
            nameTextView.text = item.album.Name
            val artist = item.album.AlbumArtist ?: ""
            val year = item.album.Year?.toString()
            val parts = mutableListOf<String>()
            if (artist.isNotEmpty()) parts.add(artist)
            if (year != null) parts.add(year)
            if (item.lastPlayedTrack != null) parts.add(item.lastPlayedTrack)
            subtitleTextView.text = parts.joinToString(" \u00b7 ")
            expandIcon.text = if (item.isExpanded) "\u25bc" else "\u25b6"

            val imageUrl = item.album.getPrimaryImageUrl(serverUrl, apiKey)
            if (imageUrl != null) {
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(imageView)
            } else {
                Glide.with(itemView.context).clear(imageView)
                imageView.setImageResource(R.drawable.ic_album_placeholder)
            }

            itemView.setOnClickListener {
                toggleAlbum(bindingAdapterPosition)
            }
        }
    }

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val trackNumberTextView: TextView = itemView.findViewById(R.id.trackNumber)
        private val nameTextView: TextView = itemView.findViewById(R.id.trackNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.trackArtistTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.trackDurationTextView)

        fun bind(item: JellyfinRecentAlbumItem.TrackItem) {
            if (item.isLastPlayed) {
                trackNumberTextView.text = "\u25b6"
                trackNumberTextView.setTextColor(Color.parseColor("#FFFF00"))
                nameTextView.setTextColor(Color.parseColor("#FFFF00"))
            } else {
                trackNumberTextView.text = (item.trackIndex + 1).toString()
                trackNumberTextView.setTextColor(itemView.context.getColor(R.color.winamp_display_text))
                nameTextView.setTextColor(itemView.context.getColor(R.color.winamp_text))
            }
            nameTextView.text = item.track.Name
            artistTextView.text = item.track.getArtistDisplay()
            durationTextView.text = formatDuration(item.track.getDurationMs())

            itemView.setOnClickListener {
                val albumTracks = mutableListOf<JellyfinItem>()
                var trackPosition = 0

                for (i in items.indices) {
                    val currentItem = items[i]
                    if (currentItem is JellyfinRecentAlbumItem.TrackItem && currentItem.parentAlbumId == item.parentAlbumId) {
                        albumTracks.add(currentItem.track)
                        if (currentItem.track.Id == item.track.Id) {
                            trackPosition = albumTracks.size - 1
                        }
                    }
                }

                onTrackClick(item.track, albumTracks, trackPosition)
            }
        }
    }

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
