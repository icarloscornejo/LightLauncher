package com.prototype.lightlauncher.core

/** How big a downloaded image may be, and how far to shrink it while decoding. */
object ImageLimits {
    const val MAX_SIDE: Int = 8192
    const val MAX_PIXELS: Long = 40_000_000L

    /** An image header that claims more than this is refused before any pixels are decoded. */
    fun acceptable(width: Int, height: Int): Boolean =
        width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS

    /** The largest power of two that still leaves the decoded image at least [targetWidth] by [targetHeight]. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }
}
