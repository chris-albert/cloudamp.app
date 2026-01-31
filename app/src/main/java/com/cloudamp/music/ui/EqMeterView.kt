package com.cloudamp.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Real-time EQ spectrum meter display styled after the CloudAmp icon.
 * Displays 20 vertical bars with green/yellow/red segments that react
 * to audio frequency data.
 *
 * Two modes of operation:
 * 1. Spectrum provider: polls a FloatArray(20) from an external source
 *    (e.g., SpectrumAudioProcessor in ExoPlayer's pipeline) each frame.
 * 2. Simulation: generates a fake animated EQ pattern (for Spotify, etc.).
 */
class EqMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
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
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint().apply { color = COLOR_BACKGROUND }

    // Current display levels (0.0 to 1.0) for each bar
    private val barLevels = FloatArray(BAR_COUNT)
    // Peak hold levels for each bar
    private val peakLevels = FloatArray(BAR_COUNT)
    private val peakHoldCounters = IntArray(BAR_COUNT)

    // External spectrum data provider (e.g., from SpectrumAudioProcessor)
    private var spectrumProvider: (() -> FloatArray?)? = null

    // Simulated levels for when no real audio data is available (Spotify)
    private var simulationActive = false
    private var simulationPhase = 0f

    /**
     * Set an external spectrum data provider. When set, the view polls this
     * function each frame to get a FloatArray of band levels (0.0 to 1.0).
     * Pass null to clear the provider and revert to simulation mode.
     */
    fun setSpectrumProvider(provider: (() -> FloatArray?)?) {
        spectrumProvider = provider
        if (provider != null) {
            simulationActive = false
            postInvalidateOnAnimation()
        }
    }

    /**
     * Start simulated EQ animation (used for Spotify where we have no audio data).
     */
    fun startSimulation() {
        simulationActive = true
        postInvalidateOnAnimation()
    }

    /**
     * Stop all visualization and clear the display.
     */
    fun stopVisualization() {
        simulationActive = false
        spectrumProvider = null
        for (i in barLevels.indices) {
            barLevels[i] = 0f
            peakLevels[i] = 0f
            peakHoldCounters[i] = 0
        }
        invalidate()
    }

    /**
     * Set whether playback is active (controls simulation animation).
     * No-op when a spectrum provider is active.
     */
    fun setPlaying(playing: Boolean) {
        if (spectrumProvider != null) return
        if (playing) {
            startSimulation()
        } else {
            simulationActive = false
            invalidate()
        }
    }

    /**
     * Update bar levels from the external spectrum provider.
     */
    private fun updateFromProvider(spectrum: FloatArray) {
        val count = minOf(spectrum.size, BAR_COUNT)
        for (i in 0 until count) {
            val value = spectrum[i].coerceIn(0f, 1f)
            if (value > barLevels[i]) {
                barLevels[i] = value
            } else {
                barLevels[i] *= DECAY_RATE
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

        // Update from provider or simulation
        val provider = spectrumProvider
        if (provider != null) {
            val spectrum = provider()
            if (spectrum != null) {
                updateFromProvider(spectrum)
            }
        } else if (simulationActive) {
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
        if (spectrumProvider != null || simulationActive) {
            postInvalidateOnAnimation()
        }
    }
}
