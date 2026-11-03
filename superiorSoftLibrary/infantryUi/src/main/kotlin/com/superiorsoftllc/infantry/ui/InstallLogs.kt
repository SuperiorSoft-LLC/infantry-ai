// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.Instant

/**
 * PKG-3: the installed app's log file. A Start-menu launch has no console, so without this every line the daemon
 * and the window print (stdout, stderr, and any logger that writes to them) is lost.
 *
 * Layout, under `<stateDir>/logs/`: `infantry.log` is the current file, `infantry.1.log` the one before it, up to
 * `infantry.4.log` -- [MAX_FILES] files in all, each at most [MAX_BYTES]. Each launch starts a new `infantry.log`
 * (the previous ones shift up by one, the oldest is deleted), and a file that reaches [MAX_BYTES] mid-run rolls the
 * same way. Only the installed profile calls [install]; the dev launches keep their console untouched.
 */
object InstallLogs {
    const val DIR_NAME = "logs"
    const val FILE_NAME = "infantry.log"
    const val MAX_FILES = 5
    const val MAX_BYTES = 10L * 1024 * 1024

    /** The logs folder of [stateDir]: what Settings shows and "Open logs folder" opens. */
    fun dir(stateDir: File): File = File(stateDir, DIR_NAME)

    /** The `index`-th file: 0 is the current `infantry.log`, 1.. are the older `infantry.N.log`. */
    fun file(dir: File, index: Int): File = File(dir, if (index == 0) FILE_NAME else "infantry.$index.log")

    /**
     * Shift every file up by one (`infantry.log` -> `infantry.1.log`, ...) and delete what would pass [maxFiles],
     * leaving the slot of `infantry.log` free. Afterwards at most `maxFiles - 1` files exist, so the new current file
     * brings the total back to [maxFiles].
     */
    fun rotate(dir: File, maxFiles: Int = MAX_FILES) {
        require(maxFiles >= 1) { "maxFiles must be >= 1, was $maxFiles" }
        dir.mkdirs()
        file(dir, maxFiles - 1).delete()
        for (index in maxFiles - 2 downTo 0) {
            val from = file(dir, index)
            if (from.exists()) {
                val to = file(dir, index + 1)
                to.delete()
                if (!from.renameTo(to)) from.delete() // a locked file must not stop the launch
            }
        }
    }

    /**
     * Route `System.out` and `System.err` to `<stateDir>/logs/infantry.log` (after [rotate]) and install an
     * uncaught-exception handler that writes the stack trace there before the thread dies. [close] on the result
     * puts the previous streams and handler back (tests; the app keeps it for its whole life).
     */
    fun install(stateDir: File, maxFiles: Int = MAX_FILES, maxBytes: Long = MAX_BYTES): Installed {
        val dir = dir(stateDir).absoluteFile
        rotate(dir, maxFiles)
        val sink = RollingFileStream(dir, maxFiles, maxBytes)
        val stream = PrintStream(sink, true, Charsets.UTF_8)
        val previousOut = System.out
        val previousErr = System.err
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        System.setOut(stream)
        System.setErr(stream)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            synchronized(stream) {
                stream.println("[infantry-crash] ${Instant.now()} uncaught on thread '${thread.name}':")
                error.printStackTrace(stream)
                stream.flush()
            }
            previousHandler?.uncaughtException(thread, error)
        }
        stream.println("[infantry-install] ${Instant.now()} log started: ${file(dir, 0)}")
        return Installed(dir, stream) {
            stream.flush()
            System.setOut(previousOut)
            System.setErr(previousErr)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            stream.close()
        }
    }

    /** The active log: its [dir], the [stream] now behind `System.out`/`System.err`, and [close] to undo it. */
    class Installed internal constructor(val dir: File, val stream: PrintStream, private val restore: () -> Unit) : AutoCloseable {
        override fun close() = restore()
    }
}

/**
 * The file behind the installed app's `System.out`/`System.err`: `infantry.log` until it reaches [maxBytes], then
 * [InstallLogs.rotate] and a fresh `infantry.log`. Every write is flushed straight to disk so a crash loses nothing.
 */
internal class RollingFileStream(private val dir: File, private val maxFiles: Int, private val maxBytes: Long) : OutputStream() {
    private var out = FileOutputStream(InstallLogs.file(dir, 0), true)
    private var written = InstallLogs.file(dir, 0).length()

    @Synchronized override fun write(b: Int) {
        rollIfFull(1)
        out.write(b)
        written += 1
    }

    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        rollIfFull(len)
        out.write(b, off, len)
        written += len
    }

    @Synchronized override fun flush() = out.flush()

    @Synchronized override fun close() = out.close()

    private fun rollIfFull(incoming: Int) {
        if (written == 0L || written + incoming <= maxBytes) return
        out.close()
        InstallLogs.rotate(dir, maxFiles)
        out = FileOutputStream(InstallLogs.file(dir, 0), true)
        written = 0
    }
}
