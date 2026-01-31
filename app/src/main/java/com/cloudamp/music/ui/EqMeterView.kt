package com.cloudamp.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Real-time EQ spectrum meter display styled after the CloudAmp icon.
 * Displays 20 vertical bars with green/yellow/red segments that react
 * to audio frequency data via Android's Visualizer API.
 */
class EqMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "EqMeterView"
        private const val BAR_COUNT = 20
        private const val SEGMENTS_PER_BAR = 24

        // Color thresholds (segment indices, 0-based)
        private const val GREEN_END = 12    // segments 0-11 are green
        private const val YELLOW_END = 18   // segments 12-17 are yellow
        // segments 18-23 are red

        // Colors matching the CloudAmp icon
        private val COLOR_GREEN = Color.parseColor("#22c55e")
        private val COLOR_YELLOW = Color.parseColor("#eab308")
        private val COLOR_RED = Color.parseColor("#ef4444")
        private val COLOR_SEGMENT_OFF = Color.parseColor("#111111")
        private val COLOR_BACKGROUND = Color.parseColor("#000000")

        // Animation constants
        private const val DECAY_RATE = 0.88f
        private const val PEAK_DECAY_RATE = 0.97f
        private const val PEAK_HOLD_FRAMES = 12

        // If Visualizer is attached but no FFT data arrives within this time, fall back to simulation
        private const val VISUALIZER_DATA_TIMEOUT_MS = 2000L
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = COLOR_BACKGROUND }

    // Current display levels (0.0 to 1.0) for each bar
    private val barLevels = FloatArray(BAR_COUNT)
    // Peak hold levels for each bar
    private val peakLevels = FloatArray(BAR_COUNT)
    private val peakHoldCounters = IntArray(BAR_COUNT)

    private var visualizer: Visualizer? = null
    private var isVisualizerActive = false
    private var visualizerAttachTime = 0L
    private var fftDataReceived = false

    // Simulated levels for when no real audio session is available (Spotify)
    private var simulationActive = false
    private var simulationPhase = 0f

    /**
     * Attach to an audio session for real-time FFT analysis.
     * Pass audioSessionId = 0 for mix output (requires RECORD_AUDIO permission).
     * Returns true if the Visualizer was successfully created and enabled.
     */
    fun attachToAudioSession(audioSessionId: Int): Boolean {
        releaseVisualizer()
        fftDataReceived = false
        return try {
            val viz = Visualizer(audioSessionId)
            viz.captureSize = Visualizer.getCaptureSizeRange()[1] // Max capture size
            viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int
                ) {
                    // Not used - we use FFT data
                }

                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int
                ) {
                    fft?.let { processFftData(it) }
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            viz.enabled = true
            visualizer = viz
            isVisualizerActive = true
            simulationActive = false
            visualizerAttachTime = SystemClock.elapsedRealtime()
            Log.d(TAG, "Visualizer attached to session $audioSessionId, enabled=${viz.enabled}")
            postInvalidateOnAnimation() // Start draw loop for timeout detection
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Visualizer for session $audioSessionId: ${e.message}", e)
            false
        }
    }

    /**
     * Returns true if the Visualizer is attached AND has actually received FFT data.
     */
    fun isReceivingData(): Boolean = isVisualizerActive && fftDataReceived

    /**
     * Start simulated EQ animation (used for Spotify where we have no audio session).
     */
    fun startSimulation() {
        simulationActive = true
        isVisualizerActive = false
        releaseVisualizer()
        postInvalidateOnAnimation()
    }

    /**
     * Stop all visualization and clear the display.
     */
    fun stopVisualization() {
        simulationActive = false
        isVisualizerActive = false
        releaseVisualizer()
        for (i in barLevels.indices) {
            barLevels[i] = 0f
            peakLevels[i] = 0f
            peakHoldCounters[i] = 0
        }
        invalidate()
    }

    /**
     * Set whether playback is active (controls simulation animation).
     */
    fun setPlaying(playing: Boolean) {
        if (!isVisualizerActive) {
            if (playing) {
                startSimulation()
            } else {
                simulationActive = false
                invalidate()
            }
        }
    }

    fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing visualizer: ${e.message}")
        }
        visualizer = null
        isVisualizerActive = false
    }

    /**
     * Process raw FFT data from the Visualizer into 20 frequency band levels.
     * FFT data is interleaved real/imaginary pairs.
     */
    private fun processFftData(fft: ByteArray) {
        fftDataReceived = true
        val n = fft.size / 2
        if (n < BAR_COUNT) return

        // Calculate magnitudes from FFT complex pairs
        val magnitudes = FloatArray(n)
        for (i in 0 until n) {
            val real = fft[2 * i].toFloat()
            val imag = fft[2 * i + 1].toFloat()
            magnitudes[i] = sqrt(real * real + imag * imag)
        }

        // Map frequency bins to 20 bars using logarithmic scale
        // This gives more resolution to lower frequencies (more musical detail)
        val newLevels = FloatArray(BAR_COUNT)
        val minBin = 1 // Skip DC
        val maxBin = n - 1
        val logMin = ln(minBin.toFloat())
        val logMax = ln(maxBin.toFloat())

        for (bar in 0 until BAR_COUNT) {
            val logStart = logMin + (logMax - logMin) * bar / BAR_COUNT
            val logEnd = logMin + (logMax - logMin) * (bar + 1) / BAR_COUNT
            val binStart = Math.exp(logStart.toDouble()).toInt().coerceIn(minBin, maxBin)
            val binEnd = Math.exp(logEnd.toDouble()).toInt().coerceIn(binStart, maxBin)

            var sum = 0f
            var count = 0
            for (bin in binStart..binEnd) {
                sum += magnitudes[bin]
                count++
            }
            val avg = if (count > 0) sum / count else 0f

            // Convert to dB-like scale (0.0 to 1.0)
            // Reference level tuned for typical music dynamics
            val db = if (avg > 0) 20f * Math.log10(avg.toDouble()).toFloat() else -60f
            val normalized = ((db + 40f) / 50f).coerceIn(0f, 1f)
            newLevels[bar] = normalized
        }

        // Apply levels with smooth decay on the UI thread
        post {
            for (i in 0 until BAR_COUNT) {
                if (newLevels[i] > barLevels[i]) {
                    barLevels[i] = newLevels[i]
                } else {
                    barLevels[i] = barLevels[i] * DECAY_RATE
                }

                // Peak hold
                if (barLevels[i] > peakLevels[i]) {
                    peakLevels[i] = barLevels[i]
                    peakHoldCounters[i] = PEAK_HOLD_FRAMES
                } else if (peakHoldCounters[i] > 0) {
                    peakHoldCounters[i]--
                } else {
                    peakLevels[i] *= PEAK_DECAY_RATE
                }
            }
            invalidate()
        }
    }

    /**
     * Update simulation levels to create a realistic-looking animated EQ.
     */
    private fun updateSimulation() {
        simulationPhase += 0.15f

        for (i in 0 until BAR_COUNT) {
            // Create a wave-like pattern with some randomness
            val wave1 = (Math.sin((simulationPhase + i * 0.4f).toDouble()) * 0.3 + 0.35).toFloat()
            val wave2 = (Math.sin((simulationPhase * 1.7f + i * 0.6f).toDouble()) * 0.2).toFloat()
            val wave3 = (Math.sin((simulationPhase * 0.5f + i * 0.2f).toDouble()) * 0.15).toFloat()
            val random = (Math.random() * 0.1f).toFloat()

            val target = (wave1 + wave2 + wave3 + random).coerceIn(0.05f, 0.95f)

            // Smooth transitions
            if (target > barLevels[i]) {
                barLevels[i] += (target - barLevels[i]) * 0.4f
            } else {
                barLevels[i] = barLevels[i] * DECAY_RATE
            }

            // Peak hold
            if (barLevels[i] > peakLevels[i]) {
                peakLevels[i] = barLevels[i]
                peakHoldCounters[i] = PEAK_HOLD_FRAMES
            } else if (peakHoldCounters[i] > 0) {
                peakHoldCounters[i]--
            } else {
                peakLevels[i] *= PEAK_DECAY_RATE
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (w == 0f || h == 0f) return

        // Auto-fallback: if Visualizer was attached but hasn't delivered any FFT data
        // within the timeout, it's not actually capturing audio - switch to simulation
        if (isVisualizerActive && !fftDataReceived) {
            val elapsed = SystemClock.elapsedRealtime() - visualizerAttachTime
            if (elapsed > VISUALIZER_DATA_TIMEOUT_MS) {
                Log.w(TAG, "Visualizer attached but no FFT data after ${elapsed}ms, falling back to simulation")
                releaseVisualizer()
                simulationActive = true
            }
        }

        // Update simulation if active
        if (simulationActive) {
            updateSimulation()
        }

        // Calculate bar dimensions
        val horizontalPadding = w * 0.02f
        val totalBarWidth = w - horizontalPadding * 2
        val barGap = totalBarWidth * 0.012f  // Gap between bars
        val barWidth = (totalBarWidth - barGap * (BAR_COUNT - 1)) / BAR_COUNT

        val verticalPadding = h * 0.08f
        val totalBarHeight = h - verticalPadding * 2
        val segmentGap = totalBarHeight * 0.015f  // Gap between segments
        val segmentHeight = (totalBarHeight - segmentGap * (SEGMENTS_PER_BAR - 1)) / SEGMENTS_PER_BAR

        for (bar in 0 until BAR_COUNT) {
            val level = barLevels[bar]
            val peakLevel = peakLevels[bar]
            val activeSegments = (level * SEGMENTS_PER_BAR).toInt()
            val peakSegment = (peakLevel * SEGMENTS_PER_BAR).toInt().coerceAtMost(SEGMENTS_PER_BAR - 1)

            val barX = horizontalPadding + bar * (barWidth + barGap)

            for (seg in 0 until SEGMENTS_PER_BAR) {
                // Segments draw bottom-up: seg 0 = bottom, seg 23 = top
                val segY = h - verticalPadding - (seg + 1) * segmentHeight - seg * segmentGap

                val isActive = seg < activeSegments
                val isPeak = seg == peakSegment && peakLevel > 0.02f

                if (isActive || isPeak) {
                    // Determine color based on segment position
                    barPaint.color = when {
                        seg < GREEN_END -> COLOR_GREEN
                        seg < YELLOW_END -> COLOR_YELLOW
                        else -> COLOR_RED
                    }
                    if (isPeak && !isActive) {
                        // Peak indicator is slightly dimmer
                        barPaint.alpha = 180
                    }
                } else {
                    barPaint.color = COLOR_SEGMENT_OFF
                }

                canvas.drawRect(
                    barX,
                    segY,
                    barX + barWidth,
                    segY + segmentHeight,
                    barPaint
                )
            }
        }

        // Request next frame if animating
        if (simulationActive || isVisualizerActive) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseVisualizer()
    }
}
