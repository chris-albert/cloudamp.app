package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.R
import com.cloudamp.music.cache.MediaCache
import com.cloudamp.music.models.Track

class QueueAdapter(
    private var tracks: List<Track> = emptyList(),
    private var currentIndex: Int = 0
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var cacheStates: Map<String, MediaCache.CacheState> = emptyMap()

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val positionTextView: TextView = itemView.findViewById(R.id.queuePositionTextView)
        private val nameTextView: TextView = itemView.findViewById(R.id.queueTrackNameTextView)
        private val artistTextView: TextView = itemView.findViewById(R.id.queueTrackArtistTextView)
        private val cacheIndicator: TextView = itemView.findViewById(R.id.queueTrackCacheIndicator)
        private val durationTextView: TextView = itemView.findViewById(R.id.queueTrackDurationTextView)

        fun bind(track: Track, position: Int, isCurrent: Boolean, state: MediaCache.CacheState) {
            positionTextView.text = if (isCurrent) "►" else "${position + 1}"
            nameTextView.text = track.name
            artistTextView.text = track.artists.joinToString(", ") { it.name }

            val minutes = track.durationMs / 60000
            val seconds = (track.durationMs % 60000) / 1000
            durationTextView.text = String.format("%d:%02d", minutes, seconds)

            // Highlight current track
            itemView.alpha = if (isCurrent) 1.0f else 0.7f

            applyCacheState(state)
        }

        private fun applyCacheState(state: MediaCache.CacheState) {
            val ctx = itemView.context
            when (state) {
                MediaCache.CacheState.FULL -> {
                    cacheIndicator.text = "●"
                    cacheIndicator.setTextColor(ctx.getColor(android.R.color.holo_green_light))
                    cacheIndicator.contentDescription = "Fully cached"
                    cacheIndicator.visibility = View.VISIBLE
                }
                MediaCache.CacheState.PARTIAL -> {
                    cacheIndicator.text = "◐"
                    cacheIndicator.setTextColor(ctx.getColor(android.R.color.holo_orange_light))
                    cacheIndicator.contentDescription = "Partially cached"
                    cacheIndicator.visibility = View.VISIBLE
                }
                MediaCache.CacheState.NONE -> {
                    cacheIndicator.text = ""
                    cacheIndicator.contentDescription = "Not cached"
                    cacheIndicator.visibility = View.INVISIBLE
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_track, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val track = tracks[position]
        val state = cacheStates[track.id] ?: MediaCache.CacheState.NONE
        holder.bind(track, position, position == currentIndex, state)
    }

    override fun getItemCount() = tracks.size

    fun updateQueue(newTracks: List<Track>, newCurrentIndex: Int) {
        tracks = newTracks
        currentIndex = newCurrentIndex
        cacheStates = snapshotStates(newTracks)
        notifyDataSetChanged()
    }

    /**
     * Re-poll the cache for current track set. Call when the queue itself
     * hasn't changed but cache contents may have (e.g. periodic tick during
     * playback as bytes stream in).
     */
    fun refreshCacheStates() {
        val updated = snapshotStates(tracks)
        if (updated != cacheStates) {
            cacheStates = updated
            notifyDataSetChanged()
        }
    }

    private fun snapshotStates(list: List<Track>): Map<String, MediaCache.CacheState> {
        if (list.isEmpty()) return emptyMap()
        val ctx = (recyclerViewRef ?: return emptyMap()).context
        val ids = list.map { it.id }
        return MediaCache.getInstance(ctx).cacheStatesFor(ids)
    }

    private var recyclerViewRef: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerViewRef = recyclerView
        // First attach: states couldn't be computed earlier (no context); fill now.
        if (cacheStates.isEmpty() && tracks.isNotEmpty()) {
            cacheStates = snapshotStates(tracks)
            notifyDataSetChanged()
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerViewRef = null
    }
}
