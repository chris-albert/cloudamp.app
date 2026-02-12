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
    data class HeaderItem(val title: String) : JellyfinLibraryItem()

    data class ArtistItem(
        val item: JellyfinItem,
        var isExpanded: Boolean = false,
        var albums: List<JellyfinItem> = emptyList(),
        var isLoadingAlbums: Boolean = false
    ) : JellyfinLibraryItem()

    data class AlbumItem(
        val item: JellyfinItem,
        val parentArtistId: String,
        var isExpanded: Boolean = false,
        var tracks: List<JellyfinItem> = emptyList(),
        var isLoadingTracks: Boolean = false
    ) : JellyfinLibraryItem()

    data class TrackItem(
        val item: JellyfinItem,
        val parentAlbumId: String
    ) : JellyfinLibraryItem()

    data class PlaylistItem(val item: JellyfinItem) : JellyfinLibraryItem()

    data class FooterItem(val parentId: String) : JellyfinLibraryItem()
}

class JellyfinLibraryAdapter(
    private val serverUrl: String,
    private val onArtistExpand: (JellyfinItem, Int) -> Unit,
    private val onAlbumExpand: (JellyfinItem, String, Int) -> Unit,
    private val onTrackClick: (JellyfinItem, List<JellyfinItem>, Int) -> Unit,
    private val onPlaylistClick: (JellyfinItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<JellyfinLibraryItem>()
    private var allArtists: List<JellyfinItem> = emptyList()
    private var allPlaylists: List<JellyfinItem> = emptyList()
    private var currentFilter: String = ""

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ARTIST = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_TRACK = 3
        private const val TYPE_PLAYLIST = 4
        private const val TYPE_FOOTER = 5
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is JellyfinLibraryItem.HeaderItem -> TYPE_HEADER
            is JellyfinLibraryItem.ArtistItem -> TYPE_ARTIST
            is JellyfinLibraryItem.AlbumItem -> TYPE_ALBUM
            is JellyfinLibraryItem.TrackItem -> TYPE_TRACK
            is JellyfinLibraryItem.PlaylistItem -> TYPE_PLAYLIST
            is JellyfinLibraryItem.FooterItem -> TYPE_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_ARTIST -> ArtistViewHolder(inflater.inflate(R.layout.item_artist, parent, false))
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_album, parent, false))
            TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_track, parent, false))
            TYPE_PLAYLIST -> PlaylistViewHolder(inflater.inflate(R.layout.item_gdrive_folder, parent, false))
            TYPE_FOOTER -> FooterViewHolder(inflater.inflate(R.layout.item_section_footer, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is JellyfinLibraryItem.HeaderItem -> (holder as HeaderViewHolder).bind(item)
            is JellyfinLibraryItem.ArtistItem -> (holder as ArtistViewHolder).bind(item)
            is JellyfinLibraryItem.AlbumItem -> (holder as AlbumViewHolder).bind(item)
            is JellyfinLibraryItem.TrackItem -> (holder as TrackViewHolder).bind(item)
            is JellyfinLibraryItem.PlaylistItem -> (holder as PlaylistViewHolder).bind(item)
            is JellyfinLibraryItem.FooterItem -> { /* Footer is just a visual separator */ }
        }
    }

    override fun getItemCount() = items.size

    fun getLetterPosition(letter: String): Int {
        return items.indexOfFirst { it is JellyfinLibraryItem.HeaderItem && it.title.startsWith(letter) }
    }

    fun getArtistCount(): Int {
        return items.count { it is JellyfinLibraryItem.ArtistItem }
    }

    fun getArtistPosition(artistIndex: Int): Int {
        var count = 0
        for (i in items.indices) {
            if (items[i] is JellyfinLibraryItem.ArtistItem) {
                if (count == artistIndex) return i
                count++
            }
        }
        return -1
    }

    fun setArtists(artists: List<JellyfinItem>, playlists: List<JellyfinItem>) {
        allArtists = artists
        allPlaylists = playlists
        currentFilter = ""
        rebuildItems(artists, playlists)
    }

    private fun rebuildItems(artists: List<JellyfinItem>, playlists: List<JellyfinItem>) {
        items.clear()

        // Sort artists purely alphabetically by display name and add section headers
        val sortedArtists = artists.sortedBy { it.Name.lowercase() }

        // Group by first letter to get counts
        val grouped = sortedArtists.groupBy { artist ->
            val firstLetter = artist.Name.firstOrNull()?.uppercaseChar() ?: '#'
            if (firstLetter.isLetter()) firstLetter else '#'
        }

        var currentLetter: Char? = null
        for (artist in sortedArtists) {
            val firstLetter = artist.Name.firstOrNull()?.uppercaseChar() ?: '#'
            val letter = if (firstLetter.isLetter()) firstLetter else '#'

            if (letter != currentLetter) {
                currentLetter = letter
                val count = grouped[letter]?.size ?: 0
                items.add(JellyfinLibraryItem.HeaderItem("$letter ($count)"))
            }
            items.add(JellyfinLibraryItem.ArtistItem(artist))
        }

        // Add playlists under a "PLAYLISTS" header
        if (playlists.isNotEmpty()) {
            items.add(JellyfinLibraryItem.HeaderItem("PLAYLISTS"))
            items.addAll(playlists.map { JellyfinLibraryItem.PlaylistItem(it) })
        }

        notifyDataSetChanged()
    }

    fun filterItems(query: String) {
        currentFilter = query
        if (query.isEmpty()) {
            rebuildItems(allArtists, allPlaylists)
            return
        }
        val filteredArtists = allArtists.filter { fuzzyMatch(it.Name, query) }
        val filteredPlaylists = allPlaylists.filter { fuzzyMatch(it.Name, query) }
        rebuildItems(filteredArtists, filteredPlaylists)
    }

    fun clearFilter() {
        currentFilter = ""
        rebuildItems(allArtists, allPlaylists)
    }

    fun setPlaylistTracks(trackItems: List<JellyfinLibraryItem>) {
        items.clear()
        items.addAll(trackItems)
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

    fun toggleArtist(position: Int) {
        val item = items[position] as? JellyfinLibraryItem.ArtistItem ?: return

        if (item.isExpanded) {
            // Collapse: remove albums, their tracks, headers, and footers
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is JellyfinLibraryItem.HeaderItem ||
                it is JellyfinLibraryItem.AlbumItem && it.parentArtistId == item.item.Id ||
                it is JellyfinLibraryItem.TrackItem ||
                it is JellyfinLibraryItem.FooterItem
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
            onArtistExpand(item.item, position)
        }
    }

    fun getArtistsWithoutImages(): List<JellyfinItem> {
        return items.filterIsInstance<JellyfinLibraryItem.ArtistItem>()
            .filter { !it.item.hasPrimaryImage() && it.albums.isEmpty() }
            .map { it.item }
    }

    fun preloadArtistAlbums(artistId: String, albums: List<JellyfinItem>) {
        val index = items.indexOfFirst { it is JellyfinLibraryItem.ArtistItem && it.item.Id == artistId }
        if (index >= 0) {
            val item = items[index] as JellyfinLibraryItem.ArtistItem
            item.albums = albums
            notifyItemChanged(index)
        }
    }

    fun setArtistAlbums(position: Int, albums: List<JellyfinItem>) {
        val item = items[position] as? JellyfinLibraryItem.ArtistItem ?: return
        item.albums = albums
        item.isLoadingAlbums = false

        if (item.isExpanded && albums.isNotEmpty()) {
            val itemsToAdd = mutableListOf<JellyfinLibraryItem>()

            itemsToAdd.add(JellyfinLibraryItem.HeaderItem("ALBUMS (${albums.size})"))
            itemsToAdd.addAll(albums.map { JellyfinLibraryItem.AlbumItem(it, item.item.Id) })
            itemsToAdd.add(JellyfinLibraryItem.FooterItem(item.item.Id))

            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
    }

    fun toggleAlbum(position: Int) {
        val item = items[position] as? JellyfinLibraryItem.AlbumItem ?: return

        if (item.isExpanded) {
            // Collapse: remove header, tracks, and footer
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is JellyfinLibraryItem.HeaderItem ||
                it is JellyfinLibraryItem.TrackItem && it.parentAlbumId == item.item.Id ||
                it is JellyfinLibraryItem.FooterItem && it.parentId == item.item.Id
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
            onAlbumExpand(item.item, item.parentArtistId, position)
        }
    }

    fun setAlbumTracks(position: Int, tracks: List<JellyfinItem>) {
        val item = items[position] as? JellyfinLibraryItem.AlbumItem ?: return
        item.tracks = tracks
        item.isLoadingTracks = false

        if (item.isExpanded && tracks.isNotEmpty()) {
            val itemsToAdd = mutableListOf<JellyfinLibraryItem>()
            itemsToAdd.add(JellyfinLibraryItem.HeaderItem("TRACKS (${tracks.size})"))
            itemsToAdd.addAll(tracks.map { JellyfinLibraryItem.TrackItem(it, item.item.Id) })
            itemsToAdd.add(JellyfinLibraryItem.FooterItem(item.item.Id))
            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    // ── ViewHolders ─────────────────────────────────────────────────────

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerTextView: TextView = itemView.findViewById(R.id.sectionHeaderTextView)

        fun bind(item: JellyfinLibraryItem.HeaderItem) {
            headerTextView.text = item.title
        }
    }

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.artistNameTextView)
        private val subtitleTextView: TextView = itemView.findViewById(R.id.artistSubtitleTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.artistImageView)
        private val letterAvatar: TextView = itemView.findViewById(R.id.artistLetterAvatar)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: JellyfinLibraryItem.ArtistItem) {
            nameTextView.text = item.item.Name
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            // Show album count when albums have been loaded
            if (item.albums.isNotEmpty()) {
                val count = item.albums.size
                subtitleTextView.text = "$count Album${if (count != 1) "s" else ""}"
                subtitleTextView.visibility = View.VISIBLE
            } else {
                subtitleTextView.visibility = View.GONE
            }

            // Fallback chain: artist image → latest album art → letter avatar
            val artistImageUrl = item.item.getPrimaryImageUrl(serverUrl)
            val albumImageUrl = if (artistImageUrl == null) {
                item.albums
                    .sortedByDescending { it.Year ?: 0 }
                    .firstNotNullOfOrNull { it.getPrimaryImageUrl(serverUrl) }
            } else null
            val displayImageUrl = artistImageUrl ?: albumImageUrl

            if (displayImageUrl != null) {
                Glide.with(itemView.context).load(displayImageUrl).into(imageView)
                letterAvatar.visibility = View.GONE
            } else {
                Glide.with(itemView.context).clear(imageView)
                imageView.setImageDrawable(null)
                letterAvatar.text = item.item.Name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                letterAvatar.visibility = View.VISIBLE
            }

            itemView.setOnClickListener {
                toggleArtist(bindingAdapterPosition)
            }
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
            releaseDateTextView.text = item.item.Year?.toString() ?: ""
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            // Display track count and total duration
            val trackLabel = if (item.item.ChildCount != null) {
                val count = item.item.ChildCount
                "$count Track${if (count != 1) "s" else ""}"
            } else ""

            val durationMs = item.item.getDurationMs()
            if (durationMs > 0) {
                artistTextView.text = "$trackLabel · ${formatDuration(durationMs)}"
            } else if (item.tracks.isNotEmpty()) {
                val totalMs = item.tracks.sumOf { it.getDurationMs() }
                artistTextView.text = "$trackLabel · ${formatDuration(totalMs)}"
            } else {
                artistTextView.text = trackLabel
            }
            trackCountTextView.visibility = View.GONE

            val imageUrl = item.item.getPrimaryImageUrl(serverUrl)
            if (imageUrl != null) {
                Glide.with(itemView.context).load(imageUrl).into(imageView)
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

        fun bind(item: JellyfinLibraryItem.TrackItem) {
            nameTextView.text = item.item.Name
            artistTextView.text = item.item.getArtistDisplay()
            durationTextView.text = formatDuration(item.item.getDurationMs())
            numberTextView.text = item.item.TrackNumber?.toString() ?: ""

            itemView.setOnClickListener {
                // Find all tracks from the same album
                val albumTracks = mutableListOf<JellyfinItem>()
                var trackPosition = 0

                for (i in items.indices) {
                    val currentItem = items[i]
                    if (currentItem is JellyfinLibraryItem.TrackItem && currentItem.parentAlbumId == item.parentAlbumId) {
                        albumTracks.add(currentItem.item)
                        if (currentItem.item.Id == item.item.Id) {
                            trackPosition = albumTracks.size - 1
                        }
                    }
                }

                onTrackClick(item.item, albumTracks, trackPosition)
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

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
