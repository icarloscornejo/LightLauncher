package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropBlurTest {

    private val grey: Int = 0xFF808080.toInt()

    private fun channel(color: Int, shift: Int): Int = (color shr shift) and 0xFF

    @Test
    fun aUniformPictureStaysTheSameWithoutATint() {
        val pixels = IntArray(8 * 6) { grey }
        val result: IntArray = BackdropBlur.soften(pixels, 8, 6, 2, 0xFF000000.toInt(), 0f)
        assertTrue(result.all { it == grey })
    }

    @Test
    fun aBrightPixelIsSpreadOverItsNeighbours() {
        val pixels = IntArray(9 * 9) { 0xFF000000.toInt() }
        pixels[4 * 9 + 4] = 0xFFFFFFFF.toInt()
        val result: IntArray = BackdropBlur.soften(pixels, 9, 9, 2, 0xFF000000.toInt(), 0f)
        assertTrue("the peak is lower", channel(result[4 * 9 + 4], 0) < 255)
        assertTrue("a neighbour got light", channel(result[4 * 9 + 5], 0) > 0)
        assertTrue("the centre is still the brightest", channel(result[4 * 9 + 4], 0) >= channel(result[4 * 9 + 5], 0))
    }

    @Test
    fun theTintIsMixedByItsShare() {
        val pixels = IntArray(4 * 4) { 0xFF000000.toInt() }
        val half: IntArray = BackdropBlur.soften(pixels, 4, 4, 1, 0xFFFFFFFF.toInt(), 0.5f)
        for (shift in intArrayOf(16, 8, 0)) assertEquals(128, channel(half[5], shift))
        val all: IntArray = BackdropBlur.soften(pixels, 4, 4, 1, 0xFF102030.toInt(), 1f)
        assertEquals(0x10, channel(all[0], 16))
        assertEquals(0x20, channel(all[0], 8))
        assertEquals(0x30, channel(all[0], 0))
    }

    @Test
    fun aBrightPictureIsDarkenedToTheLimitAndKeepsItsColours() {
        // A warm, bright picture: red 200, green 160, blue 120.
        val warm = 0xFFC8A078.toInt()
        val result: IntArray = BackdropBlur.soften(IntArray(6 * 6) { warm }, 6, 6, 1, 0xFF000000.toInt(), 0f, maxBrightness = 0.25f)
        val brightness: Double = (0.299 * channel(result[0], 16) + 0.587 * channel(result[0], 8) + 0.114 * channel(result[0], 0)) / 255.0
        assertEquals(0.25, brightness, 0.01)
        assertTrue("still warm", channel(result[0], 16) > channel(result[0], 8) && channel(result[0], 8) > channel(result[0], 0))
    }

    @Test
    fun aPictureUnderTheLimitIsLeftAlone() {
        val dark = 0xFF202020.toInt()
        val result: IntArray = BackdropBlur.soften(IntArray(4 * 4) { dark }, 4, 4, 1, 0xFF000000.toInt(), 0f, maxBrightness = 0.5f)
        assertTrue(result.all { it == dark })
    }

    @Test
    fun theResultIsOpaque() {
        val result: IntArray = BackdropBlur.soften(IntArray(3 * 3) { grey }, 3, 3, 1, 0xFFFFFFFF.toInt(), 0.3f)
        assertTrue(result.all { (it ushr 24) == 0xFF })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aWrongSizeIsRefused() {
        BackdropBlur.soften(IntArray(5), 2, 2, 1, 0xFF000000.toInt(), 0f)
    }
}
