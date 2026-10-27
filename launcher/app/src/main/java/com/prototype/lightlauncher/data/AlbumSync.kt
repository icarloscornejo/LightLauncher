package com.prototype.lightlauncher.data

import com.prototype.lightlauncher.core.AlbumUrls
import com.prototype.lightlauncher.core.AlbumListing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Why a sync stopped before it had a usable list of photos. */
enum class SyncFailure(val permanent: Boolean) {
    /** A link (or a redirect on the way) is not https on Google Photos' own hosts. */
    NOT_ALLOWED(true),
    TOO_MANY_REDIRECTS(true),
    TOO_LARGE(true),
    HTTP_ERROR(false),
    NETWORK_ERROR(false),
    /** The page had no photo links: Google changed it, or the link stopped being shared. */
    EMPTY_LISTING(true),
}

/** The photos on the TV, and when the last sync was tried in milliseconds (null when it never was). */
data class AlbumStatus(val photos: Int, val lastAttemptMillis: Long?)

/** What one sync did. */
sealed class SyncResult {
    data class Failed(val failure: SyncFailure) : SyncResult()

    data class Synced(
        val photosInAlbum: Int,
        val downloaded: Int,
        val transientFailures: Int,
        val permanentFailures: Int,
        val truncated: Boolean,
        /** No failure and the full list: only then were photos removed from the album deleted. */
        val complete: Boolean,
        val deleteErrors: Int,
        val attemptRecordError: Boolean,
    ) : SyncResult()
}

/**
 * Keeps a folder of 1080p photos from a shared Google Photos album for the screensaver.
 * Plain Kotlin (no Android), so all of it runs in JVM tests.
 *
 * - One sync at a time per process, on its own [ioDispatcher] in the process [scope], so a slow album
 *   never delays the home screen's own work.
 * - Redirects are followed by hand, at most [MAX_REDIRECTS], and every hop is checked with [AlbumUrls]
 *   before connecting.
 * - Photos removed from the album are deleted only after a complete sync: no failure, full list, not empty.
 * - The last attempt is recorded with a hash of the album link: the next one waits 24 h after a complete
 *   sync, 1 h after any failure, and a different link is due at once.
 * - Logs carry categories and the caller (home or dream), never a link or a raw exception message.
 * - Files are named by a SHA-256 of their link. A photo stored under a String.hashCode name (which
 *   collides) is renamed in place without downloading again when that name belongs to one link only,
 *   and the screensaver's shown set is translated through [shownStore] in the same step.
 */
