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
import com.cloudamp.music.api.GDriveArtist
import com.cloudamp.music.api.GDriveTrack
import com.cloudamp.music.playback.GDriveImageProvider

sealed class GDriveLibraryItem {
    data class HeaderItem(val title: String) : GDriveLibraryItem()

    data class ArtistItem(
        val artist: GDriveArtist,
        var isExpanded: Boolean = false,
        var albums: List<GDriveAlbum> = emptyList()
    ) : GDriveLibraryItem()

    data class AlbumItem(
        val album: GDriveAlbum,
        var isExpanded: Boolean = false,
        var tracks: List<GDriveTrack> = emptyList()
    ) : GDriveLibraryItem()

    data class TrackItem(
        val track: GDriveTrack,
        val parentAlbumId: String
    ) : GDriveLibraryItem()

    data class FooterItem(val parentId: String) : GDriveLibraryItem()
}

class GDriveStructuredLibraryAdapter(
    private val onArtistExpand: (GDriveArtist, Int) -> Unit,
    private val onAlbumExpand: (GDriveAlbum, Int) -> Unit,
    private val onTrackClick: (GDriveTrack, List<GDriveTrack>, Int) -> Unit,
    private val onFavoriteToggle: (GDriveAlbum) -> Unit = {},
    private val onTrackLongClick: (GDriveTrack) -> Boolean = { false }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<GDriveLibraryItem>()
    private var allArtists: List<GDriveArtist> = emptyList()
    private var currentFilter: String = ""
    private val favoriteAlbumIds = mutableSetOf<String>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ARTIST = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_TRACK = 3
        private const val TYPE_FOOTER = 4
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is GDriveLibraryItem.HeaderItem -> TYPE_HEADER
            is GDriveLibraryItem.ArtistItem -> TYPE_ARTIST
            is GDriveLibraryItem.AlbumItem -> TYPE_ALBUM
            is GDriveLibraryItem.TrackItem -> TYPE_TRACK
            is GDriveLibraryItem.FooterItem -> TYPE_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_ARTIST -> ArtistViewHolder(inflater.inflate(R.layout.item_artist, parent, false))
            TYPE_ALBUM -> AlbumViewHolder(inflater.inflate(R.layout.item_album, parent, false))
            TYPE_TRACK -> TrackViewHolder(inflater.inflate(R.layout.item_track, parent, false))
            TYPE_FOOTER -> FooterViewHolder(inflater.inflate(R.layout.item_section_footer, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GDriveLibraryItem.HeaderItem -> (holder as HeaderViewHolder).bind(item)
            is GDriveLibraryItem.ArtistItem -> (holder as ArtistViewHolder).bind(item)
            is GDriveLibraryItem.AlbumItem -> (holder as AlbumViewHolder).bind(item)
            is GDriveLibraryItem.TrackItem -> (holder as TrackViewHolder).bind(item)
            is GDriveLibraryItem.FooterItem -> { }
        }
    }

    override fun getItemCount() = items.size

    fun getLetterPosition(letter: String): Int {
        return items.indexOfFirst { it is GDriveLibraryItem.HeaderItem && it.title.startsWith(letter) }
    }

    fun getArtistCount(): Int {
        return items.count { it is GDriveLibraryItem.ArtistItem }
    }

    fun getArtistPosition(artistIndex: Int): Int {
        var count = 0
        for (i in items.indices) {
            if (items[i] is GDriveLibraryItem.ArtistItem) {
                if (count == artistIndex) return i
                count++
            }
        }
        return -1
    }

    fun setArtists(artists: List<GDriveArtist>) {
        allArtists = artists
        currentFilter = ""
        rebuildItems(artists)
    }

    private fun rebuildItems(artists: List<GDriveArtist>) {
        items.clear()

        val sorted = artists.sortedBy { it.name.lowercase() }

        val grouped = sorted.groupBy { artist ->
            val firstLetter = artist.name.firstOrNull()?.uppercaseChar() ?: '#'
            if (firstLetter.isLetter()) firstLetter else '#'
        }

        if (sorted.isNotEmpty()) {
            items.add(GDriveLibraryItem.HeaderItem("ARTISTS (${sorted.size})"))
        }

        var currentLetter: Char? = null
        for (artist in sorted) {
            val firstLetter = artist.name.firstOrNull()?.uppercaseChar() ?: '#'
            val letter = if (firstLetter.isLetter()) firstLetter else '#'

            if (letter != currentLetter) {
                currentLetter = letter
                val count = grouped[letter]?.size ?: 0
                items.add(GDriveLibraryItem.HeaderItem("$letter ($count)"))
            }
            items.add(GDriveLibraryItem.ArtistItem(artist))
        }

        notifyDataSetChanged()
    }

    fun filterItems(query: String) {
        currentFilter = query
        if (query.isEmpty()) {
            rebuildItems(allArtists)
            return
        }
        val filtered = allArtists.filter { fuzzyMatch(it.name, query) }
        rebuildItems(filtered)
    }

    fun clearFilter() {
        currentFilter = ""
        rebuildItems(allArtists)
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
        val item = items[position] as? GDriveLibraryItem.ArtistItem ?: return

        if (item.isExpanded) {
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is GDriveLibraryItem.HeaderItem ||
                it is GDriveLibraryItem.AlbumItem && it.album.artistId == item.artist.id ||
                it is GDriveLibraryItem.TrackItem ||
                it is GDriveLibraryItem.FooterItem
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
            onArtistExpand(item.artist, position)
        }
    }

    fun setArtistAlbums(position: Int, albums: List<GDriveAlbum>) {
        val item = items[position] as? GDriveLibraryItem.ArtistItem ?: return
        item.albums = albums

        if (item.isExpanded && albums.isNotEmpty()) {
            val itemsToAdd = mutableListOf<GDriveLibraryItem>()
            itemsToAdd.add(GDriveLibraryItem.HeaderItem("ALBUMS (${albums.size})"))
            itemsToAdd.addAll(albums.map { GDriveLibraryItem.AlbumItem(it) })
            itemsToAdd.add(GDriveLibraryItem.FooterItem(item.artist.id))

            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
    }

    fun preloadArtistAlbums(artistId: String, albums: List<GDriveAlbum>) {
        val index = items.indexOfFirst { it is GDriveLibraryItem.ArtistItem && it.artist.id == artistId }
        if (index >= 0) {
            val item = items[index] as GDriveLibraryItem.ArtistItem
            item.albums = albums
            notifyItemChanged(index)
        }
    }

    fun toggleAlbum(position: Int) {
        val item = items[position] as? GDriveLibraryItem.AlbumItem ?: return

        if (item.isExpanded) {
            val itemsToRemove = items.drop(position + 1).takeWhile {
                it is GDriveLibraryItem.HeaderItem ||
                it is GDriveLibraryItem.TrackItem && it.parentAlbumId == item.album.id ||
                it is GDriveLibraryItem.FooterItem && it.parentId == item.album.id
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
        val item = items[position] as? GDriveLibraryItem.AlbumItem ?: return
        item.tracks = tracks

        if (item.isExpanded && tracks.isNotEmpty()) {
            val itemsToAdd = mutableListOf<GDriveLibraryItem>()
            itemsToAdd.add(GDriveLibraryItem.HeaderItem("TRACKS (${tracks.size})"))
            itemsToAdd.addAll(tracks.map { GDriveLibraryItem.TrackItem(it, item.album.id) })
            itemsToAdd.add(GDriveLibraryItem.FooterItem(item.album.id))
            items.addAll(position + 1, itemsToAdd)
            notifyItemChanged(position)
            notifyItemRangeInserted(position + 1, itemsToAdd.size)
        }
    }

    // ── Favorites ───────────────────────────────────────────────────────

    fun isAlbumFavorite(albumId: String): Boolean = favoriteAlbumIds.contains(albumId)

    fun setFavoriteAlbumIds(albumIds: Collection<String>) {
        favoriteAlbumIds.clear()
        favoriteAlbumIds.addAll(albumIds)
        notifyDataSetChanged()
    }

    fun setAlbumFavorite(albumId: String, favorite: Boolean) {
        if (favorite) favoriteAlbumIds.add(albumId) else favoriteAlbumIds.remove(albumId)
        val index = items.indexOfFirst { it is GDriveLibraryItem.AlbumItem && it.album.id == albumId }
        if (index >= 0) notifyItemChanged(index)
    }

    // ── ViewHolders ─────────────────────────────────────────────────────

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerTextView: TextView = itemView.findViewById(R.id.sectionHeaderTextView)

        fun bind(item: GDriveLibraryItem.HeaderItem) {
            headerTextView.text = item.title
        }
    }

    inner class ArtistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.artistNameTextView)
        private val subtitleTextView: TextView = itemView.findViewById(R.id.artistSubtitleTextView)
        private val imageView: ImageView = itemView.findViewById(R.id.artistImageView)
        private val letterAvatar: TextView = itemView.findViewById(R.id.artistLetterAvatar)
        private val expandIcon: TextView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: GDriveLibraryItem.ArtistItem) {
            nameTextView.text = item.artist.name
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            val albumCount = if (item.albums.isNotEmpty()) item.albums.size else item.artist.albumCount
            if (albumCount > 0) {
                subtitleTextView.text = "$albumCount Album${if (albumCount != 1) "s" else ""}"
                subtitleTextView.visibility = View.VISIBLE
            } else {
                subtitleTextView.visibility = View.GONE
            }

            // Artist's own folder.jpg first, then fall back to first album's cover
            val coverFileId = item.artist.imageFileId
                ?: item.albums.firstOrNull()?.coverFileId
            if (coverFileId != null) {
                letterAvatar.visibility = View.GONE
                val coverUri = GDriveImageProvider.buildUri(coverFileId)
                Glide.with(itemView.context)
                    .load(coverUri)
                    .error(android.R.color.transparent)
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            letterAvatar.text = item.artist.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                            letterAvatar.visibility = View.VISIBLE
                            return false
                        }
                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean = false
                    })
                    .into(imageView)
            } else {
                Glide.with(itemView.context).clear(imageView)
                imageView.setImageDrawable(null)
                letterAvatar.text = item.artist.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
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
        private val favoriteButton: TextView = itemView.findViewById(R.id.albumFavoriteButton)

        fun bind(item: GDriveLibraryItem.AlbumItem) {
            nameTextView.text = item.album.name
            releaseDateTextView.text = item.album.year?.toString() ?: ""
            expandIcon.text = if (item.isExpanded) "▼" else "▶"

            // Heart lives on the expanded album header only (see issue #127)
            favoriteButton.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            favoriteButton.text = if (favoriteAlbumIds.contains(item.album.id)) "♥" else "♡"
            favoriteButton.setOnClickListener {
                onFavoriteToggle(item.album)
            }

            val trackCount = if (item.tracks.isNotEmpty()) item.tracks.size else item.album.trackCount
            artistTextView.text = "$trackCount Track${if (trackCount != 1) "s" else ""}"
            trackCountTextView.visibility = View.GONE

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

        fun bind(item: GDriveLibraryItem.TrackItem) {
            nameTextView.text = item.track.trackName
            artistTextView.text = item.track.artistName
            durationTextView.text = item.track.file.getFileExtension()
            numberTextView.text = item.track.trackNumber?.toString() ?: ""

            itemView.setOnClickListener {
                // Find all tracks from the same album
                val albumTracks = mutableListOf<GDriveTrack>()
                var trackPosition = 0

                for (i in items.indices) {
                    val currentItem = items[i]
                    if (currentItem is GDriveLibraryItem.TrackItem && currentItem.parentAlbumId == item.parentAlbumId) {
                        albumTracks.add(currentItem.track)
                        if (currentItem.track.file.id == item.track.file.id) {
                            trackPosition = albumTracks.size - 1
                        }
                    }
                }

                onTrackClick(item.track, albumTracks, trackPosition)
            }
            itemView.setOnLongClickListener {
                onTrackLongClick(item.track)
            }
        }
    }

    inner class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
