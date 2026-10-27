package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun aTagIsReadWithOrWithoutTheLeadingV() {
        assertEquals(listOf(1, 2, 0), AppVersion.parse("v1.2.0"))
        assertEquals(listOf(1, 10), AppVersion.parse("1.10"))
        assertEquals(listOf(2, 0, 1), AppVersion.parse(" V2.0.1 "))
    }

    @Test
    fun anythingThatIsNotDottedNumbersIsRefused() {
        for (text in listOf("", "v", "1..2", "1.2.x", "1.2-beta", "1.2.3.4.5", "-1.0", "123456.0", "1,2")) {
            assertNull("'$text'", AppVersion.parse(text))
        }
    }

    @Test
    fun theCanonicalFormHasThreeNumbers() {
        assertEquals("1.2.0", AppVersion.canonical("v1.2"))
        assertEquals("1.2.0", AppVersion.canonical("1.2.0"))
        assertEquals("1.10.0", AppVersion.canonical("1.10"))
        assertNull(AppVersion.canonical("latest"))
    }

    @Test
    fun versionsAreComparedByNumberNotByText() {
        assertTrue(AppVersion.isNewer("1.10.0", "1.9.0"))
        assertTrue(AppVersion.isNewer("v1.1.0", "1.0.1"))
        assertTrue(AppVersion.isNewer("2.0", "1.99.99"))
        assertFalse(AppVersion.isNewer("1.0.1", "1.0.1"))
        assertFalse(AppVersion.isNewer("1.0", "1.0.0"))
        assertFalse(AppVersion.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun aVersionNobodyCanReadIsNeverNewer() {
        assertFalse(AppVersion.isNewer("nightly", "1.0.0"))
        assertFalse(AppVersion.isNewer("1.1.0", "dev"))
    }
}
