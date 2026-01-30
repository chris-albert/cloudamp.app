package com.cloudamp.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.api.PlayRequest
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Track
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.playback.PlaybackManager
import com.cloudamp.music.ui.EqMeterView
import com.cloudamp.music.ui.QueueAdapter
import kotlinx.coroutines.*

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private lateinit var gdrivePlayback: GDrivePlaybackManager
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

    private lateinit var eqMeterView: EqMeterView

    private var currentTrack: Track? = null
    private var isPlaying = false
    private var currentPosition = 0L
    private var totalDuration = 0L
    private var eqAttached = false

    companion object {
        private const val TAG = "NowPlayingActivity"
        private const val REQUEST_RECORD_AUDIO = 1001
    }

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

        initializeViews()
        setupControls()
        loadCurrentTrack()
        setupEqMeter()

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

        eqMeterView = findViewById(R.id.eqMeterView)
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

    /**
     * Set up the EQ meter visualization.
     * For GDrive playback: attaches to ExoPlayer's audio session for real FFT analysis.
     * For Spotify playback: uses simulated animation (Spotify plays remotely).
     */
    private fun setupEqMeter() {
        if (isGDriveActive) {
            // GDrive uses ExoPlayer locally - we can do real audio analysis
            if (hasRecordAudioPermission()) {
                attachEqVisualizer()
            } else {
                requestRecordAudioPermission()
            }
        } else {
            // Spotify plays remotely - use simulated visualization
            eqMeterView.setPlaying(isPlaying)
        }
    }

    private fun attachEqVisualizer() {
        if (eqAttached) return
        if (isGDriveActive) {
            val sessionId = gdrivePlayback.getAudioSessionId()
            Log.d(TAG, "GDrive audio session ID: $sessionId, isPlaying: ${gdrivePlayback.isPlaying()}")

            // Try the ExoPlayer's audio session first
            if (sessionId != 0) {
                if (eqMeterView.attachToAudioSession(sessionId)) {
                    Log.d(TAG, "EQ visualizer attached to ExoPlayer session $sessionId")
                    eqAttached = true
                    return
                }
                Log.w(TAG, "Failed to attach to ExoPlayer session $sessionId, trying global mix")
            }

            // Fall back to session 0 (global mix output) which captures all device audio
            if (eqMeterView.attachToAudioSession(0)) {
                Log.d(TAG, "EQ visualizer attached to global mix (session 0)")
                eqAttached = true
                return
            }

            // Both failed - use simulation
            Log.w(TAG, "Visualizer unavailable, falling back to simulation")
            eqMeterView.setPlaying(isPlaying)
        } else {
            eqMeterView.setPlaying(isPlaying)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                attachEqVisualizer()
            } else {
                // Permission denied - use simulation instead
                Log.d(TAG, "RECORD_AUDIO permission denied, using simulation")
                eqMeterView.setPlaying(isPlaying)
            }
        }
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
            // Try to attach real visualizer if not yet connected
            if (!eqAttached && hasRecordAudioPermission()) {
                attachEqVisualizer()
            }
        } else {
            updateSpotifyPlaybackState()
            eqMeterView.setPlaying(isPlaying)
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
        eqMeterView.releaseVisualizer()
        scope.cancel()
        super.onDestroy()
    }
}
