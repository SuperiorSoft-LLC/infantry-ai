// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.Instant

/**
 * DAEMON-LOG-1: the headless daemon's rolling log, `<stateDir>/logs/infantry.log` (+ `infantry.1.log` ...), [MAX_FILES] files of at
 * most [MAX_BYTES] each. Same layout as the desktop's InstallLogs, which keeps routing the embedded daemon's System.out/err there.
 * [install] sends System.out/System.err (StallProbe, ws connect/close, script runs, slf4j-simple) and uncaught exceptions to it.
 * It is a no-op when System.err is already a [LogStream], so one process never writes two logs.
 */
object DaemonLog {
    const val MAX_FILES = 5
    const val MAX_BYTES = 5L * 1024 * 1024

    fun dir(stateDir: File) = File(stateDir, "logs")
    fun file(dir: File, index: Int) = File(dir, if (index == 0) "infantry.log" else "infantry.$index.log")

    fun rotate(dir: File, maxFiles: Int = MAX_FILES) {
        dir.mkdirs()
        file(dir, maxFiles - 1).delete()
        for (i in maxFiles - 2 downTo 0) {
            val from = file(dir, i)
            if (from.exists()) {
                val to = file(dir, i + 1)
                to.delete()
                if (!from.renameTo(to)) from.delete()
            }
        }
    }

    /** The process's own stdout from before [install] (a container's `docker logs`, a terminal); null when none was replaced. */
    @Volatile private var console: PrintStream? = null

    /**
     * RUNNER-BEARER-STALE-1: [line] goes to the log AND to the console the owner reads (`docker logs`), for the few lines the owner
     * is told to look for there (a runner's endpoint and bearer). Everything else stays in the log file only.
     */
    fun toConsole(line: String) {
        println(line)
        console?.let { synchronized(it) { it.println(line); it.flush() } }
    }

    fun install(stateDir: File, maxFiles: Int = MAX_FILES, maxBytes: Long = MAX_BYTES): AutoCloseable? {
        if (System.err is LogStream) return null
        val dir = dir(stateDir).absoluteFile
        rotate(dir, maxFiles)
        val stream = LogStream(RollingOut(dir, maxFiles, maxBytes))
        val prevOut = System.out
        val prevErr = System.err
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        console = prevOut
        System.setOut(stream)
        System.setErr(stream)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            synchronized(stream) {
                stream.println("[infantry-crash] ${Instant.now()} uncaught on thread '${t.name}':")
                e.printStackTrace(stream)
                stream.flush()
            }
            prevHandler?.uncaughtException(t, e)
        }
        stream.println("[infantry] ${Instant.now()} daemon log started: ${file(dir, 0)}")
        return AutoCloseable {
            stream.flush()
            console = null
            System.setOut(prevOut)
            System.setErr(prevErr)
            Thread.setDefaultUncaughtExceptionHandler(prevHandler)
            stream.close()
        }
    }

    /** The current log file under [stateDir]: what `infantry daemon status` and settings show. */
    fun path(stateDir: File): String = file(dir(stateDir).absoluteFile, 0).path

    class LogStream internal constructor(out: OutputStream) : PrintStream(out, true, Charsets.UTF_8)

    private class RollingOut(private val dir: File, private val maxFiles: Int, private val maxBytes: Long) : OutputStream() {
        private var out = FileOutputStream(file(dir, 0), true)
        private var written = file(dir, 0).length()

        @Synchronized override fun write(b: Int) { roll(1); out.write(b); written += 1 }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { roll(len); out.write(b, off, len); written += len }
        @Synchronized override fun flush() = out.flush()
        @Synchronized override fun close() = out.close()

        private fun roll(incoming: Int) {
            if (written == 0L || written + incoming <= maxBytes) return
            out.close()
            rotate(dir, maxFiles)
            out = FileOutputStream(file(dir, 0), true)
            written = 0
        }
    }
}
