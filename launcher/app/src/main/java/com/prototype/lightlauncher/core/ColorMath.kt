package com.prototype.lightlauncher.core

/**
 * RGB and HSV with the exact arithmetic of android.graphics.Color (Skia's SkRGBToHSV and
 * SkHSVToColor), so the colour picks give the same result on the TV and in JVM tests.
 */
object ColorMath {

    /** Hue 0..360, saturation 0..1, value 0..1 of an ARGB colour (alpha ignored). */
    fun colorToHsv(color: Int): FloatArray {
        val red: Int = (color shr 16) and 0xFF
        val green: Int = (color shr 8) and 0xFF
        val blue: Int = color and 0xFF
        val max: Int = maxOf(red, green, blue)
        val min: Int = minOf(red, green, blue)
        val value: Float = max / 255f
        if (max == 0) return floatArrayOf(0f, 0f, 0f)
        val delta: Int = max - min
        val saturation: Float = delta.toFloat() / max
        if (delta == 0) return floatArrayOf(0f, 0f, value)
        var hue: Float = when (max) {
            red -> (green - blue).toFloat() / delta
            green -> 2f + (blue - red).toFloat() / delta
            else -> 4f + (red - green).toFloat() / delta
        }
        hue *= 60f
        if (hue < 0f) hue += 360f
        return floatArrayOf(hue, saturation, value)
    }

    /** Opaque ARGB from hue 0..360, saturation 0..1 and value 0..1 (out of range values are clamped). */
    fun hsvToColor(hsv: FloatArray): Int {
        val saturation: Float = hsv[1].coerceIn(0f, 1f)
        val value: Float = hsv[2].coerceIn(0f, 1f)
        val valueByte: Int = roundToInt(value * 255f)
        if (saturation <= 0f) return rgb(valueByte, valueByte, valueByte)
        val hueSixth: Float = if (hsv[0] < 0f || hsv[0] >= 360f) 0f else hsv[0] / 60f
        val sector: Int = Math.floor(hueSixth.toDouble()).toInt()
        val fraction: Float = hueSixth - sector
        val p: Int = roundToInt((1f - saturation) * value * 255f)
        val q: Int = roundToInt((1f - saturation * fraction) * value * 255f)
        val t: Int = roundToInt((1f - saturation * (1f - fraction)) * value * 255f)
        return when (sector) {
            0 -> rgb(valueByte, t, p)
            1 -> rgb(q, valueByte, p)
            2 -> rgb(p, valueByte, t)
            3 -> rgb(p, q, valueByte)
            4 -> rgb(t, p, valueByte)
            else -> rgb(valueByte, p, q)
        }
    }

    fun rgb(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    /** Skia's SkScalarRoundToInt: floor(x + 0.5). */
    private fun roundToInt(x: Float): Int = Math.floor((x + 0.5f).toDouble()).toInt()
}
