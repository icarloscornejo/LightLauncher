package com.prototype.lightlauncher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan

/**
 * The green "VPN" capsule drawn after the network name in the Wi-Fi row while a VPN is up: a lock and the word VPN, dark on
 * green, so it reads on both the resting and the focused (white) row. With a VPN app configured and no VPN
 * up, the same capsule turns red, without the lock, so a dropped VPN is noticed.
 *
 * @param lockIcon the lock icon for the "up" badge, or null for the red "off" badge
 */
class VpnBadge(lockIcon: Drawable?, private val density: Float) : ReplacementSpan() {

    private val fill: Int = if (lockIcon != null) GREEN else RED
    private val ink: Int = if (lockIcon != null) INK else INK_OFF
    private val lock: Drawable? = lockIcon?.mutate()?.also { it.setTint(ink) }
    private val rect: RectF = RectF()

    private fun textSize(paint: Paint): Float = paint.textSize * 0.78f

    private fun lockSize(paint: Paint): Float = if (lock != null) paint.textSize * 0.8f else 0f

    private fun lockGap(): Float = if (lock != null) 4 * density else 2 * density

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val old: Float = paint.textSize
        paint.textSize = textSize(paint)
        val textWidth: Float = paint.measureText(text, start, end)
        paint.textSize = old
        // margin + left pad + lock + gap + text + right pad
        return (8 * density + 7 * density + lockSize(paint) + lockGap() + textWidth + 9 * density).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val base: Float = paint.textSize
        val lockPx: Float = lockSize(paint)
        val capsuleHeight: Float = base * 1.3f
        val centerY: Float = (top + bottom) / 2f
        val left: Float = x + 8 * density
        val right: Float = x + getSize(paint, text, start, end, null)
        rect.set(left, centerY - capsuleHeight / 2f, right, centerY + capsuleHeight / 2f)

        val oldColor: Int = paint.color
        paint.color = fill
        canvas.drawRoundRect(rect, capsuleHeight / 2f, capsuleHeight / 2f, paint)

        val lockX: Int = (left + 7 * density).toInt()
        lock?.let {
            it.setBounds(lockX, (centerY - lockPx / 2f).toInt(), (lockX + lockPx).toInt(), (centerY + lockPx / 2f).toInt())
            it.draw(canvas)
        }

        paint.color = ink
        paint.textSize = textSize(paint)
        val metrics: Paint.FontMetrics = paint.fontMetrics
        val textY: Float = centerY - (metrics.ascent + metrics.descent) / 2f
        if (text != null) canvas.drawText(text, start, end, lockX + lockPx + lockGap(), textY, paint)
        paint.textSize = base
        paint.color = oldColor
    }

    private companion object {
        const val GREEN: Int = 0xFF30D158.toInt()
        const val INK: Int = 0xFF06290F.toInt()
        const val RED: Int = 0xFFFF453A.toInt()
        const val INK_OFF: Int = 0xFFFFFFFF.toInt()
    }
}
