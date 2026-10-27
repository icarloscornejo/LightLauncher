package com.prototype.lightlauncher.ui

import android.graphics.drawable.Drawable
import android.view.View

/**
 * The picture behind everything, over the neutral backdrop of the root: the blurred picture of the last dock app's shelf
 * ([ShelfBackdrops] makes it). The home does not take the colour of what is focused.
 *
 * Two views take turns, like the shelf's two hero views: a new picture fades in on the one that is not showing while the
 * other fades out, so a change between two different blurred pictures is a blend and not a jump.
 */
class AmbientShelf(
    first: View,
    second: View,
    private val reducedMotion: Boolean,
) {
    private val views: Array<View> = arrayOf(first, second)
    private var front: Int = 0

    /** What is on screen now (or fading in), so asking for the same picture again leaves it alone; null when none. */
    private var shownKey: String? = null

    /** Fades [backdrop] in over whatever is there. [key] names the picture: the same key again does nothing. */
    fun show(key: String, backdrop: Drawable) {
        if (key == shownKey) return
        val outgoing: View = views[front]
        front = 1 - front
        val incoming: View = views[front]
        incoming.animate().cancel()
        incoming.alpha = 0f
        incoming.background = backdrop
        fadeTo(incoming, 1f)
        fadeTo(outgoing, 0f)
        shownKey = key
    }

    /** Back to the neutral backdrop. */
    fun hide() {
        if (shownKey == null) return
        shownKey = null
        fadeTo(views[front], 0f)
    }

    private fun fadeTo(view: View, alpha: Float) {
        view.animate().cancel()
        if (reducedMotion) {
            view.alpha = alpha
        } else {
            view.animate().alpha(alpha).setDuration(FADE_MS).start()
        }
    }

    private companion object {
        /** Slower than the shelf's own fades: the picture behind should drift, not compete with what is in front. */
        const val FADE_MS: Long = 450L
    }
}
