package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollageRulesTest {

    private val wide = ArtSize(1000, 562)
    private val cover = ArtSize(640, 640)

    @Test
    fun theKindFollowsTheShapeOfThePicture() {
        assertEquals(CollageKind.WIDE, CollageRules.kindOf(wide))
        assertEquals(CollageKind.COVERS, CollageRules.kindOf(cover))
        assertEquals(CollageKind.WIDE, CollageRules.kindOf(ArtSize(1280, 720)))
        assertNull(CollageRules.kindOf(ArtSize(1600, 900))) // big enough for the screen, never in a collage
        assertNull(CollageRules.kindOf(ArtSize(640, 360))) // too small to be a wide cell
        assertNull(CollageRules.kindOf(ArtSize(640, 480))) // 4:3, neither
        assertNull(CollageRules.kindOf(ArtSize(300, 300))) // a small cover
        assertNull(CollageRules.kindOf(ArtSize(800, 1200))) // poster
        assertNull(CollageRules.kindOf(null))
    }

    @Test
    fun onlyWholeSlidesAreMade() {
        val sizes: List<ArtSize?> = List(3) { wide } + List(7) { cover }
        assertEquals(emptyList<CollageSlide>(), CollageRules.slides(sizes))
    }

    @Test
    fun fourWidePicturesMakeASlideAndTheLeftoversWait() {
        val sizes: List<ArtSize?> = listOf(wide, cover, wide, null, wide, wide, wide)
        val slides: List<CollageSlide> = CollageRules.slides(sizes)
        assertEquals(1, slides.size)
        assertEquals(CollageKind.WIDE, slides[0].kind)
        assertEquals(listOf(0, 2, 4, 5), slides[0].indices)
    }

    @Test
    fun eightCoversMakeASlideAfterTheWideOnes() {
        val sizes: List<ArtSize?> = List(8) { cover } + List(4) { wide }
        val slides: List<CollageSlide> = CollageRules.slides(sizes)
        assertEquals(listOf(CollageKind.WIDE, CollageKind.COVERS), slides.map { it.kind })
        assertEquals(listOf(8, 9, 10, 11), slides[0].indices)
        assertEquals((0..7).toList(), slides[1].indices)
    }
}
