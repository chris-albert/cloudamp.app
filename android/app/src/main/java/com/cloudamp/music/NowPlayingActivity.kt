package com.cloudamp.music

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.cache.SavedQueuesManager
import com.cloudamp.music.models.Track
import com.cloudamp.music.playback.ActivePlayback
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.ui.QueueAdapter
import kotlinx.coroutines.*

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var savedQueuesManager: SavedQueuesManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var trackTitleTextView: TextView
    private lateinit var trackArtistTextView: TextView
    private lateinit var trackAlbumTextView: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var queueInfoTextView: TextView
    private lateinit var queueRecyclerView: RecyclerView
    private lateinit var queueAdapter: QueueAdapter

    private lateinit var previousButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var saveQueueButton: ImageButton

    private var currentTrack: Track? = null
    private var isPlaying = false
    private var currentPosition = 0L
    private var totalDuration = 0L
    private var lastScrolledIndex = -1

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updatePlaybackState()
            updateHandler.postDelayed(this, 1000) // Update every second
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "NOW PLAYING"

        savedQueuesManager = SavedQueuesManager.getInstance(this)

        initializeViews()
        setupControls()
        loadCurrentTrack()

        // Start periodic updates
        updateHandler.post(updateRunnable)
    }

    private fun initializeViews() {
        trackTitleTextView = findViewById(R.id.trackTitleTextView)
        trackArtistTextView = findViewById(R.id.trackArtistTextView)
        trackAlbumTextView = findViewById(R.id.trackAlbumTextView)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        totalTimeTextView = findViewById(R.id.totalTimeTextView)
        seekBar = findViewById(R.id.seekBar)
        queueInfoTextView = findViewById(R.id.queueInfoTextView)
        queueRecyclerView = findViewById(R.id.queueRecyclerView)

        queueAdapter = QueueAdapter()
        queueRecyclerView.layoutManager = LinearLayoutManager(this)
        queueRecyclerView.adapter = queueAdapter

        previousButton = findViewById(R.id.previousButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        stopButton = findViewById(R.id.stopButton)
        nextButton = findViewById(R.id.nextButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        repeatButton = findViewById(R.id.repeatButton)
        saveQueueButton = findViewById(R.id.saveQueueButton)

        saveQueueButton.setOnClickListener {
            showSaveQueueDialog()
        }
    }

    private fun showSaveQueueDialog() {
        // Check if there's an active queue to save
        val hasQueue = ActivePlayback.provider?.getQueueAsTracks()?.isNotEmpty() ?: false

        if (!hasQueue) {
            Toast.makeText(this, "No queue to save", Toast.LENGTH_SHORT).show()
            return
        }

        // Default name from current track
        val defaultName = currentTrack?.let { track ->
            track.album?.name ?: track.name
        } ?: "Queue"

        val editText = EditText(this).apply {
            setText(defaultName)
            setTextColor(resources.getColor(R.color.winamp_text, null))
            setBackgroundColor(resources.getColor(R.color.winamp_background, null))
            setPadding(48, 32, 48, 32)
            setSelection(text.length)
        }

        AlertDialog.Builder(this, R.style.Theme_CloudAmp_Dialog)
            .setTitle("Save Queue")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val saved = savedQueuesManager.saveCurrentQueue(name, currentPosition)
                    if (saved != null) {
                        Toast.makeText(
                            this,
                            "Saved: ${saved.name} (${saved.getTrackCount()} tracks)",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, "Failed to save queue", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupControls() {
        previousButton.setOnClickListener {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.skipToPrevious()
                    delay(500)
                    loadCurrentTrack()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        playPauseButton.setOnClickListener {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    if (active.isPlaying()) {
                        active.pause()
                        isPlaying = false
                        playPauseButton.setImageResource(R.drawable.ic_play)
                    } else {
                        active.play()
                        isPlaying = true
                        playPauseButton.setImageResource(R.drawable.ic_pause)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        stopButton.setOnClickListener {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.stop()
                    isPlaying = false
                    currentPosition = 0
                    seekBar.progress = 0
                    currentTimeTextView.text = "0:00"
                    playPauseButton.setImageResource(R.drawable.ic_play)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        nextButton.setOnClickListener {
            scope.launch {
                try {
                    val active = ActivePlayback.provider ?: return@launch
                    active.skipToNext()
                    delay(500)
                    loadCurrentTrack()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentPosition = progress.toLong()
                    currentTimeTextView.text = formatTime(currentPosition)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                scope.launch {
                    try {
                        ActivePlayback.provider?.seekTo(currentPosition)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    private fun loadCurrentTrack() {
        val active = ActivePlayback.provider ?: return
        val queueTracks = active.getQueueAsTracks()
        val index = active.getCurrentIndex()

        if (index in queueTracks.indices) {
            currentTrack = queueTracks[index]
            updateTrackInfo(queueTracks[index])
        }

        isPlaying = active.isPlaying()
        currentPosition = active.getCurrentPosition()
        totalDuration = active.getDuration()

        if (totalDuration > 0) {
            totalTimeTextView.text = formatTime(totalDuration)
            seekBar.max = totalDuration.toInt()
        }

        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateTrackInfo(track: Track) {
        trackTitleTextView.text = track.name
        trackArtistTextView.text = track.artists.joinToString(", ") { it.name }
        trackAlbumTextView.text = track.album?.name ?: ""

        totalDuration = track.durationMs.toLong()
        if (totalDuration > 0) {
            totalTimeTextView.text = formatTime(totalDuration)
            seekBar.max = totalDuration.toInt()
        }
    }

    private fun updatePlaybackState() {
        val active = ActivePlayback.provider ?: return
        currentPosition = active.getCurrentPosition()
        isPlaying = active.isPlaying()

        val newDuration = active.getDuration()
        if (newDuration > 0 && newDuration != totalDuration) {
            totalDuration = newDuration
            totalTimeTextView.text = formatTime(totalDuration)
            seekBar.max = totalDuration.toInt()
        }

        seekBar.progress = currentPosition.toInt()
        currentTimeTextView.text = formatTime(currentPosition)

        // Check if track changed
        val queueTracks = active.getQueueAsTracks()
        val currentIdx = active.getCurrentIndex()
        if (currentIdx in queueTracks.indices) {
            val track = queueTracks[currentIdx]
            if (currentTrack?.id != track.id) {
                currentTrack = track
                updateTrackInfo(track)
            }
        }

        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        updateQueueDisplay()
    }

    private fun updateQueueDisplay() {
        val active = ActivePlayback.provider
        val queue: List<Track> = active?.getQueueAsTracks() ?: emptyList()
        val currentIndex: Int = active?.getCurrentIndex() ?: 0

        if (queue.isEmpty()) {
            queueInfoTextView.text = "PLAYLIST"
        } else {
            queueInfoTextView.text = "PLAYLIST (${queue.size} tracks)"
        }

        queueAdapter.updateQueue(queue, currentIndex)

        // Scroll to current track when it changes
        if (currentIndex != lastScrolledIndex && queue.isNotEmpty() && currentIndex in queue.indices) {
            lastScrolledIndex = currentIndex
            queueRecyclerView.post {
                (queueRecyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentIndex, queueRecyclerView.height / 3)
            }
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        updateHandler.removeCallbacks(updateRunnable)
        scope.cancel()
        super.onDestroy()
    }
}
