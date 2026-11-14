// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** BUG MODEL-CATALOG-1 (generalised by MCP-REGISTRY-1): one source's list as the last listing left it. */
data class CachedListing<T>(
    /** The rows the source listed last time; null = it has never listed successfully. */
    val rows: List<T>?,
    /** How old [rows] are, or null when there are none. */
    val ageMs: Long?,
    /** A listing runs right now: the first one, or the refresh of an old list. */
    val listing: Boolean,
    /** Why the last listing failed; kept until one succeeds, so a picker can say what is wrong. */
    val error: String?,
)

/**
 * BUG MODEL-CATALOG-1: the `models` answer must never wait on a harness's own CLI. Listing one can take tens of seconds
 * (`--list-models` on a cold CLI, a first-run download, a lock), the callers ask once and give up after their own bound,
 * and one slow harness used to empty the catalog for EVERY harness, because the rows are merged into one answer.
 *
 * So a source's list is cached here, per source id, and the rules are:
 *  - a source that listed before is answered AT ONCE from the cache, however old it is; a stale one is refreshed in the
 *    background ([ttlMs]) and the answer says the list is being refreshed;
 *  - a source that never listed is waited for, but only up to [waitMs]: past that the answer says the source is still
 *    listing, and the refresh keeps running so the next request has it;
 *  - a failed listing keeps the last good rows and records why (so the picker says "the refresh failed", never silently
 *    drops a list); a source that never listed reports the failure;
 *  - one listing per source at a time: a burst of requests spawns one child process, not one per request.
 *
 * The cache is generic: it never names a harness, a CLI or a command, and it holds any row type (MCP-REGISTRY-1 reuses it for
 * the MCP server lists a CLI prints). Both bounds are settings ([LimitRegistry]), passed in by the owner of each cache.
 */
open class ListingCache<T>(
    /** How long a listed catalog is served as current before a refresh is started in the background. */
    private val ttlMs: () -> Long,
    /** How long a request may wait for a source that has never listed its models. */
    private val waitMs: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = REFRESHER,
) {
    private class Entry<R> {
        @Volatile var rows: List<R>? = null
        @Volatile var listedAt: Long = 0L
        @Volatile var error: String? = null

        /** The listing in flight, so a burst of requests shares one child process. Guarded by the owner's lock. */
        var listing: CompletableFuture<List<R>?>? = null
    }

    private val entries = ConcurrentHashMap<String, Entry<T>>()
    private val lock = Any()

    /**
     * [id]'s catalog, never waiting longer than [waitMs] for it. [build] runs on the refresh thread, at most once at a
     * time per [id], and is called again only when the cached rows are older than [ttlMs] (or a listing failed).
     */
    fun read(id: String, build: () -> List<T>): CachedListing<T> {
        val entry = entries.computeIfAbsent(id) { Entry<T>() }
        var known: CachedListing<T>? = null
        var pending: CompletableFuture<List<T>?>? = null
        synchronized(lock) {
            val now = clock()
            val age = entry.rows?.let { now - entry.listedAt }
            val stale = age == null || age >= ttlMs()
            if (stale) pending = refresh(entry, build)
            val rows = entry.rows
            if (rows != null) known = CachedListing(rows, age, listing = pending.isRunning(), error = entry.error)
        }
        known?.let { return it }
        // Never listed: wait for the listing in flight, bounded. The build itself is NOT cancelled, so the answer that
        // follows it comes from the cache instead of from a new child process.
        val rows = try {
            pending?.get(waitMs(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            null
        } catch (e: Exception) {
            null
        }
        synchronized(lock) {
            if (rows != null) return CachedListing(rows, 0L, listing = false, error = null)
            // Lost the race with a listing that finished while we waited: serve it rather than an empty answer.
            val age = entry.rows?.let { clock() - entry.listedAt }
            return CachedListing(entry.rows, age, listing = entry.listing.isRunning(), error = entry.error)
        }
    }

    /** [future] is still running (a completed one has already stored its rows or its reason). */
    private fun CompletableFuture<*>?.isRunning(): Boolean = this != null && !isDone

    /** Forget every cached list (the sources changed, so their lists cannot be trusted any more). */
    fun clear() = entries.clear()

    private fun refresh(entry: Entry<T>, build: () -> List<T>): CompletableFuture<List<T>?> {
        entry.listing?.let { return it }
        val pending = CompletableFuture.supplyAsync({
            try {
                build()
            } catch (e: Exception) {
                entry.error = reason(e)
                null
            }
        }, executor)
        entry.listing = pending
        pending.whenComplete { rows, _ ->
            synchronized(lock) {
                entry.listing = null
                if (rows != null) {
                    entry.rows = rows
                    entry.listedAt = clock()
                    entry.error = null
                }
            }
        }
        return pending
    }

    /** A short age, `12s` / `3m`, for a note beside a cached answer. */
    fun ageText(ms: Long): String = if (ms < 60_000) "${ms / 1000}s" else "${ms / 60_000}m"

    private fun reason(e: Exception): String = (e.message ?: e::class.simpleName.orEmpty()).take(200).ifEmpty { "listing failed" }

    companion object {
        /** One shared pool: a refresh never blocks a caller, and one slow source never queues another source's refresh. */
        val REFRESHER: Executor = Executors.newCachedThreadPool { r ->
            Thread(r, "listing-cache-refresh").apply { isDaemon = true }
        }
    }
}
