package com.prototype.lightlauncher.core

/** How a collage slide divides the screen: wide pictures two by two, square covers four by two. */
enum class CollageKind(val columns: Int, val rows: Int) {
    WIDE(2, 2),
    COVERS(4, 2),
    ;

    val cells: Int get() = columns * rows
}

/** One screen of a collage: the pictures to put in its cells, as positions in the list the slides were made from. */
class CollageSlide(val kind: CollageKind, val indices: List<Int>)

/**
 * A picture too small to fill the screen is not stretched; several of them share it instead, each at
 * about its own size (a 1000 px wide picture in a 960 x 540 cell is sharp, and so is a 640 px square cover in a
 * 480 x 540 one). Only whole slides are made: half empty cells would look broken. Pictures big enough for the screen
 * ([TopShelfRules.isHero]) never go into one.
 */
object CollageRules {
    /**
     * A wide picture sharp in a 960 px cell; from [TopShelfRules.MIN_HERO_WIDTH] on it is a full screen picture instead.
     * [TopShelfRules.plan] only reaches a collage when nothing is a card, so in practice this is the covers' fallback.
     */
    const val MIN_WIDE_WIDTH: Int = 960
    const val MIN_COVER_WIDTH: Int = 480
    private const val MIN_WIDE_ASPECT: Float = 1.6f
    private const val MAX_WIDE_ASPECT: Float = 2.4f
    private const val MIN_COVER_ASPECT: Float = 0.8f
    private const val MAX_COVER_ASPECT: Float = 1.25f

    /** The kind of cell [size] fits, or null when it is neither a wide picture nor a square cover. */
    fun kindOf(size: ArtSize?): CollageKind? {
        if (size == null || size.height <= 0) return null
        val aspect: Float = size.width.toFloat() / size.height
        return when {
            size.width in MIN_WIDE_WIDTH until TopShelfRules.MIN_HERO_WIDTH && aspect in MIN_WIDE_ASPECT..MAX_WIDE_ASPECT -> CollageKind.WIDE
            size.width >= MIN_COVER_WIDTH && aspect in MIN_COVER_ASPECT..MAX_COVER_ASPECT -> CollageKind.COVERS
            else -> null
        }
    }

    /** Whole slides from [sizes] (null = not loaded), in order: the wide pictures first, then the covers. */
    fun slides(sizes: List<ArtSize?>): List<CollageSlide> {
        val slides: MutableList<CollageSlide> = ArrayList()
        for (kind in CollageKind.entries) {
            val matching: List<Int> = sizes.indices.filter { index -> kindOf(sizes[index]) == kind }
            for (group in matching.chunked(kind.cells)) {
                if (group.size == kind.cells) slides.add(CollageSlide(kind, group))
            }
        }
        return slides
    }
}
