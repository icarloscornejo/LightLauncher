package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

class ShelfImageUrlTest {

    private fun ok(address: String) = assertNotNull(address, ShelfImageUrl.allowed(address))
    private fun refused(address: String) = assertNull(address, ShelfImageUrl.allowed(address))

    @Test
    fun plainHttpsOnAPublicNameIsAllowed() {
        ok("https://is1-ssl.mzstatic.com/image/thumb/a.jpg")
        ok("https://HTTPS.Example.com:443/a.png?x=1")
        ok(" https://cdn.example.com/a.jpg ")
    }

    @Test
    fun otherSchemesAreRefused() {
        refused("http://cdn.example.com/a.jpg")
        refused("content://media/external/images/1")
        refused("file:///data/a.jpg")
        refused("ftp://cdn.example.com/a.jpg")
        refused("//cdn.example.com/a.jpg")
        refused("cdn.example.com/a.jpg")
    }

    @Test
    fun credentialsAndOddPortsAreRefused() {
        refused("https://trusted.example.com@evil.example.org/a.jpg")
        refused("https://user:pass@cdn.example.com/a.jpg")
        refused("https://cdn.example.com:8443/a.jpg")
        refused("https://cdn.example.com:80/a.jpg")
    }

    @Test
    fun hostsThatPointAtTheOwnersNetworkAreRefused() {
        refused("https://192.168.1.1/a.jpg")
        refused("https://10.0.0.5/a.jpg")
        refused("https://127.0.0.1/a.jpg")
        refused("https://[::1]/a.jpg")
        refused("https://[fe80::1]/a.jpg")
        refused("https://localhost/a.jpg")
        refused("https://router/a.jpg")
        refused("https://printer.local/a.jpg")
        refused("https://nas.home/a.jpg")
        refused("https://svc.internal/a.jpg")
        refused("https://cdn..example.com/a.jpg")
    }

    @Test
    fun numericAddressesInOtherFormsAreRefused() {
        refused("https://0x7f.0.0.1/a.jpg")
        refused("https://127.1/a.jpg")
        refused("https://0177.0.0.1/a.jpg")
        refused("https://example.123/a.jpg")
    }

    @Test
    fun emptyOverlongAndBrokenAddressesAreRefused() {
        refused("")
        refused("   ")
        refused("https://cdn.example.com/" + "a".repeat(ShelfImageUrl.MAX_LENGTH))
        refused("https://cdn.example.com/a b.jpg")
    }

    @Test
    fun aRedirectIsCheckedLikeAnyOtherAddress() {
        val from = URI("https://cdn.example.com/start.jpg")
        assertEquals("https://other.example.org/b.jpg", ShelfImageUrl.redirect(from, "https://other.example.org/b.jpg")?.toString())
        assertEquals("https://cdn.example.com/img/c.jpg", ShelfImageUrl.redirect(from, "/img/c.jpg")?.toString())
        assertNull(ShelfImageUrl.redirect(from, "http://other.example.org/b.jpg"))
        assertNull(ShelfImageUrl.redirect(from, "https://192.168.1.1/b.jpg"))
        assertNull(ShelfImageUrl.redirect(from, "https://u@other.example.org/b.jpg"))
    }
}
