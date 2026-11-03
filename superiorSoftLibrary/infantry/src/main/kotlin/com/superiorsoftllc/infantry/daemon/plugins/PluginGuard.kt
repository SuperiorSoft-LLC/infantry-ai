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
) {
    /** PLUGIN-RECOVER-1 breaker: nanoTime the circuit reopens for a probe. */
    @Volatile private var openUntilNs = 0L
    @Volatile private var tripped = false
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

    /** Blocking form for plugin-internal threads; daemon request paths use [callSuspending]. */
    fun <T> call(what: String, timeoutMs: Long, block: () -> T): Result<T> = runBlocking { callSuspending(what, timeoutMs, block) }

    /**
     * [block] under the limits: its value, or a failure (the plugin is then disabled; a disabled plugin fails at once).
     * BOARD-STALL-2: the caller SUSPENDS while it waits (no thread held), the plugin runs on its own bounded pool, and a
     * full queue fails at once with a "busy" refusal (no strike, the plugin stays loaded).
     */
    suspend fun <T> callSuspending(what: String, timeoutMs: Long, block: () -> T): Result<T> {
        disabledReason?.let { return Result.failure(IllegalStateException("plugin $moduleId is disabled: $it")) }
        var probe = false
        if (tripped) {
            val leftMs = (openUntilNs - System.nanoTime()) / 1_000_000
            if (leftMs > 0) return Result.failure(IllegalStateException("plugin $moduleId is cooling down, retry in ${(leftMs + 999) / 1000}s; $what refused"))
            if (!probing.compareAndSet(false, true)) return Result.failure(IllegalStateException("plugin $moduleId is cooling down (probe in flight), retry in 1s; $what refused"))
            probe = true
        }
        try { return guarded(what, timeoutMs, block) } finally { if (probe) probing.set(false) }
    }

    private suspend fun <T> guarded(what: String, timeoutMs: Long, block: () -> T): Result<T> {
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
            Result.success(await(future, worker, what, timeoutMs)).also { consecutiveTimeouts.set(0); recover() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // BOARD-STALL-3: the caller gave up (its own timeout/cancel): drop the job so a queued one never runs later.
            future.cancel(true)
            throw e
        } catch (e: RunawayException) {
            future.cancel(true)
            disable(e.message ?: "$what ran away")
            Result.failure(e)
        } catch (e: TimeoutException) {
            future.cancel(true)
            // BOARD-DOWN-1: a slow call (a DB lock, a git call) fails only itself; a plugin that keeps timing out is disabled.
            val n = consecutiveTimeouts.incrementAndGet()
            if (n >= timeoutStrikes || tripped) trip("$what timed out after $timeoutMs ms ($n in a row)")
            else log("[plugins] daemon plugin $moduleId: $what timed out after $timeoutMs ms (strike $n of $timeoutStrikes; plugin stays loaded)")
            Result.failure(e)
        } catch (e: ExecutionException) {
            consecutiveTimeouts.set(0)
            val cause = e.cause ?: e
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
    private suspend fun <T> await(future: java.util.concurrent.Future<T>, worker: java.util.concurrent.atomic.AtomicReference<Thread?>, what: String, timeoutMs: Long): T {
        val began = System.nanoTime()
        val ceilingMs = maxOf(hangCeilingMs, timeoutMs)
        var warned = false
        var base = -1L
        while (true) {
            if (future.isDone) return future.get()
            delay(pollMs)
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            val t = worker.get()
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
        openUntilNs = System.nanoTime() + cooldownMs * 1_000_000
        if (tripped) return
        tripped = true
        log("[plugins] daemon plugin $moduleId TRIPPED (cooling down ${cooldownMs / 1000}s, then probes): $reason")
        runCatching { onTripped(reason) }
    }

    @Synchronized
    private fun recover() {
        if (!tripped) return
        tripped = false
        openUntilNs = 0
        log("[plugins] daemon plugin $moduleId recovered after a successful probe")
        runCatching { onRecovered() }
    }

    @Synchronized
    fun disable(reason: String) {
        if (disabledReason != null) return
        disabledReason = reason
        log("[plugins] daemon plugin $moduleId DISABLED: $reason")
        runCatching { onDisabled(reason) }
    }

    fun shutdown() { pool.shutdownNow() }
}

class PluginLimitException(message: String) : RuntimeException(message)

private class RunawayException(message: String) : RuntimeException(message)
