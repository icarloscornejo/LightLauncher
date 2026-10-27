package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishedIntentTest {

    private val open = setOf("VIEW", "MAIN")

    @Test
    fun aNullOrBlankAddressIsNotUsable() {
        assertEquals(false, PublishedIntent.isUsable(null))
        assertEquals(false, PublishedIntent.isUsable(""))
        assertEquals(false, PublishedIntent.isUsable("   "))
        assertEquals(true, PublishedIntent.isUsable("intent://watch/1#Intent;end"))
    }

    @Test
    fun aPlainOpenOfTheAppsOwnContentIsAllowed() {
        assertEquals(true, PublishedIntent.allows(null, "https", open))
        assertEquals(true, PublishedIntent.allows("VIEW", "netflix", open))
        assertEquals(true, PublishedIntent.allows("MAIN", null, open))
        assertEquals(true, PublishedIntent.allows(null, null, open))
    }

    @Test
    fun sendAndOtherActionsAreRefused() {
        assertEquals(false, PublishedIntent.allows("SEND", "content", open))
        assertEquals(false, PublishedIntent.allows("SEND_MULTIPLE", null, open))
        assertEquals(false, PublishedIntent.allows("CHOOSER", null, open))
        assertEquals(false, PublishedIntent.allows("DELETE", "https", open))
        assertEquals(false, PublishedIntent.allows("VIEW", "https", emptySet()))
    }

    @Test
    fun aFileAddressIsRefusedWhateverTheCase() {
        assertEquals(false, PublishedIntent.allows("VIEW", "file", open))
        assertEquals(false, PublishedIntent.allows(null, "FILE", open))
    }

    @Test
    fun aComponentOfAnotherPackageIsNotKept() {
        assertEquals(true, PublishedIntent.keepsComponent(null, "com.netflix.ninja"))
        assertEquals(true, PublishedIntent.keepsComponent("com.netflix.ninja", "com.netflix.ninja"))
        assertEquals(false, PublishedIntent.keepsComponent("com.other.app", "com.netflix.ninja"))
    }
}
