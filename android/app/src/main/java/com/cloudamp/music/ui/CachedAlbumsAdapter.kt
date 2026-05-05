package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cloudamp.music.R
import com.cloudamp.music.api.GDriveAlbum
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.playback.GDriveImageProvider

sealed class CachedAlbumItem {
    data class AlbumItem(
        val album: GDriveAlbum,
        var isExpanded: Boolean = false,
        var tracks: List<GDriveTrack> = emptyList()
    ) : CachedAlbumItem()

    data class TrackItem(
        val track: GDriveTrack,
        val parentAlbumId: String
    ) : CachedAlbumItem()
}

class CachedAlbumsAdapter(
    private val onAlbumExpand: (GDriveAlbum, Int) -> Unit,
    private val onTrackClick: (GDriveTrack, List<GDriveTrack>, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<CachedAlbumItem>()

    companion object {
        private const val TYPE_ALBUM = 0
        private const val TYPE_TRACK = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is CachedAlbumItem.AlbumItem -> TYPE_ALBUM
            is CachedAlbumItem.TrackItem -> TYPE_TRACK
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_album, parent, false))
            TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_track, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is CachedAlbumItem.AlbumItem -> (holder as AlbumViewHolder).bind(item)
            is CachedAlbumItem.TrackItem -> (holder as TrackViewHolder).bind(item)
        }
    }

    override fun getItemCount() = items.size

    fun setAlbums(albums: List<GDriveAlbum>) {
        items.clear()
        items.addAll(albums.map { CachedAlbumItem.AlbumItem(it) })
        notifyDataSetChanged()
    }

    fun toggleAlbum(position: Int) {
        val item = items[position] as? CachedAlbumItem.AlbumItem ?: return

        if (item.isExpanded) {
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is CachedAlbumItem.TrackItem && it.parentAlbumId == item.album.id
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
            onAlbumExpand(item.album, position)
        }
    }

    fun setAlbumTracks(position: Int, tracks: List<GDriveTrack>) {
        val item = items[position] as? CachedAlbumItem.AlbumItem ?: return
        item.tracks = tracks

        if (item.isExpanded && tracks.isNotEmpty()) {
            val trackItems = tracks.map { CachedAlbumItem.TrackItem(it, item.album.id) }
            items.addAll(position + 1, trackItems)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, trackItems.size)
        }
    }

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.albumNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.albumArtistTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.albumImageView)
        private val releaseDateTextView: TextView = itemView.findViewById(R.id.albumReleaseDateTextView)
        private val trackCountTextView: TextView = itemView.findViewById(R.id.albumTrackCountTextView)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: CachedAlbumItem.AlbumItem) {
            nameTextView.text = item.album.name
            artistTextView.text = item.album.artistName
            releaseDateTextView.text = item.album.year?.toString() ?: ""
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            val trackCount = if (item.tracks.isNotEmpty()) item.tracks.size else item.album.trackCount
            trackCountTextView.text = "$trackCount Track${if (trackCount != 1) "s" else ""}"
            trackCountTextView.visibility = View.VISIBLE

            val coverFileId = item.album.coverFileId
            if (coverFileId != null) {
                Glide.with(itemView.context)
                    .load(GDriveImageProvider.buildUri(coverFileId))
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(imageView)
            } else {
                imageView.setImageResource(R.drawable.ic_album_placeholder)
            }

            itemView.setOnClickListener {
                toggleAlbum(bindingAdapterPosition)
            }
        }
    }

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.trackNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.trackArtistTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.trackDurationTextView)
        private val numberTextView: TextView = itemView.findViewById(R.id.trackNumber)

        fun bind(item: CachedAlbumItem.TrackItem) {
            nameTextView.text = item.track.trackName
            artistTextView.text = item.track.artistName
            durationTextView.text = item.track.file.getFileExtension()
            numberTextView.text = item.track.trackNumber?.toString() ?: ""

            itemView.setOnClickListener {
                val albumTracks = mutableListOf<GDriveTrack>()
                var trackPosition = 0

                for (i in items.indices) {
                    val currentItem = items[i]
                    if (currentItem is CachedAlbumItem.TrackItem && currentItem.parentAlbumId == item.parentAlbumId) {
                        albumTracks.add(currentItem.track)
                        if (currentItem.track.file.id == item.track.file.id) {
                            trackPosition = albumTracks.size - 1
                        }
                    }
                }

                onTrackClick(item.track, albumTracks, trackPosition)
            }
        }
    }
}
