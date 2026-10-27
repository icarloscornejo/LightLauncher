package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumLinkTest {

    private val saved = "https://photos.app.goo.gl/saved"
    private val configured = "https://photos.app.goo.gl/from-config-json"

    @Test
    fun theConfiguredLinkIsUsedUntilTheOwnerSetsOne() {
        assertEquals(configured, AlbumLink.resolve(null, false, configured))
        assertEquals(configured, AlbumLink.resolve("  ", false, " $configured "))
    }

    @Test
    fun theLinkSavedOnTheTvBeatsTheConfiguredOne() {
        assertEquals(saved, AlbumLink.resolve(saved, false, configured))
        assertEquals(saved, AlbumLink.resolve(" $saved ", false, ""))
    }

    @Test
    fun aRemovedAlbumStaysRemovedWhateverTheConfigSays() {
        assertEquals("", AlbumLink.resolve(null, true, configured))
        assertEquals("", AlbumLink.resolve("", true, configured))
    }

    @Test
    fun aNewLinkWinsEvenIfTheAlbumWasRemovedBefore() {
        assertEquals(saved, AlbumLink.resolve(saved, true, configured))
    }

    @Test
    fun noLinkAnywhereIsBlank() {
        assertEquals("", AlbumLink.resolve(null, false, ""))
    }
}
