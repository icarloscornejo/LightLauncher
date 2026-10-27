package com.prototype.lightlauncher.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.prototype.lightlauncher.R
import com.prototype.lightlauncher.ShelfFeature
import com.prototype.lightlauncher.core.ArtSize
import com.prototype.lightlauncher.core.CollageKind
import com.prototype.lightlauncher.core.CollageSlide
import com.prototype.lightlauncher.core.PublishedIntent
import com.prototype.lightlauncher.core.ShelfMode
import com.prototype.lightlauncher.core.ShelfPlan
import com.prototype.lightlauncher.core.ShelfSource
import com.prototype.lightlauncher.core.TopShelfRules
import com.prototype.lightlauncher.data.ShelfImage
import com.prototype.lightlauncher.data.ShelfImageLoader
import com.prototype.lightlauncher.data.ShelfItem
import com.prototype.lightlauncher.data.TopShelf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The top shelf, what the focused dock app puts above the dock, laid out like tvOS does with the same
 * kind of data (see [TopShelfRules.plan]):
 * - an app that publishes preview programs big enough to fill the screen (1600 px wide) gets them behind the dock, one
 *   after another with a cross-fade and a slow zoom, like the tvOS carousel ([heroA] and [heroB] take turns, with a
 *   darkening, [scrim]); the carousel of an app goes on where it was when the owner left it;
 * - an app whose pictures are smaller (Watch Next, or previews of 1280 px or less: Netflix, Max, Spotify, SmartTube) gets
 *   a "Play Next" row of up to 16 cards (not "Watch Next": music apps publish there too), a row that slides sideways
 *   with the focus ([container]); square covers alone make a collage;
 * - an app with nothing usable gets its icon large. Grid apps have no shelf.
 *
 * Every show or hide bumps [generation]; a download that finishes after the focus moved on finds a newer number
 * and is dropped, so a slow answer for one app can never draw over another. The carousel stops with it. Everything
 * that talks to the provider or the network runs under [ShelfFeature], so a failure leaves the icon and never the
 * home screen down. With reduced motion (config.xml) the first picture stays still: no carousel, no zoom, no fade.
 */
