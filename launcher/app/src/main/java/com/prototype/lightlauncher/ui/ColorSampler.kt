package com.prototype.lightlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.prototype.lightlauncher.core.ColorMath

/**
 * The colours the launcher reads off an app's banner (or icon). One place for the picks the
 * tiles, the ambient glow and the screensaver all use. HSV goes through ColorMath, the
 * same arithmetic as android.graphics.Color.
 */
object ColorSampler {
    /** What a tile with no banner and no icon gets. */
    const val FALLBACK: Int = 0xFF444444.toInt()
    private const val HUE_BINS: Int = 12

    /**
     * A vivid colour for the ambient glow: the best colour of the hue that covers a real share of the banner,
     * or, when no hue does (a small multicolour logo, a grey banner), the banner's average, lifted.
     */
    fun vividColor(drawable: Drawable?): Int {
        if (drawable == null) return FALLBACK
        val width = 32
        val height = 18
        val bitmap: Bitmap = renderCopy(drawable, width, height)
        val binCounts = IntArray(HUE_BINS)
        val binBest = IntArray(HUE_BINS)
        val binBestScore = FloatArray(HUE_BINS)
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel: Int = bitmap.getPixel(x, y)
                redSum += red(pixel)
                greenSum += green(pixel)
                blueSum += blue(pixel)
                val pixelHsv: FloatArray = ColorMath.colorToHsv(pixel)
                if (pixelHsv[1] < 0.35f || pixelHsv[2] < 0.3f) continue // not a colour, just grey or dark
                val bin: Int = Math.min(HUE_BINS - 1, (pixelHsv[0] / 360f * HUE_BINS).toInt())
                binCounts[bin]++
                val score: Float = pixelHsv[1] * Math.min(1f, pixelHsv[2] * 1.4f) // saturated and not too dark
                if (score > binBestScore[bin]) {
                    binBestScore[bin] = score
                    binBest[bin] = pixel
                }
            }
        }
        bitmap.recycle()
        var winner = 0
        for (bin in 1 until HUE_BINS) if (binCounts[bin] > binCounts[winner]) winner = bin
        val pixels: Int = width * height
        // Only a hue that covers a real share of the banner counts. A tiny multicolour logo (Apple TV)
        // would hand the whole background one random pastel pixel, so it falls back to the average.
        if (binCounts[winner] < pixels * 0.12f) {
            val average: Int = ColorMath.rgb((redSum / pixels).toInt(), (greenSum / pixels).toInt(), (blueSum / pixels).toInt())
            val averageHsv: FloatArray = ColorMath.colorToHsv(average)
            averageHsv[2] = Math.max(averageHsv[2], 0.55f)
            return ColorMath.hsvToColor(averageHsv)
        }
        val bestHsv: FloatArray = ColorMath.colorToHsv(binBest[winner])
        bestHsv[1] = Math.min(1f, bestHsv[1] * 1.1f)
        bestHsv[2] = Math.max(0.5f, bestHsv[2])
        return ColorMath.hsvToColor(bestHsv)
    }

    /** Draws a private copy: changing the bounds of the drawable an ImageView shows would blank the tile. */
    private fun renderCopy(live: Drawable, width: Int, height: Int): Bitmap {
        val copy: Drawable = live.constantState?.newDrawable()?.mutate() ?: live
        val saved = Rect(copy.bounds)
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        copy.setBounds(0, 0, width, height)
        copy.draw(Canvas(bitmap))
        copy.bounds = saved
        return bitmap
    }

    private fun red(color: Int): Int = (color shr 16) and 0xFF
    private fun green(color: Int): Int = (color shr 8) and 0xFF
    private fun blue(color: Int): Int = color and 0xFF
}
