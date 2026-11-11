// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.io.BufferedReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The Phase 0 default: a plain JVM [ProcessBuilder] with pipes, no TTY, no native library.
 *
 * The three implementation details that Phase 0 proved are load-bearing live here:
 *  1. stdout and stderr are drained on SEPARATE threads (an unread stderr pipe can block the child);
 *  2. stdin is closed by the caller once the prompt is written (the child blocks on EOF);
 *  3. the process TREE is killed, not just the direct child (Windows `Process.destroy()` is
 *     direct-child-only).
 */
class PipedProcessRunner : ProcessRunner {
    override val id: String = "piped"

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        // Ticket 66153313: a worker session's spec names its build priority; the whole tree below it inherits it.
        val priority = ProcessPriority.requested(spec)
        ChildReaper.ensure()
        val builder = ProcessBuilder(escapeForWindows(ProcessPriority.wrap(spec.command, priority)))
        spec.workingDir?.let { builder.directory(it) }
        builder.environment().putAll(spec.env)
        builder.redirectErrorStream(false)

        val process = builder.start()
        ProcessPriority.applyAfterStart(process.pid(), priority)

        // Drain concurrently, before we can possibly block on the child.
        val stdoutDrain = drain(process.inputStream.bufferedReader(), listener::onStdout, "stdout")
        val stderrDrain = drain(process.errorStream.bufferedReader(), listener::onStderr, "stderr")

        return PipedRunningProcess(process, stdoutDrain, stderrDrain, listener)
    }

    /**
     * The JVM joins a Windows argv naively: an embedded `"` is not escaped and a trailing backslash is not doubled.
     * An argument that needs it (after the executable) is quoted here under the CommandLineToArgvW rules; the JVM
     * leaves an argument that already starts and ends with a quote as it is.
     */
    internal fun escapeForWindows(argv: List<String>, windows: Boolean = WINDOWS_HOST): List<String> =
        if (!windows) argv
        else argv.mapIndexed { i, a ->
            if (i == 0 || (a.isNotEmpty() && a.none { it == ' ' || it == '\t' || it == '"' })) a else windowsCommandLine(listOf(a))
        }

    private val WINDOWS_HOST = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun drain(reader: BufferedReader, sink: (String) -> Unit, name: String): Thread =
        Thread({
            try {
                reader.forEachLine(sink)
            } catch (_: Exception) {
                // The stream closed because the child died or was killed; nothing to report here.
            } finally {
                runCatching { reader.close() }
            }
        }, "infantry-drain-$name").apply {
            isDaemon = true
            start()
        }
}

private class PipedRunningProcess(
    private val process: Process,
    private val stdoutDrain: Thread,
    private val stderrDrain: Thread,
    private val listener: ProcessListener,
) : RunningProcess {

    private val exitCode = CompletableFuture<Int>()

    init {
        Thread({
            val code = process.waitFor()
            // Join the drain threads so the last lines are delivered BEFORE onExit / awaitExit.
            stdoutDrain.join()
            stderrDrain.join()
            runCatching { listener.onExit(code) }
            exitCode.complete(code)
        }, "infantry-wait-${process.pid()}").apply {
            isDaemon = true
            start()
        }
    }

    override val isAlive: Boolean get() = process.isAlive

    override val pid: Long get() = process.pid()

    override fun writeInput(text: String) {
        process.outputStream.write(text.toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    override fun closeInput() {
        runCatching { process.outputStream.close() }
    }

    override fun terminate() = ProcessTreeKiller.terminate(process)

    override fun killTree() = ProcessTreeKiller.kill(process)

    override fun awaitExit(): Int = exitCode.get()
}

/** Kills a child and its descendants. Windows has no tree kill in the JVM, so use `taskkill /T`. */
object ProcessTreeKiller {
    /** The one place the Windows tree-kill argv is built: `/T` (descendants) is required on BOTH the graceful and forced path. */
    fun taskkillArgs(pid: Long, force: Boolean): List<String> =
        listOf("taskkill", "/PID", pid.toString(), "/T") + if (force) listOf("/F") else emptyList()

    private val isWindows get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Graceful: no `/F` on Windows (a close request, which a console child may ignore), SIGTERM elsewhere. Never destroys only the parent. */
    fun terminate(process: Process) {
        if (!process.isAlive) return
        if (isWindows) {
            runCatching {
                ProcessBuilder(taskkillArgs(process.pid(), force = false))
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            }
        } else {
            runCatching { process.descendants().forEach { it.destroy() }; process.destroy() }
        }
    }

    fun kill(process: Process) {
        if (!process.isAlive) return
        if (isWindows) {
            runCatching {
                ProcessBuilder(taskkillArgs(process.pid(), force = true))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }
        } else {
            runCatching { process.descendants().forEach { it.destroy() } }
        }
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }
}
