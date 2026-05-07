package com.cloudamp.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canvas 2D port of the web warp grid visualization.
 * Renders an animated grid with audio-driven wave displacement,
 * HSV color cycling, and a trail/decay feedback effect.
 */
class WarpGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), AudioVisualizerView {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val decayPaint = Paint().apply {
        color = Color.argb(15, 0, 0, 0)
    }

    private val path = Path()
    private val zoomMatrix = Matrix()

    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null

    private val startTimeNs = System.nanoTime()

    // Smoothed frequency bands
    private var smoothBass = 0f
    private var smoothMid = 0f
    private var smoothTreble = 0f

    private var fftData: ByteArray? = null

    private val gridScale = 20
    private val segments = 30

    override fun updateFft(data: ByteArray) {
        fftData = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Ensure trail bitmap matches view size
        if (trailBitmap == null || trailBitmap!!.width != width || trailBitmap!!.height != height) {
            trailBitmap?.recycle()
            trailBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            trailCanvas = Canvas(trailBitmap!!)
        }

        val tc = trailCanvas!!
        val tb = trailBitmap!!

        // Extract frequency bands
        val data = fftData
        if (data != null) {
            val (rawBass, rawMid, rawTreble) = extractBands(data)
            smoothBass = smoothBass * 0.4f + rawBass * 0.6f
            smoothMid = smoothMid * 0.4f + rawMid * 0.6f
            smoothTreble = smoothTreble * 0.4f + rawTreble * 0.6f
        }

        val bass = smoothBass
        val mid = smoothMid
        val treble = smoothTreble
        val energy = (bass + mid + treble) / 3f

        val time = (System.nanoTime() - startTimeNs) / 1_000_000_000f

        // --- Feedback: decay + zoom ---
        // Draw semi-transparent black overlay for trail decay
        tc.drawRect(0f, 0f, w, h, decayPaint)

        // Zoom slightly toward center (simulates WebGL feedback zoom)
        val zoom = 0.99f - bass * 0.008f
        zoomMatrix.reset()
        zoomMatrix.postTranslate(-w / 2f, -h / 2f)
        zoomMatrix.postScale(zoom, zoom)
        zoomMatrix.postTranslate(w / 2f, h / 2f)

        val zoomed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val zoomedCanvas = Canvas(zoomed)
        zoomedCanvas.drawBitmap(tb, zoomMatrix, null)
        tc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        tc.drawBitmap(zoomed, 0f, 0f, null)
        zoomed.recycle()

        // --- Draw new grid lines ---
        val cellW = w / gridScale
        val cellH = h / gridScale

        // Vertical lines
        for (i in 0..gridScale) {
            path.reset()
            for (j in 0..segments) {
                val y = j * (h / segments)
                val normX = i.toFloat()
                val normY = y / h * gridScale

                val wave = sin(normX + time * 2f + bass * 6f) * 0.3f +
                        sin(normY + time * 1.5f + mid * 5f) * 0.3f

                val displacedX = i * cellW + wave * cellW

                if (j == 0) path.moveTo(displacedX, y)
                else path.lineTo(displacedX, y)
            }

            val freqIdx = (i.toFloat() / gridScale * 0.5f + time * 0.05f) % 1f
            val hue = ((time * 0.15f + freqIdx * 0.5f) % 1f) * 360f
            val brightness = 0.3f + energy * 0.5f
            linePaint.color = hsvToColor(hue, 0.8f, brightness)
            tc.drawPath(path, linePaint)
        }

        // Horizontal lines
        for (j in 0..gridScale) {
            path.reset()
            for (i in 0..segments) {
                val x = i * (w / segments)
                val normX = x / w * gridScale
                val normY = j.toFloat()

                val wave = sin(normX + time * 2f + bass * 6f) * 0.3f +
                        sin(normY + time * 1.5f + mid * 5f) * 0.3f

                val displacedY = j * cellH + wave * cellH

                if (i == 0) path.moveTo(x, displacedY)
                else path.lineTo(x, displacedY)
            }

            val freqIdx = (j.toFloat() / gridScale * 0.3f + time * 0.03f) % 1f
            val hue = ((time * 0.15f + freqIdx * 0.5f + 0.2f) % 1f) * 360f
            val brightness = 0.3f + energy * 0.5f
            linePaint.color = hsvToColor(hue, 0.8f, brightness)
            tc.drawPath(path, linePaint)
        }

        // Intersection glow dots
        val dotRadius = 2f * resources.displayMetrics.density
        for (i in 0..gridScale) {
            for (j in 0..gridScale) {
                val normX = i.toFloat()
                val normY = j.toFloat()

                val wave = sin(normX + time * 2f + bass * 6f) * 0.3f +
                        sin(normY + time * 1.5f + mid * 5f) * 0.3f

                val dx = i * cellW + wave * cellW
                val dy = j * cellH + wave * cellH

                val freqIdx = ((normX * 0.05f + normY * 0.03f) % 1f).let {
                    if (it < 0f) it + 1f else it
                }
                val hue = ((time * 0.15f + freqIdx * 0.5f + wave * 0.2f) % 1f).let {
                    if (it < 0f) it + 1f else it
                } * 360f
                val brightness = (energy * 3f).coerceAtMost(1f)

                glowPaint.color = hsvToColor(hue, 0.9f, brightness * 0.8f)
                tc.drawCircle(dx, dy, dotRadius, glowPaint)
            }
        }

        // Draw trail bitmap to screen
        canvas.drawBitmap(tb, 0f, 0f, null)
    }

    private fun extractBands(fft: ByteArray): Triple<Float, Float, Float> {
        val numBins = fft.size / 2
        if (numBins <= 1) return Triple(0f, 0f, 0f)

        fun bandEnergy(start: Int, end: Int): Float {
            var sum = 0f
            var count = 0
            for (b in start until end.coerceAtMost(numBins)) {
                val ri = 2 * b
                val ii = 2 * b + 1
                if (ii < fft.size) {
                    val r = fft[ri].toFloat()
                    val i = fft[ii].toFloat()
                    sum += sqrt(r * r + i * i)
                    count++
                }
            }
            return if (count > 0) (sum / count / 180f).coerceIn(0f, 1f) else 0f
        }

        return Triple(
            sqrt(bandEnergy(2, 8)),
            sqrt(bandEnergy(8, 40)),
            sqrt(bandEnergy(40, numBins))
        )
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): Int {
        val hNorm = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - Math.abs((hNorm / 60f) % 2f - 1f))
        val m = v - c

        val (r1, g1, b1) = when {
            hNorm < 60f -> Triple(c, x, 0f)
            hNorm < 120f -> Triple(x, c, 0f)
            hNorm < 180f -> Triple(0f, c, x)
            hNorm < 240f -> Triple(0f, x, c)
            hNorm < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
    }
}
