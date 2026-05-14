package com.cloudamp.music.ui

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.cloudamp.music.NowPlayingActivity
import com.cloudamp.music.R
import com.cloudamp.music.playback.ActivePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages the mini-player bar shown at the bottom of browsing screens.
 * Call [attach] in onCreate (after setContentView) and [startUpdates]/[stopUpdates]
 * in onResume/onPause.
 */
class MiniPlayerBar(
    private val activity: Activity,
    private val scope: CoroutineScope
) {
    private lateinit var bar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var trackInfoContainer: View

    private var currentTrackId: String? = null

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateState()
            updateHandler.postDelayed(this, 1000)
        }
    }

    fun attach() {
        bar = activity.findViewById(R.id.miniPlayerBar)
        titleText = activity.findViewById(R.id.miniPlayerTitle)
        artistText = activity.findViewById(R.id.miniPlayerArtist)
        playPauseButton = activity.findViewById(R.id.miniPlayerPlayPause)
        trackInfoContainer = activity.findViewById(R.id.miniPlayerTrackInfo)

        trackInfoContainer.setOnClickListener {
            activity.startActivity(Intent(activity, NowPlayingActivity::class.java))
        }

        playPauseButton.setOnClickListener {
            scope.launch {
                val provider = ActivePlayback.provider ?: return@launch
                if (provider.isPlaying()) {
                    provider.pause()
                } else {
                    provider.play()
                }
                updateState()
            }
        }

        // Enable marquee scrolling
        titleText.isSelected = true
    }

    fun startUpdates() {
        updateState()
        updateHandler.post(updateRunnable)
    }

    fun stopUpdates() {
        updateHandler.removeCallbacks(updateRunnable)
    }

    private fun updateState() {
        val provider = ActivePlayback.provider
        if (provider == null) {
            bar.visibility = View.GONE
            return
        }

        val queue = provider.getQueueAsTracks()
        val index = provider.getCurrentIndex()
        if (queue.isEmpty() || index !in queue.indices) {
            bar.visibility = View.GONE
            return
        }

        bar.visibility = View.VISIBLE

        val track = queue[index]
        if (track.id != currentTrackId) {
            currentTrackId = track.id
            titleText.text = track.name
            artistText.text = track.artists.joinToString(", ") { it.name }
        }

        playPauseButton.setImageResource(
            if (provider.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        )
    }
}
