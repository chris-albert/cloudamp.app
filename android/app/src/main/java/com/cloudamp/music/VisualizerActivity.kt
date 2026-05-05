package com.cloudamp.music

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cloudamp.music.playback.ActivePlayback
import com.cloudamp.music.ui.EqVisualizerView

class VisualizerActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1001
    }

    private lateinit var eqView: EqVisualizerView
    private var visualizer: Visualizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_visualizer)

        eqView = findViewById(R.id.eqVisualizerView)

        hideSystemUI()

        // Tap anywhere to close
        eqView.setOnClickListener {
            finish()
        }

        // Check permission and start visualizer
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startVisualizer()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO
            )
        }
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
                                eqView.post {
                                    eqView.updateFft(data)
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

    override fun onDestroy() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
        super.onDestroy()
    }
}
