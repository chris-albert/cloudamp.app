package com.cloudamp.music

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import com.cloudamp.music.playback.ActivePlayback
import com.cloudamp.music.ui.AudioVisualizerView
import com.cloudamp.music.ui.EqVisualizerView
import com.cloudamp.music.ui.WarpGridView
import kotlin.math.abs

enum class VisType(val label: String) {
    EQ_BARS("EQ BARS"),
    WARP_GRID("WARP GRID");

    fun next(): VisType = entries[(ordinal + 1) % entries.size]
    fun prev(): VisType = entries[(ordinal - 1 + entries.size) % entries.size]
}

class VisualizerActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1001
    }

    private lateinit var eqView: EqVisualizerView
    private lateinit var warpGridView: WarpGridView
    private lateinit var modeLabel: TextView
    private var visualizer: Visualizer? = null
    private var currentType = VisType.EQ_BARS
    private val modeLabelHandler = Handler(Looper.getMainLooper())

    private val gestureDetector by lazy {
        GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                finish()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(dx) > 100 && abs(velocityX) > 200) {
                    if (dx < 0) switchType(currentType.next())
                    else switchType(currentType.prev())
                    return true
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_visualizer)

        eqView = findViewById(R.id.eqVisualizerView)
        warpGridView = findViewById(R.id.warpGridView)
        modeLabel = findViewById(R.id.modeLabelView)

        hideSystemUI()

        // Use gesture detector on the container for swipe/tap
        val container = findViewById<FrameLayout>(R.id.vizContainer)
        container.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        // Tapping the mode label cycles visualizations instead of closing
        modeLabel.setOnClickListener { switchType(currentType.next()) }

        // Check permission; onResume starts the visualizer once granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO
            )
        }

        // Show mode label briefly on open
        showModeLabel(currentType.label)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVisualizer()
        }
    }

    override fun onPause() {
        // Release before NowPlayingActivity resumes, so its mini visualizer
        // can claim the audio session (only one Visualizer per session works)
        stopVisualizer()
        super.onPause()
    }

    private fun switchType(type: VisType) {
        currentType = type
        eqView.visibility = if (type == VisType.EQ_BARS) View.VISIBLE else View.GONE
        warpGridView.visibility = if (type == VisType.WARP_GRID) View.VISIBLE else View.GONE
        showModeLabel(type.label)
    }

    private fun showModeLabel(text: String) {
        modeLabel.animate().cancel()
        modeLabel.text = text
        modeLabel.visibility = View.VISIBLE
        modeLabel.alpha = 1f
        modeLabelHandler.removeCallbacksAndMessages(null)
        modeLabelHandler.postDelayed({
            modeLabel.animate().alpha(0f).setDuration(500).withEndAction {
                modeLabel.visibility = View.GONE
            }.start()
        }, 1500)
    }

    private fun activeView(): AudioVisualizerView {
        return if (currentType == VisType.EQ_BARS) eqView else warpGridView
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    private fun startVisualizer() {
        if (visualizer != null) return

        val sessionId = ActivePlayback.provider?.getAudioSessionId() ?: 0
        if (sessionId == 0) {
            Toast.makeText(this, "No audio playing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            vis: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            // Not used
                        }

                        override fun onFftDataCapture(
                            vis: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            fft?.let { data ->
                                val view = activeView() as View
                                view.post {
                                    activeView().updateFft(data)
                                }
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false, // waveform
                    true   // fft
                )
                enabled = true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start visualizer", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVisualizer()
            } else {
                Toast.makeText(this, "Audio permission required for visualizer", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun stopVisualizer() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
    }

    override fun onDestroy() {
        modeLabelHandler.removeCallbacksAndMessages(null)
        stopVisualizer()
        super.onDestroy()
    }
}