class AlbumSync(
    private val folder: File,
    private val fetcher: HttpFetcher,
    private val logger: Logger,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val shownStore: ShownStore,
) {
    private val running: Mutex = Mutex()
    private val emptyUrlLogged: AtomicBoolean = AtomicBoolean()

    /**
     * For callers outside a coroutine (Home, the screensaver): starts [syncIfStale] in the process scope and returns at
     * once. [link] is asked once the sync holds the lock, not now: a sync that waited behind a removal or a new link
     * must see the state of that moment, never the one it was asked in.
     */
    fun requestSync(link: () -> String, origin: String) {
        scope.launch(ioDispatcher) { syncIfStale(link, origin) }
    }

    /** The downloaded photos, empty until a first sync finished. */
    fun photos(): List<File> = folder.listFiles { _, name -> name.endsWith(".jpg") }?.toList() ?: emptyList()

    /** What the settings say about the album: how many photos are here and when the last sync was tried (null: never). */
    fun status(): AlbumStatus {
        val attempt: Long? = try {
            File(folder, ATTEMPT_FILE).readLines().getOrNull(0)?.toLongOrNull()
        } catch (e: IOException) {
            null
        }
        return AlbumStatus(photos().size, attempt)
    }

    /**
     * Syncs when the last attempt for this link is old enough. Null when there was nothing to do: no link,
     * still fresh, or another sync already running.
     */
    suspend fun syncIfStale(albumUrl: String, origin: String): SyncResult? = syncIfStale({ albumUrl }, origin)

    suspend fun syncIfStale(link: () -> String, origin: String): SyncResult? {
        if (!running.tryLock()) return null
        try {
            val albumUrl: String = link()
            if (albumUrl.isBlank()) {
                if (emptyUrlLogged.compareAndSet(false, true)) logger.info("No album link is set, photos will not sync")
                return null
            }
            if (!isDue(albumUrl)) return null
            return sync(albumUrl, origin)
        } finally {
            running.unlock()
        }
    }

    /**
     * Syncs now, whatever the last attempt was. It waits for a sync that is already running (a link that arrives
     * meanwhile is the one that counts, so it must not be dropped). Null when there is no link.
     */
    suspend fun syncNow(link: () -> String, origin: String): SyncResult? = running.withLock {
        val albumUrl: String = link()
        if (albumUrl.isBlank()) null else sync(albumUrl, origin)
    }

    /**
     * Takes the album away: the photos, the record of the last attempt and the screensaver's "shown" set. It runs under
     * the same lock as a sync, so it never meets one halfway and no file comes back after it.
     */
    suspend fun clear() {
        running.withLock {
            folder.listFiles()?.filter { it.name.endsWith(".jpg") || it.name.endsWith(".part") || it.name == ATTEMPT_FILE }?.forEach { it.delete() }
            shownStore.clear()
        }
    }

    private fun sync(albumUrl: String, origin: String): SyncResult {
        folder.mkdirs()
        val listing: AlbumListing = try {
            AlbumUrls.parse(String(fetchFollowing(albumUrl, AlbumUrls::isAllowedPageUrl, MAX_PAGE_BYTES), StandardCharsets.UTF_8))
        } catch (e: SyncException) {
            logger.warn("Dream album page could not be read ($origin): ${e.failure}")
            recordAttempt(albumUrl, complete = false)
            return SyncResult.Failed(e.failure)
        }
        if (listing.photoUrls.isEmpty()) {
            logger.warn("Dream album page has no photo links ($origin), keeping the current photos")
            recordAttempt(albumUrl, complete = false)
            return SyncResult.Failed(SyncFailure.EMPTY_LISTING)
        }

        val wanted: MutableSet<String> = HashSet()
        var downloaded = 0
        var transientFailures = 0
        var permanentFailures = 0
        // How many listed links share each old (hashCode) name: only a name used by one link can be renamed.
        val legacyNameCount: Map<String, Int> = listing.photoUrls.groupingBy { legacyFileName(it) }.eachCount()
        val renamed: MutableMap<String, String> = HashMap()
        for (photoUrl in listing.photoUrls) {
            val target = File(folder, fileName(photoUrl))
            wanted.add(target.name)
            if (target.exists()) continue
            val legacy = File(folder, legacyFileName(photoUrl))
            if (legacyNameCount[legacy.name] == 1 && legacy.exists() && legacy.renameTo(target)) {
                renamed[legacy.name] = target.name
                continue
            }
            try {
                download(photoUrl + SIZE_SUFFIX, target)
                downloaded++
            } catch (e: SyncException) {
                if (e.failure.permanent) permanentFailures++ else transientFailures++
                logger.warn("Dream photo download failed ($origin): ${e.failure}")
            }
        }

        if (renamed.isNotEmpty()) {
            shownStore.rename(renamed)
            logger.info("Dream photos renamed to the new names: ${renamed.size}")
        }
        val complete: Boolean = transientFailures == 0 && permanentFailures == 0 && !listing.truncated
        var deleteErrors = 0
        if (complete) {
            // Photos removed from the album disappear from the TV too, but only on a full, clean view.
            for (file in photos()) {
                if (file.name !in wanted && !file.delete()) deleteErrors++
            }
        }
        val attemptRecordError: Boolean = !recordAttempt(albumUrl, complete)
        val failed: Int = transientFailures + permanentFailures
        logger.info(
            "Dream album synced ($origin): ${listing.photoUrls.size} photos in the album, $failed downloads failed" +
                (if (listing.truncated) ", list truncated, nothing deleted" else ""),
        )
        if (deleteErrors > 0) logger.warn("Dream photos could not be deleted: $deleteErrors")
        if (attemptRecordError) logger.warn("Dream sync record could not be written")
        return SyncResult.Synced(
            listing.photoUrls.size, downloaded, transientFailures, permanentFailures, listing.truncated, complete,
            deleteErrors, attemptRecordError,
        )
    }

    /** Fetches [startUrl], following at most [MAX_REDIRECTS] redirects, checking every hop before connecting. */
    private fun fetchFollowing(startUrl: String, allowed: (String) -> Boolean, maxBytes: Int): ByteArray {
        var url: String = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            if (!allowed(url)) throw SyncException(SyncFailure.NOT_ALLOWED)
            val response: HttpResponse = try {
                fetcher.fetch(url, maxBytes)
            } catch (e: ResponseTooLargeException) {
                throw SyncException(SyncFailure.TOO_LARGE)
            } catch (e: IOException) {
                throw SyncException(SyncFailure.NETWORK_ERROR)
            }
            when {
                response.code == 200 -> return response.body
                response.code in 300..399 && response.location != null -> url = resolve(url, response.location)
                else -> throw SyncException(SyncFailure.HTTP_ERROR)
            }
        }
        throw SyncException(SyncFailure.TOO_MANY_REDIRECTS)
    }

    private fun resolve(base: String, location: String): String = try {
        URI(base).resolve(location).toString()
    } catch (e: URISyntaxException) {
        throw SyncException(SyncFailure.NOT_ALLOWED)
    } catch (e: IllegalArgumentException) {
        throw SyncException(SyncFailure.NOT_ALLOWED)
    }

    /** Writes to a temp file first, so a dropped connection never leaves a half photo in the slideshow. */
    private fun download(url: String, target: File) {
        val body: ByteArray = fetchFollowing(url, AlbumUrls::isAllowedPhotoUrl, MAX_PHOTO_BYTES)
        val partial = File(target.path + ".part")
        try {
            partial.writeBytes(body)
        } catch (e: IOException) {
            partial.delete()
            throw SyncException(SyncFailure.NETWORK_ERROR)
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw SyncException(SyncFailure.NETWORK_ERROR)
        }
    }

    /** Due when there is no record, the link changed, or the record is older than its interval. */
    private fun isDue(albumUrl: String): Boolean {
        val lines: List<String> = try {
            File(folder, ATTEMPT_FILE).readLines()
        } catch (e: IOException) {
            return true
        }
        val time: Long = lines.getOrNull(0)?.toLongOrNull() ?: return true
        if (lines.getOrNull(1) != sha256(albumUrl)) return true
        val interval: Long = if (lines.getOrNull(2) == "complete") COMPLETE_INTERVAL_MS else RETRY_INTERVAL_MS
        val age: Long = clock() - time
        return age < 0 || age >= interval
    }

    /** False when the record could not be written. */
    private fun recordAttempt(albumUrl: String, complete: Boolean): Boolean {
        val text: String = "${clock()}\n${sha256(albumUrl)}\n${if (complete) "complete" else "failed"}\n"
        val written: Boolean = try {
            File(folder, ATTEMPT_FILE).writeText(text)
            true
        } catch (e: IOException) {
            false
        }
        // A stray marker from the String.hashCode naming scheme is deleted; the attempt record is what counts.
        File(folder, LEGACY_MARKER).delete()
        return written
    }

    private class SyncException(val failure: SyncFailure) : Exception()

    companion object {
        const val MAX_REDIRECTS: Int = 5
        const val MAX_PAGE_BYTES: Int = 8 * 1024 * 1024
        const val MAX_PHOTO_BYTES: Int = 12 * 1024 * 1024
        const val COMPLETE_INTERVAL_MS: Long = 24L * 60 * 60 * 1000
        const val RETRY_INTERVAL_MS: Long = 60L * 60 * 1000
        const val ATTEMPT_FILE: String = ".attempt"
        private const val LEGACY_MARKER: String = ".synced"
        // Google resizes on the fly: fit inside 1920x1080, keep the aspect ratio.
        private const val SIZE_SUFFIX: String = "=w1920-h1080"

        /** A photo's file name: the SHA-256 of its link, so two links never share a file. */
        fun fileName(photoUrl: String): String = sha256(photoUrl) + ".jpg"

        /** The String.hashCode name in hex that photos may be stored under: two links can collide on it. */
        fun legacyFileName(photoUrl: String): String = Integer.toHexString(photoUrl.hashCode()) + ".jpg"

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
