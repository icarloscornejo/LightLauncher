package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfImageUpgradeTest {

    private val max = "https://images.cdn.prd.api.discomax.com/OPhZS/44moT5odFirJY2-Nw.jpeg?w=600&q=85&p=true&f=jpg"

    @Test
    fun maxAsksForTheWidthItNeedsAndKeepsThePublishedAddressAsFallback() {
        assertEquals(
            listOf("https://images.cdn.prd.api.discomax.com/OPhZS/44moT5odFirJY2-Nw.jpeg?w=1920&q=85&p=true&f=jpg", max),
            ShelfImageUpgrade.candidates(max, 1920),
        )
        assertEquals(
            "https://images.cdn.prd.api.discomax.com/OPhZS/44moT5odFirJY2-Nw.jpeg?w=800&q=85&p=true&f=jpg",
            ShelfImageUpgrade.candidates(max, 800).first(),
        )
    }

    @Test
    fun maxIsLeftAloneWhenItIsBigEnoughOrAskedNothingBigger() {
        assertEquals(listOf(max), ShelfImageUpgrade.candidates(max, 480))
        assertEquals(listOf(max), ShelfImageUpgrade.candidates(max, 600))
        assertEquals(listOf("https://example.com/a.jpg?w=600"), ShelfImageUpgrade.candidates("https://example.com/a.jpg?w=600", 1920))
    }

    @Test
    fun theWidthIsNeverRaisedAbove1920() {
        assertEquals(
            "https://images.cdn.prd.api.discomax.com/a/b.jpeg?w=1920",
            ShelfImageUpgrade.candidates("https://images.cdn.prd.api.discomax.com/a/b.jpeg?w=600", 3840).first(),
        )
    }

    @Test
    fun youtubeThumbnailsGoToMaxResolutionOnlyForCards() {
        val small = "https://i.ytimg.com/vi/YBoYyrTM1pg/sddefault.jpg"
        assertEquals(listOf("https://i.ytimg.com/vi/YBoYyrTM1pg/maxresdefault.jpg", small), ShelfImageUpgrade.candidates(small, 800))
        assertEquals(listOf(small), ShelfImageUpgrade.candidates(small, 1920))
        val custom = "https://i.ytimg.com/vi/EzksAB7oVxY/sddefault_custom_2.jpg?sqp=CMyc4tYG&rs=AOn4"
        assertEquals("https://i.ytimg.com/vi/EzksAB7oVxY/maxresdefault.jpg", ShelfImageUpgrade.candidates(custom, 800).first())
    }

    @Test
    fun otherAddressesAreUntouched() {
        val apple = "https://is1-ssl.mzstatic.com/image/thumb/CO4lIyj7dXy2D79e7FjgDA/1920x1080.jpg"
        assertEquals(listOf(apple), ShelfImageUpgrade.candidates(apple, 1920))
        val other = "https://i.ytimg.com/vi_webp/YBoYyrTM1pg/sddefault.webp"
        assertEquals(listOf(other), ShelfImageUpgrade.candidates(other, 800))
    }
}
