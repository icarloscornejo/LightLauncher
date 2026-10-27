package com.prototype.lightlauncher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.prototype.lightlauncher.data.LanguageStore
import com.prototype.lightlauncher.data.LocalizedContext

/** Lets the user change HOME through the system UI, without shell or privileged permissions. */
class HomeSettingsActivity : Activity() {
    /** Its toasts speak the language chosen in the launcher's settings. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalizedContext.wrap(newBase, LanguageStore(newBase).read()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show()
        } catch (unavailable: ActivityNotFoundException) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show()
        } catch (unavailable: SecurityException) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}
