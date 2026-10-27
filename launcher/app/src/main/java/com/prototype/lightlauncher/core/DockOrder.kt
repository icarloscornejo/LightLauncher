package com.prototype.lightlauncher.core

/** The dock (packages, one per slot) and the grid (tile keys, in order) after a move that touched both. */
class Orders(val dock: List<String>, val grid: List<String>)

/**
 * The dock the owner arranged on the TV (long press, then the arrows). Once anything is saved, the saved
 * list is the dock, members and order both: an app moved up from the grid is in it though `dock_apps` never named it,
 * and one moved down is out of it though `dock_apps` still does. `dock_apps` from config.xml or a theme only counts
 * until the first save. Position i of the dock is entry i of the list, the way [DockLayout] reads `dock_apps`, so a
 * package that is missing from the TV keeps its slot while the others swap around it.
 */
object DockOrder {

    /** The saved dock when there is one, else [configured]; no repeats either way. */
    fun reconcile(saved: List<String>, configured: List<String>): List<String> =
        if (saved.isEmpty()) configured.distinct() else saved.distinct()

    /**
     * Trades the dock app at [dockIndex] (its tile key is [dockKey]) with the grid tile at [gridIndex]: the grid tile's
     * package takes the dock slot and the dock app takes the grid place the other left. Null when either index does
     * not exist, or when the package coming up is already in the dock (a second launcher activity of a dock app).
     */
    fun swapAcross(dock: List<String>, grid: List<String>, dockIndex: Int, gridIndex: Int, dockKey: String, packageOf: (String) -> String): Orders? {
        if (dockIndex !in dock.indices || gridIndex !in grid.indices) return null
        val coming: String = packageOf(grid[gridIndex])
        if (coming in dock) return null
        return Orders(
            dock.toMutableList().also { it[dockIndex] = coming },
            grid.toMutableList().also { it[gridIndex] = dockKey },
        )
    }

    /** [order] with the entries at [first] and [second] exchanged. An index outside the list changes nothing. */
    fun swap(order: List<String>, first: Int, second: Int): List<String> {
        if (first !in order.indices || second !in order.indices || first == second) return order
        val result: MutableList<String> = order.toMutableList()
        result[first] = order[second]
        result[second] = order[first]
        return result
    }

    /** One string for the preferences: they do not keep the order of a set. Package names never contain a comma. */
    fun encode(order: List<String>): String = order.joinToString(SEPARATOR)

    /** The inverse of [encode]; null, blank or damaged text gives an empty list, which [reconcile] turns into the config order. */
    fun decode(text: String?): List<String> =
        text.orEmpty().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private const val SEPARATOR: String = ","
}
