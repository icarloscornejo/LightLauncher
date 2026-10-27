package com.prototype.lightlauncher.data

import org.junit.Assert.assertTrue
import org.junit.Test

/** The stop and deadline checks run before any connection is opened, so these tests touch no network. */
class HttpFetcherStopTest {

    private val fetcher = UrlConnectionFetcher()

    @Test
    fun aStopRequestEndsTheFetchBeforeConnecting() {
        try {
            fetcher.fetch("https://invalid.example/", 1000, Long.MAX_VALUE) { true }
            throw AssertionError("expected FetchStoppedException")
        } catch (e: FetchStoppedException) {
            assertTrue(e is java.io.IOException)
        }
    }

    @Test
    fun aDeadlineThatAlreadyPassedEndsTheFetchBeforeConnecting() {
        try {
            fetcher.fetch("https://invalid.example/", 1000, System.nanoTime() - 1) { false }
            throw AssertionError("expected FetchStoppedException")
        } catch (e: FetchStoppedException) {
            // expected
        }
    }
}
