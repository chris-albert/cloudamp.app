package com.cloudamp.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Canvas 2D port of the web oscilloscope visualization: a glowing waveform
 * line with a slowly cycling hue, a dimmer offset-hue echo line, and a
 * trail/decay feedback effect.
 */
class OscilloscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), AudioVisualizerView {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val decayPaint = Paint().apply {
        color = Color.argb(38, 0, 0, 0) // ~0.15 alpha trail decay
    }

    private val path = Path()

    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null

    private val startTimeNs = System.nanoTime()

    private var waveData: ByteArray? = null

    override fun updateFft(data: ByteArray) {
        // Oscilloscope renders waveform data only
    }

    override fun updateWaveform(data: ByteArray) {
        waveData = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (trailBitmap == null || trailBitmap!!.width != width || trailBitmap!!.height != height) {
            trailBitmap?.recycle()
            trailBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            trailCanvas = Canvas(trailBitmap!!).apply { drawColor(Color.BLACK) }
        }

        val tc = trailCanvas!!
        tc.drawRect(0f, 0f, w, h, decayPaint)

        val wave = waveData
        if (wave != null && wave.isNotEmpty()) {
            val time = (System.nanoTime() - startTimeNs) / 1_000_000_000f
            val hue = (time * 30f) % 360f
            val density = resources.displayMetrics.density
            val centerY = h / 2f

            // Main line: wide translucent stroke as glow + crisp bright core
            buildWavePath(wave, w, centerY, 0.8f)
            glowPaint.strokeWidth = 9f * density
            glowPaint.color = VisMath.hslToColor(hue, 1f, 0.6f, 0.25f)
            tc.drawPath(path, glowPaint)
            linePaint.strokeWidth = 2.5f * density
            linePaint.color = VisMath.hslToColor(hue, 1f, 0.7f, 0.9f)
            tc.drawPath(path, linePaint)

            // Second dimmer line with offset hue
            buildWavePath(wave, w, centerY, 0.6f)
            linePaint.strokeWidth = 1.5f * density
            linePaint.color = VisMath.hslToColor((hue + 120f) % 360f, 1f, 0.7f, 0.3f)
            tc.drawPath(path, linePaint)
        }

        canvas.drawBitmap(trailBitmap!!, 0f, 0f, null)
    }

    private fun buildWavePath(wave: ByteArray, w: Float, centerY: Float, amplitude: Float) {
        path.reset()
        val sliceWidth = w / wave.size
        for (i in wave.indices) {
            // Waveform bytes are unsigned 8-bit PCM centered at 128
            val v = ((wave[i].toInt() and 0xFF) - 128) / 128f
            val y = centerY + v * centerY * amplitude
            val x = i * sliceWidth
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
    }
}
