package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SlidePlannerTest {

    /** Collections.shuffle swaps index i with nextInt(i + 1); always answering i keeps the original order. */
    private class NoShuffle : Random() {
        override fun nextInt(bound: Int): Int = bound - 1
    }

    private fun narrow(name: String, aspect: Float = 0.75f): SlidePhoto = SlidePhoto(name, aspect)
    private fun wide(name: String): SlidePhoto = SlidePhoto(name, 16f / 9f)

    @Test
    fun widePhotoGoesAlone() {
        val planner = SlidePlanner(listOf(wide("w"), narrow("a"), narrow("b")), emptySet(), NoShuffle())
        assertEquals(listOf("w"), planner.nextSlide())
    }

    @Test
    fun narrowPhotoTakesTheCandidateThatFillsTheScreenBest() {
        // a (0.75) + b (1.0) = 1.75, closest to 16:9 = 1.78; a + c (0.6) = 1.35 is worse.
        val photos = listOf(narrow("a"), narrow("c", 0.6f), narrow("b", 1.0f))
        val planner = SlidePlanner(photos, emptySet(), NoShuffle())
        assertEquals(listOf("a", "b"), planner.nextSlide())
    }

    @Test
    fun widePhotosAreNeverPartners() {
        val planner = SlidePlanner(listOf(narrow("a"), wide("w"), narrow("b")), emptySet(), NoShuffle())
        assertEquals(listOf("a", "b"), planner.nextSlide())
        assertEquals(listOf("w"), planner.nextSlide())
    }

    @Test
    fun onlyTheNextFewNarrowPhotosAreCompared() {
        // The perfect partner sits after PARTNER_CANDIDATES other narrow photos, so it is not considered.
        val photos = mutableListOf(narrow("first"))
        repeat(SlidePlanner.PARTNER_CANDIDATES) { photos.add(narrow("far$it", 0.5f)) }
        photos.add(narrow("perfect", 16f / 9f - 0.75f - 0.01f))
        val slide: List<String> = SlidePlanner(photos, emptySet(), NoShuffle()).nextSlide()
        assertEquals("first", slide[0])
        assertFalse(slide.contains("perfect"))
    }

    @Test
    fun aLastNarrowPhotoWithoutPartnerGoesAlone() {
        val planner = SlidePlanner(listOf(narrow("a")), emptySet(), NoShuffle())
        assertEquals(listOf("a"), planner.nextSlide())
    }

    @Test
    fun everyPhotoIsShownOncePerRound() {
        val photos: List<SlidePhoto> = (0 until 23).map { index ->
            if (index % 4 == 0) wide("p$index") else narrow("p$index", 0.6f + index * 0.03f)
        }
        for (seed in 0L until 20L) {
            val planner = SlidePlanner(photos, emptySet(), Random(seed))
            val seen: MutableList<String> = mutableListOf()
            while (seen.size < photos.size) {
                val slide: List<String> = planner.nextSlide()
                assertTrue(slide.size in 1..2)
                seen.addAll(slide)
            }
            assertEquals(photos.size, seen.size)
            assertEquals(photos.map { it.name }.toSet(), seen.toSet())
        }
    }

    @Test
    fun aRoundContinuesFromWhatWasAlreadyShown() {
        val photos: List<SlidePhoto> = listOf(wide("a"), wide("b"), wide("c"), wide("d"))
        val planner = SlidePlanner(photos, setOf("a", "b"), Random(1))
        val next: Set<String> = (planner.nextSlide() + planner.nextSlide()).toSet()
        assertEquals(setOf("c", "d"), next)
        assertEquals(4, planner.shownCount)
    }

    @Test
    fun aNewRoundNeverStartsWithThePhotoThatJustEndedTheLastOne() {
        val photos: List<SlidePhoto> = listOf(wide("a"), wide("b"), wide("c"))
        for (seed in 0L until 50L) {
            val planner = SlidePlanner(photos, emptySet(), Random(seed))
            var last: List<String> = emptyList()
            repeat(3) { last = planner.nextSlide() }
            val firstOfNewRound: List<String> = planner.nextSlide()
            assertFalse("seed $seed", firstOfNewRound.any { it in last })
            assertEquals(1, planner.shownCount)
        }
    }

    @Test
    fun shownNamesIsACopy() {
        val planner = SlidePlanner(listOf(wide("a"), wide("b")), emptySet(), NoShuffle())
        planner.nextSlide()
        val snapshot: Set<String> = planner.shownNames()
        planner.nextSlide()
        assertEquals(setOf("a"), snapshot)
    }

    @Test(expected = IllegalArgumentException::class)
    fun needsAtLeastOnePhoto() {
        SlidePlanner(emptyList(), emptySet(), Random(0))
    }
}
