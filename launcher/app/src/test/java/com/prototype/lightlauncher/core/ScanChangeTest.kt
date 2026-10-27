package com.prototype.lightlauncher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanChangeTest {
    private val same: List<String> = listOf("APP|a|A", "APP|b|B")

    @Test
    fun theFirstScanIsAlwaysShown() {
        assertTrue(ScanChange.needsRebind(setOf(ReloadOrigin.RESUME), null, same))
    }

    @Test
    fun aResumeWithTheSameAppsLeavesTheTilesAlone() {
        assertFalse(ScanChange.needsRebind(setOf(ReloadOrigin.RESUME), same, same.toList()))
        assertFalse(ScanChange.needsRebind(setOf(ReloadOrigin.RESUME, ReloadOrigin.NEW_INTENT), same, same.toList()))
    }

    @Test
    fun aResumeWithADifferentListIsShown() {
        assertTrue(ScanChange.needsRebind(setOf(ReloadOrigin.RESUME), same, listOf("APP|a|A")))
        assertTrue(ScanChange.needsRebind(setOf(ReloadOrigin.RESUME), same, listOf("APP|a|A", "APP|b|Renamed")))
    }

    @Test
    fun aPackageEventOrADecisionAlwaysRebinds() {
        assertTrue(ScanChange.needsRebind(setOf(ReloadOrigin.PACKAGE), same, same.toList()))
        assertTrue(ScanChange.needsRebind(setOf(ReloadOrigin.RESUME, ReloadOrigin.PACKAGE), same, same.toList()))
        assertTrue(ScanChange.needsRebind(setOf(ReloadOrigin.DECISION), same, same.toList()))
    }
}
