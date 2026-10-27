package com.prototype.lightlauncher.core

/**
 * The blurred picture behind the home (what the top shelf's picture turns into when the focus goes down
 * to the grid). It works on a thumbnail of a few thousand pixels that the screen then stretches with filtering, so the
 * blur costs a few thousand pixel reads here and nothing on the GPU.
 *
 * Pure arithmetic on ARGB ints, so it runs in JVM tests.
 */
object BackdropBlur {
    /** Box blur passes over each axis; two of them look like a gaussian and leave no hard edges. */
    private const val PASSES: Int = 2

    /**
     * [pixels] (opaque, [width] by [height]) blurred with a box of [radius] on each side, then mixed [tintShare] (0 to 1)
     * of the way to [tint]: toward the theme's dark background to darken it, toward white to wash it out. When its
     * average brightness (0 to 1) is still over [maxBrightness] the whole picture is darkened to that, keeping its
     * colours: a bright picture (a white sweater, a snowy field) would otherwise light up a dark theme.
     */
    fun soften(
        pixels: IntArray, width: Int, height: Int, radius: Int, tint: Int, tintShare: Float, maxBrightness: Float = 1f,
    ): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height) { "pixels do not fit $width by $height" }
        var current: IntArray = pixels
        repeat(PASSES) {
            current = blurAxis(current, width, height, radius, horizontal = true)
            current = blurAxis(current, width, height, radius, horizontal = false)
        }
        return limitBrightness(mix(current, tint, tintShare.coerceIn(0f, 1f)), maxBrightness)
    }

    /** [pixels] scaled down so their average brightness is at most [maxBrightness]; untouched when it already is. */
    private fun limitBrightness(pixels: IntArray, maxBrightness: Float): IntArray {
        if (maxBrightness >= 1f) return pixels
        var total = 0.0
        for (pixel in pixels) {
            total += 0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)
        }
        val average: Double = total / pixels.size / 255.0
        if (average <= maxBrightness || average == 0.0) return pixels
        val factor: Float = (maxBrightness / average).toFloat()
        return IntArray(pixels.size) { index ->
            val pixel: Int = pixels[index]
            (0xFF shl 24) or
                (((((pixel shr 16) and 0xFF) * factor + 0.5f).toInt()) shl 16) or
                (((((pixel shr 8) and 0xFF) * factor + 0.5f).toInt()) shl 8) or
                ((pixel and 0xFF) * factor + 0.5f).toInt()
        }
    }

    private fun blurAxis(source: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean): IntArray {
        val output = IntArray(source.size)
        val lines: Int = if (horizontal) height else width
        val length: Int = if (horizontal) width else height
        val window: Int = 2 * radius + 1
        for (line in 0 until lines) {
            for (position in 0 until length) {
                var red = 0
                var green = 0
                var blue = 0
                for (offset in -radius..radius) {
                    val pixel: Int = source[indexOf(line, (position + offset).coerceIn(0, length - 1), width, horizontal)]
                    red += (pixel shr 16) and 0xFF
                    green += (pixel shr 8) and 0xFF
                    blue += pixel and 0xFF
                }
                output[indexOf(line, position, width, horizontal)] =
                    (0xFF shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
            }
        }
        return output
    }

    /** The index of the pixel at [position] along line [line], where a line is a row when [horizontal], else a column. */
    private fun indexOf(line: Int, position: Int, width: Int, horizontal: Boolean): Int =
        if (horizontal) line * width + position else position * width + line

    private fun mix(pixels: IntArray, tint: Int, share: Float): IntArray {
        if (share == 0f) return pixels
        val keep: Float = 1f - share
        return IntArray(pixels.size) { index ->
            val pixel: Int = pixels[index]
            (0xFF shl 24) or
                (towards((pixel shr 16) and 0xFF, (tint shr 16) and 0xFF, keep, share) shl 16) or
                (towards((pixel shr 8) and 0xFF, (tint shr 8) and 0xFF, keep, share) shl 8) or
                towards(pixel and 0xFF, tint and 0xFF, keep, share)
        }
    }

    private fun towards(channel: Int, target: Int, keep: Float, share: Float): Int = (channel * keep + target * share + 0.5f).toInt()
}
