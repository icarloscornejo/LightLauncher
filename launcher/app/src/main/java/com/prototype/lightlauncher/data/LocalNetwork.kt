package com.prototype.lightlauncher.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Where on the owner's network a phone can reach this TV. */
object LocalNetwork {

    /**
     * The TV's IPv4 address on its Wi-Fi or Ethernet network, or null when it has none. Every network is looked at, not
     * the active one: with a VPN up the active network is the VPN, which the phone cannot reach (the same reason
     * NetworkPill walks them all).
     */
    fun ipv4(context: Context): String? {
        val connectivity: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (network in connectivity.allNetworks) {
            val capabilities: NetworkCapabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val addresses = connectivity.getLinkProperties(network)?.linkAddresses ?: continue
            val found = addresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            if (found != null) return found.hostAddress
        }
        return null
    }
}
