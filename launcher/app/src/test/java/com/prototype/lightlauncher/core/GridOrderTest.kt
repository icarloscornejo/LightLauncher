package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GridOrderTest {

    @Test
    fun withNothingSavedTheDefaultOrderStays() {
        assertEquals(listOf("a", "b", "c"), GridOrder.reconcile(emptyList(), listOf("a", "b", "c")))
    }

    @Test
    fun theSavedOrderWinsAndANewAppGoesLast() {
        // "d" was installed after the order was saved.
        assertEquals(listOf("c", "a", "b", "d"), GridOrder.reconcile(listOf("c", "a", "b"), listOf("a", "b", "c", "d")))
    }

    @Test
    fun anAppThatIsGoneDropsOutAndTheRestKeepTheirOrder() {
        assertEquals(listOf("c", "b"), GridOrder.reconcile(listOf("c", "a", "b"), listOf("b", "c")))
    }

    @Test
    fun repeatsInTheSavedListAreIgnored() {
        assertEquals(listOf("b", "a"), GridOrder.reconcile(listOf("b", "b", "a"), listOf("a", "b")))
    }

    @Test
    fun movingToTheRightPushesTheOthersLeft() {
        assertEquals(listOf("b", "c", "a", "d"), GridOrder.move(listOf("a", "b", "c", "d"), 0, 2))
    }

    @Test
    fun movingToTheLeftPushesTheOthersRight() {
        assertEquals(listOf("a", "d", "b", "c"), GridOrder.move(listOf("a", "b", "c", "d"), 3, 1))
    }

    @Test
    fun movingToTheEndsWorks() {
        assertEquals(listOf("d", "a", "b", "c"), GridOrder.move(listOf("a", "b", "c", "d"), 3, 0))
        assertEquals(listOf("b", "c", "d", "a"), GridOrder.move(listOf("a", "b", "c", "d"), 0, 3))
    }

    @Test
    fun anIndexOutsideTheListOrTheSameOneChangesNothing() {
        val order = listOf("a", "b", "c")
        assertEquals(order, GridOrder.move(order, 1, 1))
        assertEquals(order, GridOrder.move(order, -1, 1))
        assertEquals(order, GridOrder.move(order, 0, 3))
    }

    @Test
    fun anAdjacentMoveIsASwapOfTheTwo() {
        assertEquals(listOf("b", "a", "c"), GridOrder.move(listOf("a", "b", "c"), 0, 1))
    }
}
