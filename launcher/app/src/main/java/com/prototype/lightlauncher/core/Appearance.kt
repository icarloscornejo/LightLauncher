package com.prototype.lightlauncher.core

/** The owner's appearance setting: the light or the dark theme. */
enum class Appearance {
    LIGHT,
    DARK,
    ;

    val isLight: Boolean get() = this == LIGHT

    /** The value the Appearance row switches to when OK is pressed. */
    fun next(): Appearance = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * A missing or unknown stored value is dark, the default look. That includes a stored "AUTO", which has no
         * matching entry.
         */
        fun parse(stored: String?): Appearance = entries.firstOrNull { it.name == stored } ?: DARK
    }
}
