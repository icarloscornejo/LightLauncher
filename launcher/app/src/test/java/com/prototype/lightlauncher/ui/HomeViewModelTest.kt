package com.prototype.lightlauncher.ui

import com.prototype.lightlauncher.core.ReloadOrigin
import com.prototype.lightlauncher.core.ReloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
    private var scans: Int = 0

    private fun viewModel(): HomeViewModel = HomeViewModel({ scans++; HomeContent(emptyList()) }, dispatcher)

    private val resume = ReloadRequest(ReloadOrigin.RESUME, animate = true, restoreFocus = true)
    private val packageEvent = ReloadRequest(ReloadOrigin.PACKAGE, animate = false, restoreFocus = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun acknowledgedDutyIsNotSeenAgainOnResubscribe() = runTest(dispatcher) {
        val home: HomeViewModel = viewModel()
        home.reload(resume)
        advanceUntilIdle()
        home.acknowledge(assertNotNullDuty(home))
        // A new collector reads the current value: tiles, and no duty to apply twice.
        assertNotNull(home.state.value.content)
        assertNull(home.state.value.duty)
    }

    @Test
    fun packageEventBeforeApplyKeepsTheResumeDuties() = runTest(dispatcher) {
        val home: HomeViewModel = viewModel()
        home.reload(resume)
        advanceUntilIdle() // the resume scan finished but the screen has not applied it
        home.reload(packageEvent)
        advanceUntilIdle()
        val duty = assertNotNullDuty(home)
        assertTrue(ReloadOrigin.RESUME in duty.origins)
        assertTrue(duty.animate && duty.restoreFocus)
    }

    @Test
    fun newerRequestCancelsTheScanInFlight() = runTest(dispatcher) {
        val home: HomeViewModel = viewModel()
        home.reload(resume)
        home.reload(packageEvent) // before the first scan was dispatched
        advanceUntilIdle()
        assertEquals(1, scans)
        assertTrue(ReloadOrigin.RESUME in assertNotNullDuty(home).origins)
    }

    @Test
    fun twoEqualResultsEachCarryTheirDuty() = runTest(dispatcher) {
        val home: HomeViewModel = viewModel()
        home.reload(packageEvent)
        advanceUntilIdle()
        val first = assertNotNullDuty(home)
        home.acknowledge(first)
        home.reload(packageEvent)
        advanceUntilIdle()
        val second = assertNotNullDuty(home)
        assertNotSame(first, second)
        assertTrue(second.id > first.id)
    }

    private fun assertNotNullDuty(home: HomeViewModel) = home.state.value.duty ?: throw AssertionError("no duty")
}
