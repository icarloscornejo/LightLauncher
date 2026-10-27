package com.prototype.lightlauncher.data

import android.content.Context
import android.content.SharedPreferences
import com.prototype.lightlauncher.core.DockOrder

/**
 * What the owner arranged on the TV (see [DockOrder] and [com.prototype.lightlauncher.core.GridOrder]):
 * the dock, as packages, and the grid, as tile keys. Preferences do not keep the order of a set, so each is one string.
 * Both are written in one commit, because a move between the dock and the grid changes both.
 */
class DockOrderStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The saved dock; empty until the owner moves something, which means "use dock_apps". */
    fun readDock(): List<String> = DockOrder.decode(preferences.getString(DOCK_KEY, null))

    /** The saved grid order; empty until the owner moves something, which means the default order. */
    fun readGrid(): List<String> = DockOrder.decode(preferences.getString(GRID_KEY, null))

    /** False when the write failed (apply() would never say). */
    fun write(dock: List<String>, grid: List<String>): Boolean =
        preferences.edit().putString(DOCK_KEY, DockOrder.encode(dock)).putString(GRID_KEY, DockOrder.encode(grid)).commit()

    private companion object {
        const val FILE_NAME: String = "dock_order"
        const val DOCK_KEY: String = "order"
        const val GRID_KEY: String = "grid"
    }
}
