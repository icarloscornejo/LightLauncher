package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadRequestsTest {

    private val resume = ReloadRequest(ReloadOrigin.RESUME, animate = true, restoreFocus = true)
    private val packageEvent = ReloadRequest(ReloadOrigin.PACKAGE, animate = false, restoreFocus = false)

    @Test
    fun nothingPendingAtStart() {
        assertNull(ReloadRequests().pending())
    }

    @Test
    fun aPackageEventNeverCancelsTheResumeDuties() {
        val requests = ReloadRequests()
        requests.add(resume)
        requests.add(packageEvent)
        val duty: ReloadDuty = requests.pending()!!
        assertEquals(setOf(ReloadOrigin.RESUME, ReloadOrigin.PACKAGE), duty.origins)
        assertTrue(duty.animate)
        assertTrue(duty.restoreFocus)
    }

    @Test
    fun aResumeFinishedButNotAppliedIsStillOwedWhenAPackageEventArrives() {
        val requests = ReloadRequests()
        requests.add(resume)
        val delivered: ReloadDuty = requests.pending()!!
        requests.add(packageEvent)
        // The screen never confirmed the first delivery: the next one still carries the resume duties.
        val next: ReloadDuty = requests.pending()!!
        assertTrue(next.animate)
        assertTrue(ReloadOrigin.RESUME in next.origins)
        assertTrue(next.id > delivered.id)
    }

    @Test
    fun confirmedDutiesAreNotRepeated() {
        val requests = ReloadRequests()
        requests.add(resume)
        val delivered: ReloadDuty = requests.pending()!!
        requests.add(packageEvent)
        requests.acknowledge(delivered.id)
        val rest: ReloadDuty = requests.pending()!!
        assertEquals(setOf(ReloadOrigin.PACKAGE), rest.origins)
        assertFalse(rest.animate)
        // Confirming again (a resubscription replaying the old value) changes nothing.
        requests.acknowledge(delivered.id)
        assertEquals(rest, requests.pending())
        requests.acknowledge(rest.id)
        assertNull(requests.pending())
    }

    @Test
    fun twoEqualRequestsStillGetTheirDuties() {
        val requests = ReloadRequests()
        requests.add(resume)
        requests.acknowledge(requests.pending()!!.id)
        requests.add(resume)
        val again: ReloadDuty = requests.pending()!!
        assertTrue(again.animate)
        assertTrue(again.restoreFocus)
    }

    @Test
    fun aDecisionMergedWithRunningResumeAndPackageEvents() {
        val requests = ReloadRequests()
        requests.add(resume)
        requests.add(packageEvent)
        requests.add(ReloadRequest(ReloadOrigin.DECISION, animate = false, restoreFocus = true))
        val duty: ReloadDuty = requests.pending()!!
        assertEquals(setOf(ReloadOrigin.RESUME, ReloadOrigin.PACKAGE, ReloadOrigin.DECISION), duty.origins)
        assertEquals(FocusAction.RESTORE, ReloadRequests.focusAction(duty, focusOnHeaderOrPopup = true, popupOrDialogOpen = false))
    }

    @Test
    fun focusRules() {
        fun duty(vararg origins: ReloadOrigin, restore: Boolean): ReloadDuty = ReloadDuty(1, origins.toSet(), false, restore)
        // Resume or Home press: always back to the remembered tile, even from a header button.
        assertEquals(FocusAction.RESTORE, ReloadRequests.focusAction(duty(ReloadOrigin.RESUME, restore = true), true, false))
        assertEquals(FocusAction.RESTORE, ReloadRequests.focusAction(duty(ReloadOrigin.NEW_INTENT, restore = false), true, false))
        // Package events alone never take the focus from a header button or popup...
        assertEquals(FocusAction.LEAVE, ReloadRequests.focusAction(duty(ReloadOrigin.PACKAGE, restore = true), true, false))
        // ...but follow the app when the grid had the focus.
        assertEquals(FocusAction.RESTORE, ReloadRequests.focusAction(duty(ReloadOrigin.PACKAGE, restore = true), false, false))
        assertEquals(FocusAction.LEAVE, ReloadRequests.focusAction(duty(ReloadOrigin.PACKAGE, restore = false), false, false))
        // A decision returns to the tile; with a popup or dialog open it leaves that alone.
        assertEquals(FocusAction.RESTORE, ReloadRequests.focusAction(duty(ReloadOrigin.DECISION, restore = true), false, false))
        assertEquals(FocusAction.LEAVE, ReloadRequests.focusAction(duty(ReloadOrigin.DECISION, restore = true), false, true))
        // An open popup or dialog always keeps the focus, whatever the origin.
        assertEquals(FocusAction.LEAVE, ReloadRequests.focusAction(duty(ReloadOrigin.RESUME, restore = true), false, true))
    }
}
