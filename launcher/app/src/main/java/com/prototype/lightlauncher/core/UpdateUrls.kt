package com.prototype.lightlauncher.core

/**
 * Where the updater may connect. Only https, only GitHub's own hosts: the API for the release, and the
 * hosts a release download is redirected to. Pure logic, so the network code can check every redirect against it
 * before connecting (the same rule as [AlbumUrls]).
 */
object UpdateUrls {
    private val HOSTS: Set<String> = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** "owner/name" as GitHub writes it: letters, digits, dots, dashes and underscores on both sides of one slash. */
    private val REPO: Regex = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")

    fun isValidRepo(repo: String): Boolean = REPO.matches(repo) && !repo.contains("..")

    /** The address of the latest release of [repo], or null when [repo] is not a repository name. */
    fun latestReleaseUrl(repo: String): String? = if (isValidRepo(repo)) "https://api.github.com/repos/$repo/releases/latest" else null

    /** May this address (or a redirect on the way to it) be fetched? */
    fun isAllowed(url: String): Boolean = AlbumUrls.isHttpsOnHost(url, HOSTS)
}
