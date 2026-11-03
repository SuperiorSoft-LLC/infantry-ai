// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DAEMON-DROP-1: names the cause of a daemon that stays up but stops answering.
 *
 * The [HealthWatchdog] runs in the headless daemon only; the desktop app embeds the daemon and had nothing watching it, so a
 * stall left no trace in the log. This probe asks the daemon's own `/health` every [intervalMs] (any HTTP answer, 503 included,
 * means the request path is alive), and when none comes within [timeoutMs] it logs `stalled` with a dump of every thread that
 * is not idle, which names what holds the request threads. It logs `answers again` with the stall's length on recovery.
 * It never halts or restarts anything.
 */
class StallProbe(
    private val url: URI,
    private val intervalMs: Long = 5_000,
    private val timeoutMs: Long = 3_000,
    private val ask: (URI, Long) -> Boolean = HttpAsk()::answers,
    private val dump: () -> String = ::busyThreadDump,
    private val log: (String) -> Unit = { System.err.println(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "infantry-stall-probe").also { it.isDaemon = true } }
    @Volatile private var stalledSince = 0L

    /** True from the first missed probe until the next answer. */
    val stalled: Boolean get() = stalledSince != 0L

    fun start(): StallProbe {
        scheduler.scheduleWithFixedDelay(::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        return this
    }

    /** One probe; public so a test drives it without waiting. */
    fun tick() {
        val ok = try { ask(url, timeoutMs) } catch (e: Exception) { false }
        if (ok) {
            if (stalledSince != 0L) log("[infantry] stall probe: /health answers again after ${clock() - stalledSince} ms")
            stalledSince = 0L
            return
        }
        if (stalledSince == 0L) {
            stalledSince = clock()
            log("[infantry] stall probe: /health did not answer within $timeoutMs ms; the daemon is up but not serving. Busy threads:\n${dump()}")
        }
    }

    override fun close() { scheduler.shutdownNow() }

    private class HttpAsk {
        private val client by lazy { HttpClient.newBuilder().build() }
        fun answers(url: URI, timeoutMs: Long): Boolean {
            val request = HttpRequest.newBuilder(url).timeout(Duration.ofMillis(timeoutMs)).GET().build()
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).get(timeoutMs, TimeUnit.MILLISECONDS)
            return true
        }
    }

    companion object {
        /** Every RUNNABLE or BLOCKED thread with its top frames: the idle pool workers are left out. */
        fun busyThreadDump(): String = Thread.getAllStackTraces().entries
            .filter { (t, _) -> t.state == Thread.State.RUNNABLE || t.state == Thread.State.BLOCKED }
            .joinToString("\n") { (t, frames) -> "  \"${t.name}\" ${t.state}\n" + frames.take(12).joinToString("\n") { "      at $it" } }

    }
}
