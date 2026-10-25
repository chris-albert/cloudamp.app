package com.cloudamp.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas 2D port of the web radial spectrum visualization: rotating
 * log-frequency bars around a bass-pulsing inner circle, with a waveform
 * ring and trail/decay feedback effect.
 */
class RadialSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), AudioVisualizerView {

    companion object {
        // Web uses 512; fewer bars keeps the software canvas fast on phones
        private const val BAR_COUNT = 256
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val circleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val decayPaint = Paint().apply {
        color = Color.argb(51, 0, 0, 0) // ~0.2 alpha trail decay
    }

    private val wavePath = Path()
    private val bars = FloatArray(BAR_COUNT)

    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null

    private val startTimeNs = System.nanoTime()

    private var fftData: ByteArray? = null
    private var waveData: ByteArray? = null

    override fun updateFft(data: ByteArray) {
        fftData = data
        invalidate()
    }

    override fun updateWaveform(data: ByteArray) {
        waveData = data
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

        val fft = fftData
        if (fft != null) {
            VisMath.logBars(fft, bars)

            val density = resources.displayMetrics.density
            val time = (System.nanoTime() - startTimeNs) / 1_000_000_000f
            val cx = w / 2f
            val cy = h / 2f
            val minDim = min(w, h)
            val innerRadius = minDim * 0.12f
            val maxBarLen = minDim * 0.3f
            val rotation = time * 0.2f

            // Bass energy for the inner circle pulse
            var bassSum = 0f
            for (i in 0 until BAR_COUNT / 16) bassSum += bars[i]
            val bassEnergy = bassSum / (BAR_COUNT / 16)
            val pulseRadius = innerRadius * (0.8f + bassEnergy * 0.5f)

            // Inner pulsing circle
            val bassHue = (time * 40f) % 360f
            circleFillPaint.color = VisMath.hslToColor(bassHue, 0.7f, 0.2f, 0.6f)
            tc.drawCircle(cx, cy, pulseRadius, circleFillPaint)
            circleStrokePaint.color = VisMath.hslToColor(bassHue, 0.8f, 0.5f, 0.5f)
            circleStrokePaint.strokeWidth = 1.5f * density
            tc.drawCircle(cx, cy, pulseRadius, circleStrokePaint)

            // Radial bars
            barPaint.strokeWidth = (2f * Math.PI.toFloat() * innerRadius) / BAR_COUNT * 0.6f
            for (i in 0 until BAR_COUNT) {
                val value = bars[i]
                val barLen = value * maxBarLen

                val angle = (i.toFloat() / BAR_COUNT) * 2f * Math.PI.toFloat() + rotation
                val cosA = cos(angle)
                val sinA = sin(angle)
                val x1 = cx + cosA * innerRadius
                val y1 = cy + sinA * innerRadius
                val x2 = cx + cosA * (innerRadius + barLen)
                val y2 = cy + sinA * (innerRadius + barLen)

                val hue = ((i.toFloat() / BAR_COUNT) * 360f + time * 50f) % 360f
                barPaint.color = VisMath.hslToColor(hue, 0.85f, 0.5f + value * 0.3f, 0.5f + value * 0.5f)
                tc.drawLine(x1, y1, x2, y2, barPaint)
            }

            // Waveform ring
            val wave = waveData
            if (wave != null && wave.size >= BAR_COUNT) {
                wavePath.reset()
                val waveStep = wave.size / BAR_COUNT
                for (i in 0 until BAR_COUNT) {
                    val v = ((wave[i * waveStep].toInt() and 0xFF) - 128) / 128f
                    val r = innerRadius * 0.7f + v * innerRadius * 0.3f
                    val angle = (i.toFloat() / BAR_COUNT) * 2f * Math.PI.toFloat() + rotation
                    val x = cx + cos(angle) * r
                    val y = cy + sin(angle) * r
                    if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                }
                wavePath.close()
                wavePaint.color = VisMath.hslToColor((bassHue + 180f) % 360f, 0.6f, 0.6f, 0.4f)
                wavePaint.strokeWidth = 1f * density
                tc.drawPath(wavePath, wavePaint)
            }
        }

        canvas.drawBitmap(trailBitmap!!, 0f, 0f, null)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
    }
}
