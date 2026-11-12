// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md section 5): runs every call into ONE plugin on that plugin's own threads, bounded
 * by a wall-clock timeout and a thread-CPU budget, with its exceptions caught. The first throw or overrun DISABLES the plugin
 * ([disabledReason], logged once); a disabled plugin is never called again and each caller applies its hook's fail mode.
 * The daemon keeps running whatever the plugin does.
 */
class PluginGuard(
    val moduleId: String,
    private val classLoader: ClassLoader?,
    private val cpuMsPerCall: Long,
    private val log: (String) -> Unit,
    /** Told once, when the plugin is disabled (the host unregisters what it can and stops it). */
    private val onDisabled: (String) -> Unit = {},
    /** BOARD-DOWN-1: consecutive wall-clock timeouts tolerated before the plugin is disabled (1 = the old first-overrun rule). */
    private val timeoutStrikes: Int = 3,
    /** GUARD-1: the hard wall-clock ceiling for a call with no result (a true hang); [call]'s timeout is only the "slow" mark. */
    private val hangCeilingMs: Long = 30_000,
    /** BOARD-STALL-2: worker threads for this plugin (limits.plugins.poolSize); calls beyond it queue. */
    private val poolSize: Int = 8,
    /** BOARD-STALL-2: calls allowed to wait for a worker (limits.plugins.queueLimit); a full queue is refused at once. */
    private val queueLimit: Int = 256,
    /** PLUGIN-RECOVER-1: how long the circuit stays open after [timeoutStrikes] timeouts; 0 = probe at once. */
    private val cooldownMs: Long = 30_000,
    /** Told once when the breaker opens (not per refused call). */
    private val onTripped: (String) -> Unit = {},
    /** Told once when a probe succeeds and the breaker closes. */
    private val onRecovered: () -> Unit = {},
    /** BOARD-HANG-1: a recovery probe is cut off after this (at most the hang ceiling), so a hung plugin re-arms the breaker fast. */
    probeMs: Long = 10_000,
    /**
     * BOARD-COOLDOWN-STUCK-1: the cheap call run on this plugin's own pool when the cooldown ends (half-open). It closes the
     * breaker before the waiting caller's call runs, so recovery never depends on that call (a full board read under memory
     * pressure) beating the probe limit. The default is a no-op round trip: it proves a worker thread picks up and returns.
     */
    private val healthProbe: () -> Unit = {},
) {
    /** PLUGIN-RECOVER-1 breaker: nanoTime the circuit reopens for a probe. */
    @Volatile private var openUntilNs = 0L
    @Volatile private var tripped = false
    /** BOARD-COOLDOWN-STUCK-1: wall-clock ms the current open episode began, and how often it (re)opened (trip + failed probes). */
    @Volatile private var openedAtMs = 0L
    @Volatile private var openStrike = 0
    private val probing = java.util.concurrent.atomic.AtomicBoolean()
    val cooling: Boolean get() = tripped

    private val consecutiveTimeouts = AtomicInteger()
    @Volatile var disabledReason: String? = null
        private set
    val disabled: Boolean get() = disabledReason != null

    private val threads = AtomicInteger()
    private val pool: ExecutorService = ThreadPoolExecutor(
        poolSize.coerceAtLeast(1), poolSize.coerceAtLeast(1), 30, TimeUnit.SECONDS, LinkedBlockingQueue(queueLimit.coerceAtLeast(0).coerceAtLeast(1)),
        { r -> Thread(r, "plugin-$moduleId-${threads.incrementAndGet()}").apply { isDaemon = true; contextClassLoader = classLoader } },
    ).apply { allowCoreThreadTimeOut(true) }
    private val cpu = ManagementFactory.getThreadMXBean()
    private val pollMs = 50L
    private val probeCeilingMs = minOf(hangCeilingMs, probeMs)

    /** Blocking form for plugin-internal threads; daemon request paths use [callSuspending]. */
    fun <T> call(what: String, timeoutMs: Long, block: () -> T): Result<T> = runBlocking { callSuspending(what, timeoutMs, block) }

    /**
     * [block] under the limits: its value, or a failure (the plugin is then disabled; a disabled plugin fails at once).
     * BOARD-STALL-2: the caller SUSPENDS while it waits (no thread held), the plugin runs on its own bounded pool, and a
     * full queue fails at once with a "busy" refusal (no strike, the plugin stays loaded).
     */
    suspend fun <T> callSuspending(what: String, timeoutMs: Long, block: () -> T): Result<T> {
        disabledReason?.let { return Result.failure(IllegalStateException("plugin $moduleId is disabled: $it")) }
        if (retired) return Result.failure(IllegalStateException("plugin $moduleId is unloaded; $what refused"))
        if (tripped) {
            val leftMs = (openUntilNs - System.nanoTime()) / 1_000_000
            if (leftMs > 0) return Result.failure(IllegalStateException("plugin $moduleId ${coolingText()}, retry in ${(leftMs + 999) / 1000}s; $what refused"))
            if (!probing.compareAndSet(false, true)) return Result.failure(IllegalStateException("plugin $moduleId ${coolingText()} (probe in flight), retry in 1s; $what refused"))
            try {
                if (tripped) {
                    // BOARD-COOLDOWN-STUCK-1: half-open runs the cheap health probe, never the caller's own (maybe heavy) call.
                    log("[plugins] daemon plugin $moduleId breaker open -> half-open: health probe (cut off at $probeCeilingMs ms)")
                    val health = guarded("health probe", probeCeilingMs, { healthProbe() }, probeCeilingMs, probe = true)
                    if (health.isFailure) {
                        val why = health.exceptionOrNull()?.message?.lineSequence()?.firstOrNull().orEmpty()
                        reopen("health probe failed: $why")
                        return Result.failure(IllegalStateException("plugin $moduleId ${coolingText()}, health probe failed ($why); $what refused"))
                    }
                }
            } finally { probing.set(false) }
        }
        return guarded(what, timeoutMs, block)
    }

    /** BOARD-COOLDOWN-STUCK-1: "cooling down since HH:MM (strike n)", the reason every refused call carries. */
    private fun coolingText(): String {
        val since = java.time.Instant.ofEpochMilli(openedAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "is cooling down since %02d:%02d (strike %d)".format(since.hour, since.minute, openStrike)
    }

    private suspend fun <T> guarded(what: String, timeoutMs: Long, block: () -> T, ceilingOverrideMs: Long? = null, probe: Boolean = false): Result<T> {
        val worker = java.util.concurrent.atomic.AtomicReference<Thread?>()
        val future = try {
            pool.submit(Callable {
                worker.set(Thread.currentThread())
                val start = runCatching { cpu.currentThreadCpuTime }.getOrDefault(-1L)
                val value = block()
                if (start >= 0) {
                    val usedMs = (cpu.currentThreadCpuTime - start) / 1_000_000
                    if (usedMs > cpuMsPerCall) throw PluginLimitException("$what used $usedMs ms of CPU (limit $cpuMsPerCall ms)")
                }
                value
            })
        } catch (e: RejectedExecutionException) {
            return Result.failure(IllegalStateException("plugin $moduleId is busy ($poolSize running, $queueLimit queued); $what refused"))
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return try {
            Result.success(await(future, worker, what, timeoutMs, ceilingOverrideMs)).also { consecutiveTimeouts.set(0); recover(what) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // BOARD-STALL-3: the caller gave up (its own timeout/cancel): drop the job so a queued one never runs later.
            future.cancel(true)
            throw e
        } catch (e: QueuedTooLongException) {
            // BOARD-BREAKER-1: the call never started (every worker busy with other calls): a backlog, not a hang. No strike.
            future.cancel(true)
            log("[plugins] daemon plugin $moduleId: ${e.message} (busy, no strike)")
            Result.failure(IllegalStateException("plugin $moduleId is busy: ${e.message}; $what refused"))
        } catch (e: RunawayException) {
            future.cancel(true)
            disable(e.message ?: "$what ran away")
            Result.failure(e)
        } catch (e: TimeoutException) {
            future.cancel(true)
            // BOARD-DOWN-1: a slow call (a DB lock, a git call) fails only itself; a plugin that keeps timing out is disabled.
            val n = consecutiveTimeouts.incrementAndGet()
            // BOARD-COOLDOWN-STUCK-1: once open, only a failed probe re-arms the window (the caller handles that). A call that
            // began before the trip and times out late must not push the window out again, or a burst of hung calls keeps the
            // breaker open for hours ("retry in 18s, 7s, 26s...").
            if (probe) Unit
            else if (tripped) log("[plugins] daemon plugin $moduleId: $what timed out after $timeoutMs ms while the breaker is open (window not extended)")
            else if (n >= timeoutStrikes) trip("$what timed out after $timeoutMs ms ($n in a row)")
            else log("[plugins] daemon plugin $moduleId: $what timed out after $timeoutMs ms (strike $n of $timeoutStrikes; plugin stays loaded)")
            Result.failure(e)
        } catch (e: ExecutionException) {
            consecutiveTimeouts.set(0)
            val cause = e.cause ?: e
            // PLUGIN-GUARD-1: a bad argument is the CALLER's error: it fails this call only and never disables the plugin.
            if (cause is IllegalArgumentException) {
                log("[plugins] daemon plugin $moduleId: $what rejected its arguments: ${cause.message?.lineSequence()?.firstOrNull().orEmpty()} (plugin stays loaded)")
                return Result.failure(cause)
            }
            // BOARD-COOLDOWN-STUCK-1: a throwing health probe only keeps the breaker open; the caller re-arms the window.
            if (probe) return Result.failure(cause)
            disable("$what threw ${cause::class.java.simpleName}: ${cause.message?.lineSequence()?.firstOrNull().orEmpty()}")
            Result.failure(cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(e)
        }
    }

    /**
     * GUARD-1: the wall clock [timeoutMs] only marks a call SLOW (one warning line, never a strike: a loaded host stretches
     * milliseconds into seconds). A strike needs a true hang: no result by the hang ceiling. A call whose own thread burns more
     * than [cpuMsPerCall] of CPU is a runaway and disables the plugin at once, wherever the wall clock stands.
     */
    private suspend fun <T> await(future: java.util.concurrent.Future<T>, worker: java.util.concurrent.atomic.AtomicReference<Thread?>, what: String, timeoutMs: Long, ceilingOverrideMs: Long? = null): T {
        val queuedAt = System.nanoTime()
        val ceilingMs = ceilingOverrideMs ?: maxOf(hangCeilingMs, timeoutMs)
        var warned = false
        var base = -1L
        // BOARD-BREAKER-1: the clock starts when a worker picks the call up. Time spent queued behind other calls (a burst of
        // reads larger than the pool) is the PLUGIN being busy, not THIS call hanging: it must never count as a strike, or a
        // burst of fast calls trips the breaker. A call still queued at the ceiling fails alone as busy.
        var began = -1L
        while (true) {
            if (future.isDone) return future.get()
            delay(pollMs)
            val t = worker.get()
            if (t == null) {
                val queuedMs = (System.nanoTime() - queuedAt) / 1_000_000
                if (queuedMs >= ceilingMs) throw QueuedTooLongException("$what waited $queuedMs ms in the queue without starting (pool $poolSize)")
                continue
            }
            if (began < 0) began = System.nanoTime()
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            if (t != null && cpu.isThreadCpuTimeSupported) {
                val c = runCatching { cpu.getThreadCpuTime(t.id) }.getOrDefault(-1L)
                if (c >= 0) {
                    if (base < 0) base = c
                    val usedMs = (c - base) / 1_000_000
                    if (usedMs > cpuMsPerCall) throw RunawayException("$what is spinning: $usedMs ms of CPU (limit $cpuMsPerCall ms)")
                }
            }
            if (elapsedMs >= ceilingMs) throw TimeoutException("$what made no result in $elapsedMs ms (hang ceiling $ceilingMs ms)")
            if (!warned && elapsedMs >= timeoutMs) {
                warned = true
                log("[plugins] daemon plugin $moduleId: $what is slow ($elapsedMs ms, host busy?); still waiting up to $ceilingMs ms, no strike")
            }
        }
    }

    @Synchronized
    private fun trip(reason: String) {
        if (tripped) return
        openUntilNs = System.nanoTime() + cooldownMs * 1_000_000
        openedAtMs = System.currentTimeMillis()
        openStrike = 1
        tripped = true
        log("[plugins] daemon plugin $moduleId breaker closed -> open (strike 1, cooling down ${cooldownMs / 1000}s, then a health probe): $reason")
        runCatching { onTripped(reason) }
    }

    /** BOARD-COOLDOWN-STUCK-1: a failed health probe opens the window again (no second notice; the episode goes on). */
    @Synchronized
    private fun reopen(reason: String) {
        if (!tripped) return
        openUntilNs = System.nanoTime() + cooldownMs * 1_000_000
        openStrike++
        log("[plugins] daemon plugin $moduleId breaker half-open -> open (strike $openStrike, cooling down ${cooldownMs / 1000}s): $reason")
    }

    @Synchronized
    private fun recover(what: String) {
        if (!tripped) return
        tripped = false
        openUntilNs = 0
        consecutiveTimeouts.set(0)
        log("[plugins] daemon plugin $moduleId breaker half-open -> closed: $what answered (open since ${coolingText().substringAfter("since ")})")
        runCatching { onRecovered() }
    }

    /** PLUGIN-HOTLOAD-1: true once [retire] ran: the plugin was unloaded on purpose, so nothing it does afterwards is a failure. */
    @Volatile var retired = false
        private set

    /**
     * PLUGIN-HOTLOAD-1: ends this guard for a planned unload. New calls are refused at once; calls already running get up to
     * [drainMs] to finish, then are interrupted. A call that fails because of this (an interrupt, a closed class loader) does NOT
     * disable the plugin or announce a failure: the plugin is leaving anyway.
     */
    fun retire(drainMs: Long) {
        retired = true
        pool.shutdown()
        val drained = try { pool.awaitTermination(drainMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!drained) {
            log("[plugins] daemon plugin $moduleId: a call was still running ${drainMs} ms after unload began; interrupting it")
            pool.shutdownNow()
        }
    }

    @Synchronized
    fun disable(reason: String) {
        if (disabledReason != null || retired) return
        disabledReason = reason
        log("[plugins] daemon plugin $moduleId DISABLED: $reason")
        runCatching { onDisabled(reason) }
    }

    fun shutdown() { pool.shutdownNow() }
}

class PluginLimitException(message: String) : RuntimeException(message)

private class RunawayException(message: String) : RuntimeException(message)

private class QueuedTooLongException(message: String) : RuntimeException(message)
