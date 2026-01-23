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

class AlbumAdapter(
    private var albums: List<Album>,
    private val onItemClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {
    
    class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val albumImage: ImageView = view.findViewById(R.id.albumImage)
        val albumName: TextView = view.findViewById(R.id.albumName)
        val artistName: TextView = view.findViewById(R.id.albumArtist)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album, parent, false)
        return AlbumViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = albums[position]
        holder.albumName.text = album.name
        holder.artistName.text = album.artists.joinToString(", ") { it.name }
        
        // Load image
        album.images?.firstOrNull()?.let { image ->
            Glide.with(holder.itemView.context)
                .load(image.url)
                .placeholder(R.drawable.ic_album_placeholder)
                .into(holder.albumImage)
        } ?: run {
            holder.albumImage.setImageResource(R.drawable.ic_album_placeholder)
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(album)
        }
    }
    
    override fun getItemCount() = albums.size
    
    fun updateData(newAlbums: List<Album>) {
        albums = newAlbums
        notifyDataSetChanged()
    }
}
