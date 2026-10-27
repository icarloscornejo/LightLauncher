package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumUrlsTest {

    // Hand-made: the page's structure with made-up ids. Real ids open the photos without the album link.
    private val page: String = """
        <script>AF_initDataCallback({data:[["https://lh3.googleusercontent.com/pw/AAAfakeOne_123",1920,1080],
        "https://lh3.googleusercontent.com/pw/AAAfakeOne_123=w600-h315-p-k",
        "https://lh3.googleusercontent.com/ogw/notAPhotoAvatar=s83",
        "https:\/\/lh3.googleusercontent.com\/pw\/escapedIsSkipped",
        ["https://lh3.googleusercontent.com/pw/BBB-fake_Two",810,1080]]});</script>
    """.trimIndent()

    @Test
    fun findsUniquePhotoLinksInPageOrder() {
        val listing: AlbumListing = AlbumUrls.parse(page)
        assertEquals(
            listOf(
                "https://lh3.googleusercontent.com/pw/AAAfakeOne_123",
                "https://lh3.googleusercontent.com/pw/BBB-fake_Two",
            ),
            listing.photoUrls,
        )
        assertFalse(listing.truncated)
    }

    @Test
    fun anEmptyPageHasNoPhotos() {
        val listing: AlbumListing = AlbumUrls.parse("<html>nothing shared here</html>")
        assertTrue(listing.photoUrls.isEmpty())
        assertFalse(listing.truncated)
    }

    @Test
    fun moreThanTheCapIsMarkedTruncated() {
        val exactly: String = (0 until AlbumUrls.MAX_PHOTOS).joinToString(" ") { "https://lh3.googleusercontent.com/pw/fake$it" }
        assertFalse(AlbumUrls.parse(exactly).truncated)
        val listing: AlbumListing = AlbumUrls.parse("$exactly https://lh3.googleusercontent.com/pw/oneTooMany")
        assertEquals(AlbumUrls.MAX_PHOTOS, listing.photoUrls.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun pageLinksMustBeHttpsOnGooglePhotos() {
        assertTrue(AlbumUrls.isAllowedPageUrl("https://photos.app.goo.gl/fakeShortLink"))
        assertTrue(AlbumUrls.isAllowedPageUrl("https://photos.google.com/share/fakeAlbum?key=fakeKey"))
        assertFalse(AlbumUrls.isAllowedPageUrl("http://photos.google.com/share/fakeAlbum"))
        assertFalse(AlbumUrls.isAllowedPageUrl("https://photos.google.com.evil.example/share"))
        assertFalse(AlbumUrls.isAllowedPageUrl("https://evil.example/photos.google.com"))
        assertFalse(AlbumUrls.isAllowedPageUrl("https://photos.google.com@evil.example/"))
        assertFalse(AlbumUrls.isAllowedPageUrl("https://photos.google.com:8443/share"))
        assertFalse(AlbumUrls.isAllowedPageUrl("photos.app.goo.gl/missingScheme"))
        assertFalse(AlbumUrls.isAllowedPageUrl("https://lh3.googleusercontent.com/pw/fake"))
        assertFalse(AlbumUrls.isAllowedPageUrl("not a url at all"))
    }

    @Test
    fun photoLinksMustBeHttpsOnTheImageServer() {
        assertTrue(AlbumUrls.isAllowedPhotoUrl("https://lh3.googleusercontent.com/pw/fake=w1920-h1080"))
        assertFalse(AlbumUrls.isAllowedPhotoUrl("http://lh3.googleusercontent.com/pw/fake"))
        assertFalse(AlbumUrls.isAllowedPhotoUrl("https://lh4.googleusercontent.com/pw/fake"))
        assertFalse(AlbumUrls.isAllowedPhotoUrl("https://photos.google.com/share/fake"))
    }
}
