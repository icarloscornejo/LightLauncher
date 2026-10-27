package com.prototype.lightlauncher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    @Test
    fun knownConversions() {
        assertArrayEquals(floatArrayOf(0f, 1f, 1f), ColorMath.colorToHsv(0xFFFF0000.toInt()), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), ColorMath.colorToHsv(0xFF000000.toInt()), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 128f / 255f), ColorMath.colorToHsv(0xFF808080.toInt()), 0.0001f)
        // Spotify's green, computed by hand with Skia's formula: green is the max channel.
        val hsv: FloatArray = ColorMath.colorToHsv(ColorMath.rgb(12, 191, 75))
        assertEquals(60f * (2f + (75f - 12f) / 179f), hsv[0], 0.001f)
        assertEquals(179f / 191f, hsv[1], 0.0001f)
        assertEquals(191f / 255f, hsv[2], 0.0001f)
    }

    @Test
    fun negativeHueWrapsAround() {
        val hsv: FloatArray = ColorMath.colorToHsv(ColorMath.rgb(255, 0, 128))
        assertTrue(hsv[0] in 0f..360f)
        assertEquals(360f - 60f * 128f / 255f, hsv[0], 0.001f)
    }

    @Test
    fun exactRoundTripsForKnownColours() {
        for (color in intArrayOf(0xFFFF0000.toInt(), 0xFF808080.toInt(), ColorMath.rgb(12, 191, 75), 0xFF000000.toInt())) {
            assertEquals(color, ColorMath.hsvToColor(ColorMath.colorToHsv(color)))
        }
    }

    @Test
    fun roundTripsStayWithinOneStepPerChannel() {
        var red = 0
        while (red <= 255) {
            var green = 0
            while (green <= 255) {
                var blue = 0
                while (blue <= 255) {
                    val back: Int = ColorMath.hsvToColor(ColorMath.colorToHsv(ColorMath.rgb(red, green, blue)))
                    assertTrue(Math.abs(((back shr 16) and 0xFF) - red) <= 1)
                    assertTrue(Math.abs(((back shr 8) and 0xFF) - green) <= 1)
                    assertTrue(Math.abs((back and 0xFF) - blue) <= 1)
                    blue += 15
                }
                green += 15
            }
            red += 15
        }
    }

    @Test
    fun outOfRangeInputsAreClamped() {
        assertEquals(0xFF000000.toInt(), ColorMath.hsvToColor(floatArrayOf(400f, 2f, -1f)))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.hsvToColor(floatArrayOf(10f, 0f, 2f)))
    }
}
