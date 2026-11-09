// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import com.superiorsoftllc.infantry.protocol.ProviderModels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG MODEL-CATALOG-1: the cache every `models` answer reads a harness's list through. A listing is a child process
 * away, so a request must never wait on it for longer than `limits.models.catalogWaitMs`, and a list that was listed
 * before is served at once (with its age) while a refresh runs behind it.
 */
class ModelCatalogCacheTest {

    private fun rows(vararg ids: String) = listOf(ProviderModels("pi", "acme", "acme", ids.toList()))

    @Test
    fun aFirstListingIsWaitedForOnlyUpToTheBoundsAndThenKeepsRunning() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val cache = ModelCatalogCache(ttlMs = { 60_000 }, waitMs = { 150 })
        val first = cache.read("harness:pi") {
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
            rows("acme/m1")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the listing started")
        assertNull(first.rows, "a slow listing must not hold the answer")
        assertTrue(first.listing, "the answer says the source is still listing")
        assertNull(first.error, "still listing is not a failure")
        val note = cache.noteFor("pi", first)
        assertTrue("pi" in note.orEmpty() && "model" in note.orEmpty(), "the note names the source: $note")

        // The listing was NOT cancelled: once it finishes the next read serves it, without listing again.
        release.countDown()
        val second = awaitRows(cache, "harness:pi") { rows("acme/never") }
        assertEquals(listOf("acme/m1"), second.rows?.flatMap { it.models })
        assertNull(second.error)
    }

    @Test
    fun aListedSourceIsAnsweredAtOnceAndAStaleOneIsRefreshedBehindTheAnswer() {
        val calls = AtomicInteger()
        val refreshed = CountDownLatch(1)
        var now = 1_000L
        val cache = ModelCatalogCache(ttlMs = { 100 }, waitMs = { 5_000 }, clock = { now })
        val first = cache.read("harness:pi") { calls.incrementAndGet(); rows("acme/m1") }
        assertEquals(listOf("acme/m1"), first.rows?.flatMap { it.models })
        assertNull(cache.noteFor("pi", first), "a current list carries no note")

        // Still current: no second listing.
        now += 50
        val current = cache.read("harness:pi") { calls.incrementAndGet(); rows("acme/m2") }
        assertEquals(listOf("acme/m1"), current.rows?.flatMap { it.models })
        assertEquals(1, calls.get(), "a current list is not listed again")

        // Past the TTL the OLD list is answered at once and a refresh runs behind it.
        now += 200
        val stale = cache.read("harness:pi") {
            calls.incrementAndGet()
            refreshed.countDown()
            rows("acme/m2")
        }
        assertEquals(listOf("acme/m1"), stale.rows?.flatMap { it.models }, "the last good list is served at once")
        assertTrue(stale.listing)
        assertEquals("showing the list from 0s ago; refreshing now", cache.noteFor("pi", stale))
        assertTrue(refreshed.await(5, TimeUnit.SECONDS))
        val fresh = awaitRows(cache, "harness:pi") { calls.incrementAndGet(); rows("acme/m3") }
        assertEquals(listOf("acme/m2"), fresh.rows?.flatMap { it.models })
    }

    @Test
    fun aFailedRefreshKeepsTheLastGoodListAndSaysWhy() {
        var now = 1_000L
        var fail = false
        val cache = ModelCatalogCache(ttlMs = { 100 }, waitMs = { 5_000 }, clock = { now })
        assertEquals(listOf("acme/m1"), cache.read("harness:pi") { rows("acme/m1") }.rows?.flatMap { it.models })
        now += 500
        fail = true
        val failing = cache.read("harness:pi") { if (fail) error("the child exited 7") else rows("acme/m2") }
        assertEquals(listOf("acme/m1"), failing.rows?.flatMap { it.models }, "a failure never drops a list the picker had")
        val noted = awaitNote(cache, "harness:pi") { if (fail) error("the child exited 7") else rows("acme/m2") }
        assertTrue("the last refresh failed: the child exited 7" in noted, noted)
    }

    @Test
    fun aSourceThatNeverListedReportsTheFailureThatNamedTheCommand() {
        val cache = ModelCatalogCache(ttlMs = { 60_000 }, waitMs = { 5_000 })
        val cached = cache.read("harness:pi") { error("pi --list-models exited 1") }
        assertNull(cached.rows)
        assertTrue(!cached.listing)
        assertEquals("could not list models: pi --list-models exited 1", cache.noteFor("pi", cached))
    }

    @Test
    fun aBurstOfRequestsSpawnsOneListing() {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        val cache = ModelCatalogCache(ttlMs = { 60_000 }, waitMs = { 100 })
        val build = {
            calls.incrementAndGet()
            release.await(20, TimeUnit.SECONDS)
            rows("acme/m1")
        }
        repeat(4) { cache.read("harness:pi", build) }
        assertEquals(1, calls.get(), "one source, one listing at a time")
        release.countDown()
        assertEquals(listOf("acme/m1"), awaitRows(cache, "harness:pi", build).rows?.flatMap { it.models })
    }

    /** Readers race the refresh thread: poll the cache (bounded) until the listing has landed. */
    private fun awaitRows(
        cache: ModelCatalogCache,
        id: String,
        build: () -> List<ProviderModels>,
    ): CachedCatalog {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            val cached = cache.read(id, build)
            if (cached.rows != null && !cached.listing) return cached
            Thread.sleep(10)
        }
        error("the cached list never landed")
    }

    /** Polls until the note appears, and returns the note (the refresh has to run first). */
    private fun awaitNote(cache: ModelCatalogCache, id: String, build: () -> List<ProviderModels>): String {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            val cached = cache.read(id, build)
            val note = cache.noteFor(id, cached)
            if (cached.rows != null && !cached.listing && note != null) return note
            Thread.sleep(10)
        }
        error("the failure note never appeared")
    }
}
