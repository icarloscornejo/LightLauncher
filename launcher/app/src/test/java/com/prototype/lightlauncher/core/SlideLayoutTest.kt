package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideLayoutTest {

    @Test
    fun twoPortraitsUseTheFullHeightAndSitTogetherInTheMiddle() {
        val layout: SlideLayout = SlideLayout.forPair(0.75f, 0.75f, 1920f, 1080f)
        assertEquals(810, layout.leftWidth)
        assertEquals(810, layout.rightWidth)
        // 150 px margin each side: centres at (150 + 405) / 1920 and (150 + 810 + 405) / 1920.
        assertEquals(555f / 1920f, layout.leftCenter, 0.0001f)
        assertEquals(1365f / 1920f, layout.rightCenter, 0.0001f)
    }

    @Test
    fun twoLandscapesShrinkToFitTheWidth() {
        val layout: SlideLayout = SlideLayout.forPair(4f / 3f, 4f / 3f, 1920f, 1080f)
        assertEquals(960, layout.leftWidth)
        assertEquals(960, layout.rightWidth)
        assertEquals(0.25f, layout.leftCenter, 0.0001f)
        assertEquals(0.75f, layout.rightCenter, 0.0001f)
    }

    @Test
    fun differentShapesKeepTheSameHeight() {
        val layout: SlideLayout = SlideLayout.forPair(0.75f, 4f / 3f, 1920f, 1080f)
        // Height 1920 / (0.75 + 1.333) = 921.6 px for both.
        assertEquals(Math.round(921.6f * 0.75f), layout.leftWidth)
        assertEquals(Math.round(921.6f * 4f / 3f), layout.rightWidth)
    }

    @Test
    fun aSinglePhotoIsCentred() {
        assertEquals(SlideLayout(0, 0, 0.5f, 0.5f), SlideLayout.SINGLE)
    }
}
