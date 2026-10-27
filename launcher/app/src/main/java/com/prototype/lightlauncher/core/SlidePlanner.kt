package com.prototype.lightlauncher.core

import java.util.Random

/** One photo the screensaver can show: its file name (its identity across sessions) and width / height. */
data class SlidePhoto(val name: String, val aspect: Float) {
    /** Wide enough to fill a 16:9 screen alone (16:9 is 1.78, 4:3 is 1.33, a phone portrait 0.75). */
    val isWide: Boolean get() = aspect >= WIDE_ASPECT

    companion object {
        const val WIDE_ASPECT: Float = 1.5f
    }
}

/**
 * Picks the screensaver's slides. A slide is one wide photo, or a narrow one plus a partner.
 * Every photo is shown once per round, and the round survives screensaver sessions through [shownNames],
 * which the caller persists. Pure logic: no files, no Android, a seeded [random] makes it reproducible.
 */
class SlidePlanner(
    private val photos: List<SlidePhoto>,
    alreadyShown: Set<String>,
    private val random: Random,
) {
    private val pending: MutableList<SlidePhoto> = ArrayList()
    private val shown: MutableSet<String> = HashSet(alreadyShown)
    private var lastSlide: List<String> = emptyList()

    init {
        require(photos.isNotEmpty()) { "SlidePlanner needs at least one photo" }
    }

    /** Photos already shown in the current round. */
    val shownCount: Int get() = shown.size
    val totalCount: Int get() = photos.size

    /** A copy of the current round's shown names, for the caller to persist after each slide. */
    fun shownNames(): Set<String> = HashSet(shown)

    /**
     * The next slide's photo names: one wide photo alone, or a narrow one plus a partner. Among the next few
     * narrow photos in the shuffle, the partner is the one whose combined width is closest to the screen's,
     * so the pair fills it best (two 4:3 shrink to 2/3 of the height, a 4:3 and a portrait barely do).
     */
    fun nextSlide(): List<String> {
        if (pending.isEmpty()) refill()
        val first: SlidePhoto = pending.removeAt(0)
        val slide: MutableList<String> = mutableListOf(first.name)
        if (!first.isWide) {
            var bestIndex = -1
            var bestGap = Float.MAX_VALUE
            var seen = 0
            var index = 0
            while (index < pending.size && seen < PARTNER_CANDIDATES) {
                val candidate: SlidePhoto = pending[index]
                if (!candidate.isWide) {
                    seen++
                    val gap: Float = Math.abs(first.aspect + candidate.aspect - SCREEN_ASPECT)
                    if (gap < bestGap) {
                        bestGap = gap
                        bestIndex = index
                    }
                }
                index++
            }
            if (bestIndex >= 0) slide.add(pending.removeAt(bestIndex).name)
        }
        shown.addAll(slide)
        lastSlide = slide
        return slide
    }

    /**
     * The photos not shown yet in this round, shuffled. When every photo has been shown, a new round starts,
     * and its first photo is never one from the slide that just ended, so nothing repeats back to back.
     */
    private fun refill() {
        photos.filterTo(pending) { it.name !in shown }
        if (pending.isEmpty()) {
            shown.clear()
            pending.addAll(photos)
        }
        java.util.Collections.shuffle(pending, random)
        var attempts = 0
        while (attempts < pending.size && pending[0].name in lastSlide) {
            pending.add(pending.removeAt(0))
            attempts++
        }
    }

    companion object {
        /** A pair is only worth showing if both photos stay a decent size: how many candidates to compare. */
        const val PARTNER_CANDIDATES: Int = 6
        const val SCREEN_ASPECT: Float = 16f / 9f
    }
}
