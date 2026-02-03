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

sealed class JellyfinLibraryItem {
    data class ArtistItem(val item: JellyfinItem) : JellyfinLibraryItem()
    data class AlbumItem(val item: JellyfinItem) : JellyfinLibraryItem()
    data class TrackItem(val item: JellyfinItem) : JellyfinLibraryItem()
    data class PlaylistItem(val item: JellyfinItem) : JellyfinLibraryItem()
    object BackItem : JellyfinLibraryItem()
}

class JellyfinLibraryAdapter(
    private val serverUrl: String,
    private val onArtistClick: (JellyfinItem) -> Unit,
    private val onAlbumClick: (JellyfinItem) -> Unit,
    private val onTrackClick: (JellyfinItem, List<JellyfinItem>, Int) -> Unit,
    private val onPlaylistClick: (JellyfinItem) -> Unit,
    private val onBackClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<JellyfinLibraryItem>()
    private var allItems: List<JellyfinLibraryItem> = emptyList()
    private var currentFilter: String = ""

    companion object {
        private const val TYPE_BACK = 0
        private const val TYPE_ARTIST = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_TRACK = 3
        private const val TYPE_PLAYLIST = 4
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is JellyfinLibraryItem.BackItem -> TYPE_BACK
            is JellyfinLibraryItem.ArtistItem -> TYPE_ARTIST
            is JellyfinLibraryItem.AlbumItem -> TYPE_ALBUM
            is JellyfinLibraryItem.TrackItem -> TYPE_TRACK
            is JellyfinLibraryItem.PlaylistItem -> TYPE_PLAYLIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BACK -> BackViewHolder(inflater.inflate(R.layout.item_gdrive_folder, parent, false))
            TYPE_ARTIST -> ArtistViewHolder(inflater.inflate(R.layout.item_artist, parent, false))
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_album, parent, false))
            TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_track, parent, false))
            TYPE_PLAYLIST -> PlaylistViewHolder(inflater.inflate(R.layout.item_gdrive_folder, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is JellyfinLibraryItem.BackItem -> (holder as BackViewHolder).bind()
            is JellyfinLibraryItem.ArtistItem -> (holder as ArtistViewHolder).bind(item)
            is JellyfinLibraryItem.AlbumItem -> (holder as AlbumViewHolder).bind(item)
            is JellyfinLibraryItem.TrackItem -> (holder as TrackViewHolder).bind(item)
            is JellyfinLibraryItem.PlaylistItem -> (holder as PlaylistViewHolder).bind(item)
        }
    }

    override fun getItemCount() = items.size

    fun setItems(newItems: List<JellyfinLibraryItem>) {
        allItems = newItems
        currentFilter = ""
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun filterItems(query: String) {
        currentFilter = query
        if (query.isEmpty()) {
            items.clear()
            items.addAll(allItems)
        } else {
            items.clear()
            items.addAll(allItems.filter { item ->
                when (item) {
                    is JellyfinLibraryItem.ArtistItem -> fuzzyMatch(item.item.Name, query)
                    is JellyfinLibraryItem.AlbumItem -> fuzzyMatch(item.item.Name, query)
                    is JellyfinLibraryItem.TrackItem -> fuzzyMatch(item.item.Name, query)
                    is JellyfinLibraryItem.PlaylistItem -> fuzzyMatch(item.item.Name, query)
                    is JellyfinLibraryItem.BackItem -> true
                }
            })
        }
        notifyDataSetChanged()
    }

    fun clearFilter() {
        currentFilter = ""
        items.clear()
        items.addAll(allItems)
        notifyDataSetChanged()
    }

    private fun fuzzyMatch(text: String, query: String): Boolean {
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        if (lowerText.contains(lowerQuery)) return true
        var textIndex = 0
        for (queryChar in lowerQuery) {
            val found = lowerText.indexOf(queryChar, textIndex)
            if (found == -1) return false
            textIndex = found + 1
        }
        return true
    }

    private fun getAllTrackItems(): List<JellyfinItem> {
        return items.filterIsInstance<JellyfinLibraryItem.TrackItem>().map { it.item }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    inner class BackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.folderNameTextView)

        fun bind() {
            nameTextView.text = ".. (Back)"
            itemView.setOnClickListener { onBackClick() }
        }
    }

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.artistNameTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.artistImageView)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: JellyfinLibraryItem.ArtistItem) {
            nameTextView.text = item.item.Name
            expandIcon.text = "▶"

            val imageUrl = item.item.getPrimaryImageUrl(serverUrl)
            if (imageUrl != null) {
                Glide.with(itemView.context).load(imageUrl).into(imageView)
            } else {
                imageView.setImageResource(R.drawable.ic_artist_placeholder)
            }

            itemView.setOnClickListener { onArtistClick(item.item) }
        }
    }

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.albumNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.albumArtistTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.albumImageView)
        private val releaseDateTextView: TextView = itemView.findViewById(R.id.albumReleaseDateTextView)
        private val trackCountTextView: TextView = itemView.findViewById(R.id.albumTrackCountTextView)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: JellyfinLibraryItem.AlbumItem) {
            nameTextView.text = item.item.Name
            artistTextView.text = item.item.getArtistDisplay()
            releaseDateTextView.text = item.item.Year?.toString() ?: ""
            trackCountTextView.text = if (item.item.ChildCount != null) "${item.item.ChildCount} tracks" else ""
            expandIcon.text = "▶"

            val imageUrl = item.item.getPrimaryImageUrl(serverUrl)
            if (imageUrl != null) {
                Glide.with(itemView.context).load(imageUrl).into(imageView)
            } else {
                imageView.setImageResource(R.drawable.ic_album_placeholder)
            }

            itemView.setOnClickListener { onAlbumClick(item.item) }
        }
    }

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.trackNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.trackArtistTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.trackDurationTextView)
        private val numberTextView: TextView = itemView.findViewById(R.id.trackNumber)

        fun bind(item: JellyfinLibraryItem.TrackItem) {
            nameTextView.text = item.item.Name
            artistTextView.text = item.item.getArtistDisplay()
            durationTextView.text = formatDuration(item.item.getDurationMs())
            numberTextView.text = item.item.TrackNumber?.toString() ?: ""

            itemView.setOnClickListener {
                val allTracks = getAllTrackItems()
                val position = allTracks.indexOf(item.item)
                onTrackClick(item.item, allTracks, position)
            }
        }
    }

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.folderNameTextView)

        fun bind(item: JellyfinLibraryItem.PlaylistItem) {
            val countStr = if (item.item.ChildCount != null) " (${item.item.ChildCount} tracks)" else ""
            nameTextView.text = "${item.item.Name}$countStr"
            itemView.setOnClickListener { onPlaylistClick(item.item) }
        }
    }
}