class TopShelfView(
    private val container: FrameLayout,
    private val heroA: ImageView,
    private val heroB: ImageView,
    private val scrim: View,
    private val shelf: TopShelf,
    private val images: ShelfImageLoader,
    /** The blurred picture behind the home: set under a full screen shelf, and kept when the shelf leaves for the grid. */
    private val backdrops: ShelfBackdrops,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val density: Float,
    private val reducedMotion: Boolean,
    /** OK on a card that has an intent. */
    private val onCardOpen: (ShelfItem) -> Unit,
    /** Up or Down (as key codes) on a card: the way out of the row. */
    private val onCardKey: (Int) -> Unit,
    /** The focused card is about to be removed: put the focus back in the dock. */
    private val onFocusLost: () -> Unit,
    /** The cards took the stage (true) or gave it back to the dock (false): the dock dims and drops, or returns. */
    private val onStage: (Boolean) -> Unit,
) {
    @Volatile
    private var generation: Int = 0

    /** The cards on screen, left to right; the ones with an intent can take the focus. */
    private val cardViews: MutableList<ImageView> = ArrayList()

    /** The same cards with their titles, as the row holds them: what dims, and what the row slides to. */
    private val slots: MutableList<View> = ArrayList()

    /** The ring over each card, in the same order. */
    private val rings: MutableList<View> = ArrayList()

    /** The row that holds them, which slides sideways under the screen when there are more cards than fit. */
    private var cardRow: LinearLayout? = null

    /** The one clock that grows the focused card, moves the others aside and slides the row (see [moveRowTo]). */
    private var rowMotion: ValueAnimator? = null

    /** Which card (index in [slots]) the row is arranged around, or -1 when none has the focus. */
    private var focusedSlot: Int = -1

    /**
     * True while the focus is in the row of cards: the row is the main thing on screen (a little bigger, a little lower)
     * and the dock behind it is dimmed. [rowBaseTranslationY] is where the shelf sits when it is not.
     */
    private var staged: Boolean = false
    private var rowBaseTranslationY: Float = 0f

    private fun setStaged(on: Boolean) {
        if (staged == on) return
        staged = on
        onStage(on)
        val scale: Float = if (on) STAGE_SCALE else 1f
        val target: Float = rowBaseTranslationY + if (on) px(STAGE_SHIFT_DP) else 0
        // Grows from its bottom left corner: the label and the first card stay put, and the row reaches up and to the right.
        container.pivotX = 0f
        container.pivotY = container.height.toFloat()
        container.animate().cancel()
        if (reducedMotion) {
            container.scaleX = scale
            container.scaleY = scale
            container.translationY = target
            return
        }
        container.animate().scaleX(scale).scaleY(scale).translationY(target).setDuration(scaled(STAGE_MS)).setInterpolator(Motion.EASE).start()
    }

    init {
        // A focused card grows past its row and casts a shadow: neither may be cut at the edge of the shelf.
        container.clipChildren = false
    }

    /** The hero view showing now; the other one receives the next picture. */
    private var front: ImageView = heroA

    /** Counts the pictures shown since the shelf appeared; even and odd ones move in opposite directions. */
    private var variant: Int = 0
    private var carousel: Job? = null
    private val zooms: MutableMap<ImageView, Animator> = HashMap()

    private fun px(dp: Float): Int = Math.round(dp * density)

    /** The app whose shelf is on screen (or being drawn), so asking for the same one again leaves it alone. */
    private var shownPackage: String? = null
    private var loadingPackage: String? = null

    /** What the shelf on screen is made of, so one that was leaving can come back the way it was. */
    private enum class Kind { NONE, HERO, COLLAGE, CARDS, ICON }

    private var shownKind: Kind = Kind.NONE

    /**
     * The app whose shelf is fading out (the focus went to the grid, or to another dock app). Its views stay until the
     * fade is over, so the exit is an animation and not a pop; [leaveGate] is what a shelf that wants those same views
     * waits on. Both are null when no shelf is leaving.
     */
    private var leavingPackage: String? = null
    private var leaveGate: CompletableDeferred<Unit>? = null

    /**
     * True while another app is in front: the shelf on screen was left as it is (only the carousel and the downloads were
     * stopped), so coming back to the same app resumes it instead of drawing it again.
     */
    private var suspended: Boolean = false

    /** The dock panel's top edge the cards or the icon on screen were placed for, so a later call with another one can move them. */
    private var shelfDockTop: Float = 0f

    /**
     * Shows the shelf of the dock app [packageName]. [dockPanelTop] is where the dock panel's top edge is, in the
     * coordinates of the screen: cards and the icon sit above it. When that app's shelf is already there (the focus came
     * back from its cards, or from the clock) nothing is redrawn: the cards go back to the dock by their own animation
     * instead of being wiped and made again. When it is the one that was just leaving, it comes back where it was (see
     * [comeBack]). A different app's shelf waits for the one on screen to fade out before it is painted on the same views.
     * [force] redraws anyway, for when what the app publishes changed.
     */
    fun show(packageName: String, largeIcon: Drawable?, dockPanelTop: Float, force: Boolean = false) {
        if (leavingPackage == packageName && !force && comeBack(packageName)) return
        if (suspended && !force && packageName == shownPackage && leavingPackage == null) {
            if (resumeShelf(packageName)) return
            clear() // it cannot come back as it was (a collage): drawn again from scratch below
        }
        if (!force && packageName == shownPackage && leavingPackage == null) {
            reposition(dockPanelTop)
            return
        }
        val mine: Int = ++generation
        if (force || leavingPackage == packageName) finishLeave() else beginLeave(toGrid = false)
        loadingPackage = packageName
        val items: List<ShelfItem> = ShelfFeature.run(emptyList()) { shelf.itemsFor(packageName) }
        val previews: List<ShelfItem> = items.filter { it.source == ShelfSource.PREVIEW }.take(PREVIEW_CANDIDATES)
        val watchNext: List<ShelfItem> = items.filter { it.source == ShelfSource.WATCH_NEXT }.take(TopShelfRules.MAX_CARDS)
        if (previews.isEmpty() && watchNext.isEmpty()) {
            backdrops.clear()
            scope.launch { if (settled(mine)) showIcon(largeIcon, dockPanelTop) }
            return
        }
        // The picture the app's full screen shelf left last time (the neutral backdrop for the rest) is there at once, and
        // the sharp one fades in over it when it has been decoded.
        backdrops.showForDock(packageName)
        scope.launch {
            // A picture that fills the screen wins. The sizes of the first few previews are read from their headers, all
            // at once and without decoding anything (a full screen picture is a big decode to throw away); the first
            // that can fill the screen is the one the carousel starts on, and the rest are fetched when its turn comes.
            val heroSizes: List<ArtSize?> = withContext(ioDispatcher) {
                previews.take(HERO_SEARCH).map { item -> async { peek(item, DECODE_HERO_WIDTH, mine) } }.awaitAll()
            }
            if (generation != mine) return@launch
            val heroIndex: Int = heroSizes.indexOfFirst { size -> size != null && TopShelfRules.isHero(size) }
            if (heroIndex >= 0) {
                beginCarousel(packageName, heroSlides(previews, mine), heroIndex, mine, largeIcon, dockPanelTop, Kind.HERO)
                return@launch
            }
            // No full screen picture: a row of cards. The first few appear as soon as they are ready and the rest join
            // them at the end of the row, so the row is not held back by its sixteenth picture. The layout is judged on
            // the pictures drawn (a bigger address found for a card, YouTube's 1280 px thumbnail, is in the size that
            // counts). Play Next (Watch Next) comes first, then the previews.
            if (presentCards(watchNext, mine, dockPanelTop)) return@launch
            if (presentCards(previews, mine, dockPanelTop)) return@launch
            if (generation != mine) return@launch
            // No card at all: what is left is a collage of square covers, or the icon.
            val previewImages: List<ShelfImage?> = loadAll(previews, mine)
            if (generation != mine) return@launch
            val plan: ShelfPlan = TopShelfRules.plan(previewImages.map(::sizeOrNull), emptyList())
            when (plan.mode) {
                // A picture past the first few turned out to fill the screen.
                ShelfMode.HERO -> beginCarousel(packageName, heroSlides(previews, mine), plan.indices.first(), mine, largeIcon, dockPanelTop, Kind.HERO)
                ShelfMode.CARDS -> {
                    val cards: List<Pair<ShelfItem, Bitmap>> = plan.indices.mapNotNull { index -> previewImages[index]?.bitmap?.let { previews[index] to it } }
                    if (settled(mine)) showCards(cards, dockPanelTop)
                }
                ShelfMode.COLLAGE -> beginCarousel(packageName, collageSlides(plan.slides, previewImages), 0, mine, largeIcon, dockPanelTop, Kind.COLLAGE)
                ShelfMode.ICON -> if (settled(mine)) showIcon(largeIcon, dockPanelTop)
            }
        }
    }

    /**
     * Shows [items] as a row of cards when any of them can be one. The first [FIRST_CARDS] are fetched together and shown
     * at once; the rest are fetched meanwhile and added at the end of the row. False when none is a card (or [items] is
     * empty), so the caller tries the next source. True also when the shelf was left behind in the meantime.
     */
    private suspend fun presentCards(items: List<ShelfItem>, mine: Int, dockPanelTop: Float): Boolean {
        if (items.isEmpty()) return false
        // The row comes back where the owner left it, so the card they were on is among the first ones drawn.
        val remembered: Int = loadingPackage?.let { lastCard[it] } ?: 0
        val headSize: Int = Math.max(FIRST_CARDS, remembered + 1)
        val head: List<ShelfItem> = items.take(headSize)
        val tail: List<ShelfItem> = items.drop(headSize)
        val headImages: List<ShelfImage?> = loadAll(head, mine)
        if (generation != mine) return true
        val first: List<Pair<ShelfItem, Bitmap>> = cardsOf(head, headImages)
        if (first.isNotEmpty()) {
            if (!settled(mine)) return true
            showCards(first, dockPanelTop, items.size)
            if (tail.isNotEmpty()) {
                val tailImages: List<ShelfImage?> = loadAll(tail, mine)
                if (generation == mine) appendCards(cardsOf(tail, tailImages))
            }
            return true
        }
        if (tail.isEmpty()) return false
        // Not one among the first: a card may still be further down the list.
        val tailImages: List<ShelfImage?> = loadAll(tail, mine)
        if (generation != mine) return true
        val later: List<Pair<ShelfItem, Bitmap>> = cardsOf(tail, tailImages)
        if (later.isEmpty()) return false
        if (!settled(mine)) return true
        showCards(later, dockPanelTop, later.size)
        return true
    }

    /** The items whose pictures can be cards ([TopShelfRules.isCard]), in order, with their bitmaps. */
    private fun cardsOf(items: List<ShelfItem>, images: List<ShelfImage?>): List<Pair<ShelfItem, Bitmap>> =
        TopShelfRules.cardIndices(images.map(::sizeOrNull)).mapNotNull { index -> images[index]?.bitmap?.let { items[index] to it } }

    /**
     * Blocking, for a background thread: downloads into the disk cache what the focus will ask for first, for each dock
     * app, so that the first visit to an app finds its pictures already here (nothing is decoded, nothing is drawn). It
     * takes the same pictures [show] takes first: the headers of the first previews at full screen size, and the first
     * cards at card size. What is already cached costs nothing, so it is cheap to call on every resume.
     */
    fun prefetch(packageNames: Set<String>) {
        ShelfFeature.run(Unit) {
            for (packageName in packageNames) {
                val items: List<ShelfItem> = shelf.itemsFor(packageName)
                val previews: List<ShelfItem> = items.filter { it.source == ShelfSource.PREVIEW }
                val watchNext: List<ShelfItem> = items.filter { it.source == ShelfSource.WATCH_NEXT }
                previews.take(HERO_SEARCH).forEach { images.prefetch(it.imageUrl, DECODE_HERO_WIDTH) { false } }
                (watchNext.take(FIRST_CARDS) + previews.take(FIRST_CARDS)).forEach { images.prefetch(it.imageUrl, DECODE_CARD_WIDTH) { false } }
            }
        }
    }

    /** The carousel of an app with full screen pictures: one slide for each of its first previews, tried when its turn comes. */
    private fun heroSlides(previews: List<ShelfItem>, mine: Int): List<Slide> =
        previews.take(HERO_CANDIDATES).map { item -> Slide { heroPicture(item, mine) } }

    /**
     * Fetches [items] at card size, in order, at most [PARALLEL_DOWNLOADS] at a time (each with its own limits, see
     * ShelfImageLoader): an app can publish a dozen rows and a TV box has little to spare. A picture that fails is null.
     */
    private suspend fun loadAll(items: List<ShelfItem>, mine: Int): List<ShelfImage?> = withContext(ioDispatcher) {
        val turns = Semaphore(PARALLEL_DOWNLOADS)
        items.map { item -> async { turns.withPermit { load(item, DECODE_CARD_WIDTH, DECODE_CARD_HEIGHT, mine) } } }.awaitAll()
    }

    /**
     * Takes the shelf away (a grid app took the focus, or a reload is about to restore it): it fades out, and when it
     * went to the grid ([toGrid]) the blurred picture of the shelf stays behind the home. When the focus is on one of
     * the cards it is handed back to the dock first: removing a focused view would let Android pick the first
     * focusable one, whatever it is. The hand-off can show the shelf again for the tile that gets the focus; then
     * that [show] has already taken over, and this must not take the shelf away after.
     */
    fun hide(toGrid: Boolean = true) {
        val heldFocus: Boolean = container.hasFocus()
        val mine: Int = ++generation
        if (heldFocus) onFocusLost()
        if (generation == mine) beginLeave(toGrid)
    }

    /**
     * Starts the fade out of the shelf on screen, which leaves its views alone until it is over (then [finishLeave]
     * clears them). Nothing painted yet (the first picture was still loading) or no animations: it is cleared at once.
     * The cards that are leaving can no longer take the focus: [clear] removing a focused view would send the focus to
     * whatever Android finds first.
     */
    private fun beginLeave(toGrid: Boolean) {
        if (leavingPackage != null) return
        val leaving: String? = shownPackage
        if (leaving == null) {
            clear()
            return
        }
        carousel?.cancel()
        carousel = null
        current?.let { place -> place.seenNanos = System.nanoTime() - place.shownAtNanos }
        current = null
        suspended = false
        if (toGrid) backdrops.showForLeaving(leaving)
        if (!motionAllowed()) {
            clear()
            return
        }
        leavingPackage = leaving
        leaveGate = CompletableDeferred()
        container.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // The cards settle with the fade, from wherever they are: cutting the motion short would leave one half grown.
        if (focusedSlot >= 0) moveRowTo(-1)
        for (hero in listOf(heroA, heroB)) hero.animate().alpha(0f).setDuration(REVEAL_MS).start()
        scrim.animate().alpha(0f).setDuration(REVEAL_MS).start()
        // The row gives the stage back while it fades: the dock comes up and the cards settle, as when the focus leaves them.
        val fade = container.animate().alpha(0f).setDuration(REVEAL_MS)
        if (staged) {
            staged = false
            onStage(false)
            fade.scaleX(1f).scaleY(1f).translationY(rowBaseTranslationY).setInterpolator(DecelerateInterpolator())
        }
        fade.withEndAction { if (leavingPackage == leaving) finishLeave() }.start()
    }

    /** The fade is over (or cut short): the views are cleared, and a shelf that waited for them can paint. */
    private fun finishLeave() {
        val gate: CompletableDeferred<Unit>? = leaveGate
        leaveGate = null
        leavingPackage = null
        clear()
        gate?.complete(Unit)
    }

    /** Waits for the shelf that is leaving to be gone. False when another [show] or [hide] has come after [mine]. */
    private suspend fun settled(mine: Int): Boolean {
        leaveGate?.await()
        return generation == mine
    }

    /**
     * The shelf that was fading out is asked for again (down to the grid and up within the fade, or Home and back): its
     * views are brought back where they were, with the same animation they left by, and its carousel goes on from the
     * picture on screen. Under a new [generation] (the old one cancels the carousel's downloads). False when that cannot
     * be done (a collage, or the carousel's place is lost): the caller then shows it from scratch.
     */
    private fun comeBack(packageName: String): Boolean {
        val kind: Kind = shownKind
        val place: Place? = places[packageName]
        val previews: List<ShelfItem> = shelf.itemsFor(packageName).filter { it.source == ShelfSource.PREVIEW }.take(PREVIEW_CANDIDATES)
        if (kind == Kind.NONE || kind == Kind.COLLAGE) return false
        if (kind == Kind.HERO && (place == null || place.index >= Math.min(previews.size, HERO_CANDIDATES))) return false
        val mine: Int = ++generation
        leavingPackage = null
        leaveGate?.complete(Unit)
        leaveGate = null
        container.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (focusedSlot >= 0) moveRowTo(-1)
        container.animate().cancel()
        for (hero in listOf(heroA, heroB)) hero.animate().cancel()
        scrim.animate().cancel()
        backdrops.showForDock(packageName)
        when (kind) {
            Kind.HERO -> {
                val behind: ImageView = if (front === heroA) heroB else heroA
                front.animate().alpha(1f).setDuration(REVEAL_MS).start()
                behind.animate().alpha(0f).setDuration(REVEAL_MS).start()
                scrim.animate().alpha(1f).setDuration(REVEAL_MS).start()
                container.alpha = 0f
                if (place != null) {
                    remember(packageName, place.index)
                    startCarousel(packageName, heroSlides(previews, mine), place.index, mine)
                }
            }
            else -> {
                container.scaleX = 1f
                container.scaleY = 1f
                if (kind == Kind.CARDS) container.translationY = rowBaseTranslationY
                container.animate().alpha(1f).setDuration(REVEAL_MS).start()
            }
        }
        return true
    }

    /**
     * The activity paused and another app is coming up. Downloads and the carousel stop, and the shelf stays exactly as
     * it is: nothing of ours is visible over the other app, and coming back to the same dock app then finds its shelf
     * where it was ([resumeShelf]) instead of drawing it again. Removing the cards would also send a focused card's focus
     * to a dock tile, which would start the shelf again behind the app that opened. A shelf that was still loading its
     * first picture is simply cancelled (the resume asks for it again).
     */
    fun pause() {
        carousel?.cancel()
        carousel = null
        current?.let { place -> place.seenNanos = System.nanoTime() - place.shownAtNanos }
        current = null
        generation++
        if (shownPackage != null && leavingPackage == null) suspended = true
    }

    /**
     * The focus came back to the dock app whose shelf was left in place by [pause]: its carousel goes on from the picture
     * on screen (new [generation], so its downloads are not cancelled), and cards and the icon need nothing. False for a
     * collage, which cannot be rebuilt without its pictures.
     */
    private fun resumeShelf(packageName: String): Boolean {
        if (shownKind == Kind.COLLAGE || shownKind == Kind.NONE) return false
        val previews: List<ShelfItem> = shelf.itemsFor(packageName).filter { it.source == ShelfSource.PREVIEW }.take(PREVIEW_CANDIDATES)
        val place: Place? = places[packageName]
        if (shownKind == Kind.HERO && (place == null || place.index >= Math.min(previews.size, HERO_CANDIDATES))) return false
        val mine: Int = ++generation
        if (shownKind == Kind.HERO && place != null) {
            remember(packageName, place.index)
            startCarousel(packageName, heroSlides(previews, mine), place.index, mine)
        }
        suspended = false
        backdrops.showForDock(packageName)
        return true
    }

    /** Moves the focus to the first card that can be opened. False when there is none (or they are fading out). */
    fun focusFirstCard(): Boolean {
        if (leavingPackage != null) return false
        // Back to the card the owner left (down to the dock and up again), else the first one.
        val remembered: ImageView? = shownPackage?.let { lastCard[it] }?.let { cardViews.getOrNull(it) }?.takeIf { it.isFocusable }
        return (remembered ?: cardViews.firstOrNull { it.isFocusable })?.requestFocus() == true
    }

    /** The card each app's shelf had the focus on last (an index in [cardViews]), kept while the process lives. */
    private val lastCard: MutableMap<String, Int> = HashMap()

    /** Blocking, like [load]: the size an image was published at, read from its header. */
    private fun CoroutineScope.peek(item: ShelfItem, width: Int, mine: Int): ArtSize? =
        ShelfFeature.run(null) { images.peek(item.imageUrl, width) { generation != mine || !isActive } }

    /** Blocking: call on an IO dispatcher, inside the coroutine that owns the download. */
    private fun CoroutineScope.load(item: ShelfItem, width: Int, height: Int, mine: Int): ShelfImage? =
        ShelfFeature.run(null) { images.load(item.imageUrl, width, height) { generation != mine || !isActive } }

    private fun sizeOf(image: ShelfImage): ArtSize = ArtSize(image.originalWidth, image.originalHeight)

    private fun sizeOrNull(image: ShelfImage?): ArtSize? = image?.let(::sizeOf)

    private fun clear() {
        carousel?.cancel()
        carousel = null
        current?.let { place -> place.seenNanos = System.nanoTime() - place.shownAtNanos }
        current = null
        container.animate().cancel()
        container.alpha = 0f
        if (staged) {
            // The shelf goes away with the dock still dimmed: give the stage back at once.
            staged = false
            onStage(false)
        }
        container.scaleX = 1f
        container.scaleY = 1f
        container.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        shownPackage = null
        shownKind = Kind.NONE
        suspended = false
        cardViews.clear()
        slots.clear()
        rings.clear()
        rowMotion?.cancel()
        rowMotion = null
        focusedSlot = -1
        cardRow?.animate()?.cancel()
        cardRow = null
        container.removeAllViews()
        for (hero in listOf(heroA, heroB)) {
            hero.animate().cancel()
            zooms.remove(hero)?.cancel()
            hero.alpha = 0f
            hero.scaleX = 1f
            hero.scaleY = 1f
            hero.setImageDrawable(null)
        }
        front = heroA
        variant = 0
        scrim.animate().cancel()
        scrim.alpha = 0f
    }

    // ---- full screen pictures ----------------------------------------------------------------------

    /** One screen of the carousel: [make] builds its picture when its turn comes, null when it cannot be shown. */
    private class Slide(val make: suspend () -> Bitmap?)

    /** Where the carousel of an app was when the owner left it, so coming back does not start over. */
    private class Place(var index: Int, var shownAtNanos: Long, var seenNanos: Long)

    private val places: MutableMap<String, Place> = HashMap()

    /** The place of the app on screen now; [clear] closes it with how long its last picture was actually seen. */
    private var current: Place? = null

    private fun collageSlides(slides: List<CollageSlide>, pictures: List<ShelfImage?>): List<Slide> =
        slides.map { slide -> Slide { withContext(ioDispatcher) { compose(slide, pictures) } } }

    /** A preview as a full screen picture, or null when it is not big enough to be one (the carousel then skips it). */
    private suspend fun heroPicture(item: ShelfItem, mine: Int): Bitmap? {
        val image: ShelfImage = withContext(ioDispatcher) { load(item, DECODE_HERO_WIDTH, DECODE_HERO_HEIGHT, mine) } ?: return null
        return if (TopShelfRules.isHero(sizeOf(image))) image.bitmap else null
    }

    /**
     * Shows the first slide and rotates through the rest every [HERO_INTERVAL_MS]. An app the owner already saw goes
     * on with the slide after the one it was on (the same one if it had hardly been seen); a new one starts at
     * [firstUsable], which is known to work. A slide that cannot be made is skipped, and the carousel ends quietly
     * when no other can be.
     */
    private suspend fun beginCarousel(packageName: String, slides: List<Slide>, firstUsable: Int, mine: Int, icon: Drawable?, dockPanelTop: Float, kind: Kind) {
        val place: Place? = places[packageName]
        var index: Int = if (place == null) firstUsable else {
            val seenEnough: Boolean = place.seenNanos >= HERO_INTERVAL_MS * 1_000_000L / 2
            (place.index + if (seenEnough) 1 else 0) % slides.size
        }
        var bitmap: Bitmap? = slides[index].make()
        if (bitmap == null && index != firstUsable) {
            index = firstUsable
            bitmap = slides[index].make()
        }
        if (generation != mine) return
        if (!settled(mine)) return
        if (bitmap == null) {
            showIcon(icon, dockPanelTop)
            return
        }
        showHero(bitmap, mine, kind)
        remember(packageName, index)
        startCarousel(packageName, slides, index, mine)
    }

    private fun remember(packageName: String, index: Int) {
        val place: Place = places.getOrPut(packageName) { Place(index, 0L, 0L) }
        place.index = index
        place.shownAtNanos = System.nanoTime()
        place.seenNanos = 0L
        current = place
    }

    private fun showHero(bitmap: Bitmap, mine: Int, kind: Kind) {
        val packageName: String? = loadingPackage
        shownPackage = packageName
        shownKind = kind
        if (packageName != null) backdrops.remember(packageName, bitmap, fullScreen = true, firstOfVisit = true) { generation == mine || leavingPackage == packageName }
        front = heroA
        heroA.setImageBitmap(bitmap)
        reveal(heroA)
        reveal(scrim)
        // The first picture sits still for a moment after the shelf appears; the rest are already moving as they fade in.
        startZoom(heroA, variant, MOTION_DELAY_MS)
    }

    private fun startCarousel(packageName: String, slides: List<Slide>, firstIndex: Int, mine: Int) {
        if (!motionAllowed() || slides.size < 2) return
        carousel = scope.launch {
            val unusable: MutableSet<Int> = HashSet()
            var shown: Int = firstIndex
            while (isActive && generation == mine) {
                delay(HERO_INTERVAL_MS)
                if (generation != mine) return@launch
                var next: Bitmap? = null
                var index: Int = shown
                for (step in 1 until slides.size) {
                    index = (shown + step) % slides.size
                    if (index in unusable) continue
                    next = slides[index].make()
                    if (generation != mine) return@launch
                    if (next != null) break
                    unusable.add(index)
                }
                if (next == null) return@launch // nothing else can fill the screen: stay on this one
                shown = index
                remember(packageName, shown)
                crossfadeTo(next)
                // So that leaving on this picture blurs this one, not the first.
                backdrops.remember(packageName, next, fullScreen = true, firstOfVisit = false) { generation == mine || leavingPackage == packageName }
            }
        }
    }

    /**
     * A collage slide: its pictures cut to the cells of the screen (centred, no stretching beyond what the cell needs)
     * with a thin dark line between them. Null when one of its pictures is gone.
     */
    private fun compose(slide: CollageSlide, pictures: List<ShelfImage?>): Bitmap? {
        val width: Int = DECODE_HERO_WIDTH
        val height: Int = DECODE_HERO_HEIGHT
        val kind: CollageKind = slide.kind
        val cellWidth: Int = width / kind.columns
        val cellHeight: Int = height / kind.rows
        val output: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        slide.indices.forEachIndexed { position, index ->
            val source: Bitmap = pictures.getOrNull(index)?.bitmap ?: return null
            val destination = Rect(
                (position % kind.columns) * cellWidth,
                (position / kind.columns) * cellHeight,
                (position % kind.columns + 1) * cellWidth,
                (position / kind.columns + 1) * cellHeight,
            )
            // Centre crop: the part of the picture that has the cell's shape.
            val scale: Float = Math.max(cellWidth.toFloat() / source.width, cellHeight.toFloat() / source.height)
            val cropWidth: Int = Math.round(cellWidth / scale)
            val cropHeight: Int = Math.round(cellHeight / scale)
            val left: Int = (source.width - cropWidth) / 2
            val top: Int = (source.height - cropHeight) / 2
            canvas.drawBitmap(source, Rect(left, top, left + cropWidth, top + cropHeight), destination, paint)
        }
        val half: Int = COLLAGE_GAP_PX / 2
        for (column in 1 until kind.columns) canvas.drawRect((column * cellWidth - half).toFloat(), 0f, (column * cellWidth + half).toFloat(), height.toFloat(), GAP_PAINT)
        for (row in 1 until kind.rows) canvas.drawRect(0f, (row * cellHeight - half).toFloat(), width.toFloat(), (row * cellHeight + half).toFloat(), GAP_PAINT)
        return output
    }

    /** The other hero view fades in over this one with the new picture, and starts its own zoom from the beginning. */
    private fun crossfadeTo(bitmap: Bitmap) {
        val outgoing: ImageView = front
        val incoming: ImageView = if (outgoing === heroA) heroB else heroA
        front = incoming
        variant++
        incoming.animate().cancel()
        zooms.remove(incoming)?.cancel()
        incoming.alpha = 0f
        incoming.setImageBitmap(bitmap)
        incoming.animate().alpha(1f).setDuration(scaled(FADE_MS)).start()
        outgoing.animate().alpha(0f).setDuration(scaled(FADE_MS)).withEndAction {
            // The picture that left is cleaned up only if it has not been brought back in the meantime.
            if (front !== outgoing) {
                zooms.remove(outgoing)?.cancel()
                outgoing.setImageDrawable(null)
            }
        }.start()
        startZoom(incoming, variant, 0L)
    }

    /**
     * A slow push-in, the way a documentary moves over a still: one small zoom at constant speed that never stops
     * (an ease at the end made the picture freeze just before the next one, which read as stop and start), beginning
     * after [delayMillis] (the first picture waits a moment, the ones that follow move from the first frame of their
     * fade in) and lasting past the next fade, so the picture leaving is still moving. The zoom is about a point that
     * changes from one picture to the next, off the centre, so the picture also drifts a little away from that point
     * without a second animation, and its edges can never come into view because the scale never drops below 1.
     */
    private fun startZoom(view: ImageView, variant: Int, delayMillis: Long) {
        if (!motionAllowed()) return
        zooms.remove(view)?.cancel()
        val width: Float = (if (view.width > 0) view.width else container.resources.displayMetrics.widthPixels).toFloat()
        val height: Float = (if (view.height > 0) view.height else container.resources.displayMetrics.heightPixels).toFloat()
        val pivot: Pair<Float, Float> = ZOOM_PIVOTS[variant % ZOOM_PIVOTS.size]
        view.pivotX = width * pivot.first
        view.pivotY = height * pivot.second
        view.scaleX = ZOOM_FROM
        view.scaleY = ZOOM_FROM
        val zoom: ObjectAnimator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, ZOOM_FROM, ZOOM_TO),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, ZOOM_FROM, ZOOM_TO),
        )
        zoom.startDelay = scaled(delayMillis)
        zoom.duration = scaled(ZOOM_MS)
        zoom.interpolator = LinearInterpolator()
        zooms[view] = zoom
        zoom.start()
    }

    private fun motionAllowed(): Boolean = !reducedMotion && Motion.animatorScale(container.context) > 0f

    /** A duration that lasts [millis] on screen whatever the system's animation speed is (see [Motion.scaled]). */
    private fun scaled(millis: Long): Long = Motion.scaled(container.context, millis)

    // ---- cards and icon ----------------------------------------------------------------------------

    /** [total] is how many cards the row will have when the late ones are in (the room it will need at the end). */
    private fun showCards(cards: List<Pair<ShelfItem, Bitmap>>, dockPanelTop: Float, total: Int = cards.size) {
        if (cards.isEmpty()) return
        val packageName: String? = loadingPackage
        shownPackage = packageName
        shownKind = Kind.CARDS
        // The row comes back as the owner left it: the card they were on is where the first one would be.
        val anchor: Int = (packageName?.let { lastCard[it] } ?: 0).coerceIn(0, cards.lastIndex)
        // That card is what stays behind the home when the focus goes down (the dock itself keeps the neutral backdrop);
        // a card that takes the focus puts its own there (see [cardSlot]).
        if (packageName != null) backdrops.remember(packageName, cards[anchor].second, fullScreen = false, firstOfVisit = true) { leavingPackage == packageName }
        val column = LinearLayout(container.context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        val label: TextView = labelView(container.context.getString(R.string.play_next))
        column.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(LABEL_HEIGHT_DP)))
        val row = LinearLayout(container.context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            // A group with padding clips its children to the padding box unless told not to.
            clipToPadding = false
            setPadding(px(START_DP), 0, px(START_DP), 0)
        }
        for ((index, entry) in cards.withIndex()) {
            val gap: Int = if (index == cards.lastIndex) 0 else px(GAP_DP)
            row.addView(cardSlot(entry.first, entry.second), LinearLayout.LayoutParams(px(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = gap })
        }
        // The row is as wide as all its cards, wider than the screen when there are more than fit: it slides sideways
        // under the screen (see [alignRowTo]) while the label above stays where it is.
        cardRow = row
        column.addView(row, LinearLayout.LayoutParams(rowWidthFor(cards.size), ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        shelfDockTop = dockPanelTop
        rowBaseTranslationY = cardsTop(dockPanelTop)
        container.translationY = rowBaseTranslationY
        if (anchor > 0) row.translationX = -restingOffset(anchor, total).toFloat()
        revealCards(label, anchor)
    }

    /** How far the row is slid so card [anchor] sits where the first one starts, with no card focused (the row is not staged). */
    private fun restingOffset(anchor: Int, total: Int): Int {
        val width: Int = if (container.width > 0) container.width else container.resources.displayMetrics.widthPixels
        val farthest: Int = Math.max(0, rowWidthFor(total) - width)
        return (anchor * (px(CARD_WIDTH_DP) + px(GAP_DP))).coerceIn(0, farthest)
    }

    /**
     * The cards come in one after another from the right, a little apart, and the label with the first (a new shelf
     * only: a shelf that comes back, or resumes, is already there). Without it, or without animations, the whole shelf
     * fades in as every other does.
     */
    private fun revealCards(label: View, firstVisible: Int) {
        if (!ENTRY_STAGGER || !motionAllowed()) {
            reveal(container)
            return
        }
        container.animate().cancel()
        container.alpha = 1f
        val slide: Float = px(ENTRY_SLIDE_DP).toFloat()
        label.alpha = 0f
        label.animate().alpha(1f).setDuration(scaled(MOTION_MS)).setInterpolator(Motion.EASE).start()
        for ((index, slot) in slots.drop(firstVisible).take(ENTRY_VISIBLE).withIndex()) {
            slot.alpha = 0f
            slot.translationX = slide
            slot.animate().alpha(1f).translationX(0f)
                .setStartDelay(scaled(index * ENTRY_STEP_MS))
                .setDuration(scaled(MOTION_MS))
                .setInterpolator(Motion.EASE)
                .start()
        }
    }

    /**
     * The width of a row of [count] cards, with the room at both ends (the same on the left, where the focus rests, and
     * on the right, which also has what the focused card grows by: the last card, grown, ends where it would rest).
     */
    private fun rowWidthFor(count: Int): Int =
        2 * px(START_DP) + count * px(CARD_WIDTH_DP) + (count - 1) * px(GAP_DP) + growExtra().toInt()

    /** How much wider the focused card gets, which the cards after it make room for. */
    private fun growExtra(): Float = px(CARD_WIDTH_DP) * (GROW - 1f)

    /** Adds [cards] at the end of the row on screen (the ones that were still loading when it appeared). */
    private fun appendCards(cards: List<Pair<ShelfItem, Bitmap>>) {
        val row: LinearLayout = cardRow ?: return
        if (cards.isEmpty() || row.childCount == 0) return
        val gap: Int = px(GAP_DP)
        // The card that was the last had no space after it; now it has one.
        (row.getChildAt(row.childCount - 1).layoutParams as LinearLayout.LayoutParams).marginEnd = gap
        for ((index, entry) in cards.withIndex()) {
            val slot: View = cardSlot(entry.first, entry.second)
            if (focusedSlot >= 0) {
                // A card has the focus: the new ones are dimmed like the others and made room for after it.
                slot.alpha = DIMMED_ALPHA
                slot.translationX = growExtra()
            }
            row.addView(slot, LinearLayout.LayoutParams(px(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = if (index == cards.lastIndex) 0 else gap
            })
        }
        row.layoutParams = row.layoutParams.apply { width = rowWidthFor(row.childCount) }
    }

    /**
     * Arranges the row around the card [focused] (an index in [slots], or -1 when none has the focus), the way Netflix
     * does: the focused card grows from its left edge and the ones after it move aside by what it gains, the others dim,
     * and the row slides to keep the focused card where the first one starts (the cards before it leave on the left; near
     * the ends it stops sliding, so the first card is never pushed right and the last one never leaves a gap on the right).
     *
     * All of it runs on one clock, one curve, from wherever each view is now, so a key pressed in the middle of a move
     * carries on from there instead of starting over. Only transforms and alpha change: nothing is laid out again.
     */
    private fun moveRowTo(focused: Int) {
        val row: LinearLayout = cardRow ?: return
        focusedSlot = focused
        rowMotion?.cancel()
        rowMotion = null
        // A card still coming in is taken over from where it is.
        for (slot in slots) slot.animate().cancel()
        val count: Int = slots.size
        val extra: Float = growExtra()
        val toScale = FloatArray(count) { if (it == focused) GROW else 1f }
        val toShift = FloatArray(count) { if (focused in 0 until it) extra else 0f }
        val toAlpha = FloatArray(count) { if (focused < 0 || it == focused) 1f else DIMMED_ALPHA }
        val toRing = FloatArray(count) { if (it == focused) 1f else 0f }
        var toRow: Float = row.translationX
        if (focused >= 0) {
            // The row is drawn bigger while it has the stage, so less of it fits on the screen.
            val width: Int = if (container.width > 0) container.width else container.resources.displayMetrics.widthPixels
            val screen: Int = if (staged) Math.round(width / STAGE_SCALE) else width
            val farthest: Int = Math.max(0, row.layoutParams.width - screen)
            toRow = -(slots[focused].left - px(START_DP)).coerceIn(0, farthest).toFloat()
        }
        val fromScale = FloatArray(count) { slots[it].scaleX }
        val fromShift = FloatArray(count) { slots[it].translationX }
        val fromAlpha = FloatArray(count) { slots[it].alpha }
        val fromRing = FloatArray(count) { rings[it].alpha }
        val fromRow: Float = row.translationX
        val apply: (Float) -> Unit = { fraction ->
            for (index in 0 until count) {
                val slot: View = slots[index]
                val scale: Float = fromScale[index] + (toScale[index] - fromScale[index]) * fraction
                slot.scaleX = scale
                slot.scaleY = scale
                slot.translationX = fromShift[index] + (toShift[index] - fromShift[index]) * fraction
                slot.alpha = fromAlpha[index] + (toAlpha[index] - fromAlpha[index]) * fraction
                rings[index].alpha = fromRing[index] + (toRing[index] - fromRing[index]) * fraction
            }
            row.translationX = fromRow + (toRow - fromRow) * fraction
        }
        if (!motionAllowed()) {
            apply(1f)
            return
        }
        rowMotion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = scaled(MOTION_MS)
            interpolator = Motion.EASE
            addUpdateListener { apply(it.animatedValue as Float) }
            start()
        }
    }

    /** The grey heading over the row, the way tvOS titles it. */
    private fun labelView(text: String): TextView = TextView(container.context).apply {
        this.text = text
        textSize = 19f
        typeface = resources.getFont(R.font.outfit_semibold)
        setTextColor(context.getColor(R.color.text_tertiary))
        includeFontPadding = false
        gravity = Gravity.BOTTOM
        setPadding(px(START_DP), 0, 0, px(LABEL_GAP_DP))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * One "Play Next" card with its title under it. When the app gave an intent for it ([PublishedIntent.isUsable]) it
     * can take the focus and opens on OK; without one it is only a picture. The focused card does not grow (a growing
     * card ran into its neighbours and the edge of the row): it gets a ring while the others dim a little, and the
     * whole row slides to keep it in place ([alignRowTo]). Left and Right stay
     * inside the row, Up and Down are the way out ([onCardKey]).
     */
    private fun cardSlot(item: ShelfItem, bitmap: Bitmap): View {
        val context = container.context
        val card = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // No background colour behind the picture: it is always there and fills the view, and a second layer under
            // it would show through when the slot is dimmed without an off screen copy.
            setImageBitmap(bitmap)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, px(CARD_RADIUS_DP).toFloat())
                }
            }
            clipToOutline = true
        }
        // The ring is its own view over the card, so it can fade in and out without touching the picture.
        val ring = View(context).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(CARD_RADIUS_DP).toFloat()
                setStroke(px(RING_DP), context.getColor(R.color.text_primary))
            }
        }
        val title = TextView(context).apply {
            text = item.title
            textSize = 16f
            typeface = resources.getFont(R.font.outfit_medium)
            setTextColor(context.getColor(R.color.text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(px(TITLE_INSET_DP), px(TITLE_GAP_DP), px(TITLE_INSET_DP), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val picture = FrameLayout(context).apply {
            clipChildren = false
            addView(card, FrameLayout.LayoutParams(px(CARD_WIDTH_DP), px(CARD_HEIGHT_DP)))
            addView(ring, FrameLayout.LayoutParams(px(CARD_WIDTH_DP), px(CARD_HEIGHT_DP)))
        }
        val slot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            // Grows from the left edge and the bottom of the picture (see [moveRowTo]): upwards, into the room under the
            // label, and the title under it only grows by a few dp, inside its block. Growing from the middle took the
            // title below the bottom of the shelf, where the dock covers it. The picture and the title never overlap, so
            // dimming the slot needs no off screen copy of it.
            pivotX = 0f
            pivotY = px(CARD_HEIGHT_DP).toFloat()
            forceHasOverlappingRendering(false)
            addView(picture, LinearLayout.LayoutParams(px(CARD_WIDTH_DP), px(CARD_HEIGHT_DP)))
            addView(title, LinearLayout.LayoutParams(px(CARD_WIDTH_DP), px(TITLE_BLOCK_DP)))
        }
        slots.add(slot)
        rings.add(ring)
        cardViews.add(card)
        if (!PublishedIntent.isUsable(item.intentUri)) return slot
        card.isFocusable = true
        card.contentDescription = item.title
        card.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        card.setOnClickListener { onCardOpen(item) }
        card.setOnFocusChangeListener { _, focused ->
            if (focused) {
                setStaged(true)
                val index: Int = slots.indexOf(slot)
                shownPackage?.let { pkg ->
                    lastCard[pkg] = index
                    // The focused card's picture is kept to be the blurred one behind the home when the focus goes down to
                    // the grid. It is not drawn while the cards have the focus (only if the shelf is already leaving).
                    backdrops.remember(pkg, bitmap, fullScreen = false, firstOfVisit = false) { leavingPackage == pkg }
                }
                moveRowTo(index)
            } else {
                // The focus moved to another card (which takes over the row when it arrives) or out of the row: then all
                // settle again, and the dock gets the stage back.
                card.post {
                    if (!container.hasFocus()) {
                        moveRowTo(-1)
                        setStaged(false)
                    }
                }
            }
        }
        card.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // The next card in that direction that can be opened; at the end of the row the key is taken, so
                    // the focus finder never throws the focus to some tile far away.
                    val openable: List<ImageView> = cardViews.filter { it.isFocusable }
                    val step: Int = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    openable.getOrNull(openable.indexOf(card) + step)?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    onCardKey(keyCode)
                    true
                }
                else -> false
            }
        }
        return slot
    }

    /** The app's icon, large and centred in the free space above the dock. Nothing when the app has no icon. */
    private fun showIcon(icon: Drawable?, dockPanelTop: Float) {
        if (icon == null) return
        shownPackage = loadingPackage
        shownKind = Kind.ICON
        val view = ImageView(container.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Many TV icons are a square with its own background; round it like an app icon instead of a hard box.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, px(ICON_DP) * ICON_RADIUS_SHARE)
                }
            }
            clipToOutline = true
        }
        container.addView(view, FrameLayout.LayoutParams(px(ICON_DP), px(ICON_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        shelfDockTop = dockPanelTop
        container.translationY = iconTop(dockPanelTop)
        reveal(container)
    }

    /** Where the row of cards sits: its bottom (label, cards, titles) just above the dock panel. */
    private fun cardsTop(dockPanelTop: Float): Float =
        dockPanelTop - px(DOCK_GAP_DP) - px(CARD_HEIGHT_DP) - px(TITLE_BLOCK_DP) - px(LABEL_HEIGHT_DP)

    /** Where the icon sits: centred in the free space between the clock pill and the dock panel. */
    private fun iconTop(dockPanelTop: Float): Float {
        val regionTop: Float = px(ICON_REGION_TOP_DP).toFloat()
        val regionBottom: Float = dockPanelTop - px(DOCK_GAP_DP)
        return regionTop + Math.max(0f, (regionBottom - regionTop - px(ICON_DP)) / 2f)
    }

    /**
     * The shelf on screen was placed for a dock panel that is not where it is now (it was drawn before the grid had its
     * final padding, as happens right after the activity is recreated): moves it, without drawing it again.
     */
    private fun reposition(dockPanelTop: Float) {
        if (Math.abs(dockPanelTop - shelfDockTop) < 1f) return
        shelfDockTop = dockPanelTop
        when (shownKind) {
            Kind.CARDS -> {
                rowBaseTranslationY = cardsTop(dockPanelTop)
                container.translationY = rowBaseTranslationY + if (staged) px(STAGE_SHIFT_DP) else 0
            }
            Kind.ICON -> container.translationY = iconTop(dockPanelTop)
            else -> Unit
        }
    }

    private fun reveal(view: View) {
        view.animate().cancel()
        if (reducedMotion) {
            view.alpha = 1f
        } else {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(REVEAL_MS).start()
        }
    }

    private companion object {
        /**
         * Previews fetched for the cards (or a collage, which needs at least eight covers), how many carousel slides a
         * hero app gets, how many previews are tried one by one before the app is judged to have no full screen picture
         * (so a slow first answer is not multiplied), and how many downloads run at once.
         */
        val PREVIEW_CANDIDATES: Int = TopShelfRules.MAX_CARDS
        const val HERO_CANDIDATES: Int = 12
        const val HERO_SEARCH: Int = 3

        /** The cards shown as soon as they are ready: about what the screen holds (two and a half) and a few to scroll to. */
        const val FIRST_CARDS: Int = 6
        const val PARALLEL_DOWNLOADS: Int = 6

        /** The cells of a collage are told apart by a thin dark line. */
        const val COLLAGE_GAP_PX: Int = 6
        val GAP_PAINT: Paint = Paint().apply { color = Color.BLACK }

        /**
         * How long a full screen picture stays and how long the change takes, how long it sits still before it starts to
         * move, and the move itself: a push-in of 5% over the whole time, about a point that changes picture by picture
         * (as shares of the width and height). The speed is the one it had with ten seconds a picture (7% in 13 s), so a
         * shorter stay only means the picture moves less before the next one.
         */
        const val HERO_INTERVAL_MS: Long = 6_000L
        const val FADE_MS: Long = 1_200L
        const val MOTION_DELAY_MS: Long = 1_000L
        const val ZOOM_FROM: Float = 1.0f
        const val ZOOM_TO: Float = 1.05f

        /** Longer than the picture stays plus its fade, so the one fading out is still moving, never frozen, as it goes. */
        const val ZOOM_MS: Long = 9_000L
        val ZOOM_PIVOTS: List<Pair<Float, Float>> = listOf(0.5f to 0.5f, 0.35f to 0.4f, 0.65f to 0.45f, 0.45f to 0.35f)

        /** What the loader shrinks to: the screen for a full screen picture, about a card for a card. */
        const val DECODE_HERO_WIDTH: Int = 1920
        const val DECODE_HERO_HEIGHT: Int = 1080
        const val DECODE_CARD_WIDTH: Int = 800
        const val DECODE_CARD_HEIGHT: Int = 450

        const val CARD_WIDTH_DP: Float = 400f
        const val CARD_HEIGHT_DP: Float = 210f
        const val CARD_RADIUS_DP: Float = 18f

        /**
         * How much the focused card grows (the ones after it move aside by what it gains), and how long every movement of the
         * row lasts (on screen, whatever the system's animation speed): the growing, the moving aside, the sliding, the
         * dimming and the stage below all take this long, so they arrive together.
         */
        const val GROW: Float = 1.10f
        const val MOTION_MS: Long = Motion.MOVE_MS

        /** The cards coming in one after another (false: the whole shelf fades in): how far from the right they start, the delay between two, and how many. */
        const val ENTRY_STAGGER: Boolean = true
        const val ENTRY_SLIDE_DP: Float = 24f
        const val ENTRY_STEP_MS: Long = 40L
        const val ENTRY_VISIBLE: Int = 5

        /** The ring of the focused card, how much the other cards dim while one is focused, and the title under each card. */
        /** When the row has the stage: how much bigger it is, how far it drops (the dock drops with it), and how long that takes. */
        const val STAGE_SCALE: Float = 1.12f
        const val STAGE_SHIFT_DP: Float = 52f
        const val STAGE_MS: Long = MOTION_MS
        const val RING_DP: Float = 3f
        const val DIMMED_ALPHA: Float = 0.62f
        const val TITLE_GAP_DP: Float = 10f
        const val TITLE_INSET_DP: Float = 4f
        const val TITLE_BLOCK_DP: Float = 40f
        const val GAP_DP: Float = 28f
        const val START_DP: Float = 50f
        // The label sits clear of the focused card, which grows up into the space between them.
        const val LABEL_HEIGHT_DP: Float = 62f
        const val LABEL_GAP_DP: Float = 32f

        /** Clear space between the shelf and the dock panel. */
        const val DOCK_GAP_DP: Float = 28f
        const val ICON_DP: Float = 190f
        const val ICON_RADIUS_SHARE: Float = 0.22f

        /** The icon is centred below the clock pill (24 dp from the top, 44 dp high, plus some air). */
        const val ICON_REGION_TOP_DP: Float = 80f
        const val REVEAL_MS: Long = 200L
    }
}
