package com.prototype.lightlauncher.core

/**
 * The dock row and the grid under it. [dock] always has one slot per column: the favourite package's entry, or
 * null for a vacancy; [rest] is everything else, in the order it came in.
 */
class DockArrangement<T>(val dock: List<T?>, val rest: List<T>)

/**
 * Which entries sit in the dock. A favourite that is missing or hidden leaves its slot empty (the first app of the
 * grid does not slide into the dock), and a package with several launcher activities fills
 * one slot only (its other activities go to the grid). Favourites past the last slot are ignored.
 */
object DockLayout {

    fun <T> arrange(favourites: List<String>, slots: Int, entries: List<T>, packageOf: (T) -> String): DockArrangement<T> {
        val chosen: MutableList<T?> = ArrayList()
        val used: MutableSet<T> = HashSet()
        for (favourite in favourites.distinct().take(maxOf(0, slots))) {
            val entry: T? = entries.firstOrNull { packageOf(it) == favourite }
            chosen.add(entry)
            if (entry != null) used.add(entry)
        }
        while (chosen.size < slots) chosen.add(null)
        return DockArrangement(chosen, entries.filter { it !in used })
    }
}
