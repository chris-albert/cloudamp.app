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
import com.cloudamp.music.api.PlayRequest
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.cache.SavedQueuesManager
import com.cloudamp.music.models.Track
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.playback.PlaybackManager
import com.cloudamp.music.ui.QueueAdapter
import kotlinx.coroutines.*

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private lateinit var gdrivePlayback: GDrivePlaybackManager
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

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updatePlaybackState()
            updateHandler.postDelayed(this, 1000) // Update every second
        }
    }

    private val isGDriveActive: Boolean
        get() = GDrivePlaybackManager.isActiveProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "NOW PLAYING"

        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager.getInstance(this)
        gdrivePlayback = GDrivePlaybackManager.getInstance(this)
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
        val hasQueue = if (isGDriveActive) {
            gdrivePlayback.getQueue().isNotEmpty()
        } else {
            playbackManager.getCurrentQueue().isNotEmpty()
        }

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
            if (isGDriveActive) {
                gdrivePlayback.skipToPrevious()
                updateGDriveTrackInfo()
            } else {
                scope.launch {
                    try {
                        spotifyClient.api.previous()
                        delay(500)
                        loadCurrentTrack()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        playPauseButton.setOnClickListener {
            if (isGDriveActive) {
                if (gdrivePlayback.isPlaying()) {
                    gdrivePlayback.pause()
                    isPlaying = false
                    playPauseButton.setImageResource(R.drawable.ic_play)
                } else {
                    gdrivePlayback.play()
                    isPlaying = true
                    playPauseButton.setImageResource(R.drawable.ic_pause)
                }
            } else {
                scope.launch {
                    try {
                        if (isPlaying) {
                            spotifyClient.api.pause()
                            isPlaying = false
                            playPauseButton.setImageResource(R.drawable.ic_play)
                        } else {
                            spotifyClient.api.play(PlayRequest())
                            isPlaying = true
                            playPauseButton.setImageResource(R.drawable.ic_pause)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        stopButton.setOnClickListener {
            if (isGDriveActive) {
                gdrivePlayback.stop()
                isPlaying = false
                currentPosition = 0
                seekBar.progress = 0
                currentTimeTextView.text = "0:00"
                playPauseButton.setImageResource(R.drawable.ic_play)
            } else {
                scope.launch {
                    try {
                        spotifyClient.api.pause()
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
        }

        nextButton.setOnClickListener {
            if (isGDriveActive) {
                gdrivePlayback.skipToNext()
                updateGDriveTrackInfo()
            } else {
                scope.launch {
                    try {
                        spotifyClient.api.next()
                        delay(500)
                        loadCurrentTrack()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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
                if (isGDriveActive) {
                    gdrivePlayback.seekTo(currentPosition)
                } else {
                    scope.launch {
                        try {
                            spotifyClient.api.seek(currentPosition)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        })
    }

    private fun loadCurrentTrack() {
        if (isGDriveActive) {
            updateGDriveTrackInfo()
        } else {
            loadSpotifyTrack()
        }
    }

    private fun loadSpotifyTrack() {
        scope.launch {
            try {
                val response = spotifyClient.api.getCurrentPlayback()
                if (response.isSuccessful) {
                    val playback = response.body()
                    playback?.item?.let { track ->
                        currentTrack = track
                        updateTrackInfo(track)
                        isPlaying = playback.isPlaying
                        currentPosition = playback.progressMs.toLong()
                        totalDuration = track.durationMs.toLong()

                        playPauseButton.setImageResource(
                            if (isPlaying) R.drawable.ic_pause
                            else R.drawable.ic_play
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateGDriveTrackInfo() {
        val queue = gdrivePlayback.getQueue()
        val index = gdrivePlayback.getCurrentIndex()

        if (index in queue.indices) {
            val file = queue[index]
            val track = gdrivePlayback.driveFileToTrack(file)
            currentTrack = track
            updateTrackInfo(track)
        }

        isPlaying = gdrivePlayback.isPlaying()
        currentPosition = gdrivePlayback.getCurrentPosition()
        totalDuration = gdrivePlayback.getDuration()

        if (totalDuration > 0) {
            totalTimeTextView.text = formatTime(totalDuration)
            seekBar.max = totalDuration.toInt()
        }

        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause
            else R.drawable.ic_play
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
        if (isGDriveActive) {
            updateGDrivePlaybackState()
        } else {
            updateSpotifyPlaybackState()
        }
        updateQueueDisplay()
    }

    private fun updateSpotifyPlaybackState() {
        if (isPlaying && currentPosition < totalDuration) {
            currentPosition += 1000
            seekBar.progress = currentPosition.toInt()
            currentTimeTextView.text = formatTime(currentPosition)
        }
    }

    private fun updateGDrivePlaybackState() {
        currentPosition = gdrivePlayback.getCurrentPosition()
        isPlaying = gdrivePlayback.isPlaying()

        val newDuration = gdrivePlayback.getDuration()
        if (newDuration > 0 && newDuration != totalDuration) {
            totalDuration = newDuration
            totalTimeTextView.text = formatTime(totalDuration)
            seekBar.max = totalDuration.toInt()
        }

        seekBar.progress = currentPosition.toInt()
        currentTimeTextView.text = formatTime(currentPosition)

        // Check if track changed
        val currentIdx = gdrivePlayback.getCurrentIndex()
        val queue = gdrivePlayback.getQueue()
        if (currentIdx in queue.indices) {
            val file = queue[currentIdx]
            val track = gdrivePlayback.driveFileToTrack(file)
            if (currentTrack?.id != track.id) {
                currentTrack = track
                updateTrackInfo(track)
            }
        }

        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause
            else R.drawable.ic_play
        )
    }

    private fun updateQueueDisplay() {
        val queue: List<Track>
        val currentIndex: Int

        if (isGDriveActive) {
            queue = gdrivePlayback.getQueueAsTracks()
            currentIndex = gdrivePlayback.getCurrentIndex()
        } else {
            queue = playbackManager.getCurrentQueue()
            currentIndex = playbackManager.getCurrentIndex()
        }

        if (queue.isEmpty()) {
            queueInfoTextView.text = "PLAYLIST"
        } else {
            queueInfoTextView.text = "PLAYLIST (${queue.size} tracks)"
        }

        queueAdapter.updateQueue(queue, currentIndex)
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
