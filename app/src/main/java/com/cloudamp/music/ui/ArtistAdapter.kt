package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.cloudamp.music.R
import com.cloudamp.music.models.Artist

class ArtistAdapter(
    private var artists: List<Artist>,
    private val onItemClick: (Artist) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder>() {
    
    class ArtistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val artistImage: ImageView = view.findViewById(R.id.artistImage)
        val artistName: TextView = view.findViewById(R.id.artistName)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_artist, parent, false)
        return ArtistViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        val artist = artists[position]
        holder.artistName.text = artist.name
        
        // Load image
        artist.images?.firstOrNull()?.let { image ->
            Glide.with(holder.itemView.context)
                .load(image.url)
                .placeholder(R.drawable.ic_artist_placeholder)
                .into(holder.artistImage)
        } ?: run {
            holder.artistImage.setImageResource(R.drawable.ic_artist_placeholder)
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(artist)
        }
    }
    
    override fun getItemCount() = artists.size
    
    fun updateData(newArtists: List<Artist>) {
        artists = newArtists
        notifyDataSetChanged()
    }
}
