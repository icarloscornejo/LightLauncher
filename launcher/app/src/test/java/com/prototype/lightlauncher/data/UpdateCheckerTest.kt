package com.prototype.lightlauncher.data

import com.prototype.lightlauncher.core.UpdateOffer
import com.prototype.lightlauncher.core.UpdateRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The download and its checks, with a fake network. (Reading the release itself needs org.json, which JVM tests do not have.) */
class UpdateCheckerTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val apkBytes: ByteArray = "pretend this is an apk".toByteArray()
    private val apkHash: String = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val start = "https://github.com/owner/name/releases/download/v1.1.0/LightLauncher-1.1.0.apk"
    private val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1"

    private class RecordingLogger : Logger {
        val lines: MutableList<String> = mutableListOf()
        override fun info(message: String) { lines.add(message) }
        override fun warn(message: String) { lines.add(message) }
    }

    private class FakeFetcher : HttpFetcher {
        val answers: MutableMap<String, () -> HttpResponse> = mutableMapOf()
        val requested: MutableList<String> = mutableListOf()

        fun body(url: String, bytes: ByteArray) { answers[url] = { HttpResponse(200, null, bytes) } }
        fun redirect(url: String, to: String) { answers[url] = { HttpResponse(302, to, ByteArray(0)) } }

        override fun fetch(url: String, maxBytes: Int): HttpResponse {
            requested.add(url)
            val answer: () -> HttpResponse = answers[url] ?: throw IOException("no route to $url")
            return answer()
        }
    }

    private val fetcher = FakeFetcher()
    private val logger = RecordingLogger()
    private val folder: File get() = File(temp.root, "update")
    private fun checker(): UpdateChecker = UpdateChecker(fetcher, folder, logger)
    private fun offer(sha256: String = apkHash): UpdateOffer = UpdateOffer("1.1.0", start, sha256, apkBytes.size.toLong())

    @Test
    fun theApkBehindTheRedirectIsKeptWhenItsDigestMatches() {
        fetcher.redirect(start, cdn)
        fetcher.body(cdn, apkBytes)
        val file: File? = checker().download(offer())
        assertNotNull(file)
        assertEquals(UpdateChecker.APK_NAME, file!!.name)
        assertEquals(apkBytes.toList(), file.readBytes().toList())
        assertEquals(listOf(UpdateChecker.APK_NAME), folder.list()!!.toList()) // no .part left behind
    }

    @Test
    fun aDigestInUpperCaseIsTheSameDigest() {
        fetcher.body(start, apkBytes)
        assertNotNull(checker().download(offer(sha256 = apkHash.uppercase())))
    }

    @Test
    fun aFileWithAnotherDigestIsRefusedAndNothingIsWritten() {
        fetcher.body(start, "something else".toByteArray())
        assertNull(checker().download(offer()))
        assertFalse(File(folder, UpdateChecker.APK_NAME).exists())
        assertTrue(folder.list().isNullOrEmpty())
    }

    @Test
    fun aRedirectToAnotherHostIsNeverFollowed() {
        fetcher.redirect(start, "https://evil.example/LightLauncher-1.1.0.apk")
        fetcher.body("https://evil.example/LightLauncher-1.1.0.apk", apkBytes)
        assertNull(checker().download(offer()))
        assertEquals(listOf(start), fetcher.requested) // it never connected to the other host
    }

    @Test
    fun aRedirectToPlainHttpIsNeverFollowed() {
        fetcher.redirect(start, "http://objects.githubusercontent.com/x")
        assertNull(checker().download(offer()))
        assertEquals(listOf(start), fetcher.requested)
    }

    @Test
    fun aStartingAddressThatIsNotGithubsIsNeverFetched() {
        val elsewhere = UpdateOffer("1.1.0", "https://evil.example/a.apk", apkHash, 10L)
        assertNull(checker().download(elsewhere))
        assertTrue(fetcher.requested.isEmpty())
    }

    @Test
    fun tooManyRedirectsStopTheDownload() {
        fetcher.redirect(start, cdn)
        fetcher.redirect(cdn, start)
        assertNull(checker().download(offer()))
        assertEquals(UpdateChecker.MAX_REDIRECTS + 1, fetcher.requested.size)
    }

    @Test
    fun aBodyThatIsTooLargeStopsTheDownload() {
        fetcher.answers[start] = { throw ResponseTooLargeException() }
        assertNull(checker().download(offer()))
        assertTrue(folder.list().isNullOrEmpty())
    }

    @Test
    fun aServerErrorAndALostConnectionBothFail() {
        fetcher.answers[start] = { HttpResponse(404, null, ByteArray(0)) }
        assertNull(checker().download(offer()))
        fetcher.answers.clear() // now nothing answers at all
        assertNull(checker().download(offer()))
    }

    @Test
    fun anOldFileIsReplacedAndTheLogsHoldNoAddress() {
        folder.mkdirs()
        File(folder, "old.apk").writeText("left over")
        fetcher.body(start, apkBytes)
        assertNotNull(checker().download(offer()))
        assertFalse(File(folder, "old.apk").exists())
        fetcher.answers.clear()
        checker().download(offer())
        assertTrue(logger.lines.isNotEmpty())
        for (line in logger.lines) assertFalse(line, line.contains("github") || line.contains("http"))
    }

    @Test
    fun theLimitTheUpdaterAsksForIsTheRulesLimit() {
        var asked = -1
        val capturing = object : HttpFetcher {
            override fun fetch(url: String, maxBytes: Int): HttpResponse {
                asked = maxBytes
                return HttpResponse(200, null, apkBytes)
            }
        }
        UpdateChecker(capturing, folder, logger).download(offer())
        assertEquals(UpdateRules.MAX_APK_BYTES, asked)
    }
}
