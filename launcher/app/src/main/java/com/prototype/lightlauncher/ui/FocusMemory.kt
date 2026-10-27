package com.prototype.lightlauncher.ui

/**
 * The tile the owner last focused, kept for the whole process: it survives Android destroying the home
 * activity while another app is open (low-memory TVs, or "Don't keep activities"), so coming back lands on the
 * same app. It does not survive the process: after a force-stop or a reboot Home starts on the first dock tile.
 */
object FocusMemory {
    /** Position of the last focused tile. */
    var slot: Int = 0

    /** Stable key (component) of the last focused tile, so focus follows the app when the list changes. */
    var key: String? = null
}
