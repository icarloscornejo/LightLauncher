package com.prototype.lightlauncher.core

/**
 * Where a slide's photos go. Pixel widths (0 means "take what is left", used by a single
 * photo) and the glow centres as fractions of the screen width.
 */
data class SlideLayout(
    val leftWidth: Int,
    val rightWidth: Int,
    val leftCenter: Float,
    val rightCenter: Float,
) {
    companion object {
        /** One photo, centred, filling what the screen allows. */
        val SINGLE: SlideLayout = SlideLayout(0, 0, 0.5f, 0.5f)

        /**
         * Both photos of a pair get the same height, so each one's width follows its own shape: no photo is
         * squeezed into a fixed half. They are as tall as the screen allows, side by side and centred together.
         */
        fun forPair(leftAspect: Float, rightAspect: Float, screenWidth: Float, screenHeight: Float): SlideLayout {
            val height: Float = Math.min(screenHeight, screenWidth / (leftAspect + rightAspect))
            val leftWidth: Int = Math.round(height * leftAspect)
            val rightWidth: Int = Math.round(height * rightAspect)
            val margin: Float = (screenWidth - leftWidth - rightWidth) / 2f
            return SlideLayout(
                leftWidth,
                rightWidth,
                (margin + leftWidth / 2f) / screenWidth,
                (margin + leftWidth + rightWidth / 2f) / screenWidth,
            )
        }
    }
}
