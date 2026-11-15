// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.http

/**
 * HTTP-SEAM-1 (DaemonApi 1.61.0, additive): a pure retry/backoff value. No I/O and no clock of its own: [delayMs] is a function
 * of the attempt number and one random fraction, and [Backoff.run] sleeps through a [Sleeper] the caller supplies (a fake in tests).
 *
 * - [maxAttempts] counts every try, the first included (1 = never retry).
 * - The wait after try `n` is `baseDelayMs * factor^(n-1)`, capped at [maxDelayMs], then spread by +/- [jitter] (0..1).
 * - [retryStatuses] are the HTTP statuses worth another try; a transport failure (no status) is always worth one.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 250L,
    val maxDelayMs: Long = 10_000L,
    val factor: Double = 2.0,
    val jitter: Double = 0.2,
    val retryStatuses: Set<Int> = TRANSIENT,
) {
    /** True when try number [attempt] (1-based) failed with [status] (null = no answer) and another try is allowed. */
    fun shouldRetry(attempt: Int, status: Int?): Boolean = attempt < maxAttempts && (status == null || status in retryStatuses)

    /** The wait after try [attempt] (1-based). [random] is in 0..1; 0.5 is the un-jittered middle. */
    fun delayMs(attempt: Int, random: Double = MIDDLE): Long {
        var d = baseDelayMs.toDouble()
        repeat((attempt - 1).coerceAtLeast(0)) { d = minOf(d * factor, maxDelayMs.toDouble()) }
        d = minOf(d, maxDelayMs.toDouble())
        val spread = (random.coerceIn(0.0, 1.0) - MIDDLE) * 2 * jitter.coerceIn(0.0, 1.0)
        return (d * (1 + spread)).toLong().coerceIn(0L, maxDelayMs)
    }

    companion object {
        private const val MIDDLE = 0.5

        /** Statuses that mean "try again": timeout, too early, rate limited, server errors. */
        val TRANSIENT: Set<Int> = setOf(408, 425, 429, 500, 502, 503, 504)

        /** One try, no retry. */
        val NONE = RetryPolicy(maxAttempts = 1)
    }
}

/** Waits [ms]. The seam's real one sleeps the thread; a test passes one that records. */
fun interface Sleeper {
    fun sleep(ms: Long)
}

/** Runs an operation under a [RetryPolicy]. */
object Backoff {
    /**
     * Calls [op] with the 1-based attempt number until [statusOf] says it is done or the policy gives up, sleeping
     * [RetryPolicy.delayMs] between tries. A thrown exception counts as "no status" and is retried while allowed, then rethrown.
     * Returns the last result and the attempts made.
     */
    fun <T> run(policy: RetryPolicy, sleeper: Sleeper, random: () -> Double = { 0.5 }, statusOf: (T) -> Int?, op: (attempt: Int) -> T): Pair<T, Int> {
        var attempt = 0
        while (true) {
            attempt++
            val r = try { op(attempt) } catch (e: Exception) {
                if (!policy.shouldRetry(attempt, null)) throw e
                sleeper.sleep(policy.delayMs(attempt, random())); continue
            }
            if (!policy.shouldRetry(attempt, statusOf(r))) return r to attempt
            sleeper.sleep(policy.delayMs(attempt, random()))
        }
    }
}
