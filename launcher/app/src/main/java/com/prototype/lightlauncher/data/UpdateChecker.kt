package com.prototype.lightlauncher.data

import com.prototype.lightlauncher.core.ReleaseAsset
import com.prototype.lightlauncher.core.UpdateDecision
import com.prototype.lightlauncher.core.UpdateOffer
import com.prototype.lightlauncher.core.UpdateRules
import com.prototype.lightlauncher.core.UpdateUrls
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** What looking for an update found. */
sealed class CheckResult {
    object UpToDate : CheckResult()

    data class Available(val offer: UpdateOffer) : CheckResult()

    /** No network, no such repository (or no release), or an answer that is not a release we can use. */
    object Failed : CheckResult()
}

/**
 * Asks GitHub for the latest release of a repository and downloads its APK. Plain Kotlin apart from
 * org.json, so the download and its checks run in JVM tests with a fake [HttpFetcher].
 *
 * - Redirects are followed by hand, at most [MAX_REDIRECTS], and every hop is checked with [UpdateUrls] before
 *   connecting (the download redirects from github.com to a githubusercontent host).
 * - A file is kept only if its SHA-256 equals the digest GitHub published for it; the check runs on the bytes in memory,
 *   so a wrong file is never written. The file is written under a temporary name and renamed, so a dropped disk write
 *   never leaves a half APK to install.
 * - Logs carry a category, never an address or a server message.
 */
class UpdateChecker(
    private val fetcher: HttpFetcher,
    private val folder: File,
    private val logger: Logger,
) {
    fun check(repo: String, installedVersion: String): CheckResult {
        val url: String = UpdateUrls.latestReleaseUrl(repo) ?: run {
            logger.warn("Update check refused: not a repository name")
            return CheckResult.Failed
        }
        val body: ByteArray = try {
            fetchFollowing(url, MAX_RELEASE_BYTES)
        } catch (e: FetchFailed) {
            logger.warn("Update check failed: ${e.reason}")
            return CheckResult.Failed
        }
        val release: Release = parse(body) ?: run {
            logger.warn("Update check failed: the answer is not a release")
            return CheckResult.Failed
        }
        return when (val decision: UpdateDecision = UpdateRules.decide(release.tag, release.assets, installedVersion)) {
            is UpdateDecision.Offer -> CheckResult.Available(decision.offer)
            UpdateDecision.UpToDate -> CheckResult.UpToDate
            UpdateDecision.Unusable -> {
                logger.warn("Update check failed: the release cannot be installed safely")
                CheckResult.Failed
            }
        }
    }

    /** The downloaded APK, or null when the download or its checks failed (nothing is left on disk then). */
    fun download(offer: UpdateOffer): File? {
        val body: ByteArray = try {
            fetchFollowing(offer.url, UpdateRules.MAX_APK_BYTES)
        } catch (e: FetchFailed) {
            logger.warn("Update download failed: ${e.reason}")
            return null
        }
        if (!sha256Hex(body).equals(offer.sha256, ignoreCase = true)) {
            logger.warn("Update download refused: the file does not match its digest")
            return null
        }
        folder.mkdirs()
        folder.listFiles()?.forEach { it.delete() }
        val target = File(folder, APK_NAME)
        val partial = File(folder, "$APK_NAME.part")
        try {
            partial.writeBytes(body)
        } catch (e: IOException) {
            partial.delete()
            logger.warn("Update download failed: the file could not be written")
            return null
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            logger.warn("Update download failed: the file could not be renamed")
            return null
        }
        return target
    }

    private class Release(val tag: String, val assets: List<ReleaseAsset>)

    private fun parse(body: ByteArray): Release? = try {
        val root = JSONObject(String(body, StandardCharsets.UTF_8))
        val assets: MutableList<ReleaseAsset> = ArrayList()
        val listed = root.optJSONArray("assets")
        if (listed != null) {
            for (index in 0 until listed.length()) {
                val asset: JSONObject = listed.optJSONObject(index) ?: continue
                assets.add(
                    ReleaseAsset(
                        name = asset.optString("name"),
                        url = asset.optString("browser_download_url"),
                        digest = asset.optString("digest").ifEmpty { null },
                        size = asset.optLong("size"),
                    ),
                )
            }
        }
        Release(root.optString("tag_name"), assets)
    } catch (e: JSONException) {
        // The message of a JSONException quotes the document: only the category is logged.
        null
    }

    /** Fetches [startUrl], following at most [MAX_REDIRECTS] redirects, checking every hop before connecting. */
    private fun fetchFollowing(startUrl: String, maxBytes: Int): ByteArray {
        var url: String = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            if (!UpdateUrls.isAllowed(url)) throw FetchFailed("not allowed")
            val response: HttpResponse = try {
                fetcher.fetch(url, maxBytes)
            } catch (e: ResponseTooLargeException) {
                throw FetchFailed("too large")
            } catch (e: IOException) {
                throw FetchFailed("network")
            }
            when {
                response.code == 200 -> return response.body
                response.code in 300..399 && response.location != null -> url = resolve(url, response.location)
                else -> throw FetchFailed("http error")
            }
        }
        throw FetchFailed("too many redirects")
    }

    private fun resolve(base: String, location: String): String = try {
        URI(base).resolve(location).toString()
    } catch (e: URISyntaxException) {
        throw FetchFailed("not allowed")
    } catch (e: IllegalArgumentException) {
        throw FetchFailed("not allowed")
    }

    private class FetchFailed(val reason: String) : Exception()

    companion object {
        const val MAX_REDIRECTS: Int = 5
        const val MAX_RELEASE_BYTES: Int = 1024 * 1024
        const val APK_NAME: String = "LightLauncher.apk"

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
