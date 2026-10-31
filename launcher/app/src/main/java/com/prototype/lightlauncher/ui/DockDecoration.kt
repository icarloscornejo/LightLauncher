package com.prototype.lightlauncher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.prototype.lightlauncher.R

/**
 * Draws the tvOS style glass panel behind the first (dock) row and adds a gap below it. The panel is translucent
 * (the glow shows through). It spans all [dockSize] slots, vacancies included, so the
 * panel keeps its width when a favourite is missing.
 */
class DockDecoration(
    private val dockSize: Int,
    private val density: Float,
    /** The panel's fill and its edge, from the colour tokens (they differ between the light and dark theme). */
    panelFill: Int,
    panelStroke: Int,
) : RecyclerView.ItemDecoration() {

    private val band: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelFill }
    private val edge: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = panelStroke
    }
    private val rect: RectF = RectF()

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position: Int = parent.getChildAdapterPosition(view)
        if (position in 0 until dockSize) {
            outRect.top = (TOP_DP * density).toInt()
            outRect.bottom = (BOTTOM_DP * density).toInt()
        }
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        var any = false
        for (index in 0 until parent.childCount) {
            val child: View = parent.getChildAt(index)
            val position: Int = parent.getChildAdapterPosition(child)
            if (position !in 0 until dockSize) continue
            val card: View = child.findViewById(R.id.card) ?: continue
            val left: Float = (child.left + card.left).toFloat()
            // Layout position only: a tile sliding into or out of the dock in move mode must not stretch the panel.
            val top: Float = (child.top + card.top).toFloat()
            val right: Float = left + card.width
            val bottom: Float = top + card.height
            if (!any) {
                rect.set(left, top, right, bottom)
                any = true
            } else {
                rect.union(left, top, right, bottom)
            }
        }
        if (!any) return
        val pad: Float = PANEL_PAD_DP * density
        rect.inset(-pad, -pad)
        val radius: Float = PANEL_RADIUS_DP * density
        canvas.drawRoundRect(rect, radius, radius, band)
        canvas.drawRoundRect(rect, radius, radius, edge)
    }

    companion object {
        /** Space the dock row gets above and below it (dp); MainActivity's dock parking uses the same numbers. */
        const val TOP_DP: Float = 6f
        const val BOTTOM_DP: Float = 12f

        /** How far the panel reaches past the tiles, and its corner radius (dp). */
        const val PANEL_PAD_DP: Float = 19f
        private const val PANEL_RADIUS_DP: Float = 28f
    }
}
