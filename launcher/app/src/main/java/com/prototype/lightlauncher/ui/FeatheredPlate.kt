package com.prototype.lightlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * A translucent black plate whose chosen edges fade out smoothly, so it reads as a soft shadow
 * behind text instead of a box. An edge that is not faded stays solid (a plate flush with a screen corner).
 * The fade is baked into one small bitmap whenever the size changes; drawing it is a single texture blit.
 *
 * @param maxAlpha opacity of the solid middle, 0 to 255
 * @param featherPx how far in from a faded edge the fade reaches
 */
class FeatheredPlate(
    private val maxAlpha: Int,
    private val featherPx: Float,
    private val fadeLeft: Boolean,
    private val fadeTop: Boolean,
    private val fadeRight: Boolean,
    private val fadeBottom: Boolean,
) : Drawable() {

    private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null

    override fun onBoundsChange(bounds: Rect) {
        val width: Int = bounds.width()
        val height: Int = bounds.height()
        bitmap?.recycle()
        bitmap = null
        if (width <= 0 || height <= 0) return
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val fromTop: Float = if (fadeTop) y + 0.5f else Float.MAX_VALUE
            val fromBottom: Float = if (fadeBottom) height - y - 0.5f else Float.MAX_VALUE
            val fadeY: Float = smoothStep(Math.min(fromTop, fromBottom) / featherPx)
            for (x in 0 until width) {
                val fromLeft: Float = if (fadeLeft) x + 0.5f else Float.MAX_VALUE
                val fromRight: Float = if (fadeRight) width - x - 0.5f else Float.MAX_VALUE
                val fadeX: Float = smoothStep(Math.min(fromLeft, fromRight) / featherPx)
                pixels[y * width + x] = Math.round(maxAlpha * fadeX * fadeY) shl 24 // black, alpha only
            }
        }
        bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun smoothStep(t: Float): Float {
        val clamped: Float = t.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    override fun draw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, null, bounds, paint) }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
