package com.prototype.lightlauncher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.prototype.lightlauncher.HomeSettingsActivity
import com.prototype.lightlauncher.R
import com.prototype.lightlauncher.core.DockOrder
import com.prototype.lightlauncher.data.TileEntry
import com.prototype.lightlauncher.data.TileKind

/**
 * App tiles: the first [dockSize] positions are the pinned dock, then the alphabetical grid, in the
 * order [com.prototype.lightlauncher.data.AppCatalog] gives them. This class only binds tiles and animates their focus.
 */
class AppAdapter(
    private val context: Context,
    private val listener: TileListener?,
    private val dockSize: Int,
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private val reducedMotion: Boolean,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    interface TileListener {
        fun onTileFocused(position: Int, card: View)

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        fun onTileVerticalKey(position: Int, keyCode: Int): Boolean

        /** Called for a long press on a tile. Return true when handled; false opens the app's details screen. */
        fun onTileLongPress(position: Int): Boolean
    }

    private val items: MutableList<TileEntry> = ArrayList()

    init {
        // Tiles keep their identity across reloads, so a focused tile stays on its app instead of its view being
        // rebound to whatever app now sits in that slot.
        setHasStableIds(true)
    }

    /** Replaces the tiles. */
    @Suppress("NotifyDataSetChanged") // the whole list is rebuilt from the package manager every time
    fun submit(entries: List<TileEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view: View = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val density: Float = parent.resources.displayMetrics.density
        val width: Int = if (parent.width > 0) parent.width else parent.measuredWidth
        val inner: Int = width - parent.paddingLeft - parent.paddingRight
        val tileWidth: Int = tileWidthPx(inner, dockSize, density)
        val tileHeight: Int = tileHeightPx(tileWidth)
        val card: View = view.findViewById(R.id.card)
        card.layoutParams = card.layoutParams.apply {
            this.width = tileWidth
            this.height = tileHeight
        }
        val name: View = view.findViewById(R.id.name)
        name.layoutParams = (name.layoutParams as ViewGroup.MarginLayoutParams).apply {
            this.width = tileWidth
            // Clear of the focused tile's zoom, which grows it by FOCUS_SCALE - 1 of its height.
            topMargin = tileHeight + Math.round(12 * density)
        }
        return Holder(view, listener, reducedMotion)
    }

    /** A tile that crossed between the dock and the grid only needs its name shown or hidden; the rest stays as it is. */
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (ZONE_CHANGED in payloads && items[position].kind != TileKind.EMPTY) {
            holder.name.visibility = if (position < dockSize) View.INVISIBLE else View.VISIBLE
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry: TileEntry = items[position]
        if (entry.kind == TileKind.EMPTY) {
            bindVacancy(holder)
            return
        }
        // A holder that showed a vacancy is reused for a real tile.
        holder.card.visibility = View.VISIBLE
        holder.card.isFocusable = true
        holder.card.isClickable = true
        holder.name.text = entry.label
        holder.name.visibility = if (position < dockSize) View.INVISIBLE else View.VISIBLE // the dock has no labels
        val focused: Boolean = holder.card.isFocused // a rebind after a reload can hit the focused tile
        holder.name.alpha = if (focused) 1f else 0f
        holder.card.scaleX = if (focused) FOCUS_SCALE else 1f
        holder.card.scaleY = if (focused) FOCUS_SCALE else 1f
        holder.card.translationZ = if (focused) FOCUS_LIFT_DP * holder.card.resources.displayMetrics.density else 0f
        if (holder.card !== movingView) holder.itemView.translationZ = 0f // a recycled view of the moved tile
        if (entry.banner != null) {
            holder.banner.setImageDrawable(entry.banner)
            holder.banner.visibility = View.VISIBLE
            holder.icon.visibility = View.GONE
            holder.label.visibility = View.GONE
        } else {
            holder.banner.setImageDrawable(null)
            holder.banner.visibility = View.GONE
            holder.icon.setImageDrawable(entry.icon)
            holder.icon.visibility = View.VISIBLE
            holder.label.text = entry.label
            holder.label.visibility = View.VISIBLE
        }
        holder.card.setOnClickListener { launch(entry) }
        holder.card.setOnLongClickListener {
            val position: Int = holder.bindingAdapterPosition
            val handled: Boolean = position != RecyclerView.NO_POSITION && listener?.onTileLongPress(position) == true
            if (!handled) openDetails(entry.pkg)
            true
        }
    }

    /** The app's system details screen (force stop, clear cache), which is where a tile's long press goes. */
    fun openDetails(packageName: String) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(details)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app details screen on this TV")
        }
    }

    /**
     * Exchanges two tiles in place, for the dock's move mode. The view of each tile travels with it (stable ids, no
     * item animator), so the focused tile stays focused. A move is a pair of notifications unless the tiles are
     * neighbours: the first brings [lo] to [hi], the second takes what was at [hi] to [lo]'s old place.
     */
    fun swap(first: Int, second: Int) {
        if (first == second || first !in items.indices || second !in items.indices) return
        val lo: Int = Math.min(first, second)
        val hi: Int = Math.max(first, second)
        java.util.Collections.swap(items, lo, hi)
        notifyItemMoved(lo, hi)
        if (hi - lo > 1) notifyItemMoved(hi - 1, lo)
        if (lo < dockSize && hi >= dockSize) {
            // The two tiles traded the dock for the grid: the dock shows no names, the grid does.
            notifyItemChanged(lo, ZONE_CHANGED)
            notifyItemChanged(hi, ZONE_CHANGED)
        }
    }

    /** Takes the tile at [from] out and puts it at [to]; the tiles in between move one place over (the grid's move mode). */
    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    /**
     * Fills the empty dock slot [vacancy] with the grid tile at [from]: the vacancy is dropped (the list is one shorter)
     * and the tile takes its place, the ones in between move one over. The tile's view travels with it, so it keeps focus.
     */
    fun fill(vacancy: Int, from: Int) {
        if (vacancy !in items.indices || from <= vacancy || from !in items.indices) return
        DockOrder.fillSlot(items, vacancy, from)
        notifyItemRemoved(vacancy)
        notifyItemMoved(from - 1, vacancy)
        // The tile left the grid for the dock: the dock shows no names.
        notifyItemChanged(vacancy, ZONE_CHANGED)
    }

    /** The keys of the tiles under the dock that the owner can arrange, in order: not the optional Default home tile. */
    fun gridKeys(): List<String> =
        items.drop(dockSize).filter { it.kind == TileKind.APP }.map { it.key }

    private var movingView: View? = null

    /**
     * The tile being moved grows past its focus size and lifts higher, so it reads as picked up (the shake of the first
     * version was dropped). It is the same kind of animation as a focus change, so it feels like the rest of the home.
     */
    fun startMoving(tile: View) {
        stopMoving()
        movingView = tile
        // The card's own lift only orders it inside its item; the items are what the grid stacks.
        (tile.parent as? View)?.translationZ = MOVING_ITEM_Z_DP * tile.resources.displayMetrics.density
        animateTile(tile, MOVING_SCALE, MOVING_LIFT_DP)
    }

    /** Puts the tile back to its focused size. Safe to call when nothing is being moved. */
    fun stopMoving() {
        movingView?.let { tile ->
            (tile.parent as? View)?.translationZ = 0f
            animateTile(tile, FOCUS_SCALE, FOCUS_LIFT_DP)
        }
        movingView = null
    }

    private fun animateTile(tile: View, scale: Float, liftDp: Float) {
        val lift: Float = liftDp * tile.resources.displayMetrics.density
        tile.animate().cancel()
        if (reducedMotion) {
            tile.scaleX = scale
            tile.scaleY = scale
            tile.translationZ = lift
            return
        }
        tile.animate().scaleX(scale).scaleY(scale).translationZ(lift)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withLayer()
            .start()
    }

    /** An empty dock slot: it keeps its place in the row (so the panel keeps its width) but cannot take focus. */
    private fun bindVacancy(holder: Holder) {
        holder.card.setOnClickListener(null)
        holder.card.setOnLongClickListener(null)
        // Setting a listener makes the view clickable again, so the flags come after.
        holder.card.isClickable = false
        holder.card.isLongClickable = false
        holder.card.isFocusable = false
        holder.card.visibility = View.INVISIBLE
        holder.name.visibility = View.INVISIBLE
        holder.card.scaleX = 1f
        holder.card.scaleY = 1f
        holder.card.translationZ = 0f
    }

    /** True for an empty dock slot: the keys skip it. */
    fun isVacant(position: Int): Boolean = items.getOrNull(position)?.kind == TileKind.EMPTY

    /** Opens the tile's activity by explicit component, with no extras. */
    private fun launch(entry: TileEntry) {
        if (entry.kind == TileKind.HOME_SETTINGS) {
            context.startActivity(Intent(context, HomeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val intent: Intent = Intent(Intent.ACTION_MAIN)
            // Android 13+ matches an explicit intent against the target's filter, and a phone app only has LAUNCHER.
            .addCategory(if (entry.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.cant_open, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(context, context.getString(R.string.cant_open, entry.label), Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    /** Stable key for the app at a position (its component), or null. */
    fun keyAt(position: Int): String? = items.getOrNull(position)?.key

    /** Position of the app with this key, or -1 when it is gone. */
    fun positionOf(key: String?): Int = if (key == null) -1 else items.indexOfFirst { it.key == key }

    /** The name of the app at a position, or an empty string. */
    fun labelAt(position: Int): String = items.getOrNull(position)?.label ?: ""

    /** The package of the app at a position, or an empty string. */
    fun packageAt(position: Int): String = items.getOrNull(position)?.pkg ?: ""

    /** The big icon the top shelf shows when an app has nothing to publish: the xxxhdpi one for dock apps. */
    fun largeIconAt(position: Int): Drawable? = items.getOrNull(position)?.let { it.largeIcon ?: it.icon }

    class Holder(view: View, listener: TileListener?, reducedMotion: Boolean) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.card)
        val banner: ImageView = view.findViewById(R.id.banner)
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
        val name: TextView = view.findViewById(R.id.name)

        init {
            val density: Float = view.resources.displayMetrics.density
            card.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(target: View, outline: Outline) {
                    outline.setRoundRect(0, 0, target.width, target.height, cornerRadius(target.width))
                }
            }
            card.clipToOutline = true

            card.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN &&
                    keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
                ) return@setOnKeyListener false
                val position: Int = bindingAdapterPosition
                listener != null && position != RecyclerView.NO_POSITION && listener.onTileVerticalKey(position, keyCode)
            }

            card.setOnFocusChangeListener { focusedView, focused ->
                val scale: Float = if (focused) FOCUS_SCALE else 1.0f
                // The focused tile lifts, with a soft shadow under it.
                val lift: Float = if (focused) FOCUS_LIFT_DP * density else 0f
                focusedView.animate().cancel()
                name.animate().cancel()
                if (reducedMotion) {
                    // No overshoot: the extra frames redraw the banner and the dock band on a weak GPU.
                    focusedView.animate().scaleX(scale).scaleY(scale).translationZ(lift)
                        .setDuration(if (focused) 120 else 80)
                        .setInterpolator(DecelerateInterpolator())
                        .withLayer()
                        .start()
                    name.animate().alpha(if (focused) 1f else 0f).setDuration(100).start()
                } else {
                    focusedView.animate().scaleX(scale).scaleY(scale).translationZ(lift)
                        .setDuration(if (focused) 260 else 180)
                        .setInterpolator(OvershootInterpolator(if (focused) 1.6f else 0f))
                        .withLayer()
                        .start()
                    name.animate().alpha(if (focused) 1f else 0f)
                        .translationY(if (focused) density * 4f else -density * 6f)
                        .setDuration(220).start()
                }
                val position: Int = bindingAdapterPosition
                if (focused && listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(position, focusedView)
                }
            }
        }
    }

    companion object {
        private const val TAG: String = "LightLauncher"

        /** tvOS style zoom. The tile grows into the gap between tiles, which is wider than the growth. */
        const val FOCUS_SCALE: Float = 1.20f

        /** How far the focused tile lifts (translationZ, dp), which is what its shadow follows. */
        const val FOCUS_LIFT_DP: Float = 10f

        /** Payload of a change notification: the tile moved between the dock and the grid. */
        private const val ZONE_CHANGED: String = "zone"

        /** Move mode: the picked up tile's size and lift, bigger than a focused one (FOCUS_SCALE, FOCUS_LIFT_DP). */
        private const val MOVING_SCALE: Float = 1.26f
        private const val MOVING_LIFT_DP: Float = 22f

        /** Raises the moved tile's whole item above its neighbours, so the tiles it trades places with slide behind it. */
        private const val MOVING_ITEM_Z_DP: Float = 40f

        /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
        const val DESIGN_W_DP: Float = 180f
        const val DESIGN_H_DP: Float = 101f

        /** Space between two tiles of a row (dp). The zoomed tile reaches about half of FOCUS_SCALE - 1 into it. */
        const val TILE_GAP_DP: Float = 24f

        /** Corner radius as a share of the tile width: the same shape at any column count. */
        private const val CORNER_RATIO: Float = 0.11f

        /**
         * Tile width for a grid of this inner width: the cell minus the gap, capped at the design size so a few
         * columns on a 1080p TV do not get bigger than they were.
         */
        fun tileWidthPx(innerWidthPx: Int, columns: Int, density: Float): Int {
            val design: Int = Math.round(DESIGN_W_DP * density)
            if (innerWidthPx <= 0 || columns <= 0) return design
            val cell: Int = innerWidthPx / columns
            val fit: Int = (cell - TILE_GAP_DP * density).toInt()
            return Math.max(Math.round(80 * density), Math.min(design, fit))
        }

        fun tileHeightPx(tileWidthPx: Int): Int = Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP)

        /** Height of one grid item: the tile, the name label under it and the item's own padding. */
        fun itemHeightPx(tileHeightPx: Int, density: Float): Int = tileHeightPx + Math.round(50 * density)

        fun cornerRadius(tileWidthPx: Int): Float = CORNER_RATIO * tileWidthPx
    }
}
