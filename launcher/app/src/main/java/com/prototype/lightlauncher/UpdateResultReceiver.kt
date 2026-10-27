package com.prototype.lightlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.prototype.lightlauncher.data.LanguageStore
import com.prototype.lightlauncher.data.LocalizedContext

/**
 * Gets the answer of the system installer for an update started from the settings. Not exported: only the
 * session we created holds the address of this receiver. The system first asks for the owner's confirmation (it comes as
 * an intent to start), then reports how it ended; the updater keeps the row up to date with it.
 */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status: Int = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val container: AppContainer = (context.applicationContext as LightLauncherApp).container
        val notInstalled: Boolean = container.updater.onInstallStatus(status)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }
            // A different signing key: the system refuses to put this APK over the installed app.
            PackageInstaller.STATUS_FAILURE_CONFLICT -> toast(context, R.string.update_other_key)
        }
        // The services that were noted for after the update are not needed when it did not happen.
        if (notInstalled) AccessibilityRestore.clear(context)
    }

    private fun toast(context: Context, message: Int) {
        val localized: Context = LocalizedContext.wrap(context, LanguageStore(context).read())
        Toast.makeText(localized, message, Toast.LENGTH_LONG).show()
    }
}
