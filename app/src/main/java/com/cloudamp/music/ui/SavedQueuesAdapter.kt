package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.R
import com.cloudamp.music.models.SavedQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SavedQueuesAdapter(
    private val onQueueClick: (SavedQueue) -> Unit,
    private val onRenameClick: (SavedQueue) -> Unit,
    private val onDeleteClick: (SavedQueue) -> Unit
) : RecyclerView.Adapter<SavedQueuesAdapter.ViewHolder>() {

    private var queues: List<SavedQueue> = emptyList()

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val providerIcon: ImageView = itemView.findViewById(R.id.providerIcon)
        val nameTextView: TextView = itemView.findViewById(R.id.queueNameTextView)
        val detailsTextView: TextView = itemView.findViewById(R.id.queueDetailsTextView)
        val currentTrackTextView: TextView = itemView.findViewById(R.id.queueCurrentTrackTextView)
        val renameButton: ImageButton = itemView.findViewById(R.id.renameButton)
        val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_queue, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val queue = queues[position]

        holder.nameTextView.text = queue.name

        // Provider icon
        val iconRes = R.drawable.ic_gdrive
        holder.providerIcon.setImageResource(iconRes)

        // Details: track count, provider, last played
        val trackCount = queue.getTrackCount()
        val provider = queue.getDisplayProvider()
        val lastPlayed = formatTimestamp(queue.lastPlayedAt)
        holder.detailsTextView.text = "$trackCount tracks | $provider | $lastPlayed"

        // Current track position info
        val currentTrack = queue.getCurrentTrackName()
        if (currentTrack != null) {
            holder.currentTrackTextView.visibility = View.VISIBLE
            val time = formatTime(queue.currentPositionMs)
            holder.currentTrackTextView.text = "\u25B6 ${queue.currentIndex + 1}/${trackCount} $time | $currentTrack"
        } else {
            holder.currentTrackTextView.visibility = View.GONE
        }

        // Click handlers
        holder.itemView.setOnClickListener { onQueueClick(queue) }
        holder.renameButton.setOnClickListener { onRenameClick(queue) }
        holder.deleteButton.setOnClickListener { onDeleteClick(queue) }
    }

    override fun getItemCount(): Int = queues.size

    fun setQueues(newQueues: List<SavedQueue>) {
        queues = newQueues
        notifyDataSetChanged()
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "Never"
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> {
                val format = SimpleDateFormat("MMM d", Locale.getDefault())
                format.format(Date(timestamp))
            }
        }
    }
}
