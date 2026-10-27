package com.prototype.lightlauncher.data

import com.prototype.lightlauncher.core.AlbumUrls
import com.prototype.lightlauncher.core.LinkPage
import com.prototype.lightlauncher.core.LinkRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * A small web server that lives only while the "send the album link from your phone" screen is open. The
 * phone scans a QR code that holds `http://<tv address>:<port>/<token>`, opens it, pastes the link of a shared Google
 * Photos album and sends it; [onLink] takes it. Written on plain sockets, no library.
 *
 * What keeps an open port on the owner's network safe enough for that:
 * - the path is a random 128-bit [token] that only the QR code shows; any other path is a 404;
 * - it answers one connection at a time, reads at most [LinkRequest.MAX_BYTES], and gives up on a slow one;
 * - only a client on the local network (private, link-local or loopback address) is answered at all;
 * - what it accepts is a link to Google Photos ([AlbumUrls.isAllowedPageUrl]) and nothing else, and it never writes
 *   anything it was sent back into a page;
 * - it stops when the screen closes, after the first link it accepts, or after [maxLifetimeMs].
 */
class LinkServer(
    private val logger: Logger,
    /** Takes the link; true when it was kept. */
    private val onLink: (String) -> Boolean,
    private val maxLifetimeMs: Long = DEFAULT_LIFETIME_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The secret part of the address. */
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var stopped: Boolean = false

    val isRunning: Boolean get() = socket?.isClosed == false

    /** Starts listening on a free port. False when the TV would not give one. */
    fun start(): Boolean {
        if (socket != null) return true
        val server: ServerSocket = try {
            ServerSocket(0, 1).apply { soTimeout = ACCEPT_TIMEOUT_MS }
        } catch (e: IOException) {
            logger.warn("Link server could not start: ${e.javaClass.simpleName}")
            return false
        }
        socket = server
        port = server.localPort
        val thread = Thread({ serve(server) }, "link-server")
        thread.isDaemon = true
        thread.start()
        logger.info("Link server started")
        return true
    }

    /** Closes the port. Safe to call more than once. */
    fun stop() {
        stopped = true
        try {
            socket?.close()
        } catch (e: IOException) {
            logger.warn("Link server did not close cleanly: ${e.javaClass.simpleName}")
        }
    }

    private fun serve(server: ServerSocket) {
        val deadline: Long = clock() + maxLifetimeMs
        try {
            while (!stopped && clock() < deadline) {
                val client: Socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    break
                }
                val accepted: Boolean = client.use { answer(it) }
                if (accepted) break
            }
        } finally {
            stop()
            logger.info("Link server stopped")
        }
    }

    /** Reads one request and answers it. True when it carried the link and it was kept. */
    private fun answer(client: Socket): Boolean {
        val address = client.inetAddress
        if (!(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)) {
            logger.warn("Link server refused a client outside the local network")
            return false
        }
        client.soTimeout = READ_TIMEOUT_MS
        val raw: String? = try {
            read(client)
        } catch (e: IOException) {
            return false
        }
        val request: LinkRequest? = raw?.let(LinkRequest::parse)
        if (request == null) {
            respond(client, 400, "Bad Request", "")
            return false
        }
        val spanish: Boolean = LinkPage.prefersSpanish(request.acceptLanguage)
        if (request.path != "/$token") {
            respond(client, 404, "Not Found", "")
            return false
        }
        if (request.method == "GET") {
            respond(client, 200, "OK", LinkPage.form(spanish, invalid = false))
            return false
        }
        val link: String = request.fields["link"].orEmpty().trim()
        if (link.length > MAX_LINK_LENGTH || !AlbumUrls.isAllowedPageUrl(link)) {
            logger.warn("Link server got something that is not an album link")
            respond(client, 400, "Bad Request", LinkPage.form(spanish, invalid = true))
            return false
        }
        if (!onLink(link)) {
            respond(client, 500, "Internal Server Error", LinkPage.form(spanish, invalid = false))
            return false
        }
        logger.info("Link server got an album link")
        respond(client, 200, "OK", LinkPage.done(spanish))
        return true
    }

    /** The whole request as text, or null when it is too big, too slow or not finished. */
    private fun read(client: Socket): String? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        val input = client.getInputStream()
        val giveUpAt: Long = clock() + READ_TOTAL_MS
        var wanted: Int = Int.MAX_VALUE
        while (buffer.size() < wanted) {
            if (clock() > giveUpAt) return null
            val count: Int = try {
                input.read(chunk)
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (count < 0) return null
            buffer.write(chunk, 0, count)
            if (buffer.size() > LinkRequest.MAX_BYTES) return null
            val text: String = buffer.toString(StandardCharsets.ISO_8859_1.name())
            val headerEnd: Int = LinkRequest.headerEnd(text)
            if (headerEnd >= 0) {
                val length: Int = LinkRequest.contentLength(text)
                if (length < 0 || headerEnd + length > LinkRequest.MAX_BYTES) return null
                wanted = headerEnd + length
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun respond(client: Socket, code: Int, reason: String, body: String) {
        val bytes: ByteArray = body.toByteArray(StandardCharsets.UTF_8)
        val head: String = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "Content-Security-Policy: ${LinkPage.CONTENT_SECURITY_POLICY}\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n\r\n"
        try {
            client.getOutputStream().apply {
                write(head.toByteArray(StandardCharsets.ISO_8859_1))
                write(bytes)
                flush()
            }
        } catch (e: IOException) {
            // The phone went away: nothing to do.
        }
    }

    companion object {
        const val DEFAULT_LIFETIME_MS: Long = 5 * 60_000L
        private const val ACCEPT_TIMEOUT_MS: Int = 500
        private const val READ_TIMEOUT_MS: Int = 2_000
        private const val READ_TOTAL_MS: Long = 4_000L
        private const val MAX_LINK_LENGTH: Int = 2048
    }
}
