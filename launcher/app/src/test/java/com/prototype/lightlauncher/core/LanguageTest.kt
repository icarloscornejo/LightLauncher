package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageTest {

    @Test
    fun aStoredNameComesBackAsItself() {
        for (language in Language.entries) assertEquals(language, Language.parse(language.name))
    }

    @Test
    fun anythingUnknownIsAutomatic() {
        assertEquals(Language.AUTO, Language.parse(null))
        assertEquals(Language.AUTO, Language.parse(""))
        assertEquals(Language.AUTO, Language.parse("FR"))
        assertEquals(Language.AUTO, Language.parse("en"))
    }

    @Test
    fun onlyTheTwoPinnedLanguagesHaveATag() {
        assertNull(Language.AUTO.tag)
        assertEquals("en", Language.EN.tag)
        assertEquals("es", Language.ES.tag)
    }
}
