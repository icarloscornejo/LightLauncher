package com.prototype.lightlauncher.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP answer: the status, the redirect target if any, and the body (empty unless the status is 200). */
class HttpResponse(val code: Int, val location: String?, val body: ByteArray)

/** The body would be larger than allowed: nothing more is read. */
class ResponseTooLargeException : IOException("response too large")

/** The caller asked to stop, or the total time ran out, so the connection was dropped. */
class FetchStoppedException : IOException("fetch stopped")

/**
 * A single request that never follows redirects by itself, so the caller can check every
 * hop against its allow list before connecting.
 */
interface HttpFetcher {
    @Throws(IOException::class)
    fun fetch(url: String, maxBytes: Int): HttpResponse
}

/** The real fetcher on HttpURLConnection. */
class UrlConnectionFetcher(
    // photos.app.goo.gl only redirects plain clients to the album; browsers get a JavaScript page.
    private val userAgent: String = "curl/8.0",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : HttpFetcher {

    override fun fetch(url: String, maxBytes: Int): HttpResponse = fetch(url, maxBytes, Long.MAX_VALUE) { false }

    /**
     * [fetch] with a total time limit ([deadlineNanos], on the System.nanoTime clock) and a way to
     * give up ([shouldStop]), both checked before connecting and between 16 KB blocks. A server that dribbles bytes
     * is cut off at the deadline plus at most one read timeout. ponytail: a read already blocked is not
     * interrupted; the read timeout is what ends it.
     */
    fun fetch(url: String, maxBytes: Int, deadlineNanos: Long, shouldStop: () -> Boolean): HttpResponse {
        if (shouldStop() || System.nanoTime() > deadlineNanos) throw FetchStoppedException()
        val connection: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            val code: Int = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return HttpResponse(code, connection.getHeaderField("Location"), ByteArray(0))
            }
            connection.inputStream.use { input: InputStream -> return HttpResponse(code, null, readLimited(input, maxBytes, deadlineNanos, shouldStop)) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int, deadlineNanos: Long, shouldStop: () -> Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (shouldStop() || System.nanoTime() > deadlineNanos) throw FetchStoppedException()
            val read: Int = input.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) throw ResponseTooLargeException()
        }
        return out.toByteArray()
    }
}
