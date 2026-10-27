package com.prototype.lightlauncher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The screensaver's album link as the owner set it from the settings: the link a phone sent, or a mark
 * that the owner took the album away. Either one beats config.json (see [com.prototype.lightlauncher.core.AlbumLink]).
 * The link is only kept here, in the app's private storage; it is never logged.
 */
class AlbumLinkStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun link(): String? = preferences.getString(KEY_LINK, null)

    fun removed(): Boolean = preferences.getBoolean(KEY_REMOVED, false)

    /** Keeps [link] and clears the mark of removal. False when the write failed (apply() would never say). */
    fun save(link: String): Boolean = preferences.edit().putString(KEY_LINK, link).putBoolean(KEY_REMOVED, false).commit()

    /** Takes the link away and marks it, so the file's link does not come back. False when the write failed. */
    fun remove(): Boolean = preferences.edit().remove(KEY_LINK).putBoolean(KEY_REMOVED, true).commit()

    private companion object {
        const val FILE_NAME: String = "album"
        const val KEY_LINK: String = "link"
        const val KEY_REMOVED: String = "removed"
    }
}
