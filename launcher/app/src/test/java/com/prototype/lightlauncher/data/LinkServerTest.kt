package com.prototype.lightlauncher.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Real sockets on the loopback address: the server is the one thing here that talks to the network. */
class LinkServerTest {

    private class RecordingLogger : Logger {
        val lines: MutableList<String> = mutableListOf()
        override fun info(message: String) { lines.add(message) }
        override fun warn(message: String) { lines.add(message) }
    }

    private val logger = RecordingLogger()
    private val received: MutableList<String> = mutableListOf()
    private var accept: Boolean = true
    private var lifetime: Long = 30_000L
    private var server: LinkServer? = null

    private fun started(): LinkServer = LinkServer(logger, { link -> received.add(link); accept }, lifetime).also {
        assertTrue(it.start())
        server = it
    }

    @After
    fun stopServer() {
        server?.stop()
    }

    /** Sends [request] as it is and returns everything the server answers. */
    private fun talk(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply { write(request.toByteArray(StandardCharsets.ISO_8859_1)); flush() }
            String(socket.getInputStream().readBytes(), StandardCharsets.UTF_8)
        }

    private fun get(server: LinkServer, path: String = "/${server.token}", headers: String = ""): String =
        talk(server.port, "GET $path HTTP/1.1\r\nHost: tv\r\n$headers\r\n")

    private fun post(server: LinkServer, body: String, path: String = "/${server.token}"): String =
        talk(server.port, "POST $path HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body")

    @Test
    fun theTokenPathServesTheFormInThePhonesLanguage() {
        val server = started()
        val english = get(server)
        assertTrue(english.startsWith("HTTP/1.1 200 OK"))
        assertTrue(english.contains("name=\"link\""))
        assertTrue(english.contains("lang=\"en\""))
        assertTrue(get(server, headers = "Accept-Language: es-419,es;q=0.9\r\n").contains("lang=\"es\""))
        assertTrue(english.contains("Content-Security-Policy: default-src 'none'"))
        assertTrue(english.contains("Cache-Control: no-store"))
    }

    @Test
    fun anyOtherPathIsNotFound() {
        val server = started()
        assertTrue(get(server, "/").startsWith("HTTP/1.1 404"))
        assertTrue(get(server, "/${server.token}x").startsWith("HTTP/1.1 404"))
        assertTrue(get(server, "/favicon.ico").startsWith("HTTP/1.1 404"))
        assertTrue(post(server, "link=https%3A%2F%2Fphotos.app.goo.gl%2Fabc", "/wrong").startsWith("HTTP/1.1 404"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun anAlbumLinkIsKeptAndTheServerStops() {
        val server = started()
        val answer = post(server, "link=https%3A%2F%2Fphotos.app.goo.gl%2FAbC123")
        assertTrue(answer.startsWith("HTTP/1.1 200 OK"))
        assertEquals(listOf("https://photos.app.goo.gl/AbC123"), received)
        Thread.sleep(800)
        assertFalse(server.isRunning)
    }

    @Test
    fun whatIsNotAGooglePhotosLinkIsRefusedAndTheServerKeepsWaiting() {
        val server = started()
        for (bad in listOf("https%3A%2F%2Fevil.example%2Fx", "http%3A%2F%2Fphotos.app.goo.gl%2Fabc", "not+a+link", "")) {
            val answer = post(server, "link=$bad")
            assertTrue("for $bad: $answer", answer.startsWith("HTTP/1.1 400"))
            assertTrue(answer.contains("class=\"error\""))
        }
        assertTrue(received.isEmpty())
        assertTrue(server.isRunning)
        assertTrue(post(server, "link=https%3A%2F%2Fphotos.google.com%2Fshare%2Fabc").startsWith("HTTP/1.1 200"))
    }

    @Test
    fun aLinkTheAppCouldNotKeepIsAnErrorAndTheServerKeepsWaiting() {
        accept = false
        val server = started()
        assertTrue(post(server, "link=https%3A%2F%2Fphotos.app.goo.gl%2Fabc").startsWith("HTTP/1.1 500"))
        assertTrue(server.isRunning)
    }

    @Test
    fun anOversizedOrBrokenRequestGetsNoPage() {
        val server = started()
        val huge = "link=" + "a".repeat(5000)
        val answer = try { post(server, huge) } catch (e: IOException) { "" }
        assertFalse(answer.startsWith("HTTP/1.1 200"))
        assertTrue(talk(server.port, "NOT HTTP\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(talk(server.port, "DELETE /${server.token} HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(received.isEmpty())
        assertTrue(server.isRunning)
    }

    @Test
    fun theServerStopsOnItsOwnAfterItsLifetime() {
        lifetime = 600L
        val server = started()
        assertTrue(server.isRunning)
        Thread.sleep(1_800)
        assertFalse(server.isRunning)
        assertTrue(try { get(server); false } catch (e: IOException) { true })
    }

    @Test
    fun stopClosesThePortAndCanBeCalledAgain() {
        val server = started()
        val port = server.port
        server.stop()
        server.stop()
        assertFalse(server.isRunning)
        assertTrue(try { talk(port, "GET / HTTP/1.1\r\n\r\n"); false } catch (e: IOException) { true })
    }

    @Test
    fun theTokenIs128BitsAndNeverTheSameTwice() {
        val a = LinkServer(logger, { true })
        val b = LinkServer(logger, { true })
        assertEquals(32, a.token.length)
        assertTrue(a.token.all { it in "0123456789abcdef" })
        assertTrue(a.token != b.token)
        assertNull(null)
    }

    @Test
    fun theLogsNeverCarryTheLinkOrTheToken() {
        val server = started()
        post(server, "link=https%3A%2F%2Fphotos.app.goo.gl%2FSECRETlink9")
        Thread.sleep(800)
        assertTrue(logger.lines.isNotEmpty())
        assertFalse(logger.lines.any { it.contains("SECRETlink9") || it.contains(server.token) || it.contains("photos.app.goo.gl") })
    }
}
