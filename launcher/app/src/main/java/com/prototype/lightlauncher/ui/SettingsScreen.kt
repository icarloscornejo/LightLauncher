package com.prototype.lightlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.prototype.lightlauncher.R

/**
 * One row of a settings page. The screen draws what the fields say; whoever owns the row changes the fields and calls
 * [SettingsScreen.refresh]. [id] is unique across all pages: it is how a row is found again after the activity is
 * recreated (a theme or language change).
 */
class SettingsRow(val id: String, var title: CharSequence) {
    var subtitle: CharSequence? = null

    /** Grey text on the right (a value, or why the row is not available). */
    var value: CharSequence? = null

    /** Keeps the value's view on screen even with no text yet: something else (the Wi-Fi name) fills it. */
    var showsValue: Boolean = false
    var chevron: Boolean = false
    var checked: Boolean = false

    /** False draws the row faded: it is not available here. Its click still runs, to say why. */
    var enabled: Boolean = true
    var iconRes: Int = 0

    /** Puts the icon beside the value on the right (the Wi-Fi symbol by the network's name), not before the title. */
    var iconAtEnd: Boolean = false

    /** What the line of help under the list says while this row has the focus (else the page's own). */
    var hint: CharSequence? = null
    var onClick: (() -> Unit)? = null
    var onLongClick: (() -> Boolean)? = null

    /**
     * Makes the row an accordion: OK opens the rows this returns right under it (asked when it opens, so what they
     * show is current) and OK again, or Back, closes them. When there is nothing to show, [onEmpty] runs instead.
     */
    var children: (() -> List<SettingsRow>)? = null
    var onEmpty: (() -> Unit)? = null
    internal var expanded: Boolean = false

    /** A row inside an accordion: set in a little from the edge. */
    var indented: Boolean = false
}

/**
 * A list of rows with a title. [parent] is the page Back returns to, null for the first one. [content] is something to
 * show above the rows (the QR code); [onShow] and [onHide] run when the page comes on screen and when it leaves it.
 */
class SettingsPage(
    val id: String,
    var title: CharSequence,
    val rows: List<SettingsRow>,
    val parent: String? = null,
    var hint: CharSequence? = null,
    val content: View? = null,
    val onShow: (() -> Unit)? = null,
    val onHide: (() -> Unit)? = null,
)

/**
 * The settings screen the clock pill opens: a full-width list of rounded rows over a soft gradient,
 * like Apple TV's Settings, built with plain views. The focused row turns white,
 * grows a little and lifts. Pages are lists of [SettingsRow]; OK on a row can open another page (a stack, Back goes
 * up one). It is a sibling of [content], not a child: while it is open [content] blocks focus, so the D-pad can
 * never reach the grid or the clock pill behind it. Each page's views are built once and shown and hidden, so what
 * other classes hold of a row (the Wi-Fi name's TextView, the Inputs row that hides itself) stays valid.
 */
