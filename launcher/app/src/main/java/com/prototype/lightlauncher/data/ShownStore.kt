package com.prototype.lightlauncher.data

import android.content.Context
import android.content.SharedPreferences

/** The screensaver's round so far: photo names already shown, and the version they were read at. */
class ShownSnapshot(val names: Set<String>, val version: Long)

/**
 * The single owner of the screensaver's "shown this round" set (dream_state). Two writers share
 * it: the running screensaver after every slide, and AlbumSync when it renames photo files. A rename bumps
 * the version, and a write based on an older version is refused, so a running session never puts the old
 * names back over translated ones; it re-reads instead.
 */
interface ShownStore {
    fun read(): ShownSnapshot

    /** Saves [names] if nothing renamed them since [basedOnVersion]. False means: re-read and try again. */
    fun write(names: Set<String>, basedOnVersion: Long): Boolean

    /** Renames entries (old file name to new) and bumps the version. */
    fun rename(oldToNew: Map<String, String>)

    /** Forgets the round (the album was removed) and bumps the version, so a running screensaver reads again. */
    fun clear()
}

/** dream_state in SharedPreferences, behind one lock. */
class PrefsShownStore(private val context: Context) : ShownStore {
    private val lock: Any = Any()
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun read(): ShownSnapshot = synchronized(lock) {
        ShownSnapshot(HashSet(prefs.getStringSet(KEY_SHOWN, emptySet()) ?: emptySet()), prefs.getLong(KEY_VERSION, 0L))
    }

    override fun write(names: Set<String>, basedOnVersion: Long): Boolean = synchronized(lock) {
        if (prefs.getLong(KEY_VERSION, 0L) != basedOnVersion) return false
        prefs.edit().putStringSet(KEY_SHOWN, HashSet(names)).apply()
        true
    }

    override fun rename(oldToNew: Map<String, String>) {
        synchronized(lock) {
            val current: Set<String> = prefs.getStringSet(KEY_SHOWN, emptySet()) ?: emptySet()
            val translated: Set<String> = current.mapTo(HashSet()) { oldToNew[it] ?: it }
            prefs.edit().putStringSet(KEY_SHOWN, translated).putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, 0L) + 1).apply()
        }
    }

    override fun clear() {
        synchronized(lock) {
            prefs.edit().putStringSet(KEY_SHOWN, HashSet()).putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, 0L) + 1).apply()
        }
    }

    private companion object {
        const val FILE: String = "dream_state"
        const val KEY_SHOWN: String = "shown"
        const val KEY_VERSION: String = "version"
    }
}
