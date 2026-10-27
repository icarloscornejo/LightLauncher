package com.prototype.lightlauncher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ShelfFeatureTest {

    private val reported: MutableList<Throwable> = mutableListOf()

    @Before
    fun setUp() {
        ShelfFeature.reset()
        reported.clear()
        ShelfFeature.reporter = { reported.add(it) }
    }

    @After
    fun tearDown() {
        ShelfFeature.reset()
    }

    @Test
    fun aStepThatWorksReturnsItsValue() {
        assertEquals(7, ShelfFeature.run(0) { 7 })
        assertTrue(ShelfFeature.working())
    }

    @Test
    fun theFirstFailureSwitchesTheShelfOffAndReportsOnce() {
        assertEquals("fallback", ShelfFeature.run("fallback") { error("boom") })
        assertFalse(ShelfFeature.working())
        assertEquals(1, reported.size)
        // Once off, later steps are not even tried.
        var ran = false
        assertEquals("fallback", ShelfFeature.run("fallback") { ran = true; "value" })
        assertFalse(ran)
        assertEquals(1, reported.size)
    }

    @Test
    fun aCancelledCoroutineIsNotAFailure() {
        try {
            ShelfFeature.run(0) { throw CancellationException("moved on") }
            throw AssertionError("the cancellation should have gone through")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(ShelfFeature.working())
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aLinkageErrorFromABadClassInitialiserAlsoSwitchesItOff() {
        assertEquals(0, ShelfFeature.run(0) { throw ExceptionInInitializerError("bad init") })
        assertFalse(ShelfFeature.working())
    }

    @Test
    fun aReporterThatThrowsDoesNotTakeTheCallerDown() {
        ShelfFeature.reporter = { throw IllegalStateException("log failed") }
        assertEquals(0, ShelfFeature.run(0) { error("boom") })
        assertFalse(ShelfFeature.working())
    }
}
