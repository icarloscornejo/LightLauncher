package com.prototype.lightlauncher.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import com.prototype.lightlauncher.R

/**
 * The network status: the Wi-Fi name (or Ethernet, or Offline), with a VPN capsule when a VPN is up, or a red
 * "VPN off" one when a configured VPN app has none running. [open] opens the VPN app when one is configured.
 * It fills the Wi-Fi row of the settings screen ([valueView] and [iconView]).
 */
class NetworkPill(
    private val context: Context,
    private val valueView: TextView,
    private val iconView: ImageView,
    /** config.xml vpn_app; empty when there is none. */
    private val vpnApp: String,
    private val density: Float,
) {
    /** Shows the Wi-Fi network name on the button when it can be read, otherwise the connection type. */
    fun update() {
        var text: String = context.getString(R.string.wifi)
        var vpn = false
        var online = false
        var icon: Int = R.drawable.ic_wifi
        try {
            val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
            var wifi = false
            var ethernet = false
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            @Suppress("DEPRECATION")
            for (network in connectivity?.allNetworks ?: emptyArray()) {
                val caps: NetworkCapabilities = connectivity?.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true
                    continue
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                online = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            }
            if (!online) {
                text = context.getString(R.string.offline)
                vpn = false
            } else if (ethernet) {
                text = context.getString(R.string.ethernet)
                icon = R.drawable.ic_ethernet
            } else if (wifi) {
                val wifiManager: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val ssid: String? = wifiManager?.connectionInfo?.ssid?.replace("\"", "")?.trim()
                if (!ssid.isNullOrEmpty() && !ssid.contains("unknown") && ssid != "0x") text = ssid
            }
        } catch (e: SecurityException) {
            // The network name needs the location permission (granted at install); without it the pill says Wi-Fi.
            Log.w(TAG, "network name unreadable: ${e.javaClass.simpleName}")
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        val vpnDown: Boolean = !vpn && online && vpnApp.isNotEmpty()
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            val badge: String = context.getString(if (vpn) R.string.vpn else R.string.vpn_off)
            val span = VpnBadge(if (vpn) context.getDrawable(R.drawable.ic_lock) else null, density * BADGE_SCALE)
            // The value has a maximum width and ellipsizes at the end, which would cut the badge off after a long
            // network name, so shorten the name to whatever room the badge leaves.
            val paint: TextPaint = valueView.paint
            val room: Float = valueView.maxWidth - valueView.totalPaddingStart - valueView.totalPaddingEnd -
                span.getSize(paint, badge, 0, badge.length, null) - paint.measureText(" ") - 2 * density
            text = TextUtils.ellipsize(text, paint, Math.max(0f, room), TextUtils.TruncateAt.END).toString()
            val label = SpannableString("$text $badge")
            label.setSpan(span, text.length + 1, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            valueView.text = label
        } else {
            valueView.text = text
        }
        iconView.setImageResource(icon)
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    fun open() {
        var intent: Intent? = if (vpnApp.isEmpty()) null else context.packageManager.getLeanbackLaunchIntentForPackage(vpnApp)
        if (intent == null && vpnApp.isNotEmpty()) intent = context.packageManager.getLaunchIntentForPackage(vpnApp)
        SystemIntents.open(context, intent ?: SystemIntents.forAction(context, Settings.ACTION_WIFI_SETTINGS))
    }

    private companion object {
        const val TAG: String = "LightLauncher"

        /** Size of the VPN capsule against the text it sits in. */
        const val BADGE_SCALE: Float = 0.85f
    }
}
