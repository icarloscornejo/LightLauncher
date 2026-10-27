package com.prototype.lightlauncher.core

/**
 * Whether a fresh scan of the apps has to be put on screen. Coming back to Home from another app scans
 * again (a package may have changed while away), but when nothing did, rebinding every tile and replaying the entry
 * animation only makes the home look like it reloaded.
 */
object ScanChange {
    /**
     * [before] is the signature of what is on screen (null when nothing is), [after] the one of the scan that arrived.
     * A package event or a decision (a saved reorder) always rebinds (an updated app can change its icon without changing its
     * name); a resume or a Home press only does when the signature differs.
     */
    fun needsRebind(origins: Set<ReloadOrigin>, before: List<String>?, after: List<String>): Boolean =
        before == null || origins.any { it != ReloadOrigin.RESUME && it != ReloadOrigin.NEW_INTENT } || before != after
}
