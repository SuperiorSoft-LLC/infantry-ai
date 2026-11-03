// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usage

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USAGE-AUTO (contract 1.124.0): re-reads every live plan meter on its own every `usage.refreshMinutes` (default 5,
 * 0 = off), pushing the new report to clients through [onRefreshed]. One thread, one read at a time (an overlapping
 * tick is skipped); a failed read doubles the wait (up to [MAX_BACKOFF_MS]) and a good read resets it. The thread wakes
 * at most once a minute, never faster. Manual meters are never read here (they have no source).
 */
class UsageRefresher(
    private val read: () -> List<ProviderLimits>,
    private val minutes: () -> Int,
    private val onRefreshed: () -> Unit,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val running = AtomicBoolean(false)
    @Volatile private var failures = 0
    private var executor: ScheduledExecutorService? = null

    /** Consecutive failed refreshes (0 after a good one). */
    fun failureCount(): Int = failures

    /** Runs one refresh if enabled and none is in flight; returns the delay in ms until the next one. */
    fun tick(): Long {
        val every = minutes()
        if (every <= 0) { failures = 0; return IDLE_RECHECK_MS }
        if (running.compareAndSet(false, true)) {
            try {
                val results = read()
                if (results.any { isFailure(it) }) failures++ else failures = 0
                runCatching { onRefreshed() }
            } catch (e: Exception) {
                failures++
                log("[infantry] usage refresh failed: ${e::class.simpleName}")
            } finally {
                running.set(false)
            }
        }
        return delayFor(every, failures)
    }

    @Synchronized
    fun start() {
        if (executor != null) return
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "infantry-usage-refresh").apply { isDaemon = true } }
        executor = ex
        fun loop(delay: Long) {
            ex.schedule({
                val next = try { tick() } catch (e: Throwable) { IDLE_RECHECK_MS }
                if (!ex.isShutdown) loop(next)
            }, delay, TimeUnit.MILLISECONDS)
        }
        loop(IDLE_RECHECK_MS) // the first read waits a minute: the boot already reads once, lazily
    }

    @Synchronized
    fun stop() { executor?.shutdownNow(); executor = null }

    companion object {
        const val IDLE_RECHECK_MS = 60_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L

        /** [minutes] doubled once per consecutive failure, at least a minute, at most 30 minutes. */
        fun delayFor(minutes: Int, failures: Int): Long {
            val base = minutes.coerceAtLeast(1) * 60_000L
            var d = base
            repeat(failures.coerceIn(0, 12)) { d = (d * 2).coerceAtMost(maxOf(MAX_BACKOFF_MS, base)) }
            return d
        }

        /** A transient source failure (not "no credentials" or "live sources off", which a retry cannot fix). */
        fun isFailure(l: ProviderLimits): Boolean = l.reason?.let {
            it == LimitReason.NETWORK || it == LimitReason.RATE_LIMITED || it == LimitReason.BAD_RESPONSE ||
                it == LimitReason.SOURCE_FAILED || it.startsWith("http_")
        } ?: false
    }
}
