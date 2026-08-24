package com.cloudamp.music.ui

/**
 * Common contract for views that render audio visualizations from FFT data.
 */
interface AudioVisualizerView {
    fun updateFft(data: ByteArray)
    fun updateWaveform(data: ByteArray) {}
}
