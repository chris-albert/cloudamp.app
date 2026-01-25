package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.R
import com.cloudamp.music.models.Track

class QueueAdapter(
    private var tracks: List<Track> = emptyList(),
    private var currentIndex: Int = 0
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val positionTextView: TextView = itemView.findViewById(R.id.queuePositionTextView)
        private val nameTextView: TextView = itemView.findViewById(R.id.queueTrackNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.queueTrackArtistTextView)
        private val durationTextView: TextView = itemView.findViewById(R.id.queueTrackDurationTextView)

        fun bind(track: Track, position: Int, isCurrent: Boolean) {
            positionTextView.text = if (isCurrent) "►" else "${position + 1}"
            nameTextView.text = track.name
            artistTextView.text = track.artists.joinToString(", ") { it.name }

            val minutes = track.durationMs / 60000
            val seconds = (track.durationMs % 60000) / 1000
            durationTextView.text = String.format("%d:%02d", minutes, seconds)

            // Highlight current track
            if (isCurrent) {
                itemView.alpha = 1.0f
            } else {
                itemView.alpha = 0.7f
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_track, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(tracks[position], position, position == currentIndex)
    }

    override fun getItemCount() = tracks.size

    fun updateQueue(newTracks: List<Track>, newCurrentIndex: Int) {
        tracks = newTracks
        currentIndex = newCurrentIndex
        notifyDataSetChanged()
    }
}
