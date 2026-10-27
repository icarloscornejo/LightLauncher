package com.prototype.lightlauncher.core

/** What the "Block ads (DNS)" switch shows. */
enum class DnsBlockState { UNAVAILABLE, ON, OFF }

/**
 * How Android's Private DNS setting maps onto the switch. Yes means the hostname mode with AdGuard's
 * resolver and nothing else: any other server the owner set (or the automatic or off modes) reads as No, and
 * [customServer] says which one would be replaced so the row can show it before it is.
 */
object PrivateDnsRules {
    /** Private DNS exists from Android 9. */
    const val MIN_SDK: Int = 28
    const val BLOCKING_HOST: String = "dns.adguard-dns.com"
    const val MODE_HOSTNAME: String = "hostname"
    const val MODE_AUTOMATIC: String = "opportunistic"

    fun state(sdk: Int, mode: String?, host: String?): DnsBlockState = when {
        sdk < MIN_SDK -> DnsBlockState.UNAVAILABLE
        mode == MODE_HOSTNAME && BLOCKING_HOST.equals(host?.trim(), ignoreCase = true) -> DnsBlockState.ON
        else -> DnsBlockState.OFF
    }

    /** The server of the owner's own Private DNS when it is neither ours nor automatic, else null. */
    fun customServer(sdk: Int, mode: String?, host: String?): String? {
        if (sdk < MIN_SDK || mode != MODE_HOSTNAME) return null
        val server: String = host?.trim().orEmpty()
        return if (server.isEmpty() || BLOCKING_HOST.equals(server, ignoreCase = true)) null else server
    }
}
