package com.prototype.lightlauncher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.prototype.lightlauncher.core.AppVersion

/**
 * Android switches this app's accessibility services off when the app is replaced (build-and-install.sh
 * says so and turns them back on). An update installed from the settings does not run that script, and without
 * [HomeKeyService] the Home button of a Xiaomi remote does nothing. So before installing, the services that are on are
 * noted with the version being installed ([save]); the first start of that version turns them on again
 * ([restoreIfUpdated]); an update that did not happen forgets the note ([clear]), so a later reinstall that was
 * made without the flag never switches a service on by itself.
 */
object AccessibilityRestore {
    private const val PREFS: String = "accessibility_restore"
    private const val KEY_VERSION: String = "version"
    private const val KEY_SERVICES: String = "services"
    private const val SETTING_SERVICES: String = "enabled_accessibility_services"
    private const val SETTING_ENABLED: String = "accessibility_enabled"

    private fun ours(context: Context): List<ComponentName> =
        listOf(ComponentName(context, HomeKeyService::class.java), ComponentName(context, InputKeyService::class.java))

    /** The services in the system setting, as components ("pkg/.Class" and "pkg/pkg.Class" are the same one). */
    private fun enabled(context: Context): List<ComponentName> =
        (Settings.Secure.getString(context.contentResolver, SETTING_SERVICES) ?: "")
            .split(':')
            .mapNotNull { if (it.isBlank()) null else ComponentName.unflattenFromString(it) }

    /** Notes which of our services are on now, to be turned on again after [targetVersion] is installed. */
    fun save(context: Context, targetVersion: String) {
        val on: List<ComponentName> = enabled(context)
        val services: List<String> = ours(context).filter { it in on }.map { it.flattenToString() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VERSION, targetVersion)
            .putString(KEY_SERVICES, services.joinToString(":"))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Turns the noted services on again when the installed version is the one the note was made for. Always forgets the
     * note. False when something had to be restored and could not be (no permission to change the setting): the
     * caller says so.
     */
    fun restoreIfUpdated(context: Context, installedVersion: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val noted: String = prefs.getString(KEY_VERSION, null) ?: return true
        if (noted != AppVersion.canonical(installedVersion)) return true // the update did not happen (yet): keep the note
        val wanted: List<ComponentName> = (prefs.getString(KEY_SERVICES, "") ?: "")
            .split(':')
            .mapNotNull { if (it.isBlank()) null else ComponentName.unflattenFromString(it) }
        clear(context)
        val missing: List<ComponentName> = wanted.filter { it !in enabled(context) }
        if (missing.isEmpty()) return true
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        // Appended to the others, never replacing them (the owner may use other accessibility services).
        val all: List<String> = (enabled(context) + missing).map { it.flattenToString() }
        Settings.Secure.putString(context.contentResolver, SETTING_SERVICES, all.joinToString(":"))
        Settings.Secure.putInt(context.contentResolver, SETTING_ENABLED, 1)
        return true
    }
}
