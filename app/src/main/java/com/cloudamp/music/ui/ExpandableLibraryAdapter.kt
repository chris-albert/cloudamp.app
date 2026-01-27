package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cloudamp.music.R
import com.cloudamp.music.models.Album
import com.cloudamp.music.models.Artist
import com.cloudamp.music.models.Track

sealed class LibraryItem {
    data class ArtistItem(
        val artist: Artist,
        var isExpanded: Boolean = false,
        var albums: List<Album> = emptyList(),
        var isLoadingAlbums: Boolean = false
    ) : LibraryItem()

    data class HeaderItem(
        val title: String
    ) : LibraryItem()

    data class AlbumItem(
        val album: Album,
        val parentArtistId: String,
        var isExpanded: Boolean = false,
        var tracks: List<Track> = emptyList(),
        var isLoadingTracks: Boolean = false
    ) : LibraryItem()

    data class TrackItem(
        val track: Track,
        val parentAlbumId: String
    ) : LibraryItem()

    data class FooterItem(
        val parentId: String
    ) : LibraryItem()
}

class ExpandableLibraryAdapter(
    private val onArtistClick: (Artist, Int) -> Unit,
    private val onAlbumClick: (Album, String, Int) -> Unit,
    private val onTrackClick: (Track, List<Track>, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<LibraryItem>()
    private val savedAlbumIds = mutableSetOf<String>()

    fun setSavedAlbumIds(ids: Set<String>) {
        savedAlbumIds.clear()
        savedAlbumIds.addAll(ids)
    }

    companion object {
        private const val TYPE_ARTIST = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_TRACK = 3
        private const val TYPE_FOOTER = 4
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is LibraryItem.ArtistItem -> TYPE_ARTIST
            is LibraryItem.HeaderItem -> TYPE_HEADER
            is LibraryItem.AlbumItem -> TYPE_ALBUM
            is LibraryItem.TrackItem -> TYPE_TRACK
            is LibraryItem.FooterItem -> TYPE_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ARTIST -> ArtistViewHolder(inflater.inflate(R.layout.item_artist, parent, false))
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_album, parent, false))
            TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_track, parent, false))
            TYPE_FOOTER -> FooterViewHolder(inflater.inflate(R.layout.item_section_footer, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is LibraryItem.ArtistItem -> {
                (holder as ArtistViewHolder).bind(item)
            }
            is LibraryItem.HeaderItem -> {
                (holder as HeaderViewHolder).bind(item)
            }
            is LibraryItem.AlbumItem -> {
                (holder as AlbumViewHolder).bind(item)
            }
            is LibraryItem.TrackItem -> {
                (holder as TrackViewHolder).bind(item)
            }
            is LibraryItem.FooterItem -> {
                // Footer doesn't need binding - it's just a visual separator
            }
        }
    }

    override fun getItemCount() = items.size

    fun setArtists(artists: List<Artist>) {
        items.clear()

        // Group artists by first letter and add section headers
        var currentLetter: Char? = null
        for (artist in artists) {
            val firstLetter = artist.name.firstOrNull()?.uppercaseChar() ?: '#'
            val letter = if (firstLetter.isLetter()) firstLetter else '#'

            if (letter != currentLetter) {
                currentLetter = letter
                items.add(LibraryItem.HeaderItem(letter.toString()))
            }
            items.add(LibraryItem.ArtistItem(artist))
        }

        notifyDataSetChanged()
    }

    fun toggleArtist(position: Int) {
        val item = items[position] as? LibraryItem.ArtistItem ?: return

        if (item.isExpanded) {
            // Collapse: remove header, albums, their tracks, and footers
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is LibraryItem.HeaderItem ||
                it is LibraryItem.AlbumItem && it.parentArtistId == item.artist.id ||
                it is LibraryItem.TrackItem ||
                it is LibraryItem.FooterItem
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
            onArtistClick(item.artist, position)
        }
    }

    fun setArtistAlbums(position: Int, albums: List<Album>) {
        val item = items[position] as? LibraryItem.ArtistItem ?: return
        item.albums = albums
        item.isLoadingAlbums = false

        if (item.isExpanded && albums.isNotEmpty()) {
            val itemsToAdd = mutableListOf<LibraryItem>()

            // Group albums into categories
            val savedAlbums = mutableListOf<Album>()
            val lps = mutableListOf<Album>()
            val eps = mutableListOf<Album>()
            val singles = mutableListOf<Album>()

            for (album in albums) {
                when {
                    savedAlbumIds.contains(album.id) -> savedAlbums.add(album)
                    album.getAlbumCategory() == "single" -> singles.add(album)
                    album.getAlbumCategory() == "ep" -> eps.add(album)
                    else -> lps.add(album)
                }
            }

            // Sort each group by release date (newest first)
            val sortByReleaseDate: (Album) -> String = { it.releaseDate ?: "" }

            // Add Saved Albums section
            if (savedAlbums.isNotEmpty()) {
                itemsToAdd.add(LibraryItem.HeaderItem("♥ SAVED ALBUMS"))
                itemsToAdd.addAll(savedAlbums.sortedByDescending(sortByReleaseDate)
                    .map { LibraryItem.AlbumItem(it, item.artist.id) })
            }

            // Add LP's section
            if (lps.isNotEmpty()) {
                itemsToAdd.add(LibraryItem.HeaderItem("▶ LP's"))
                itemsToAdd.addAll(lps.sortedByDescending(sortByReleaseDate)
                    .map { LibraryItem.AlbumItem(it, item.artist.id) })
            }

            // Add EP's section
            if (eps.isNotEmpty()) {
                itemsToAdd.add(LibraryItem.HeaderItem("▶ EP's"))
                itemsToAdd.addAll(eps.sortedByDescending(sortByReleaseDate)
                    .map { LibraryItem.AlbumItem(it, item.artist.id) })
            }

            // Add Singles section
            if (singles.isNotEmpty()) {
                itemsToAdd.add(LibraryItem.HeaderItem("▶ SINGLES"))
                itemsToAdd.addAll(singles.sortedByDescending(sortByReleaseDate)
                    .map { LibraryItem.AlbumItem(it, item.artist.id) })
            }

            // Add footer at the end
            if (itemsToAdd.isNotEmpty()) {
                itemsToAdd.add(LibraryItem.FooterItem(item.artist.id))
            }

            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
    }

    fun toggleAlbum(position: Int) {
        val item = items[position] as? LibraryItem.AlbumItem ?: return

        if (item.isExpanded) {
            // Collapse: remove header, tracks, and footer
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is LibraryItem.HeaderItem ||
                it is LibraryItem.TrackItem && it.parentAlbumId == item.album.id ||
                it is LibraryItem.FooterItem && it.parentId == item.album.id
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
            onAlbumClick(item.album, item.parentArtistId, position)
        }
    }

    fun setAlbumTracks(position: Int, tracks: List<Track>) {
        val item = items[position] as? LibraryItem.AlbumItem ?: return
        item.tracks = tracks
        item.isLoadingTracks = false

        if (item.isExpanded && tracks.isNotEmpty()) {
            val itemsToAdd = mutableListOf<LibraryItem>()
            itemsToAdd.add(LibraryItem.HeaderItem("▶ TRACKS"))
            itemsToAdd.addAll(tracks.map { LibraryItem.TrackItem(it, item.album.id) })
            itemsToAdd.add(LibraryItem.FooterItem(item.album.id))
            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
    }

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.artistNameTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.artistImageView)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: LibraryItem.ArtistItem) {
            nameTextView.text = item.artist.name
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            item.artist.images?.firstOrNull()?.url?.let { imageUrl ->
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .into(imageView)
            }

            itemView.setOnClickListener {
                toggleArtist(bindingAdapterPosition)
            }
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerTextView: TextView = itemView.findViewById(R.id.sectionHeaderTextView)

        fun bind(item: LibraryItem.HeaderItem) {
            headerTextView.text = item.title
        }
    }

    inner class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.albumNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.albumArtistTextView)
        private val releaseDateTextView: TextView = itemView.findViewById(R.id.albumReleaseDateTextView)
        private val trackCountTextView: TextView = itemView.findViewById(R.id.albumTrackCountTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.albumImageView)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: LibraryItem.AlbumItem) {
            nameTextView.text = item.album.name
            artistTextView.text = item.album.artists.joinToString(", ") { it.name }
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            // Format release date (extract year from YYYY-MM-DD or YYYY format)
            releaseDateTextView.text = item.album.releaseDate?.let { date ->
                if (date.length >= 4) date.substring(0, 4) else date
            } ?: ""

            // Display track count
            trackCountTextView.text = item.album.totalTracks?.let { count ->
                "$count Track${if (count != 1) "s" else ""}"
            } ?: ""

            item.album.images?.firstOrNull()?.url?.let { imageUrl ->
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .into(imageView)
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

        fun bind(item: LibraryItem.TrackItem) {
            nameTextView.text = item.track.name
            artistTextView.text = item.track.artists.joinToString(", ") { it.name }

            val minutes = item.track.durationMs / 60000
            val seconds = (item.track.durationMs % 60000) / 1000
            durationTextView.text = String.format("%d:%02d", minutes, seconds)

            itemView.setOnClickListener {
                // Find all tracks from the same album
                val albumTracks = mutableListOf<Track>()
                var trackPosition = 0

                for (i in items.indices) {
                    val currentItem = items[i]
                    if (currentItem is LibraryItem.TrackItem && currentItem.parentAlbumId == item.parentAlbumId) {
                        albumTracks.add(currentItem.track)
                        if (currentItem.track.id == item.track.id) {
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
