package com.tvbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The remote-controlled mouse pointer. It is a small view that is moved with translationX/Y,
 * so moving it never forces a repaint of the whole screen (much smoother on a TV box).
 */
class CursorView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val size = (28 * d).toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
    }

    init { isClickable = false; isFocusable = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)

    fun setPos(x: Float, y: Float) {
        translationX = x - size / 2f
        translationY = y - size / 2f
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(size / 2f, size / 2f, 9 * d, fill)
        c.drawCircle(size / 2f, size / 2f, 9 * d, stroke)
    }
}
