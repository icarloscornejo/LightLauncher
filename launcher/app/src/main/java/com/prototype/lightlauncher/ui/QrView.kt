package com.prototype.lightlauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.prototype.lightlauncher.core.QrMatrix

/**
 * Draws a QR code the way a phone reads it best: dark modules on a white card with a quiet border of four
 * modules, at a whole number of pixels per module so no edge is blurred. Square: it takes the smaller of its sides.
 */
class QrView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = RectF()
    private var matrix: QrMatrix? = null

    fun show(matrix: QrMatrix?) {
        this.matrix = matrix
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side: Int = Math.min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val code: QrMatrix = matrix ?: return
        val modules: Int = code.size + 2 * QUIET
        val pixels: Int = Math.max(1, width / modules)
        val side: Float = (pixels * modules).toFloat()
        val left: Float = (width - side) / 2f
        val top: Float = (height - side) / 2f
        paint.color = Color.WHITE
        card.set(left, top, left + side, top + side)
        canvas.drawRoundRect(card, pixels * 2f, pixels * 2f, paint)
        paint.color = Color.BLACK
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (!code.isDark(x, y)) continue
                val x0: Float = left + (x + QUIET) * pixels
                val y0: Float = top + (y + QUIET) * pixels
                canvas.drawRect(x0, y0, x0 + pixels, y0 + pixels, paint)
            }
        }
    }

    private companion object {
        /** The light border the standard asks for around the code, in modules. */
        const val QUIET: Int = 4
    }
}
