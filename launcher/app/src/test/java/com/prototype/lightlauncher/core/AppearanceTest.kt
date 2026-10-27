package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {

    @Test
    fun theSettingSwitchesBetweenLightAndDark() {
        assertEquals(Appearance.DARK, Appearance.LIGHT.next())
        assertEquals(Appearance.LIGHT, Appearance.DARK.next())
    }

    @Test
    fun aMissingOrUnknownStoredValueIsDark() {
        assertEquals(Appearance.DARK, Appearance.parse(null))
        assertEquals(Appearance.DARK, Appearance.parse(""))
        assertEquals(Appearance.DARK, Appearance.parse("sepia"))
        assertEquals(Appearance.LIGHT, Appearance.parse("LIGHT"))
    }

    @Test
    fun theRemovedAutomaticValueFallsBackToDark() {
        assertEquals(Appearance.DARK, Appearance.parse("AUTO"))
    }

    @Test
    fun onlyLightIsLight() {
        assertTrue(Appearance.LIGHT.isLight)
        assertFalse(Appearance.DARK.isLight)
    }
}
