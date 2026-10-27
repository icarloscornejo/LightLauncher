package com.prototype.lightlauncher.core

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Which image addresses the top shelf may download. They come from other apps (preview programs), so
 * there is no host list to check against like the screensaver has; instead the address must be plain https on the
 * default port, with no credentials, and must name a public looking host (a DNS name, never an IP literal, a
 * single label or a local name), so an app cannot point the launcher at a device on the owner's own network.
 *
 * ponytail: a public looking name that resolves to a private address is not caught, because the connection resolves
 * it itself; add resolving and checking the address first if that ever matters.
 */
object ShelfImageUrl {
    const val MAX_LENGTH: Int = 2048
    const val MAX_REDIRECTS: Int = 3

    private val LOCAL_SUFFIXES: List<String> = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".corp")

    /** The address as a [URI] when it may be fetched, else null. */
    fun allowed(address: String): URI? {
        if (address.isBlank() || address.length > MAX_LENGTH) return null
        val uri: URI = try {
            URI(address.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        // "https://trusted.example@evil.example/" names evil.example; never accept credentials at all.
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host: String = uri.host?.lowercase(Locale.ROOT) ?: return null
        return if (publicLooking(host)) uri else null
    }

    /** The next hop of a redirect, resolved against the current address, or null when it may not be followed. */
    fun redirect(from: URI, location: String): URI? {
        val next: URI = try {
            from.resolve(location)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return allowed(next.toString())
    }

    private fun publicLooking(host: String): Boolean {
        if (host.contains(':') || host.contains('[')) return false // IPv6 literal
        if (!host.contains('.')) return false // "router", "localhost"
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return false
        val labels: List<String> = host.split('.')
        if (labels.all { label -> label.isNotEmpty() && label.all { it.isDigit() } }) return false // IPv4 literal
        // Numeric forms the resolver may still read as an address (0x7f.0.0.1, 127.1, octal): no real top level
        // domain is numeric or starts with a digit.
        if (labels.last().firstOrNull()?.isDigit() == true) return false
        return labels.none { it.isEmpty() }
    }
}
