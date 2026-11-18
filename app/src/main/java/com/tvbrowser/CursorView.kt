package com.tvbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Transparent overlay that draws the remote-controlled mouse pointer. */
class CursorView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var cx = 0f
    private var cy = 0f
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
    }

    init { isClickable = false; isFocusable = false }

    fun setPos(x: Float, y: Float) { cx = x; cy = y; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawCircle(cx, cy, 9 * d, fill)
        c.drawCircle(cx, cy, 9 * d, stroke)
    }
}
