package com.cloudamp.music.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cloudamp.music.R

class AlphabetSidebarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnLetterSelectedListener {
        fun onLetterSelected(letter: String)
    }

    private val letters = listOf("#") + ('A'..'Z').map { it.toString() }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.winamp_text)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.winamp_text)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    var listener: OnLetterSelectedListener? = null
    private var selectedIndex = -1
    private var isTouching = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val itemHeight = height.toFloat() / letters.size
        textPaint.textSize = (itemHeight * 0.7f).coerceAtMost(28f)
        highlightPaint.textSize = textPaint.textSize

        val centerX = width / 2f

        for (i in letters.indices) {
            val y = itemHeight * i + itemHeight / 2f + textPaint.textSize / 3f
            val paint = if (i == selectedIndex && isTouching) highlightPaint else textPaint
            canvas.drawText(letters[i], centerX, y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isTouching = true
                val index = (event.y / height * letters.size).toInt()
                    .coerceIn(0, letters.size - 1)
                if (index != selectedIndex) {
                    selectedIndex = index
                    listener?.onLetterSelected(letters[index])
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                selectedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
