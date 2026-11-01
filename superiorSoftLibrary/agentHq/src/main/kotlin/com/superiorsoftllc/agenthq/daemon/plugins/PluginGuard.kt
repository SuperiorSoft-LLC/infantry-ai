package com.superiorsoftllc.agenthq.daemon.plugins

import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
) {
    private val consecutiveTimeouts = AtomicInteger()
    @Volatile var disabledReason: String? = null
        private set
    val disabled: Boolean get() = disabledReason != null

    private val threads = AtomicInteger()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "plugin-$moduleId-${threads.incrementAndGet()}").apply { isDaemon = true; contextClassLoader = classLoader }
    }
    private val cpu = ManagementFactory.getThreadMXBean()
    private val pollMs = 50L

    /** [block] under the limits: its value, or a failure (the plugin is then disabled; a disabled plugin fails at once). */
    fun <T> call(what: String, timeoutMs: Long, block: () -> T): Result<T> {
        disabledReason?.let { return Result.failure(IllegalStateException("plugin $moduleId is disabled: $it")) }
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
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return try {
            Result.success(await(future, worker, what, timeoutMs)).also { consecutiveTimeouts.set(0) }
        } catch (e: RunawayException) {
            future.cancel(true)
            disable(e.message ?: "$what ran away")
            Result.failure(e)
        } catch (e: TimeoutException) {
            future.cancel(true)
            // BOARD-DOWN-1: a slow call (a DB lock, a git call) fails only itself; a plugin that keeps timing out is disabled.
            val n = consecutiveTimeouts.incrementAndGet()
            if (n >= timeoutStrikes) disable("$what timed out after $timeoutMs ms ($n in a row)")
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
    private fun <T> await(future: java.util.concurrent.Future<T>, worker: java.util.concurrent.atomic.AtomicReference<Thread?>, what: String, timeoutMs: Long): T {
        val began = System.nanoTime()
        val ceilingMs = maxOf(hangCeilingMs, timeoutMs)
        var warned = false
        var base = -1L
        while (true) {
            try { return future.get(pollMs, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
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
