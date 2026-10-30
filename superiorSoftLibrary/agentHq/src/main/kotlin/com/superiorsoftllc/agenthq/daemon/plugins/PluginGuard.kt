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
) {
    @Volatile var disabledReason: String? = null
        private set
    val disabled: Boolean get() = disabledReason != null

    private val threads = AtomicInteger()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "plugin-$moduleId-${threads.incrementAndGet()}").apply { isDaemon = true; contextClassLoader = classLoader }
    }
    private val cpu = ManagementFactory.getThreadMXBean()

    /** [block] under the limits: its value, or a failure (the plugin is then disabled; a disabled plugin fails at once). */
    fun <T> call(what: String, timeoutMs: Long, block: () -> T): Result<T> {
        disabledReason?.let { return Result.failure(IllegalStateException("plugin $moduleId is disabled: $it")) }
        val future = try {
            pool.submit(Callable {
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
            Result.success(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            future.cancel(true)
            disable("$what timed out after $timeoutMs ms")
            Result.failure(e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            disable("$what threw ${cause::class.java.simpleName}: ${cause.message?.lineSequence()?.firstOrNull().orEmpty()}")
            Result.failure(cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(e)
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
