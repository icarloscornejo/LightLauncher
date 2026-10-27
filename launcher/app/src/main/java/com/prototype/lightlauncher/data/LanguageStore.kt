package com.prototype.lightlauncher.data

import android.content.Context
import android.content.SharedPreferences
import com.prototype.lightlauncher.core.Language

/**
 * The language setting (Automatic, English or Spanish). Like [AppearanceStore] it is read before the
 * application and the container exist (MainActivity.attachBaseContext, the screensaver), so it takes a plain Context.
 */
class LanguageStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): Language = Language.parse(preferences.getString(KEY, null))

    /** False when the write failed (apply() would never say). */
    fun write(value: Language): Boolean = preferences.edit().putString(KEY, value.name).commit()

    private companion object {
        const val FILE_NAME: String = "language"
        const val KEY: String = "setting"
    }
}
