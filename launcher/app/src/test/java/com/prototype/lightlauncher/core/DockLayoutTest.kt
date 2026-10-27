package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DockLayoutTest {

    /** A launcher activity: its package and a name that tells two activities of one package apart. */
    private data class Entry(val pkg: String, val name: String)

    private fun arrange(favourites: List<String>, slots: Int, entries: List<Entry>): DockArrangement<Entry> =
        DockLayout.arrange(favourites, slots, entries) { it.pkg }

    private val spotify = Entry("spotify", "main")
    private val netflix = Entry("netflix", "main")
    private val max = Entry("max", "main")
    private val files = Entry("files", "main")
    private val browser = Entry("browser", "main")

    @Test
    fun dockFollowsTheFavouriteOrderNotTheEntryOrder() {
        val result = arrange(listOf("netflix", "spotify"), 2, listOf(files, spotify, netflix))
        assertEquals(listOf<Entry?>(netflix, spotify), result.dock)
        assertEquals(listOf(files), result.rest)
    }

    @Test
    fun aMissingFavouriteLeavesAVacancyInsteadOfPullingInAGridApp() {
        // Six slots, six favourites, one of them (max) not installed or hidden: it never reaches the entries.
        val favourites = listOf("spotify", "netflix", "max", "x1", "x2", "x3")
        val result = arrange(favourites, 6, listOf(browser, files, spotify, netflix))
        assertEquals(listOf<Entry?>(spotify, netflix, null, null, null, null), result.dock)
        assertEquals(listOf(browser, files), result.rest)
    }

    @Test
    fun aReturningFavouriteTakesItsOwnSlotBack() {
        val favourites = listOf("spotify", "max", "netflix")
        val without = arrange(favourites, 3, listOf(spotify, netflix))
        val with = arrange(favourites, 3, listOf(spotify, max, netflix))
        assertEquals(listOf<Entry?>(spotify, null, netflix), without.dock)
        assertEquals(listOf<Entry?>(spotify, max, netflix), with.dock)
    }

    @Test
    fun aPackageWithSeveralActivitiesFillsOneSlotAndTheRestGoToTheGrid() {
        val second = Entry("spotify", "second")
        val result = arrange(listOf("spotify", "netflix"), 2, listOf(spotify, second, netflix))
        assertEquals(listOf<Entry?>(spotify, netflix), result.dock)
        assertEquals(listOf(second), result.rest)
    }

    @Test
    fun fewerFavouritesThanSlotsLeavesTheTailVacant() {
        val result = arrange(listOf("spotify"), 3, listOf(spotify, files))
        assertEquals(listOf<Entry?>(spotify, null, null), result.dock)
        assertEquals(listOf(files), result.rest)
    }

    @Test
    fun favouritesPastTheLastSlotAndRepeatsAreIgnored() {
        val result = arrange(listOf("spotify", "spotify", "netflix", "max"), 2, listOf(spotify, netflix, max))
        assertEquals(listOf<Entry?>(spotify, netflix), result.dock)
        assertEquals(listOf(max), result.rest)
    }

    @Test
    fun noEntriesGivesAllVacancies() {
        val result = arrange(listOf("spotify"), 2, emptyList())
        assertEquals(listOf<Entry?>(null, null), result.dock)
        assertEquals(emptyList<Entry>(), result.rest)
    }
}
