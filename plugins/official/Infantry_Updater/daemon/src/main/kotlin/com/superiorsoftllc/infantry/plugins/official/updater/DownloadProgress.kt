// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.updater

/**
 * UPDATE-UX-1: what a download reports while it runs: [bytes] as they arrive (the window's "Downloading 1.0.7 - 45%")
 * and [note] for each step (checksum read, request sent, HTTP answer), which the window writes to its log.
 */
interface DownloadProgress {
    fun bytes(done: Long, total: Long) {}
    fun note(text: String) {}

    companion object {
        val NONE: DownloadProgress = object : DownloadProgress {}
    }
}

/**
 * UPDATE-UX-1: aborts a read that receives nothing for [timeoutMs]. A blocked `InputStream.read` on the JDK HTTP client
 * never returns on a dead connection; when [alive] has not been called for [timeoutMs], this runs [onStall] (close the
 * stream) and interrupts the reading thread, so the read throws and the caller checks [fired]. [close] stops the watch
 * and clears an interrupt it left behind.
 */
class StallWatch(private val timeoutMs: Long, private val onStall: () -> Unit) : AutoCloseable {
    private val reader = Thread.currentThread()
    @Volatile private var last = System.nanoTime()
    @Volatile private var done = false
    @Volatile var fired = false
        private set

    private val watcher = Thread({
        val tick = (timeoutMs / TICKS_PER_TIMEOUT).coerceAtLeast(1)
        while (!done) {
            try { Thread.sleep(tick) } catch (_: InterruptedException) { return@Thread }
            if (!done && (System.nanoTime() - last) / 1_000_000 >= timeoutMs) {
                fired = true
                runCatching(onStall)
                reader.interrupt()
                return@Thread
            }
        }
    }, "infantry-update-stall-watch").apply { isDaemon = true; start() }

    /** A byte arrived. */
    fun alive() { last = System.nanoTime() }

    override fun close() {
        done = true
        watcher.interrupt()
        // The reading thread may carry the watch's own interrupt: wait for the watch without tripping on it.
        var interrupted = false
        while (watcher.isAlive) {
            try { watcher.join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (fired) Thread.interrupted() else if (interrupted) Thread.currentThread().interrupt()
    }

    private companion object {
        /** How often per timeout the watch looks: a stall is noticed within a tenth of the timeout. */
        const val TICKS_PER_TIMEOUT = 10
    }
}
