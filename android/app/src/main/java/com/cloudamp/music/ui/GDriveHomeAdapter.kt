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
import com.cloudamp.music.playback.GDriveImageProvider

class GDriveHomeAdapter(
    private val onAlbumClick: (GDriveAlbum) -> Unit,
    private val onShuffleClick: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val albums = mutableListOf<GDriveAlbum>()
    private var showShuffle = false

    companion object {
        private const val VIEW_TYPE_ALBUM = 0
        private const val VIEW_TYPE_SHUFFLE = 1
    }

    fun setAlbums(newAlbums: List<GDriveAlbum>, showShuffleButton: Boolean = false) {
        albums.clear()
        albums.addAll(newAlbums)
        showShuffle = showShuffleButton && onShuffleClick != null
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = albums.size + if (showShuffle) 1 else 0

    override fun getItemViewType(position: Int): Int {
        return if (showShuffle && position == albums.size) VIEW_TYPE_SHUFFLE else VIEW_TYPE_ALBUM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_SHUFFLE) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_shuffle, parent, false)
            ShuffleViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_album, parent, false)
            AlbumViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ShuffleViewHolder) {
            holder.itemView.setOnClickListener { onShuffleClick?.invoke() }
            return
        }
        val album = albums[position]
        val vh = holder as AlbumViewHolder
        vh.albumName.text = "${album.name}\n${album.artistName}"

        val coverFileId = album.coverFileId
        if (coverFileId != null) {
            Glide.with(holder.itemView.context)
                .load(GDriveImageProvider.buildUri(coverFileId))
                .placeholder(R.drawable.ic_gdrive)
                .error(R.drawable.ic_gdrive)
                .centerCrop()
                .into(vh.albumArt)
        } else {
            vh.albumArt.setImageResource(R.drawable.ic_gdrive)
        }

        holder.itemView.setOnClickListener { onAlbumClick(album) }
    }

    class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val albumArt: ImageView = view.findViewById(R.id.albumArt)
        val albumName: TextView = view.findViewById(R.id.albumName)
    }

    class ShuffleViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
