package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigRulesTest {

    @Test
    fun intInRangeKeepsOnlyValuesInsideTheBounds() {
        assertEquals(1, ConfigRules.intInRange(1, 1, 8))
        assertEquals(8, ConfigRules.intInRange(8, 1, 8))
        assertNull(ConfigRules.intInRange(0, 1, 8))
        assertNull(ConfigRules.intInRange(99, 1, 8))
    }

    @Test
    fun hexColorsAreForcedOpaque() {
        assertEquals(0xFF112233.toInt(), ConfigRules.parseOpaqueColor("#112233"))
        assertEquals(0xFF112233.toInt(), ConfigRules.parseOpaqueColor("#80112233"))
        assertEquals(0xFFABCDEF.toInt(), ConfigRules.parseOpaqueColor(" #abcdef "))
    }

    @Test
    fun namedColorsMatchAndroidsTable() {
        assertEquals(0xFF000080.toInt(), ConfigRules.parseOpaqueColor("Navy"))
        assertEquals(0xFFFF0000.toInt(), ConfigRules.parseOpaqueColor("red"))
        assertEquals(0xFF888888.toInt(), ConfigRules.parseOpaqueColor("grey"))
        assertEquals(0xFF444444.toInt(), ConfigRules.parseOpaqueColor("DARKGRAY"))
    }

    @Test
    fun anythingElseIsRejected() {
        assertNull(ConfigRules.parseOpaqueColor(""))
        assertNull(ConfigRules.parseOpaqueColor("#12345"))
        assertNull(ConfigRules.parseOpaqueColor("#1234567"))
        assertNull(ConfigRules.parseOpaqueColor("#GGGGGG"))
        assertNull(ConfigRules.parseOpaqueColor("#-12345"))
        assertNull(ConfigRules.parseOpaqueColor("rojo"))
        assertNull(ConfigRules.parseOpaqueColor("112233"))
    }
}
