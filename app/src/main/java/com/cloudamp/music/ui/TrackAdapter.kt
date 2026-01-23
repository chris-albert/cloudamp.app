package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.R
import com.cloudamp.music.models.Track

class TrackAdapter(
    private var tracks: List<Track>,
    private val onItemClick: (Track, Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {
    
    class TrackViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val trackNumber: TextView = view.findViewById(R.id.trackNumber)
        val trackName: TextView = view.findViewById(R.id.trackName)
        val trackArtist: TextView = view.findViewById(R.id.trackArtist)
        val trackDuration: TextView = view.findViewById(R.id.trackDuration)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.trackNumber.text = "${track.trackNumber ?: (position + 1)}"
        holder.trackName.text = track.name
        holder.trackArtist.text = track.artists.joinToString(", ") { it.name }
        holder.trackDuration.text = formatDuration(track.durationMs)
        
        holder.itemView.setOnClickListener {
            onItemClick(track, position)
        }
    }
    
    override fun getItemCount() = tracks.size
    
    fun updateData(newTracks: List<Track>) {
        tracks = newTracks
        notifyDataSetChanged()
    }
    
    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000).toInt()
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", minutes, secs)
    }
}
