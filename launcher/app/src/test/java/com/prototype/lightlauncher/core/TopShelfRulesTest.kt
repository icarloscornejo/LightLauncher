package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopShelfRulesTest {

    private val full = ArtSize(1920, 1080)
    private val card = ArtSize(853, 480)
    private val tiny = ArtSize(300, 169)
    private val poster = ArtSize(800, 1200)

    @Test
    fun aLandscapeImageOfAtLeast480PixelsIsACard() {
        assertTrue(TopShelfRules.isCard(ArtSize(480, 270)))
        assertTrue(TopShelfRules.isCard(full))
        assertTrue(TopShelfRules.isCard(ArtSize(800, 600))) // 4:3
    }

    @Test
    fun aValidCardUnder960PixelsIsKept() {
        // A card is shown at about 800 px: a 960 px minimum applied after shrinking would refuse it.
        assertEquals(listOf(0), TopShelfRules.cardIndices(listOf(ArtSize(800, 450))))
    }

    @Test
    fun theArtMaxPublishesIsACard() {
        // Max publishes 600 x 338 art, which a 640 px minimum would turn into the icon.
        assertTrue(TopShelfRules.isCard(ArtSize(600, 338)))
        assertEquals(ShelfMode.CARDS, TopShelfRules.plan(emptyList(), listOf(ArtSize(600, 338))).mode)
    }

    @Test
    fun narrowPortraitAndSquareArtIsNotACard() {
        assertFalse(TopShelfRules.isCard(ArtSize(479, 270)))
        assertFalse(TopShelfRules.isCard(tiny))
        assertFalse(TopShelfRules.isCard(poster))
        assertFalse(TopShelfRules.isCard(ArtSize(800, 800))) // square
        assertFalse(TopShelfRules.isCard(ArtSize(2000, 500))) // banner strip
        assertFalse(TopShelfRules.isCard(ArtSize(1280, 0)))
    }

    @Test
    fun aHeroIsWideEnoughAndAboutSixteenByNineOrWider() {
        assertTrue(TopShelfRules.isHero(full))
        assertTrue(TopShelfRules.isHero(ArtSize(1600, 900)))
        assertTrue(TopShelfRules.isHero(ArtSize(3840, 2160)))
        assertFalse(TopShelfRules.isHero(card)) // fine as a card, soft as a full screen picture
        assertFalse(TopShelfRules.isHero(ArtSize(1280, 720))) // the most Spotify and YouTube publish: cards, not stretched
        assertFalse(TopShelfRules.isHero(ArtSize(1599, 900)))
        assertFalse(TopShelfRules.isHero(ArtSize(1600, 1200))) // 4:3 would lose too much cropped to the screen
        assertFalse(TopShelfRules.isHero(ArtSize(1920, 600))) // wider than 2.4:1
        assertFalse(TopShelfRules.isHero(ArtSize(1600, 0)))
    }

    @Test
    fun theCardsKeepTheOrderSkipUnloadedAndUnusableOnesAndStopAtTheLimit() {
        val sizes: List<ArtSize?> = listOf(ArtSize(800, 450), null, tiny) + List(20) { full }
        val cards: List<Int> = TopShelfRules.cardIndices(sizes)
        assertEquals(listOf(0) + (3 until 18).toList(), cards)
        assertEquals(TopShelfRules.MAX_CARDS, cards.size)
        assertEquals(16, TopShelfRules.MAX_CARDS)
    }

    @Test
    fun anAppWithAPreviewHeroGetsThePictureEvenIfItAlsoHasWatchNext() {
        // Apple TV in the screenshots: the showcase picture, not a row.
        val plan = TopShelfRules.plan(previews = listOf(full, full), watchNext = listOf(full, full))
        assertEquals(ShelfMode.HERO, plan.mode)
        assertEquals(ShelfSource.PREVIEW, plan.source)
        assertEquals(listOf(0), plan.indices)
    }

    @Test
    fun spotifyGetsCardsFromTheWidePicturesAndIgnoresTheSquareCovers() {
        // What Spotify publishes: ten 1280 x 720 pictures and some square covers. None is big enough for the screen.
        val spotify: List<ArtSize?> = List(10) { ArtSize(1280, 720) } + List(5) { ArtSize(640, 640) }
        val plan = TopShelfRules.plan(spotify, emptyList())
        assertEquals(ShelfMode.CARDS, plan.mode)
        assertEquals(ShelfSource.PREVIEW, plan.source)
        assertEquals((0 until 10).toList(), plan.indices)
    }

    @Test
    fun youtubeThumbnailsAreCards() {
        // SmartTube: 4:3 thumbnails of 640 x 480, or the 1280 x 720 ones the upgrade finds.
        assertEquals(ShelfMode.CARDS, TopShelfRules.plan(List(8) { ArtSize(640, 480) }, emptyList()).mode)
        assertEquals(ShelfMode.CARDS, TopShelfRules.plan(List(8) { ArtSize(1280, 720) }, emptyList()).mode)
    }

    @Test
    fun squareCoversMakeACollageOnlyWhenNothingElseIsACard() {
        val cover = ArtSize(640, 640)
        val plan = TopShelfRules.plan(List(8) { cover }, emptyList())
        assertEquals(ShelfMode.COLLAGE, plan.mode)
        assertEquals(listOf((0..7).toList()), plan.slides.map { it.indices })
        // A single wide picture among them is a card, and the row of cards wins.
        assertEquals(ShelfMode.CARDS, TopShelfRules.plan(List(8) { cover } + ArtSize(1000, 562), emptyList()).mode)
        // Too few covers for a whole slide: the icon.
        assertEquals(ShelfMode.ICON, TopShelfRules.plan(List(5) { cover }, emptyList()).mode)
    }

    @Test
    fun aHeroPlanHasNoCollageSlides() {
        val plan = TopShelfRules.plan(listOf(card, full, card, card, card, ArtSize(1280, 720)), emptyList())
        assertEquals(ShelfMode.HERO, plan.mode)
        assertEquals(listOf(1), plan.indices)
        assertTrue(plan.slides.isEmpty())
    }

    @Test
    fun theFirstPreviewThatCanBeAHeroIsTheOneUsed() {
        val plan = TopShelfRules.plan(previews = listOf(card, null, full), watchNext = emptyList())
        assertEquals(ShelfMode.HERO, plan.mode)
        assertEquals(listOf(2), plan.indices)
    }

    @Test
    fun anAppWithOnlyWatchNextGetsAContinueWatchingRow() {
        // Netflix in the screenshots.
        val plan = TopShelfRules.plan(previews = emptyList(), watchNext = listOf(full, card))
        assertEquals(ShelfMode.CARDS, plan.mode)
        assertEquals(ShelfSource.WATCH_NEXT, plan.source)
        assertEquals(listOf(0, 1), plan.indices)
    }

    @Test
    fun previewsTooSmallForAHeroFallBackToWatchNextCardsFirst() {
        val plan = TopShelfRules.plan(previews = listOf(card, card), watchNext = listOf(full))
        assertEquals(ShelfMode.CARDS, plan.mode)
        assertEquals(ShelfSource.WATCH_NEXT, plan.source)
    }

    @Test
    fun previewsTooSmallForAHeroStillMakeCardsWhenThereIsNoWatchNext() {
        val plan = TopShelfRules.plan(previews = listOf(card, tiny, card), watchNext = emptyList())
        assertEquals(ShelfMode.CARDS, plan.mode)
        assertEquals(ShelfSource.PREVIEW, plan.source)
        assertEquals(listOf(0, 2), plan.indices)
    }

    @Test
    fun nothingUsableMeansTheIcon() {
        for (plan in listOf(
            TopShelfRules.plan(emptyList(), emptyList()),
            TopShelfRules.plan(listOf(null, null), listOf(null)),
            TopShelfRules.plan(listOf(tiny, poster), listOf(tiny)),
        )) {
            assertEquals(ShelfMode.ICON, plan.mode)
            assertNull(plan.source)
            assertTrue(plan.indices.isEmpty())
        }
    }
}
