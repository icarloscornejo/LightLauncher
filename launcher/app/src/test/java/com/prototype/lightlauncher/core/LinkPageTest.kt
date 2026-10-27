package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPageTest {

    @Test
    fun thePhoneLanguageDecidesTheText() {
        assertTrue(LinkPage.prefersSpanish("es-419,es;q=0.9,en;q=0.8"))
        assertTrue(LinkPage.prefersSpanish(" ES"))
        assertFalse(LinkPage.prefersSpanish("en-US,en;q=0.9,es;q=0.5"))
        assertFalse(LinkPage.prefersSpanish(null))
        assertFalse(LinkPage.prefersSpanish(""))
    }

    @Test
    fun theFormPostsALinkBackToItself() {
        val html = LinkPage.form(spanish = false, invalid = false)
        assertTrue(html.contains("<form method=\"post\">"))
        assertTrue(html.contains("name=\"link\""))
        assertTrue(html.contains("lang=\"en\""))
        assertFalse(html.contains("class=\"error\""))
        assertTrue(LinkPage.form(spanish = true, invalid = false).contains("lang=\"es\""))
    }

    @Test
    fun anInvalidLinkAddsTheErrorLineInTheRightLanguage() {
        assertTrue(LinkPage.form(spanish = false, invalid = true).contains("does not look like a Google Photos album link"))
        assertTrue(LinkPage.form(spanish = true, invalid = true).contains("no parece un enlace"))
    }

    @Test
    fun noScriptAndNoExternalResourceIsEverInAPage() {
        for (html in listOf(LinkPage.form(false, false), LinkPage.form(true, true), LinkPage.done(false), LinkPage.done(true))) {
            assertFalse(html.contains("<script"))
            assertFalse(html.contains("src="))
            assertFalse(html.contains("http-equiv"))
        }
    }

    @Test
    fun thePolicyAllowsNothingButItsOwnStyleAndForm() {
        assertEquals("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'", LinkPage.CONTENT_SECURITY_POLICY)
    }
}
