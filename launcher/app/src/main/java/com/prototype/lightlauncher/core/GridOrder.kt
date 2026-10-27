package com.prototype.lightlauncher.core

/**
 * The order of the grid under the dock after the owner moved tiles on the TV. The entries are tile keys
 * (the component of each app), in the order they show. Without a saved order the grid keeps its default one
 * (cards first, then alphabetical).
 */
object GridOrder {

    /** The saved keys that still exist, in the saved order, then the ones nobody placed yet (a new app) in [current] order. */
    fun reconcile(saved: List<String>, current: List<String>): List<String> {
        val present: Set<String> = current.toSet()
        val kept: List<String> = saved.distinct().filter { it in present }
        val keptSet: Set<String> = kept.toSet()
        return kept + current.filter { it !in keptSet }
    }

    /**
     * Takes the entry at [from] out and puts it at [to]: what is in between moves one place over, like a card moved in a
     * hand. An index outside the list, or the same one twice, changes nothing.
     */
    fun move(order: List<String>, from: Int, to: Int): List<String> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        val result: MutableList<String> = order.toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }
}
