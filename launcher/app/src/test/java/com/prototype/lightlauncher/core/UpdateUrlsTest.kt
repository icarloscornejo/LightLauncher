package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlsTest {

    @Test
    fun githubsOwnHostsOverHttpsAreAllowed() {
        assertTrue(UpdateUrls.isAllowed("https://api.github.com/repos/owner/name/releases/latest"))
        assertTrue(UpdateUrls.isAllowed("https://github.com/owner/name/releases/download/v1.1.0/LightLauncher-1.1.0.apk"))
        assertTrue(UpdateUrls.isAllowed("https://objects.githubusercontent.com/github-production-release-asset/1"))
        assertTrue(UpdateUrls.isAllowed("https://release-assets.githubusercontent.com/github-production-release-asset/1"))
        assertTrue(UpdateUrls.isAllowed("https://API.GitHub.com:443/x"))
    }

    @Test
    fun everythingElseIsRefused() {
        for (url in listOf(
            "http://api.github.com/repos/o/n/releases/latest", // not https
            "https://github.com.evil.example/x",
            "https://evil.example/github.com",
            "https://github.com@evil.example/x", // the credentials trick
            "https://api.github.com:8443/x",
            "https://raw.githubusercontent.com/o/n/main/x", // GitHub, but not a host a release is served from
            "ftp://github.com/x",
            "not a url",
            "",
        )) {
            assertFalse("'$url'", UpdateUrls.isAllowed(url))
        }
    }

    @Test
    fun onlyARepositoryNameMakesAnAddress() {
        assertEquals("https://api.github.com/repos/icarloscornejo/LightLauncher/releases/latest", UpdateUrls.latestReleaseUrl("icarloscornejo/LightLauncher"))
        assertTrue(UpdateUrls.isValidRepo("a.b-c_d/e.f-g_h"))
        for (repo in listOf("", "owner", "owner/", "/name", "a/b/c", "a b/c", "o/n?x=1", "o/n#frag", "../x", "o/..")) {
            assertFalse("'$repo'", UpdateUrls.isValidRepo(repo))
            assertNull("'$repo'", UpdateUrls.latestReleaseUrl(repo))
        }
    }
}
