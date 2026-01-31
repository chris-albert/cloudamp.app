package com.cloudamp.music.playback

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ExoPlayer AudioProcessor that taps into the audio pipeline to compute
 * a real-time 20-band frequency spectrum. Audio passes through unmodified.
 *
 * This is more reliable than Android's Visualizer API because it reads
 * PCM data directly from ExoPlayer's rendering pipeline.
 */
class SpectrumAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val BAND_COUNT = 20
        private const val FFT_SIZE = 1024
    }

    private var sampleRate = 44100
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT

    // Circular sample buffer for FFT input (mono, normalized -1.0 to 1.0)
    private val sampleBuffer = FloatArray(FFT_SIZE)
    private var sampleIndex = 0

    // Published spectrum: 20 bands, each 0.0 to 1.0. Replaced atomically.
    @Volatile
    var spectrum = FloatArray(BAND_COUNT)
        private set

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        return inputAudioFormat // Pass through unchanged
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Read PCM samples for analysis (don't consume the buffer)
        val pos = inputBuffer.position()
        val lim = inputBuffer.limit()
        val order = inputBuffer.order()
        inputBuffer.order(ByteOrder.nativeOrder())
        extractSamples(inputBuffer)
        inputBuffer.position(pos)
        inputBuffer.limit(lim)
        inputBuffer.order(order)

        // Pass audio through unchanged
        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)
        output.put(inputBuffer)
        output.flip()
    }

    private fun extractSamples(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT) return

        val bytesPerSample = 2 // 16-bit
        val frameSize = bytesPerSample * channelCount

        while (buffer.remaining() >= frameSize) {
            // Read first channel
            val sample = buffer.short.toFloat() / Short.MAX_VALUE

            // Mix to mono: average all channels
            var mixed = sample
            for (ch in 1 until channelCount) {
                mixed += buffer.short.toFloat() / Short.MAX_VALUE
            }
            mixed /= channelCount

            sampleBuffer[sampleIndex] = mixed
            sampleIndex++

            if (sampleIndex >= FFT_SIZE) {
                computeSpectrum()
                sampleIndex = 0
            }
        }
    }

    private fun computeSpectrum() {
        // Apply Hanning window
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            val window = 0.5 * (1 - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)))
            real[i] = sampleBuffer[i] * window.toFloat()
        }

        // In-place Cooley-Tukey radix-2 FFT
        fft(real, imag)

        // Map FFT bins to 20 bands using logarithmic frequency scale
        val bands = FloatArray(BAND_COUNT)
        val halfN = FFT_SIZE / 2
        val logMin = Math.log(1.0)
        val logMax = Math.log(halfN.toDouble())

        for (band in 0 until BAND_COUNT) {
            val logStart = logMin + (logMax - logMin) * band / BAND_COUNT
            val logEnd = logMin + (logMax - logMin) * (band + 1) / BAND_COUNT
            val binStart = Math.exp(logStart).toInt().coerceIn(1, halfN - 1)
            val binEnd = Math.exp(logEnd).toInt().coerceIn(binStart, halfN - 1)

            var sum = 0f
            var count = 0
            for (bin in binStart..binEnd) {
                val mag = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
                sum += mag
                count++
            }
            val avg = if (count > 0) sum / count else 0f

            // Convert to dB-like normalized scale (0.0 to 1.0)
            val db = if (avg > 0.001f) 20f * log10(avg) else -60f
            bands[band] = ((db + 20f) / 40f).coerceIn(0f, 1f)
        }

        // Atomic publish
        spectrum = bands
    }

    /**
     * In-place Cooley-Tukey radix-2 FFT.
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (j > i) {
                var tmp = real[j]; real[j] = real[i]; real[i] = tmp
                tmp = imag[j]; imag[j] = imag[i]; imag[i] = tmp
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }

        // Butterfly operations
        var step = 2
        while (step <= n) {
            val halfStep = step / 2
            val angle = -2.0 * Math.PI / step
            for (k in 0 until halfStep) {
                val w = angle * k
                val wr = cos(w).toFloat()
                val wi = Math.sin(w).toFloat()
                var i = k
                while (i < n) {
                    val ir = i + halfStep
                    val tr = wr * real[ir] - wi * imag[ir]
                    val ti = wr * imag[ir] + wi * real[ir]
                    real[ir] = real[i] - tr
                    imag[ir] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += step
                }
            }
            step *= 2
        }
    }
}
