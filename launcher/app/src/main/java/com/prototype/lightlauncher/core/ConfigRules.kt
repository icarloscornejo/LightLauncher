package com.prototype.lightlauncher.core

import java.util.Locale

/**
 * The checks on config.json values, pure so they can be tested on the JVM. The Android side
 * (Config) only reads the JSON and asks these rules whether each value is acceptable.
 */
object ConfigRules {

    /** The value when it is within [min, max], or null so the caller keeps its default. */
    fun intInRange(value: Int, min: Int, max: Int): Int? = if (value in min..max) value else null

    /**
     * An opaque ARGB colour from "#RRGGBB", "#AARRGGBB" or one of the names android.graphics.Color.parseColor
     * knows, or null when the text is none of those. The alpha is always forced to opaque, as the launcher
     * did before: the window behind is black anyway.
     */
    fun parseOpaqueColor(text: String): Int? {
        val trimmed: String = text.trim()
        if (trimmed.startsWith("#")) {
            val hex: String = trimmed.substring(1)
            if ((hex.length != 6 && hex.length != 8) || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return null
            }
            return (hex.toLong(16).toInt()) or OPAQUE
        }
        return NAMED_COLORS[trimmed.lowercase(Locale.ROOT)]
    }

    private const val OPAQUE: Int = 0xFF000000.toInt()

    /** The same names and values as android.graphics.Color's own table. */
    private val NAMED_COLORS: Map<String, Int> = mapOf(
        "black" to 0xFF000000.toInt(),
        "darkgray" to 0xFF444444.toInt(),
        "gray" to 0xFF888888.toInt(),
        "lightgray" to 0xFFCCCCCC.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF00FF00.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "aqua" to 0xFF00FFFF.toInt(),
        "fuchsia" to 0xFFFF00FF.toInt(),
        "darkgrey" to 0xFF444444.toInt(),
        "grey" to 0xFF888888.toInt(),
        "lightgrey" to 0xFFCCCCCC.toInt(),
        "lime" to 0xFF00FF00.toInt(),
        "maroon" to 0xFF800000.toInt(),
        "navy" to 0xFF000080.toInt(),
        "olive" to 0xFF808000.toInt(),
        "purple" to 0xFF800080.toInt(),
        "silver" to 0xFFC0C0C0.toInt(),
        "teal" to 0xFF008080.toInt(),
    )
}
