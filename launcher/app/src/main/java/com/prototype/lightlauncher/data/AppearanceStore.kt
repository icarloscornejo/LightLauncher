package com.prototype.lightlauncher.data

import android.content.Context
import android.content.SharedPreferences
import com.prototype.lightlauncher.core.Appearance

/**
 * The appearance setting (Light or Dark). It is read in MainActivity.attachBaseContext,
 * before the application and the container exist, so it takes a plain Context and nothing else.
 */
class AppearanceStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): Appearance = Appearance.parse(preferences.getString(KEY, null))

    /** False when the write failed (apply() would never say). */
    fun write(value: Appearance): Boolean = preferences.edit().putString(KEY, value.name).commit()

    private companion object {
        const val FILE_NAME: String = "appearance"
        const val KEY: String = "setting"
    }
}
