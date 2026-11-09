// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** BUG MODEL-CATALOG-1: one source's model list as the last listing left it. */
data class CachedCatalog(
    /** The rows the source listed last time; null = it has never listed successfully. */
    val rows: List<ProviderModels>?,
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
 * The cache is generic: it never names a harness, a CLI or a command. Both bounds are settings ([LimitRegistry]).
 */
class ModelCatalogCache(
    /** How long a listed catalog is served as current before a refresh is started in the background. */
    private val ttlMs: () -> Long = { LimitRegistry.MODELS_CATALOG_TTL_MS.default },
    /** How long a request may wait for a source that has never listed its models. */
    private val waitMs: () -> Long = { LimitRegistry.MODELS_CATALOG_WAIT_MS.default },
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: Executor = REFRESHER,
) {
    private class Entry {
        @Volatile var rows: List<ProviderModels>? = null
        @Volatile var listedAt: Long = 0L
        @Volatile var error: String? = null

        /** The listing in flight, so a burst of requests shares one child process. Guarded by the owner's lock. */
        var listing: CompletableFuture<List<ProviderModels>?>? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val lock = Any()

    /**
     * [id]'s catalog, never waiting longer than [waitMs] for it. [build] runs on the refresh thread, at most once at a
     * time per [id], and is called again only when the cached rows are older than [ttlMs] (or a listing failed).
     */
    fun read(id: String, build: () -> List<ProviderModels>): CachedCatalog {
        val entry = entries.computeIfAbsent(id) { Entry() }
        var known: CachedCatalog? = null
        var pending: CompletableFuture<List<ProviderModels>?>? = null
        synchronized(lock) {
            val now = clock()
            val age = entry.rows?.let { now - entry.listedAt }
            val stale = age == null || age >= ttlMs()
            if (stale) pending = refresh(entry, build)
            val rows = entry.rows
            if (rows != null) known = CachedCatalog(rows, age, listing = pending.isRunning(), error = entry.error)
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
            if (rows != null) return CachedCatalog(rows, 0L, listing = false, error = null)
            // Lost the race with a listing that finished while we waited: serve it rather than an empty answer.
            val age = entry.rows?.let { clock() - entry.listedAt }
            return CachedCatalog(entry.rows, age, listing = entry.listing.isRunning(), error = entry.error)
        }
    }

    /** [future] is still running (a completed one has already stored its rows or its reason). */
    private fun CompletableFuture<*>?.isRunning(): Boolean = this != null && !isDone

    /** What the answer says about [id] beside [cached]; null when its list is current. */
    fun noteFor(id: String, cached: CachedCatalog): String? = when {
        cached.rows == null && cached.error != null -> "could not list models: ${cached.error}"
        cached.rows == null -> "$id has not listed its models yet; either wait for that listing (limit " +
            "${LimitRegistry.MODELS_CATALOG_WAIT_MS.key}) or type the model id its --model accepts"
        cached.ageMs == null -> null
        cached.listing -> "showing the list from ${ageText(cached.ageMs)} ago; refreshing now"
        cached.error != null -> "showing the list from ${ageText(cached.ageMs)} ago: the last refresh failed: ${cached.error}"
        else -> null
    }

    /** Forget every cached list (the sources changed, so their lists cannot be trusted any more). */
    fun clear() = entries.clear()

    private fun refresh(entry: Entry, build: () -> List<ProviderModels>): CompletableFuture<List<ProviderModels>?> {
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

    private fun ageText(ms: Long): String = if (ms < 60_000) "${ms / 1000}s" else "${ms / 60_000}m"

    private fun reason(e: Exception): String = (e.message ?: e::class.simpleName.orEmpty()).take(200).ifEmpty { "listing failed" }

    companion object {
        /** One shared pool: a refresh never blocks a caller, and one slow source never queues another source's refresh. */
        private val REFRESHER: Executor = Executors.newCachedThreadPool { r ->
            Thread(r, "model-catalog-refresh").apply { isDaemon = true }
        }
    }
}
