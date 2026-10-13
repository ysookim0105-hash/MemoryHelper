package com.memoryhelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class GridOverlayView(context: Context) : View(context) {

    private var state: GridState? = null
    private var sw = 0
    private var sh = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    // 15 vivid hues, semi-transparent
    private val COLORS = intArrayOf(
        0xAA4A90E2.toInt(), 0xAAE74C3C.toInt(), 0xAA2ECC71.toInt(),
        0xAAF39C12.toInt(), 0xAA9B59B6.toInt(), 0xAA1ABC9C.toInt(),
        0xAAE67E22.toInt(), 0xAA3498DB.toInt(), 0xAAE91E63.toInt(),
        0xAA00BCD4.toInt(), 0xAA8BC34A.toInt(), 0xAAFF5722.toInt(),
        0xAA607D8B.toInt(), 0xAACDDC39.toInt(), 0xAA795548.toInt()
    )

    fun update(gridState: GridState, screenW: Int, screenH: Int) {
        state = gridState; sw = screenW; sh = screenH
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = state ?: return
        val cfg = s.config

        val gl = cfg.leftFrac * sw
        val gt = cfg.topFrac * sh
        val cellW = (cfg.rightFrac - cfg.leftFrac) * sw / cfg.cols
        val cellH = (cfg.bottomFrac - cfg.topFrac) * sh / cfg.rows

        for (i in 0 until s.total) {
            val col = i % cfg.cols
            val row = i / cfg.cols
            val x = gl + col * cellW
            val y = gt + row * cellH
            val rect = RectF(x + 5, y + 5, x + cellW - 5, y + cellH - 5)
            val cardId = s.cards[i]

            when {
                cardId > 0 -> {
                    fill.color = COLORS[(cardId - 1) % COLORS.size]
                    canvas.drawRoundRect(rect, 14f, 14f, fill)
                    text.textSize = cellH * 0.42f
                    canvas.drawText(
                        cardId.toString(),
                        x + cellW / 2,
                        y + cellH / 2 + text.textSize * 0.36f,
                        text
                    )
                }
                cardId == 0 -> {
                    stroke.color = 0x55FFFFFF
                    canvas.drawRoundRect(rect, 10f, 10f, stroke)
                }
            }
        }
    }
}
