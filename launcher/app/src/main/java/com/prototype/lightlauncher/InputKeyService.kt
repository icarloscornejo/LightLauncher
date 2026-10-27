package com.prototype.lightlauncher

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast
 * voice remote send KEYCODE_TV for it; on Google TV the stock launcher handles that key, so with the stock
 * launcher disabled the button does nothing. Only KEYCODE_TV is consumed; every other key passes through.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
class InputKeyService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_TV) return false
        if (event.action == KeyEvent.ACTION_UP) {
            try {
                startActivity(Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                // Say why the button did nothing instead of swallowing it.
                Log.w("LightLauncher", "Input button: this TV has no input switcher ($ACTION_VIEW_INPUTS)")
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private companion object {
        const val ACTION_VIEW_INPUTS: String = "com.android.tv.action.VIEW_INPUTS"
    }
}
