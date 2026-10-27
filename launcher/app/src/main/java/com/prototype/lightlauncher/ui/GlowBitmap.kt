package com.prototype.lightlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/** One radial glow: centre and radius as fractions of the bitmap (radius of its width), and its colour. */
class GlowSpot(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val color: Int,
)

/**
 * The one recipe for the ambient glows. A small bitmap stretched over the screen is one texture:
 * a full-screen GradientDrawable is drawn on the CPU on these TVs and made fades crawl.
 *
 * The home and the screensaver differ on purpose, and those differences are parameters, not copies:
 * the home's glow is transparent around it (a theme wallpaper shows through) and fills the whole canvas;
 * the screensaver's sits on opaque black (it covers the previous slide while fading in) and draws circles.
 */
class GlowBitmap(
    private val width: Int,
    private val height: Int,
    /** 0 leaves the bitmap transparent around the glows. */
    private val backgroundColor: Int,
    /** Alpha (0 to 255) at each gradient stop, from the centre out. */
    private val stopAlphas: IntArray,
    /** Where each stop sits, 0 (centre) to 1 (edge). */
    private val stopPositions: FloatArray,
    /** True fills the whole canvas with each gradient; false draws only the circle. */
    private val fillCanvas: Boolean,
) {
    fun draw(spots: List<GlowSpot>): Bitmap {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (backgroundColor != 0) canvas.drawColor(backgroundColor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (spot in spots) {
            val rgb: Int = spot.color and 0x00FFFFFF
            val centerX: Float = width * spot.centerX
            val centerY: Float = height * spot.centerY
            val radius: Float = width * spot.radius
            paint.shader = RadialGradient(
                centerX, centerY, radius,
                IntArray(stopAlphas.size) { (stopAlphas[it] shl 24) or rgb },
                stopPositions,
                Shader.TileMode.CLAMP,
            )
            if (fillCanvas) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            else canvas.drawCircle(centerX, centerY, radius, paint)
        }
        return bitmap
    }

    companion object {
        /** The screensaver's, a bit stronger because it only shows around the photos. */
        val DREAM: GlowBitmap = GlowBitmap(192, 108, 0xFF000000.toInt(), intArrayOf(0x95, 0x3C, 0x00), floatArrayOf(0f, 0.5f, 1f), fillCanvas = false)
    }
}

/**
 * The home's neutral backdrop, a vertical gradient from [top] to [bottom] as a small bitmap the screen
 * stretches (a full screen GradientDrawable is drawn on the CPU on these TVs, and this sits under every fade of the
 * home). The two tones are only a dozen levels apart, which would show as wide bands at 8 bits, so each pixel is
 * dithered: rounded up or down at random, in proportion to the fraction it lost.
 */
object NeutralBackdrop {
    private const val WIDTH: Int = 128
    private const val HEIGHT: Int = 270

    fun bitmap(top: Int, bottom: Int): Bitmap {
        val random = java.util.Random(7L)
        val pixels = IntArray(WIDTH * HEIGHT)
        for (row in 0 until HEIGHT) {
            val share: Float = row / (HEIGHT - 1).toFloat()
            for (column in 0 until WIDTH) {
                pixels[row * WIDTH + column] = (0xFF shl 24) or
                    (level(top shr 16, bottom shr 16, share, random) shl 16) or
                    (level(top shr 8, bottom shr 8, share, random) shl 8) or
                    level(top, bottom, share, random)
            }
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }

    private fun level(from: Int, to: Int, share: Float, random: java.util.Random): Int {
        val start: Int = from and 0xFF
        val value: Float = start + ((to and 0xFF) - start) * share
        return (value + random.nextFloat()).toInt().coerceIn(0, 255)
    }
}
