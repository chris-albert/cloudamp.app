package com.cloudamp.music.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.R
import com.cloudamp.music.models.Track
import java.util.Collections

/**
 * Ordered track list for a single playlist. Supports tap-to-play, a remove
 * button, and drag-to-reorder via a handle (driven by an ItemTouchHelper
 * owned by the activity).
 */
class PlaylistTracksAdapter(
    private val onTrackClick: (Int) -> Unit,
    private val onRemoveClick: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<PlaylistTracksAdapter.ViewHolder>() {

    private val tracks = mutableListOf<Track>()

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val positionTextView: TextView = itemView.findViewById(R.id.playlistTrackPosition)
        val nameTextView: TextView = itemView.findViewById(R.id.playlistTrackName)
        val artistTextView: TextView = itemView.findViewById(R.id.playlistTrackArtist)
        val removeButton: ImageButton = itemView.findViewById(R.id.removeButton)
        val dragHandle: View = itemView.findViewById(R.id.dragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_track, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        holder.positionTextView.text = "${position + 1}"
        holder.nameTextView.text = track.name
        holder.artistTextView.text = track.artists.joinToString(", ") { it.name }

        holder.itemView.setOnClickListener { onTrackClick(holder.bindingAdapterPosition) }
        holder.removeButton.setOnClickListener { onRemoveClick(holder.bindingAdapterPosition) }
        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
    }

    override fun getItemCount(): Int = tracks.size

    fun setTracks(newTracks: List<Track>) {
        tracks.clear()
        tracks.addAll(newTracks)
        notifyDataSetChanged()
    }

    /** Swap two adjacent-or-not positions during a drag; persisted by the caller on drop. */
    fun moveTrack(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) Collections.swap(tracks, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(tracks, i, i - 1)
        }
        notifyItemMoved(from, to)
    }
}
