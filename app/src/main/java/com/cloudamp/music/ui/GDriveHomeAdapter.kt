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
    private val onAlbumClick: (GDriveAlbum) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val albums = mutableListOf<GDriveAlbum>()

    fun setAlbums(newAlbums: List<GDriveAlbum>) {
        albums.clear()
        albums.addAll(newAlbums)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = albums.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_album, parent, false)
        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
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
}
