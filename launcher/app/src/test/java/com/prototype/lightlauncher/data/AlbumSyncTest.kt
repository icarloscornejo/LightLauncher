package com.prototype.lightlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlbumSyncTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val shortLink = "https://photos.app.goo.gl/fakeShort"
    private val albumPage = "https://photos.google.com/share/fakeAlbum?key=fakeKey"
    private fun photo(id: String): String = "https://lh3.googleusercontent.com/pw/$id"
    private fun page(vararg ids: String): String = ids.joinToString(" ") { "\"${photo(it)}\"" }

    private class RecordingLogger : Logger {
        val lines: MutableList<String> = mutableListOf()
        override fun info(message: String) { lines.add(message) }
        override fun warn(message: String) { lines.add(message) }
    }

    /** Answers by URL; anything unknown is a network error. Records every URL it was asked for. */
    private class FakeFetcher : HttpFetcher {
        val answers: MutableMap<String, () -> HttpResponse> = mutableMapOf()
        val requested: MutableList<String> = mutableListOf()

        fun page(url: String, body: String) { answers[url] = { HttpResponse(200, null, body.toByteArray()) } }
        fun redirect(url: String, to: String) { answers[url] = { HttpResponse(302, to, ByteArray(0)) } }
        fun photo(url: String, bytes: String = "jpeg:$url") { answers["$url=w1920-h1080"] = { HttpResponse(200, null, bytes.toByteArray()) } }

        override fun fetch(url: String, maxBytes: Int): HttpResponse {
            synchronized(requested) { requested.add(url) }
            val answer: () -> HttpResponse = answers[url] ?: throw IOException("no route to $url")
            return answer()
        }
    }

    private var now: Long = 1_000_000_000L
    private val logger = RecordingLogger()
    private val fetcher = FakeFetcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The shown set in memory, with the same version rule as the real one. */
    private class MemoryShownStore(var names: Set<String> = emptySet()) : ShownStore {
        var version: Long = 0
        override fun read(): ShownSnapshot = ShownSnapshot(names, version)
        override fun write(names: Set<String>, basedOnVersion: Long): Boolean {
            if (basedOnVersion != version) return false
            this.names = names
            return true
        }
        override fun rename(oldToNew: Map<String, String>) {
            names = names.mapTo(HashSet()) { oldToNew[it] ?: it }
            version++
        }
        override fun clear() {
            names = emptySet()
            version++
        }
    }

    private val shown = MemoryShownStore()

    private fun newSync(folder: File = temp.root): AlbumSync =
        AlbumSync(folder, fetcher, logger, { now }, scope, Dispatchers.IO, shown)

    private fun album(vararg ids: String) {
        fetcher.redirect(shortLink, albumPage)
        fetcher.page(albumPage, page(*ids))
        ids.forEach { fetcher.photo(photo(it)) }
    }

    private fun names(sync: AlbumSync): Set<String> = sync.photos().map { it.name }.toSet()

    @Test
    fun firstSyncFollowsTheShortLinkAndDownloadsEverything() = runBlocking {
        album("a1", "b2")
        val sync = newSync()
        val result = sync.syncIfStale(shortLink, "home") as SyncResult.Synced
        assertEquals(2, result.downloaded)
        assertTrue(result.complete)
        assertEquals(setOf(AlbumSync.fileName(photo("a1")), AlbumSync.fileName(photo("b2"))), names(sync))
    }

    @Test
    fun aCompleteSyncIsNotRepeatedForADayButAChangedLinkIsDueAtOnce() = runBlocking {
        album("a1")
        val sync = newSync()
        sync.syncIfStale(shortLink, "home")
        val before: Int = fetcher.requested.size
        now += AlbumSync.COMPLETE_INTERVAL_MS - 1
        assertNull(sync.syncIfStale(shortLink, "home"))
        assertEquals(before, fetcher.requested.size)
        // Another link (a theme with a corrected album) does not wait.
        fetcher.page("https://photos.google.com/share/other", page("a1"))
        assertTrue(sync.syncIfStale("https://photos.google.com/share/other", "home") is SyncResult.Synced)
        now += 2
        fetcher.requested.clear()
        assertTrue(sync.syncIfStale(shortLink, "home") is SyncResult.Synced)
    }

    @Test
    fun aPartialSyncKeepsOldPhotosAndRetriesAfterAnHour() = runBlocking {
        album("a1", "b2")
        val sync = newSync()
        sync.syncIfStale(shortLink, "home")
        // The album changes: b2 leaves, c3 arrives but its download fails.
        fetcher.page(albumPage, page("a1", "c3"))
        now += AlbumSync.COMPLETE_INTERVAL_MS
        val result = sync.syncIfStale(shortLink, "home") as SyncResult.Synced
        assertFalse(result.complete)
        assertEquals(1, result.transientFailures)
        assertTrue(AlbumSync.fileName(photo("b2")) in names(sync))
        // Within the hour nothing is fetched; after it, the sync runs again.
        fetcher.requested.clear()
        now += AlbumSync.RETRY_INTERVAL_MS - 1
        assertNull(sync.syncIfStale(shortLink, "home"))
        assertTrue(fetcher.requested.isEmpty())
        fetcher.photo(photo("c3"))
        now += 1
        val retried = sync.syncIfStale(shortLink, "home") as SyncResult.Synced
        assertTrue(retried.complete)
        assertFalse(AlbumSync.fileName(photo("b2")) in names(sync))
    }

    @Test
    fun aTruncatedListDeletesNothing() = runBlocking {
        album("keep")
        val sync = newSync()
        sync.syncIfStale(shortLink, "home")
        val many: Array<String> = Array(301) { "p$it" }
        fetcher.page(albumPage, page(*many))
        many.forEach { fetcher.photo(photo(it)) }
        now += AlbumSync.COMPLETE_INTERVAL_MS
        val result = sync.syncIfStale(shortLink, "home") as SyncResult.Synced
        assertTrue(result.truncated)
        assertFalse(result.complete)
        assertTrue(AlbumSync.fileName(photo("keep")) in names(sync))
    }

    @Test
    fun anEmptyPageKeepsEverything() = runBlocking {
        album("a1")
        val sync = newSync()
        sync.syncIfStale(shortLink, "home")
        fetcher.page(albumPage, "<html>no photos</html>")
        now += AlbumSync.COMPLETE_INTERVAL_MS
        assertEquals(SyncResult.Failed(SyncFailure.EMPTY_LISTING), sync.syncIfStale(shortLink, "home"))
        assertEquals(1, sync.photos().size)
    }

    @Test
    fun forbiddenRedirectsAreRejectedWithoutContactingTheTarget() = runBlocking {
        val sync = newSync()
        fetcher.redirect(shortLink, "https://evil.example/album")
        assertEquals(SyncResult.Failed(SyncFailure.NOT_ALLOWED), sync.syncIfStale(shortLink, "home"))
        assertFalse(fetcher.requested.any { it.contains("evil.example") })

        fetcher.redirect("https://photos.google.com/share/http", "http://photos.google.com/share/plain")
        assertEquals(SyncResult.Failed(SyncFailure.NOT_ALLOWED), newSync(temp.newFolder()).syncIfStale("https://photos.google.com/share/http", "home"))
        assertFalse(fetcher.requested.any { it.startsWith("http://") })

        fetcher.redirect("https://photos.google.com/share/a", "https://photos.google.com/share/b")
        fetcher.redirect("https://photos.google.com/share/b", "https://photos.google.com/share/a")
        assertEquals(SyncResult.Failed(SyncFailure.TOO_MANY_REDIRECTS), newSync(temp.newFolder()).syncIfStale("https://photos.google.com/share/a", "home"))
    }

    @Test
    fun aPhotoRedirectedOffTheImageServerIsNotDownloaded() = runBlocking {
        fetcher.page(albumPage, page("x1"))
        fetcher.answers["${photo("x1")}=w1920-h1080"] = { HttpResponse(302, "https://evil.example/x1.jpg", ByteArray(0)) }
        val result = newSync().syncIfStale(albumPage, "home") as SyncResult.Synced
        assertEquals(1, result.permanentFailures)
        assertFalse(fetcher.requested.any { it.contains("evil.example") })
    }

    @Test
    fun eachKindOfFailureWaitsAnHourBeforeTryingAgain() = runBlocking {
        val sync = newSync()
        // Network error before the listing.
        assertEquals(SyncResult.Failed(SyncFailure.NETWORK_ERROR), sync.syncIfStale(shortLink, "home"))
        fetcher.requested.clear()
        now += AlbumSync.RETRY_INTERVAL_MS - 1
        assertNull(sync.syncIfStale(shortLink, "home"))
        assertTrue(fetcher.requested.isEmpty())
        // A not allowed link is also only retried after the hour.
        val notAllowed = "photos.app.goo.gl/missingScheme"
        assertEquals(SyncResult.Failed(SyncFailure.NOT_ALLOWED), sync.syncIfStale(notAllowed, "home"))
        now += 1
        assertNull(sync.syncIfStale(notAllowed, "home"))
        now += AlbumSync.RETRY_INTERVAL_MS
        album("a1")
        assertTrue(sync.syncIfStale(shortLink, "home") is SyncResult.Synced)
    }

    @Test
    fun aFailedLinkFollowedByAnotherLinkFetchesAtOnce() = runBlocking {
        val sync = newSync()
        assertTrue(sync.syncIfStale("https://photos.google.com/share/broken", "home") is SyncResult.Failed)
        fetcher.requested.clear()
        album("a1")
        assertTrue(sync.syncIfStale(shortLink, "home") is SyncResult.Synced)
        assertTrue(fetcher.requested.isNotEmpty())
    }

    @Test
    fun twoSyncsAtOnceFetchThePageOnce() = runBlocking {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        fetcher.page(albumPage, page("a1"))
        fetcher.photo(photo("a1"))
        val slowPage: () -> HttpResponse = fetcher.answers.getValue(albumPage)
        fetcher.answers[albumPage] = {
            inside.countDown()
            release.await(5, TimeUnit.SECONDS)
            slowPage()
        }
        val sync = newSync()
        val first = async(Dispatchers.IO) { sync.syncIfStale(albumPage, "home") }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        // While the first one is stuck on the network, other work still runs, and a second sync bows out.
        val otherWork: Int = withTimeout(2_000) { async(Dispatchers.Default) { 21 * 2 }.await() }
        assertEquals(42, otherWork)
        assertNull(sync.syncIfStale(albumPage, "dream"))
        release.countDown()
        assertTrue(first.await() is SyncResult.Synced)
        assertEquals(1, fetcher.requested.count { it == albumPage })
    }

    @Test
    fun logsNeverCarryTheLinkOrRawMessages() = runBlocking {
        val sentinel = "SENTINELzz9"
        fetcher.answers["https://photos.app.goo.gl/$sentinel"] = { throw IOException("failed to reach https://photos.app.goo.gl/$sentinel") }
        val sync = newSync()
        sync.syncIfStale("https://photos.app.goo.gl/$sentinel", "home")
        sync.syncIfStale("photos.app.goo.gl/$sentinel", "dream")
        sync.syncIfStale("", "home")
        assertTrue(logger.lines.isNotEmpty())
        assertFalse(logger.lines.any { it.contains(sentinel) || it.contains("photos.app.goo.gl") })
    }

    @Test
    fun syncNowRunsEvenWhenTheLastSyncWasJustNow() = runBlocking {
        album("a1")
        val sync = newSync()
        sync.syncIfStale(shortLink, "home")
        now += 1
        assertNull(sync.syncIfStale(shortLink, "home"))
        assertTrue(sync.syncNow({ shortLink }, "settings") is SyncResult.Synced)
        assertNull(sync.syncNow({ "" }, "settings"))
    }

    @Test
    fun clearTakesAwayThePhotosTheRecordAndTheShownSet() = runBlocking {
        album("a1", "b2")
        val sync = newSync()
        sync.syncIfStale(shortLink, "home")
        shown.names = setOf("seen.jpg")
        File(temp.root, "half.jpg.part").writeText("x")
        sync.clear()
        assertTrue(sync.photos().isEmpty())
        assertFalse(File(temp.root, ".attempt").exists())
        assertFalse(File(temp.root, "half.jpg.part").exists())
        assertTrue(shown.names.isEmpty())
        assertEquals(AlbumStatus(0, null), sync.status())
    }

    @Test
    fun theStatusCountsThePhotosAndSaysWhenTheLastSyncWas() = runBlocking {
        album("a1", "b2")
        val sync = newSync()
        assertEquals(AlbumStatus(0, null), sync.status())
        sync.syncIfStale(shortLink, "home")
        assertEquals(AlbumStatus(2, now), sync.status())
    }

    /** A first sync stuck on the network, while the owner changes the link and asks for another sync. */
    private suspend fun syncWhileAnotherIsStuck(secondLink: () -> String, beforeRelease: () -> Unit): SyncResult? {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        album("a1")
        fetcher.page("https://photos.google.com/share/second", page("b2"))
        fetcher.photo(photo("b2"))
        val slowPage: () -> HttpResponse = fetcher.answers.getValue(albumPage)
        fetcher.answers[albumPage] = {
            inside.countDown()
            release.await(5, TimeUnit.SECONDS)
            slowPage()
        }
        val sync = newSync()
        val first = CoroutineScope(Dispatchers.IO).async { sync.syncIfStale(shortLink, "home") }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val second = CoroutineScope(Dispatchers.IO).async { sync.syncNow(secondLink, "settings") }
        beforeRelease()
        release.countDown()
        first.await()
        return second.await()
    }

    @Test
    fun aLinkSavedDuringASyncIsSyncedRightAfterIt() = runBlocking {
        var link = shortLink
        val result = syncWhileAnotherIsStuck({ link }, { link = "https://photos.google.com/share/second" })
        assertTrue(result is SyncResult.Synced)
        assertTrue(fetcher.requested.contains("https://photos.google.com/share/second"))
    }

    @Test
    fun anAlbumRemovedWhileASyncWaitsIsNotDownloadedAgain() = runBlocking {
        var link = shortLink
        val result = syncWhileAnotherIsStuck({ link }, { link = "" })
        assertNull(result)
        assertFalse(fetcher.requested.contains("https://photos.google.com/share/second"))
        // The sync that was already running keeps what it had; the removal itself is clear(), under the same lock.
        assertEquals(1, fetcher.requested.count { it == albumPage })
    }

    @Test
    fun undeletablePhotosAreReported() = runBlocking {
        album("a1", "gone")
        val folder: File = temp.newFolder("locked")
        val sync = newSync(folder)
        sync.syncIfStale(shortLink, "home")
        fetcher.page(albumPage, page("a1"))
        now += AlbumSync.COMPLETE_INTERVAL_MS
        folder.setWritable(false)
        // An existing file in a locked folder can still be overwritten: lock the record itself too.
        File(folder, AlbumSync.ATTEMPT_FILE).setWritable(false)
        // Running as root (some CI machines) ignores the permission; the check means nothing there.
        assumeFalse(File(folder, "probe").let { probe -> runCatching { probe.createNewFile() }.getOrDefault(false).also { if (it) probe.delete() } })
        val result = sync.syncIfStale(shortLink, "home") as SyncResult.Synced
        folder.setWritable(true)
        assertEquals(1, result.deleteErrors)
        assertTrue(result.attemptRecordError)
    }

    @Test
    fun oldNamedPhotosAreRenamedWithoutDownloadingAndTheRoundIsTranslated() = runBlocking {
        album("a1", "b2")
        val oldA = File(temp.root, AlbumSync.legacyFileName(photo("a1"))).apply { writeText("old photo a1") }
        val oldB = File(temp.root, AlbumSync.legacyFileName(photo("b2"))).apply { writeText("old photo b2") }
        shown.names = setOf(oldA.name)
        val before: Long = shown.version
        val sync = newSync()
        val result = sync.syncIfStale(shortLink, "home") as SyncResult.Synced
        assertEquals(0, result.downloaded)
        assertFalse(fetcher.requested.any { it.contains("lh3.googleusercontent.com") })
        assertEquals(setOf(AlbumSync.fileName(photo("a1")), AlbumSync.fileName(photo("b2"))), names(sync))
        assertEquals("old photo a1", File(temp.root, AlbumSync.fileName(photo("a1"))).readText())
        assertFalse(oldA.exists() || oldB.exists())
        assertEquals(setOf(AlbumSync.fileName(photo("a1"))), shown.names)
        assertTrue(shown.version > before)
    }

    @Test
    fun linksThatCollideOnTheOldNameAreDownloadedSeparately() = runBlocking {
        // "Aa" and "BB" have the same String.hashCode, so these two links would share one file under that name.
        val first = "collideAa"
        val second = "collideBB"
        assertEquals(AlbumSync.legacyFileName(photo(first)), AlbumSync.legacyFileName(photo(second)))
        fetcher.page(albumPage, page(first, second))
        fetcher.photo(photo(first), "content one")
        fetcher.photo(photo(second), "content two")
        File(temp.root, AlbumSync.legacyFileName(photo(first))).writeText("whichever came first")
        val sync = newSync()
        val result = sync.syncIfStale(albumPage, "home") as SyncResult.Synced
        assertEquals(2, result.downloaded)
        assertEquals("content one", File(temp.root, AlbumSync.fileName(photo(first))).readText())
        assertEquals("content two", File(temp.root, AlbumSync.fileName(photo(second))).readText())
        // The complete sync then removes the ambiguous old file.
        assertEquals(setOf(AlbumSync.fileName(photo(first)), AlbumSync.fileName(photo(second))), names(sync))
    }

    @Test
    fun anOldNamesWriteDuringTheMigrationDoesNotUndoTheTranslation() = runBlocking {
        album("a1")
        val oldName: String = AlbumSync.legacyFileName(photo("a1"))
        File(temp.root, oldName).writeText("old photo")
        shown.names = setOf(oldName)
        val sessionSnapshot: ShownSnapshot = shown.read() // a running screensaver read the set before the sync
        newSync().syncIfStale(shortLink, "dream")
        // Its next write still carries the old name: refused, the session has to re-read.
        assertFalse(shown.write(setOf(oldName), sessionSnapshot.version))
        assertEquals(setOf(AlbumSync.fileName(photo("a1"))), shown.names)
    }
}
