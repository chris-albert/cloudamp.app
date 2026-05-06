package com.cloudamp.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Custom view that renders an EQ bar visualization from FFT data.
 * Logarithmically maps frequency bins to visual bars with gradient colors,
 * peak tracking, and a reflection effect.
 */
class EqVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val barCount = 64
    private val gap = 2f * resources.displayMetrics.density
    private val peaks = FloatArray(barCount)
    private val smoothedBars = FloatArray(barCount)

    private var fftData: ByteArray? = null

    /**
     * Update the FFT data. Called from the Visualizer callback.
     * The byte array is the raw FFT output from Android's Visualizer.
     */
    fun updateFft(data: ByteArray) {
        fftData = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.drawColor(0xFF000000.toInt())

        val data = fftData ?: return
        val bars = computeLogBars(data)

        val barWidth = (w - gap * (barCount - 1)) / barCount
        val baseY = h * 0.80f
        val reflSpace = h - baseY

        for (i in 0 until barCount) {
            // Smooth the bars for a less jittery look
            smoothedBars[i] = smoothedBars[i] * 0.4f + bars[i] * 0.6f
            val val_ = smoothedBars[i]
            val barH = val_ * baseY

            // Peak tracking
            if (peaks[i] < barH) {
                peaks[i] = barH
            } else {
                peaks[i] = max(0f, peaks[i] - 1.5f * resources.displayMetrics.density)
            }

            val x = i * (barWidth + gap)

            // Color: warm bass (red/orange hue~0-30) → cool treble (blue hue~200)
            val hue = 200f - (i.toFloat() / barCount) * 200f
            val sat = 0.8f + val_ * 0.2f
            val light = 0.45f + val_ * 0.2f

            // Bar gradient
            val colorBottom = hslToColor(hue, sat, light * 0.6f)
            val colorTop = hslToColor(hue, sat, light)

            val gradient = LinearGradient(
                x, baseY, x, baseY - barH,
                colorBottom, colorTop,
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradient
            canvas.drawRect(x, baseY - barH, x + barWidth, baseY, barPaint)

            // Peak indicator
            if (peaks[i] > 2f) {
                peakPaint.color = hslToColor(hue, 0.9f, 0.7f)
                canvas.drawRect(x, baseY - peaks[i], x + barWidth, baseY - peaks[i] + 2f * resources.displayMetrics.density, peakPaint)
            }

            // Reflection
            val reflH = min(barH * 0.4f, reflSpace)
            if (reflH > 0) {
                val reflGradient = LinearGradient(
                    x, baseY, x, baseY + reflH,
                    hslToColor(hue, sat, light, 0.3f),
                    hslToColor(hue, sat, light, 0f),
                    Shader.TileMode.CLAMP
                )
                reflectionPaint.shader = reflGradient
                canvas.drawRect(x, baseY, x + barWidth, baseY + reflH, reflectionPaint)
            }
        }
    }

    /**
     * Convert Android Visualizer FFT bytes to logarithmically-spaced bar magnitudes (0..1).
     *
     * The FFT data from Android is interleaved real/imaginary byte pairs.
     * Raw FFT magnitudes naturally skew heavily toward the low end, so we
     * apply a frequency-dependent gain ramp and sqrt compression to produce
     * perceptually balanced bars (white noise should look roughly flat).
     */
    private fun computeLogBars(fft: ByteArray): FloatArray {
        val out = FloatArray(barCount)
        val numBins = fft.size / 2

        if (numBins <= 1) return out

        val logMin = ln(2.0) // Skip DC, start from bin 2
        val logMax = ln(numBins.toDouble())
        val logStep = (logMax - logMin) / barCount

        for (i in 0 until barCount) {
            val startBin = floor(exp(logMin + logStep * i)).toInt().coerceIn(1, numBins - 1)
            val endBin = floor(exp(logMin + logStep * (i + 1))).toInt().coerceIn(startBin + 1, numBins)

            var sum = 0f
            var count = 0
            for (b in startBin until endBin) {
                val realIdx = 2 * b
                val imagIdx = 2 * b + 1
                if (imagIdx < fft.size) {
                    val real = fft[realIdx].toFloat()
                    val imag = fft[imagIdx].toFloat()
                    val magnitude = sqrt(real * real + imag * imag)
                    sum += magnitude
                    count++
                }
            }

            if (count > 0) {
                val avgMag = sum / count
                val normalized = (avgMag / 180f).coerceIn(0f, 1f)
                // sqrt compression: brings quiet signals up for better visual range
                out[i] = sqrt(normalized)
            }
        }
        return out
    }

    /**
     * Convert HSL to ARGB color int.
     */
    private fun hslToColor(h: Float, s: Float, l: Float, alpha: Float = 1f): Int {
        val hNorm = h / 360f
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs((hNorm * 6f) % 2f - 1f))
        val m = l - c / 2f

        val (r1, g1, b1) = when {
            hNorm < 1f / 6f -> Triple(c, x, 0f)
            hNorm < 2f / 6f -> Triple(x, c, 0f)
            hNorm < 3f / 6f -> Triple(0f, c, x)
            hNorm < 4f / 6f -> Triple(0f, x, c)
            hNorm < 5f / 6f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
