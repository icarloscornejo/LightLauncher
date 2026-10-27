package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelTextTest {
    // Built from code points so the source stays plain ASCII.
    private fun chars(vararg codePoints: Int): String = codePoints.joinToString("") { String(Character.toChars(it)) }

    private val bidiOverride: String = chars(0x202E)
    private val lineSeparator: String = chars(0x2028)
    private val zeroWidthJoiner: String = chars(0x200D)
    private val zeroWidthSpace: String = chars(0x200B)

    @Test
    fun cleanStripsFormattingAndBounds() {
        assertEquals("Netflix", LabelText.clean("Netflix$bidiOverride", "io.example"))
        assertEquals("a b", LabelText.clean("a\n\nb", "io.example"))
        assertEquals("a b c", LabelText.clean("  a \t b${lineSeparator}c  ", "io.example"))
        assertEquals("Zero width", LabelText.clean("Zero$zeroWidthJoiner width$zeroWidthSpace", "io.example"))
        assertEquals("Retro", LabelText.clean("Retro", "io.example"))
        assertEquals("io.example", LabelText.clean("", "io.example"))
        assertEquals("io.example", LabelText.clean("$bidiOverride\n${chars(0)}", "io.example"))
        assertEquals("io.example", LabelText.clean(null, "io.example"))
        val big: String = "x".repeat(100)
        assertEquals(40, LabelText.clean(big, "io.example").length)
        // 39 letters then an emoji (a surrogate pair): the pair does not fit in 40 and is left out whole.
        val emoji: String = chars(0x1F3AE)
        assertEquals(39, LabelText.clean(big.substring(0, 39) + emoji + "y", "io.example").length)
        assertEquals(big.substring(0, 38) + emoji, LabelText.clean(big.substring(0, 38) + emoji + "y", "io.example"))
    }
}
