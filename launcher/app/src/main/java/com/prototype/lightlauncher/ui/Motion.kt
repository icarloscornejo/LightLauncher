package com.prototype.lightlauncher.ui

import android.content.Context
import android.provider.Settings
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * The curve and the clock shared by the movements that go together when a card takes the focus (the row
 * of cards, the stage the shelf takes, the dock that dims and drops), so they start, run and stop as one.
 */
object Motion {
    /** The emphasized decelerate curve: quick off the mark, a very soft landing. */
    val EASE: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** How long the movements that go together last, on screen. */
    const val MOVE_MS: Long = 380L

    /** The system's animation speed: 0 means animations are off, and this launcher keeps it at 0.5 on some TVs. */
    fun animatorScale(context: Context): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    /**
     * A duration that lasts [millis] on screen whatever the system's animation speed is. Android multiplies every
     * animator's duration by that speed, so at 0.5 a "slow" movement would run twice as fast without this.
     */
    fun scaled(context: Context, millis: Long): Long {
        val scale: Float = animatorScale(context)
        return if (scale > 0f) (millis / scale).toLong() else 0L
    }
}
