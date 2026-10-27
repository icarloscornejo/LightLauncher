package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {

    private val hash = "a".repeat(64)
    private val url = "https://github.com/owner/name/releases/download/v1.1.0/LightLauncher-1.1.0.apk"

    private fun apk(
        name: String = "LightLauncher-1.1.0.apk",
        link: String = url,
        digest: String? = "sha256:$hash",
        size: Long = 500_000L,
    ): ReleaseAsset = ReleaseAsset(name, link, digest, size)

    private fun decide(tag: String = "v1.1.0", assets: List<ReleaseAsset> = listOf(apk()), installed: String = "1.0.1"): UpdateDecision =
        UpdateRules.decide(tag, assets, installed)

    @Test
    fun aNewerReleaseWithItsApkAndDigestIsOffered() {
        val decision = decide() as UpdateDecision.Offer
        assertEquals("1.1.0", decision.offer.version)
        assertEquals(url, decision.offer.url)
        assertEquals(hash, decision.offer.sha256)
    }

    @Test
    fun theDigestIsKeptInLowerCase() {
        val decision = decide(assets = listOf(apk(digest = "sha256:" + "AB".repeat(32)))) as UpdateDecision.Offer
        assertEquals("ab".repeat(32), decision.offer.sha256)
    }

    @Test
    fun theSameOrAnOlderReleaseIsUpToDate() {
        assertEquals(UpdateDecision.UpToDate, decide(tag = "v1.0.1"))
        assertEquals(UpdateDecision.UpToDate, decide(tag = "v1.0.0"))
    }

    @Test
    fun aTagThatIsNotAVersionCannotBeUsed() {
        assertEquals(UpdateDecision.Unusable, decide(tag = "nightly"))
    }

    @Test
    fun aNewerReleaseThatCannotBeInstalledSafelyIsRefused() {
        // No digest from GitHub: nothing to check the download against, so it fails closed.
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(digest = null))))
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(digest = "md5:$hash"))))
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(digest = "sha256:abc"))))
        // No APK with the name of the version, or two of them.
        assertEquals(UpdateDecision.Unusable, decide(assets = emptyList()))
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(name = "LightLauncher-1.0.9.apk"))))
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(), apk())))
        // Too big, empty, or served from somewhere that is not GitHub's.
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(size = UpdateRules.MAX_APK_BYTES + 1L))))
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(size = 0L))))
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(link = "https://evil.example/LightLauncher-1.1.0.apk"))))
        assertEquals(UpdateDecision.Unusable, decide(assets = listOf(apk(link = "http://github.com/x.apk"))))
    }

    @Test
    fun theLimitItselfIsAllowed() {
        assertTrue(decide(assets = listOf(apk(size = UpdateRules.MAX_APK_BYTES.toLong()))) is UpdateDecision.Offer)
    }
}
