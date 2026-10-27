package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkRequestTest {

    private fun post(path: String, body: String): String =
        "POST $path HTTP/1.1\r\nHost: tv\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.length}\r\n\r\n$body"

    @Test
    fun aGetNamesItsPathAndDropsTheQuery() {
        val request = LinkRequest.parse("GET /abc?x=1 HTTP/1.1\r\nHost: tv\r\n\r\n")!!
        assertEquals("GET", request.method)
        assertEquals("/abc", request.path)
        assertEquals(emptyMap<String, String>(), request.fields)
    }

    @Test
    fun theLanguageThePhoneAsksForIsKept() {
        assertEquals("es-419,es;q=0.9", LinkRequest.parse("GET /a HTTP/1.1\r\nAccept-Language: es-419,es;q=0.9\r\n\r\n")!!.acceptLanguage)
        assertNull(LinkRequest.parse("GET /a HTTP/1.1\r\nHost: tv\r\n\r\n")!!.acceptLanguage)
    }

    @Test
    fun aPostGivesItsDecodedFields() {
        val request = LinkRequest.parse(post("/abc", "link=https%3A%2F%2Fphotos.app.goo.gl%2FA1b2&x=a+b"))!!
        assertEquals("POST", request.method)
        assertEquals("https://photos.app.goo.gl/A1b2", request.fields["link"])
        assertEquals("a b", request.fields["x"])
    }

    @Test
    fun otherMethodsAndBrokenRequestsAreRefused() {
        assertNull(LinkRequest.parse("DELETE /abc HTTP/1.1\r\n\r\n"))
        assertNull(LinkRequest.parse("GET abc HTTP/1.1\r\n\r\n"))
        assertNull(LinkRequest.parse("GET /abc\r\n\r\n"))
        assertNull(LinkRequest.parse("GET /abc HTTP/1.1\r\nHost: tv\r\n")) // the headers never ended
        assertNull(LinkRequest.parse(post("/abc", "link=%ZZ")))
    }

    @Test
    fun theHeadersAreSeenToEndAndTheContentLengthIsRead() {
        val raw = post("/abc", "link=1")
        assertEquals(raw.length - "link=1".length, LinkRequest.headerEnd(raw))
        assertEquals(6, LinkRequest.contentLength(raw))
        assertEquals(-1, LinkRequest.headerEnd("GET / HTTP/1.1\r\nHost: tv\r\n"))
        assertEquals(0, LinkRequest.contentLength("GET / HTTP/1.1\r\n\r\n"))
        assertEquals(-1, LinkRequest.contentLength("POST / HTTP/1.1\r\nContent-Length: x\r\n\r\n"))
        assertEquals(-1, LinkRequest.contentLength("POST / HTTP/1.1\r\nContent-Length: -5\r\n\r\n"))
    }
}
