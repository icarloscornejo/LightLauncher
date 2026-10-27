package com.prototype.lightlauncher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Whether the home shows the top shelf (cards or the app icon above the dock). Off by default, which leaves only
 * the plain background: while it is off nothing is read from the TV provider and nothing
 * is downloaded. The owner switches it on the shortcuts screen.
 */
class TopShelfSetting(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY, false)

    /** False when the write failed (apply() would never say). */
    fun write(enabled: Boolean): Boolean = preferences.edit().putBoolean(KEY, enabled).commit()

    private companion object {
        const val FILE_NAME: String = "top_shelf"
        const val KEY: String = "enabled"
    }
}
