package com.cloudamp.music.ui

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Shared math for converting Android Visualizer FFT bytes (interleaved
 * real/imaginary pairs) into perceptually balanced magnitudes, mirroring the
 * web app's log-frequency remapping.
 */
object VisMath {

    /**
     * Fill [out] with logarithmically-spaced bar magnitudes in 0..1.
     * Applies /180 normalization and sqrt compression like EqVisualizerView.
     */
    fun logBars(fft: ByteArray, out: FloatArray) {
        val count = out.size
        val numBins = fft.size / 2
        if (numBins <= 1) {
            out.fill(0f)
            return
        }

        val logMin = ln(2.0) // Skip DC, start from bin 2
        val logMax = ln(numBins.toDouble())
        val logStep = (logMax - logMin) / count

        for (i in 0 until count) {
            val startBin = floor(exp(logMin + logStep * i)).toInt().coerceIn(1, numBins - 1)
            val endBin = floor(exp(logMin + logStep * (i + 1))).toInt().coerceIn(startBin + 1, numBins)

            var sum = 0f
            var n = 0
            for (b in startBin until endBin) {
                val realIdx = 2 * b
                val imagIdx = 2 * b + 1
                if (imagIdx < fft.size) {
                    val real = fft[realIdx].toFloat()
                    val imag = fft[imagIdx].toFloat()
                    sum += sqrt(real * real + imag * imag)
                    n++
                }
            }

            out[i] = if (n > 0) sqrt((sum / n / 180f).coerceIn(0f, 1f)) else 0f
        }
    }

    /** Convert HSL to ARGB color int. */
    fun hslToColor(h: Float, s: Float, l: Float, alpha: Float = 1f): Int {
        val hNorm = (((h % 360f) + 360f) % 360f) / 360f
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
