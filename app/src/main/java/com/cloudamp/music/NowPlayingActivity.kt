package com.cloudamp.music

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.cloudamp.music.api.SpotifyApiClient
import com.cloudamp.music.models.Track
import com.cloudamp.music.playback.PlaybackManager
import kotlinx.coroutines.*

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var spotifyClient: SpotifyApiClient
    private lateinit var playbackManager: PlaybackManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var albumArtImageView: ImageView
    private lateinit var trackTitleTextView: TextView
    private lateinit var trackArtistTextView: TextView
    private lateinit var trackAlbumTextView: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var queueInfoTextView: TextView

    private lateinit var previousButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "NOW PLAYING"

        spotifyClient = SpotifyApiClient.getInstance(this)
        playbackManager = PlaybackManager(this, spotifyClient)

        initializeViews()
        setupControls()
        loadCurrentTrack()

        // Start periodic updates
        updateHandler.post(updateRunnable)
    }

    private fun initializeViews() {
        albumArtImageView = findViewById(R.id.albumArtImageView)
        trackTitleTextView = findViewById(R.id.trackTitleTextView)
        trackArtistTextView = findViewById(R.id.trackArtistTextView)
        trackAlbumTextView = findViewById(R.id.trackAlbumTextView)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        totalTimeTextView = findViewById(R.id.totalTimeTextView)
        seekBar = findViewById(R.id.seekBar)
        queueInfoTextView = findViewById(R.id.queueInfoTextView)

        previousButton = findViewById(R.id.previousButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        stopButton = findViewById(R.id.stopButton)
        nextButton = findViewById(R.id.nextButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        repeatButton = findViewById(R.id.repeatButton)
    }

    private fun setupControls() {
        previousButton.setOnClickListener {
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

        playPauseButton.setOnClickListener {
            scope.launch {
                try {
                    if (isPlaying) {
                        spotifyClient.api.pause()
                        isPlaying = false
                        playPauseButton.setImageResource(R.drawable.ic_play)
                    } else {
                        currentTrack?.let { track ->
                            playbackManager.playTracks(listOf(track), 0)
                        }
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

        nextButton.setOnClickListener {
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
                        spotifyClient.api.seek(currentPosition)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    private fun loadCurrentTrack() {
        scope.launch {
            try {
                val response = spotifyClient.api.getCurrentPlayback()
                if (response.isSuccessful) {
                    val playback = response.body()
                    playback?.item?.let { track ->
                        currentTrack = track
                        updateTrackInfo(track)
                        isPlaying = playback.isPlaying
                        currentPosition = playback.progressMs ?: 0L
                        totalDuration = track.durationMs

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

    private fun updateTrackInfo(track: Track) {
        trackTitleTextView.text = track.name
        trackArtistTextView.text = track.artists.joinToString(", ") { it.name }
        trackAlbumTextView.text = track.album.name

        totalDuration = track.durationMs
        totalTimeTextView.text = formatTime(totalDuration)
        seekBar.max = totalDuration.toInt()

        // Load album art
        track.album.images?.firstOrNull()?.url?.let { imageUrl ->
            Glide.with(this)
                .load(imageUrl)
                .placeholder(R.drawable.ic_album_placeholder)
                .into(albumArtImageView)
        }
    }

    private fun updatePlaybackState() {
        if (isPlaying && currentPosition < totalDuration) {
            currentPosition += 1000 // Increment by 1 second
            seekBar.progress = currentPosition.toInt()
            currentTimeTextView.text = formatTime(currentPosition)
        }
        updateQueueDisplay()
    }

    private fun updateQueueDisplay() {
        val queue = playbackManager.getCurrentQueue()
        val currentIndex = playbackManager.getCurrentIndex()

        if (queue.isEmpty()) {
            queueInfoTextView.text = "PLAYLIST: No tracks queued"
        } else {
            val remainingTracks = queue.size - currentIndex - 1
            val currentTrackName = queue.getOrNull(currentIndex)?.name ?: "Unknown"

            if (remainingTracks > 0) {
                queueInfoTextView.text = "PLAYLIST: $currentTrackName (+$remainingTracks more)"
            } else {
                queueInfoTextView.text = "PLAYLIST: $currentTrackName (last track)"
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
