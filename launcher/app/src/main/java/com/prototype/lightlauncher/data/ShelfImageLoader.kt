package com.prototype.lightlauncher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.prototype.lightlauncher.core.ArtSize
import com.prototype.lightlauncher.core.ImageLimits
import com.prototype.lightlauncher.core.ShelfImageUpgrade
import com.prototype.lightlauncher.core.ShelfImageUrl
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A shelf image shrunk for the screen, with the size the app published it at (that is what the card rules use). */
class ShelfImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

/**
 * The top shelf's images, from the addresses other apps publish. This is the one place the launcher
 * downloads from hosts it cannot list in advance, so every limit is here:
 * - the address must pass [ShelfImageUrl] (https, port 443, no credentials, a public looking name), and so must every
 *   redirect, at most [ShelfImageUrl.MAX_REDIRECTS] of them, followed by hand;
 * - at most [MAX_BYTES] per image and [TOTAL_TIME_NANOS] in all, and a stop request ends the download;
 * - a header that claims too many pixels is refused before decoding, and the decode is shrunk to the size the caller needs (a card or a full screen picture);
 * - what was downloaded is cached under cacheDir (at most [MAX_FILES] files and [MAX_DISK_BYTES]), with a small
 *   memory cache of decoded images in front;
 * - logs carry a category ("address not allowed", "too large", ...), never an address, a host or a raw message.
 *
 * Network and decode problems are expected here: they give "no image" (null), they are not exceptions the caller
 * has to guard. Call [load] on an IO dispatcher.
 */