class SettingsScreen(
    private val screen: View,
    private val content: ViewGroup,
    private val density: Float,
    private val reducedMotion: Boolean,
) {
    private class RowViews(val row: SettingsRow, val view: View) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val valueIcon: ImageView = view.findViewById(R.id.valueIcon)

        /** Where this row's icon goes: before the title, or beside the value. */
        val iconTarget: ImageView get() = if (row.iconAtEnd) valueIcon else icon
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val value: TextView = view.findViewById(R.id.value)
        val check: ImageView = view.findViewById(R.id.check)
        val chevron: ImageView = view.findViewById(R.id.chevron)
    }

    private class Built(val page: SettingsPage, val container: LinearLayout, val rows: List<RowViews>)

    private val titleView: TextView = screen.findViewById(R.id.settingsTitle)
    private val hintView: TextView = screen.findViewById(R.id.settingsHint)
    private val pagesHost: FrameLayout = screen.findViewById(R.id.settingsPages)
    private val built: MutableMap<String, Built> = LinkedHashMap()
    private val rowsById: MutableMap<String, RowViews> = HashMap()

    /** The page each row sits on, and the rows an open accordion put under its row. */
    private val pageOfRow: MutableMap<String, String> = HashMap()
    private val openChildren: MutableMap<String, List<RowViews>> = HashMap()

    /** The pages open now, first to last; Back removes the last. */
    private val stack: MutableList<String> = ArrayList()

    /** The row each page of the stack had focused when it opened the next one, so Back lands on it again. */
    private val returnTo: MutableMap<String, String> = HashMap()
    private var focusabilityBefore: Int = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    private var origin: View? = null

    var isOpen: Boolean = false
        private set

    /** The page on screen, or null when the screen is closed. */
    val currentPageId: String? get() = if (isOpen) stack.lastOrNull() else null

    /** The row with the focus, or null. */
    val focusedRowId: String?
        get() = built[currentPageId]?.rows?.firstOrNull { it.view.isFocused }?.row?.id

    /** Builds the views of [page]. Called once per page, before the screen is opened. */
    fun add(page: SettingsPage) {
        val inflater: LayoutInflater = LayoutInflater.from(screen.context)
        val container = LinearLayout(screen.context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            visibility = View.GONE
        }
        page.content?.let { container.addView(it) }
        val rows: List<RowViews> = page.rows.map { row ->
            val views: RowViews = inflateRow(inflater, container, page, row)
            container.addView(views.view)
            views
        }
        pagesHost.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        built[page.id] = Built(page, container, rows)
    }

    /** The views of one row, with its listeners, registered so it can be found by id. */
    private fun inflateRow(inflater: LayoutInflater, container: ViewGroup, page: SettingsPage, row: SettingsRow): RowViews {
        val view: View = inflater.inflate(R.layout.item_setting, container, false)
        val views = RowViews(row, view)
        if (row.indented) {
            // Set in by padding, not by a margin: the row keeps the full width of the list, only its content moves.
            view.setPaddingRelative(view.paddingStart + Math.round(INDENT_DP * density), view.paddingTop, view.paddingEnd, view.paddingBottom)
        }
        bind(views)
        view.setOnClickListener { if (row.children != null) toggle(row) else row.onClick?.invoke() }
        view.setOnLongClickListener { row.onLongClick?.invoke() ?: false }
        view.setOnFocusChangeListener { focused, hasFocus -> onRowFocus(page, row, focused, hasFocus) }
        rowsById[row.id] = views
        pageOfRow[row.id] = page.id
        return views
    }

    /** Opens or closes the accordion [row]. */
    private fun toggle(row: SettingsRow) {
        if (row.expanded) collapse(row, focusParent = true) else expand(row)
    }

    private fun expand(row: SettingsRow) {
        val parent: RowViews = rowsById[row.id] ?: return
        val built: Built = built[pageOfRow[row.id]] ?: return
        val kids: List<SettingsRow> = row.children?.invoke().orEmpty()
        if (kids.isEmpty()) {
            row.onEmpty?.invoke()
            return
        }
        val inflater: LayoutInflater = LayoutInflater.from(screen.context)
        var at: Int = built.container.indexOfChild(parent.view)
        val added: List<RowViews> = kids.map { kid ->
            kid.indented = true
            val views: RowViews = inflateRow(inflater, built.container, built.page, kid)
            built.container.addView(views.view, ++at)
            views
        }
        openChildren[row.id] = added
        row.expanded = true
        bind(parent)
        added.first().view.requestFocus()
        if (reducedMotion) return
        // They unfold one after another, each sliding down a little as it fades in.
        added.forEachIndexed { index, kid ->
            kid.view.alpha = 0f
            kid.view.translationY = -REVEAL_SHIFT_DP * density
            kid.view.animate().alpha(if (kid.row.enabled) 1f else DISABLED_ALPHA).translationY(0f)
                .setStartDelay(index * REVEAL_STAGGER_MS).setDuration(REVEAL_MS).start()
        }
    }

    private fun collapse(row: SettingsRow, focusParent: Boolean) {
        val kids: List<RowViews> = openChildren.remove(row.id) ?: return
        val parent: RowViews? = rowsById[row.id]
        val hadFocus: Boolean = kids.any { it.view.hasFocus() }
        for (kid in kids) {
            rowsById.remove(kid.row.id)
            pageOfRow.remove(kid.row.id)
        }
        row.expanded = false
        parent?.let(::bind)
        // The focus goes first, so it is never on a row that is about to leave.
        if (focusParent && hadFocus) parent?.view?.requestFocus()
        if (reducedMotion || !isOpen) {
            for (kid in kids) (kid.view.parent as? ViewGroup)?.removeView(kid.view)
            return
        }
        // They fold away together, quickly (the same time they take to come, in the other direction), and leave the list.
        for (kid in kids) {
            kid.view.isFocusable = false
            kid.view.animate().alpha(0f).translationY(-REVEAL_SHIFT_DP * density).setStartDelay(0).setDuration(REVEAL_MS)
                .withEndAction { (kid.view.parent as? ViewGroup)?.removeView(kid.view) }.start()
        }
    }

    /** The accordion open on the page on screen, if any. */
    private fun openAccordion(): SettingsRow? =
        openChildren.keys.firstOrNull { pageOfRow[it] == stack.lastOrNull() }?.let { rowsById[it]?.row }

    /** The view of a row, for the classes that keep one (the Inputs popup anchors on its row and hides it). */
    fun rowView(id: String): View = rowsById.getValue(id).view

    /** The grey value of a row, for what fills it from outside (the Wi-Fi name). */
    fun valueView(id: String): TextView = rowsById.getValue(id).value

    /** The icon of a row. */
    fun iconView(id: String): ImageView = rowsById.getValue(id).iconTarget

    /** Draws a row again after its fields changed. */
    fun refresh(row: SettingsRow) {
        rowsById[row.id]?.let(::bind)
    }

    private fun bind(views: RowViews) {
        val row: SettingsRow = views.row
        views.title.text = row.title
        views.subtitle.text = row.subtitle
        views.subtitle.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (row.value != null || row.showsValue) views.value.text = row.value
        views.value.visibility = if (row.value != null || row.showsValue) View.VISIBLE else View.GONE
        views.check.visibility = if (row.checked) View.VISIBLE else View.GONE
        views.chevron.visibility = if (row.chevron || row.children != null) View.VISIBLE else View.GONE
        // An accordion's chevron points down while it is open.
        views.chevron.animate().rotation(if (row.expanded) 90f else 0f).setDuration(if (reducedMotion) 0 else 160).start()
        if (row.iconRes != 0) {
            views.iconTarget.setImageResource(row.iconRes)
            views.iconTarget.visibility = View.VISIBLE
        }
        views.view.alpha = if (row.enabled) 1f else DISABLED_ALPHA
    }

    /** The same animation as a tile of the grid (overshoot), a little smaller: the row is wide, a tile is not. */
    private fun onRowFocus(page: SettingsPage, row: SettingsRow, view: View, focused: Boolean) {
        val scale: Float = if (focused) FOCUS_SCALE else 1f
        val lift: Float = if (focused) LIFT_DP * density else 0f
        view.animate().cancel()
        if (reducedMotion) {
            view.animate().scaleX(scale).scaleY(scale).translationZ(lift).setDuration(80).start()
        } else {
            view.animate().scaleX(scale).scaleY(scale).translationZ(lift)
                .setDuration(if (focused) 240 else 160)
                .setInterpolator(OvershootInterpolator(if (focused) 1.4f else 0f))
                .start()
        }
        if (focused) hintView.text = row.hint ?: page.hint
    }

    /**
     * Shows the screen over [from], the view that gets the focus back when it closes. [pageId] and [rowId] pick where
     * to start (the parents of the page are put on the stack, so Back goes up).
     */
    fun open(from: View, pageId: String, rowId: String? = null) {
        if (isOpen) return
        isOpen = true
        origin = from
        focusabilityBefore = content.descendantFocusability
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        screen.visibility = View.VISIBLE
        stack.clear()
        returnTo.clear()
        var chain: String? = pageId
        while (chain != null) {
            stack.add(0, chain)
            chain = built.getValue(chain).page.parent
        }
        show(pageId, rowId)
    }

    /** Opens the page [pageId] over the current one. */
    fun push(pageId: String, rowId: String? = null) {
        if (!isOpen) return
        focusedRowId?.let { returnTo[stack.last()] = it }
        stack.add(pageId)
        show(pageId, rowId)
    }

    /** Back: closes an open accordion first, then goes up one page. False when this is already the first page (the caller closes the screen). */
    fun back(): Boolean {
        if (!isOpen) return false
        openAccordion()?.let {
            collapse(it, focusParent = true)
            return true
        }
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        val page: String = stack.last()
        show(page, returnTo[page])
        return true
    }

    /** Hides the screen. [restoreFocus] is false when something else (onPause, Home) puts the focus back. */
    fun close(restoreFocus: Boolean) {
        if (!isOpen) return
        isOpen = false
        for (id in openChildren.keys.toList()) rowsById[id]?.row?.let { collapse(it, focusParent = false) }
        shown?.let { built[it]?.page?.onHide?.invoke() }
        shown = null
        screen.visibility = View.GONE
        content.descendantFocusability = focusabilityBefore
        stack.clear()
        if (restoreFocus) origin?.requestFocus()
        origin = null
    }

    /** The page whose views are on screen, so it can be told when it leaves. */
    private var shown: String? = null

    private fun show(pageId: String, rowId: String?) {
        val target: Built = built.getValue(pageId)
        shown?.let { built[it]?.page?.onHide?.invoke() }
        shown = pageId
        for (other in built.values) if (other !== target) other.container.visibility = View.GONE
        target.container.visibility = View.VISIBLE
        if (!reducedMotion) {
            target.container.alpha = 0f
            target.container.animate().alpha(1f).setDuration(160).start()
        }
        titleView.text = target.page.title
        hintView.text = target.page.hint
        // Where the focus goes: the row asked for, else the one that is checked (a list of choices), else the first.
        val focusable: List<RowViews> = target.rows.filter { it.view.visibility == View.VISIBLE }
        (focusable.firstOrNull { it.row.id == rowId } ?: focusable.firstOrNull { it.row.checked } ?: focusable.firstOrNull())?.view?.requestFocus()
        target.page.onShow?.invoke()
    }

    private companion object {
        /** How far the content of an accordion's rows is set in, and how they come in and go out. */
        const val INDENT_DP: Float = 28f
        const val REVEAL_MS: Long = 180L
        const val REVEAL_STAGGER_MS: Long = 35L
        const val REVEAL_SHIFT_DP: Float = 8f
        const val FOCUS_SCALE: Float = 1.025f
        const val LIFT_DP: Float = 8f
        const val DISABLED_ALPHA: Float = 0.5f
    }
}
