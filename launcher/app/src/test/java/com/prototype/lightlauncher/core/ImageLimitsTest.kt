package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLimitsTest {

    @Test
    fun ordinarySizesAreAcceptable() {
        assertTrue(ImageLimits.acceptable(1920, 1080))
        assertTrue(ImageLimits.acceptable(3840, 2160))
        assertTrue(ImageLimits.acceptable(1, 1))
    }

    @Test
    fun hugeOrEmptyHeadersAreRefused() {
        assertFalse(ImageLimits.acceptable(0, 100))
        assertFalse(ImageLimits.acceptable(100, 0))
        assertFalse(ImageLimits.acceptable(-5, 100))
        assertFalse(ImageLimits.acceptable(ImageLimits.MAX_SIDE + 1, 10))
        assertFalse(ImageLimits.acceptable(8000, 8000)) // 64 million pixels
        assertFalse(ImageLimits.acceptable(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun theSampleSizeKeepsTheDecodedImageAtLeastTheTarget() {
        assertEquals(1, ImageLimits.sampleSize(800, 450, 800, 450))
        assertEquals(1, ImageLimits.sampleSize(1200, 675, 800, 450))
        assertEquals(2, ImageLimits.sampleSize(1600, 900, 800, 450))
        assertEquals(4, ImageLimits.sampleSize(3840, 2160, 800, 450))
        assertEquals(1, ImageLimits.sampleSize(100, 100, 800, 450)) // never enlarges
    }
}