class ShelfImageLoader(
    cacheRoot: File,
    private val fetcher: UrlConnectionFetcher,
    private val logger: Logger,
) {
    private val directory: File = File(cacheRoot, "shelf")

    private val memory: LruCache<String, ShelfImage> = object : LruCache<String, ShelfImage>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ShelfImage): Int = value.bitmap.byteCount
    }

    /**
     * The image, shrunk to stay at least [targetWidth] by [targetHeight] (a card needs about 800 by 450, a full screen
     * picture 1920 by 1080), or null when it is not allowed, could not be fetched or decoded, or [isCancelled] said stop.
     */
    fun load(address: String, targetWidth: Int, targetHeight: Int, isCancelled: () -> Boolean): ShelfImage? {
        // A bigger version of the same picture first, when the host has a known way to ask for one (ShelfImageUpgrade).
        for (candidate in candidatesFor(address, targetWidth)) {
            if (isCancelled()) return null
            val image: ShelfImage? = loadOne(candidate, targetWidth, targetHeight, isCancelled)
            noteUpgrade(candidate, address, targetWidth, image != null, isCancelled)
            if (image != null) return image
        }
        return null
    }

    /**
     * The size the app published the image at, without decoding it: the bytes come from the cache or the network like in
     * [load], but only their header is read. For deciding whether a picture can fill the screen before paying for it.
     */
    fun peek(address: String, targetWidth: Int, isCancelled: () -> Boolean): ArtSize? {
        for (candidate in candidatesFor(address, targetWidth)) {
            if (isCancelled()) return null
            val key: String = fileKey(candidate)
            memory.get("$key@$targetWidth")?.let { return ArtSize(it.originalWidth, it.originalHeight) }
            val bytes: ByteArray? = readCached(key) ?: download(candidate, key, isCancelled)
            val size: ArtSize? = bytes?.let(::boundsOf)
            if (bytes != null && size == null) discard(key)
            noteUpgrade(candidate, address, targetWidth, size != null, isCancelled)
            if (size != null) return size
        }
        return null
    }

    /** Downloads the image into the disk cache and stops there (nothing is decoded): the next [load] finds it. */
    fun prefetch(address: String, targetWidth: Int, isCancelled: () -> Boolean) {
        for (candidate in candidatesFor(address, targetWidth)) {
            if (isCancelled()) return
            val key: String = fileKey(candidate)
            if (File(directory, key).isFile) return
            val ok: Boolean = download(candidate, key, isCancelled) != null
            noteUpgrade(candidate, address, targetWidth, ok, isCancelled)
            if (ok) return
        }
    }

    /**
     * What a host keeps refusing is not asked for again: after two refusals of a bigger address and no success, the
     * published one is used straight away (Max's CDN answers an error to the card sizes, which cost a round trip each).
     */
    private val upgradeResults: MutableMap<String, IntArray> = java.util.concurrent.ConcurrentHashMap()

    private fun candidatesFor(address: String, targetWidth: Int): List<String> =
        ShelfImageUpgrade.candidates(address, targetWidth).filter { candidate ->
            candidate == address || upgradeResults[upgradeKey(candidate, targetWidth)]?.let { it[0] >= 2 && it[1] == 0 } != true
        }

    private fun noteUpgrade(candidate: String, published: String, targetWidth: Int, ok: Boolean, isCancelled: () -> Boolean) {
        if (candidate == published || (!ok && isCancelled())) return
        val result: IntArray = upgradeResults.getOrPut(upgradeKey(candidate, targetWidth)) { IntArray(2) }
        result[if (ok) 1 else 0]++
    }

    private fun upgradeKey(address: String, targetWidth: Int): String = (try { URI(address).host } catch (e: java.net.URISyntaxException) { null }).orEmpty() + "@" + targetWidth

    private fun loadOne(address: String, targetWidth: Int, targetHeight: Int, isCancelled: () -> Boolean): ShelfImage? {
        val started: Long = System.nanoTime()
        val key: String = fileKey(address)
        // One download on disk serves every size; only the decoded picture depends on the target.
        val memoryKey = "$key@$targetWidth"
        memory.get(memoryKey)?.let {
            logger.info("shelf image: ${it.originalWidth}x${it.originalHeight} from memory in ${(System.nanoTime() - started) / 1_000_000} ms")
            return it
        }
        val cached: ByteArray? = readCached(key)
        val bytes: ByteArray = cached ?: download(address, key, isCancelled) ?: return null
        val image: ShelfImage? = decode(bytes, targetWidth, targetHeight)
        if (image == null) {
            discard(key)
            logger.warn("shelf image: undecodable")
            return null
        }
        memory.put(memoryKey, image)
        // The size the app published it at is what decides cards, and the one number the log may carry about an image.
        logger.info(
            "shelf image: ${image.originalWidth}x${image.originalHeight} from ${if (cached != null) "cache" else "network"} " +
                "in ${(System.nanoTime() - started) / 1_000_000} ms",
        )
        return image
    }

    private fun download(address: String, key: String, isCancelled: () -> Boolean): ByteArray? {
        var uri: URI = ShelfImageUrl.allowed(address) ?: run {
            logger.warn("shelf image: address not allowed")
            return null
        }
        val deadline: Long = System.nanoTime() + TOTAL_TIME_NANOS
        try {
            repeat(ShelfImageUrl.MAX_REDIRECTS + 1) {
                val response: HttpResponse = fetcher.fetch(uri.toString(), MAX_BYTES, deadline, isCancelled)
                when (response.code) {
                    200 -> {
                        if (response.body.isEmpty()) {
                            logger.warn("shelf image: empty answer")
                            return null
                        }
                        store(key, response.body)
                        return response.body
                    }
                    301, 302, 303, 307, 308 -> {
                        uri = response.location?.let { ShelfImageUrl.redirect(uri, it) } ?: run {
                            logger.warn("shelf image: redirect not allowed")
                            return null
                        }
                    }
                    else -> {
                        logger.warn("shelf image: http error")
                        return null
                    }
                }
            }
            logger.warn("shelf image: too many redirects")
        } catch (e: FetchStoppedException) {
            // Cancelled, or out of time: not worth a log line, the owner already moved on.
        } catch (e: ResponseTooLargeException) {
            logger.warn("shelf image: too large")
        } catch (e: IOException) {
            logger.warn("shelf image: network error")
        }
        return null
    }

    /** The size in the header of the image, or null for one that is corrupt or absurdly large. */
    private fun boundsOf(bytes: ByteArray): ArtSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (ImageLimits.acceptable(bounds.outWidth, bounds.outHeight)) ArtSize(bounds.outWidth, bounds.outHeight) else null
    }

    /** Decodes from the bytes, shrunk to the target. Null for a corrupt or absurdly large image. */
    private fun decode(bytes: ByteArray, targetWidth: Int, targetHeight: Int): ShelfImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!ImageLimits.acceptable(bounds.outWidth, bounds.outHeight)) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageLimits.sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        }
        // A picture that fits the pixel limit can still not fit the heap of a small TV: skip it, never crash Home.
        val bitmap: Bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            logger.warn("shelf image: too large to decode")
            null
        } ?: return null
        return ShelfImage(bitmap, bounds.outWidth, bounds.outHeight)
    }

    private fun readCached(key: String): ByteArray? {
        val file = File(directory, key)
        return try {
            if (!file.isFile || file.length() > MAX_BYTES) return null
            file.setLastModified(System.currentTimeMillis()) // the oldest are the ones trimmed first
            file.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    /** A failed write only costs the cache: the image in hand is still used. */
    private fun store(key: String, bytes: ByteArray) {
        try {
            directory.mkdirs()
            // Unique per write: two loads of the same picture must never share one half-written file.
            val temporary = File(directory, "$key.${System.nanoTime()}.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(File(directory, key))) temporary.delete()
            trim()
        } catch (e: IOException) {
            logger.warn("shelf image: cache write failed")
        }
    }

    private fun discard(key: String) {
        File(directory, key).delete()
    }

    /** Deletes the least recently used files until the cache is within its limits. */
    private fun trim() {
        // A write that died halfway leaves its temporary file behind; nothing reads those.
        directory.listFiles { file -> file.name.endsWith(".tmp") && System.currentTimeMillis() - file.lastModified() > STALE_TEMPORARY_MILLIS }
            ?.forEach { it.delete() }
        val files: MutableList<File> = directory.listFiles { file -> file.isFile && !file.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total: Long = files.sumOf { it.length() }
        while (files.isNotEmpty() && (files.size > MAX_FILES || total > MAX_DISK_BYTES)) {
            val oldest: File = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private fun fileKey(address: String): String =
        MessageDigest.getInstance("SHA-256").digest(address.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_BYTES: Int = 3_000_000
        const val STALE_TEMPORARY_MILLIS: Long = 3_600_000L
        const val TOTAL_TIME_NANOS: Long = 8_000_000_000L
        /** Room for a full screen picture (8 MB at 1920 by 1080) and a row of sixteen cards (about 1.4 MB each). */
        const val MEMORY_BYTES: Int = 40 * 1024 * 1024

        /**
         * What the dock apps publish, kept on disk so that going back to an app does not download it again: six apps
         * with sixteen pictures of about 100 KB are under 10 MB. At 40 files they pushed each other out as the focus
         * moved from one app to the next.
         */
        const val MAX_FILES: Int = 400
        const val MAX_DISK_BYTES: Long = 128L * 1024 * 1024
    }
}
