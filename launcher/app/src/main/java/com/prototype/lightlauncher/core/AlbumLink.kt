package com.prototype.lightlauncher.core

/**
 * Which Google Photos album the screensaver uses. The owner can set it from the settings (a phone sends
 * the link, see LinkServer) or take it away; both beat what config.xml or the TV's config.json say, so a link the
 * owner removed on the TV does not come back from the file the next time Home resumes.
 */
object AlbumLink {

    /**
     * The link to sync: the one saved on the TV, else nothing when the owner removed it, else the configured one
     * (config.json, then config.xml). Blank means there is no album.
     */
    fun resolve(stored: String?, removed: Boolean, configured: String): String {
        val saved: String = stored.orEmpty().trim()
        return when {
            saved.isNotEmpty() -> saved
            removed -> ""
            else -> configured.trim()
        }
    }
}
