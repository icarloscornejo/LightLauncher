package com.prototype.lightlauncher.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.prototype.lightlauncher.core.DnsBlockState
import com.prototype.lightlauncher.core.PrivateDnsRules

/**
 * The "Block ads (DNS)" switch, over Android's Private DNS (Settings.Global). Writing needs
 * WRITE_SECURE_SETTINGS, which is granted over adb (build-and-install.sh); without it the switch is shown as not
 * available. The rules for what counts as Yes live in [PrivateDnsRules].
 */
class PrivateDns(private val context: Context, private val sdk: Int = Build.VERSION.SDK_INT) {

    private val resolver get() = context.contentResolver

    fun state(): DnsBlockState = PrivateDnsRules.state(sdk, mode(), server())

    /** The owner's own Private DNS server, when turning the switch on would replace it. */
    fun customServer(): String? = PrivateDnsRules.customServer(sdk, mode(), server())

    /** True when the app may change the setting. */
    fun canWrite(): Boolean = sdk >= PrivateDnsRules.MIN_SDK &&
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Yes: AdGuard's resolver in hostname mode. No: the automatic mode. The server is written first so the mode never
     * points at a stale one. False when Android refused.
     */
    fun set(on: Boolean): Boolean = try {
        if (on) {
            Settings.Global.putString(resolver, KEY_SERVER, PrivateDnsRules.BLOCKING_HOST)
            Settings.Global.putString(resolver, KEY_MODE, PrivateDnsRules.MODE_HOSTNAME)
        } else {
            Settings.Global.putString(resolver, KEY_MODE, PrivateDnsRules.MODE_AUTOMATIC)
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "private DNS not changed: ${e.javaClass.simpleName}")
        false
    }

    private fun mode(): String? = Settings.Global.getString(resolver, KEY_MODE)

    private fun server(): String? = Settings.Global.getString(resolver, KEY_SERVER)

    private companion object {
        const val TAG: String = "LightLauncher"
        const val PERMISSION: String = "android.permission.WRITE_SECURE_SETTINGS"
        const val KEY_MODE: String = "private_dns_mode"
        const val KEY_SERVER: String = "private_dns_specifier"
    }
}
