package com.prototype.lightlauncher.core

import java.net.URI
import java.net.URISyntaxException

/** The photo links found on a shared album page. [truncated] means the page had more than the cap. */
data class AlbumListing(val photoUrls: List<String>, val truncated: Boolean)

/**
 * Reads a shared Google Photos album page and decides which links the screensaver may follow.
 * Only https, and only Google's own hosts: the short link and album page for the page, the image server for
 * photos. Pure logic, so the network code can check every redirect against it before connecting.
 */
object AlbumUrls {
    /** More than this many photos and the listing counts as truncated: nothing gets deleted on a partial view. */
    const val MAX_PHOTOS: Int = 300

    private val PHOTO_URL: Regex = Regex("https://lh3\\.googleusercontent\\.com/pw/[A-Za-z0-9_-]+")
    private val PAGE_HOSTS: Set<String> = setOf("photos.app.goo.gl", "photos.google.com")
    private val PHOTO_HOSTS: Set<String> = setOf("lh3.googleusercontent.com")

    /** The unique photo links in page order, at most [MAX_PHOTOS]. */
    fun parse(page: String): AlbumListing {
        val unique: LinkedHashSet<String> = LinkedHashSet()
        var truncated = false
        for (match in PHOTO_URL.findAll(page)) {
            if (match.value in unique) continue
            if (unique.size == MAX_PHOTOS) {
                truncated = true
                break
            }
            unique.add(match.value)
        }
        return AlbumListing(ArrayList(unique), truncated)
    }

    /** May the album page (or a redirect on the way to it) be fetched? */
    fun isAllowedPageUrl(url: String): Boolean = isHttpsOnHost(url, PAGE_HOSTS)

    /** May this photo (or a redirect on the way to it) be fetched? */
    fun isAllowedPhotoUrl(url: String): Boolean = isHttpsOnHost(url, PHOTO_HOSTS)

    /** https on port 443, no credentials, and one of [hosts]: also what the updater allows (see [UpdateUrls]). */
    fun isHttpsOnHost(url: String, hosts: Set<String>): Boolean {
        val uri: URI = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        // userInfo is rejected so "https://photos.google.com@evil.example/" can never pass as Google.
        return uri.scheme == "https" && uri.userInfo == null && uri.host?.lowercase() in hosts &&
            (uri.port == -1 || uri.port == 443)
    }
}
