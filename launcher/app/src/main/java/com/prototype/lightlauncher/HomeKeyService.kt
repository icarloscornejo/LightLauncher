package com.prototype.lightlauncher

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home (Xiaomi
 * sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once the stock
 * launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.prototype.lightlauncher/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
class HomeKeyService : AccessibilityService() {

    /** Scancodes of remote buttons that should open Settings (config.xml, settings_key_scancodes). */
    private var settingsScancodes: IntArray = IntArray(0)

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsScancodes = resources.getIntArray(R.array.settings_key_scancodes)
    }

    private fun isSettingsKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_SETTINGS || event.scanCode in settingsScancodes

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Log.w("LightLauncher", "Settings button: this TV has no settings screen to open")
                }
            }
            return true
        }
        if (event.keyCode != KeyEvent.KEYCODE_ALL_APPS) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            val home: Intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(home)
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
