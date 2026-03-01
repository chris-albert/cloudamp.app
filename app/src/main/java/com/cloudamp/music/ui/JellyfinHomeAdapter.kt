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

class JellyfinHomeAdapter(
    private val serverUrl: String,
    private val apiKey: String? = null,
    private val onAlbumClick: (JellyfinItem) -> Unit,
    private val onMoreClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val albums = mutableListOf<JellyfinItem>()

    companion object {
        private const val TYPE_ALBUM = 0
        private const val TYPE_MORE = 1
    }

    fun setAlbums(newAlbums: List<JellyfinItem>) {
        albums.clear()
        albums.addAll(newAlbums)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int {
        if (albums.isEmpty()) return 0
        return albums.size + 1 // +1 for the "more" item
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < albums.size) TYPE_ALBUM else TYPE_MORE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_ALBUM) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_album, parent, false)
            AlbumViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_more, parent, false)
            MoreViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AlbumViewHolder -> {
                val album = albums[position]
                holder.albumName.text = album.Name

                val imageUrl = album.getPrimaryImageUrl(serverUrl, apiKey)
                if (imageUrl != null) {
                    Glide.with(holder.itemView.context)
                        .load(imageUrl)
                        .placeholder(R.drawable.ic_jellyfin)
                        .centerCrop()
                        .into(holder.albumArt)
                } else {
                    Glide.with(holder.itemView.context).clear(holder.albumArt)
                    holder.albumArt.setImageResource(R.drawable.ic_album_placeholder)
                }

                holder.itemView.setOnClickListener { onAlbumClick(album) }
            }
            is MoreViewHolder -> {
                holder.itemView.setOnClickListener { onMoreClick() }
            }
        }
    }

    class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val albumArt: ImageView = view.findViewById(R.id.albumArt)
        val albumName: TextView = view.findViewById(R.id.albumName)
    }

    class MoreViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
