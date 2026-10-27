package com.prototype.lightlauncher.core

/** Where a shelf item came from: the app's preview programs (its showcase) or its Watch Next row (what to continue). */
enum class ShelfSource { PREVIEW, WATCH_NEXT }

/** What the top shelf shows for a dock app: one big picture, a collage of smaller ones, a row of cards, or just the app icon. */
enum class ShelfMode { HERO, COLLAGE, CARDS, ICON }

/** The size of an image as the app published it, before it is shrunk for the screen. */
class ArtSize(val width: Int, val height: Int)

/**
 * The outcome of [TopShelfRules.plan]: the mode and, for HERO and CARDS, where the pictures come from and which of
 * the loaded ones to use ([indices] point into the list of that [source]; for HERO, the first full screen picture).
 * [slides] say which small pictures share each screen as a collage (COLLAGE only).
 */
class ShelfPlan(val mode: ShelfMode, val source: ShelfSource?, val indices: List<Int>, val slides: List<CollageSlide> = emptyList())

/**
 * How the top shelf is laid out. An app that publishes preview programs gets a full screen picture (Apple TV,
 * YouTube); an app that only publishes Watch Next gets a "Continue Watching" row of cards (Netflix); an app with
 * nothing usable shows its icon. The size that counts is the one the app published: a card is shown at about 800 px and a hero at 1920, so art much narrower than that would be
 * stretched, and a portrait poster would be cropped to a sliver in a landscape slot. A card may be up to a third
 * smaller than its slot (Max publishes 600 px wide art), a hero may not: it is shown at the full width of the screen.
 */
object TopShelfRules {
    /** Cards shown per app: a row that slides sideways, so as many as the app publishes (the reader keeps 16 per source). */
    const val MAX_CARDS: Int = 16
    const val MIN_CARD_WIDTH: Int = 480

    /**
     * A picture from this width can fill the screen (it is enlarged by a fifth at most). Narrower ones, 1280 x 720 being
     * the most Spotify and YouTube publish, are cards: stretched to the screen they would look blurred, and framed in the
     * middle they would look like a small picture on a dark wall, so they go in the row of cards, at a size they are sharp at.
     */
    const val MIN_HERO_WIDTH: Int = 1600
    private const val MIN_ASPECT: Float = 1.2f
    private const val MIN_HERO_ASPECT: Float = 1.6f
    private const val MAX_ASPECT: Float = 2.4f

    fun isCard(size: ArtSize): Boolean = landscape(size, MIN_CARD_WIDTH, MIN_ASPECT)

    /** Wide enough and close to 16:9 or wider, so it can fill the screen without cropping away the subject. */
    fun isHero(size: ArtSize): Boolean = landscape(size, MIN_HERO_WIDTH, MIN_HERO_ASPECT)

    private fun landscape(size: ArtSize, minWidth: Int, minAspect: Float): Boolean {
        if (size.width < minWidth || size.height <= 0) return false
        return (size.width.toFloat() / size.height) in minAspect..MAX_ASPECT
    }

    /** Positions in [sizes] (null = the image could not be loaded) that make a card, in order, at most [MAX_CARDS]. */
    fun cardIndices(sizes: List<ArtSize?>): List<Int> =
        sizes.indices.filter { index -> sizes[index]?.let(::isCard) == true }.take(MAX_CARDS)

    /**
     * Chooses the layout from the sizes of the loaded preview and Watch Next images, in order (null = not loaded):
     * the first preview picture that can be a hero wins; failing that, the Watch Next cards ("Play Next"); failing
     * that, preview pictures that are landscape and wide enough make a row of cards; failing that, enough square covers
     * make a collage; failing that, the icon.
     */
    fun plan(previews: List<ArtSize?>, watchNext: List<ArtSize?>): ShelfPlan {
        val hero: Int = previews.indexOfFirst { size -> size?.let(::isHero) == true }
        if (hero >= 0) return ShelfPlan(ShelfMode.HERO, ShelfSource.PREVIEW, listOf(hero))
        val watchCards: List<Int> = cardIndices(watchNext)
        if (watchCards.isNotEmpty()) return ShelfPlan(ShelfMode.CARDS, ShelfSource.WATCH_NEXT, watchCards)
        val previewCards: List<Int> = cardIndices(previews)
        if (previewCards.isNotEmpty()) return ShelfPlan(ShelfMode.CARDS, ShelfSource.PREVIEW, previewCards)
        val collage: List<CollageSlide> = CollageRules.slides(previews)
        if (collage.isNotEmpty()) return ShelfPlan(ShelfMode.COLLAGE, ShelfSource.PREVIEW, emptyList(), collage)
        return ShelfPlan(ShelfMode.ICON, null, emptyList())
    }
}
