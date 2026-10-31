package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DockOrderTest {

    private val configured = listOf("spotify", "netflix", "max", "prime")

    @Test
    fun withNothingSavedTheConfigOrderIsUsed() {
        assertEquals(configured, DockOrder.reconcile(emptyList(), configured))
    }

    @Test
    fun theSavedOrderWinsOverTheConfigOrder() {
        val saved = listOf("max", "spotify", "prime", "netflix")
        assertEquals(saved, DockOrder.reconcile(saved, configured))
    }

    @Test
    fun onceSomethingIsSavedTheSavedDockIsTheDockWhateverTheConfigSays() {
        // "files" was moved up from the grid, "prime" down to it: neither matches dock_apps any more.
        val saved = listOf("max", "spotify", "files", "netflix")
        val changed = listOf("spotify", "netflix", "max", "disney")
        assertEquals(saved, DockOrder.reconcile(saved, changed))
    }

    @Test
    fun repeatsInTheSavedListOrTheConfigAreIgnored() {
        assertEquals(
            listOf("netflix", "spotify"),
            DockOrder.reconcile(listOf("netflix", "netflix", "spotify"), listOf("spotify", "netflix", "spotify")),
        )
        assertEquals(listOf("spotify", "netflix"), DockOrder.reconcile(emptyList(), listOf("spotify", "netflix", "spotify")))
    }

    private val packageOf: (String) -> String = { key -> key.substringBefore('/') }

    @Test
    fun swapAcrossTradesTheDockAppForTheGridTile() {
        val dock = listOf("spotify", "netflix", "max")
        val grid = listOf("files/.Main", "browser/.Main", "clock/.Main")
        val result = DockOrder.swapAcross(dock, grid, 1, 2, "netflix/.Main", packageOf)!!
        assertEquals(listOf("spotify", "clock", "max"), result.dock)
        assertEquals(listOf("files/.Main", "browser/.Main", "netflix/.Main"), result.grid)
    }

    @Test
    fun swapAcrossRefusesAPackageThatIsAlreadyInTheDock() {
        val dock = listOf("spotify", "netflix")
        val grid = listOf("netflix/.Second", "files/.Main")
        assertEquals(null, DockOrder.swapAcross(dock, grid, 0, 0, "spotify/.Main", packageOf))
    }

    @Test
    fun swapAcrossWithAnIndexThatDoesNotExistChangesNothing() {
        val dock = listOf("spotify")
        val grid = listOf("files/.Main")
        assertEquals(null, DockOrder.swapAcross(dock, grid, 3, 0, "spotify/.Main", packageOf))
        assertEquals(null, DockOrder.swapAcross(dock, grid, 0, 5, "spotify/.Main", packageOf))
    }

    @Test
    fun fillVacancyPutsTheGridPackageInTheSlotAndTakesItsKeyOutOfTheGrid() {
        val dock = listOf("spotify", "smarttube", "appletv", "netflix")
        val grid = listOf("files/.Main", "browser/.Main", "clock/.Main")
        val result = DockOrder.fillVacancy(dock, grid, 1, 2, packageOf)!!
        assertEquals(listOf("spotify", "clock", "appletv", "netflix"), result.dock)
        assertEquals(listOf("files/.Main", "browser/.Main"), result.grid)
    }

    @Test
    fun fillVacancyRefusesAPackageAlreadyInTheDockOrAnIndexThatDoesNotExist() {
        val dock = listOf("spotify", "smarttube")
        val grid = listOf("spotify/.Second", "files/.Main")
        assertEquals(null, DockOrder.fillVacancy(dock, grid, 1, 0, packageOf))
        assertEquals(null, DockOrder.fillVacancy(dock, grid, 5, 1, packageOf))
        assertEquals(null, DockOrder.fillVacancy(dock, grid, 1, 7, packageOf))
    }

    @Test
    fun fillSlotGivesWhatTheCatalogBuildsFromTheSavedOrders() {
        // Xiaomi: smarttube and appletv are not installed, so the dock slots 1 and 2 are vacancies.
        val favourites = listOf("spotify", "smarttube", "appletv", "netflix", "prime", "max")
        val installed = listOf("spotify/.M", "netflix/.M", "prime/.M", "max/.M", "a/.M", "b/.M", "t/.M", "c/.M")
        val before = DockLayout.arrange(favourites, 6, installed, packageOf)
        val items: MutableList<String> = (before.dock.map { it ?: "vacant" } + listOf("a/.M", "b/.M", "t/.M", "c/.M")).toMutableList()
        val orders = DockOrder.fillVacancy(favourites, listOf("a/.M", "b/.M", "t/.M", "c/.M"), 1, 2, packageOf)!!

        DockOrder.fillSlot(items, 1, 8)

        val after = DockLayout.arrange(orders.dock, 6, installed, packageOf)
        val expected = after.dock.map { it ?: "vacant" } + orders.grid
        assertEquals(expected, items)
        assertEquals(listOf("spotify/.M", "t/.M", "vacant", "netflix/.M", "prime/.M", "max/.M", "a/.M", "b/.M", "c/.M"), items)
    }

    @Test
    fun fillSlotWithTheTileRightUnderTheVacancy() {
        val items = mutableListOf("s", "vacant", "n", "a", "t", "b")
        DockOrder.fillSlot(items, 1, 4)
        assertEquals(listOf("s", "t", "n", "a", "b"), items)
    }

    @Test
    fun fillSlotOutsideTheListChangesNothing() {
        val items = mutableListOf("s", "vacant", "a")
        DockOrder.fillSlot(items, 1, 9)
        DockOrder.fillSlot(items, 2, 1)
        assertEquals(listOf("s", "vacant", "a"), items)
    }

    @Test
    fun swapExchangesTwoEntriesAndLeavesTheOthersAlone() {
        assertEquals(listOf("a", "d", "c", "b", "e"), DockOrder.swap(listOf("a", "b", "c", "d", "e"), 1, 3))
        assertEquals(listOf("a", "d", "c", "b", "e"), DockOrder.swap(listOf("a", "b", "c", "d", "e"), 3, 1))
    }

    @Test
    fun aMissingAppKeepsItsSlotWhenTheNeighboursSwapAroundIt() {
        // "max" is not installed, so the dock shows a vacancy at index 1: the move from 0 to 2 steps over it.
        val order = listOf("spotify", "max", "netflix", "prime")
        assertEquals(listOf("netflix", "max", "spotify", "prime"), DockOrder.swap(order, 0, 2))
    }

    @Test
    fun entriesPastTheLastDockSlotAreNotTouched() {
        val order = listOf("a", "b", "c", "extra1", "extra2")
        assertEquals(listOf("c", "b", "a", "extra1", "extra2"), DockOrder.swap(order, 0, 2))
    }

    @Test
    fun swapOutsideTheListOrWithItselfChangesNothing() {
        val order = listOf("a", "b")
        assertEquals(order, DockOrder.swap(order, 0, 5))
        assertEquals(order, DockOrder.swap(order, -1, 1))
        assertEquals(order, DockOrder.swap(order, 1, 1))
    }

    @Test
    fun encodedOrderComesBackTheSame() {
        val order = listOf("com.spotify.tv.android", "com.netflix.ninja", "com.wbd.stream")
        assertEquals(order, DockOrder.decode(DockOrder.encode(order)))
        assertEquals(emptyList<String>(), DockOrder.decode(DockOrder.encode(emptyList())))
    }

    @Test
    fun damagedTextGivesAnEmptyOrderOrSkipsTheEmptyParts() {
        assertEquals(emptyList<String>(), DockOrder.decode(null))
        assertEquals(emptyList<String>(), DockOrder.decode(""))
        assertEquals(emptyList<String>(), DockOrder.decode(" , ,"))
        assertEquals(listOf("a", "b"), DockOrder.decode(",a,, b ,"))
    }
}
