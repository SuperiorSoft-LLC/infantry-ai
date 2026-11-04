// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import com.pty4j.Command
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.File

/**
 * RUN-CONSOLE (ticket 67f117b6): the byte-stream sibling of [ProcessRunner] for saved-command runs. The child gets a
 * real pseudo-terminal, so it prints what it prints in a terminal (colours, `\r` progress, cursor movement), and the
 * daemon hands the raw bytes on untouched. stdout and stderr are one stream, as in any terminal.
 */
interface TerminalListener {
    /** A chunk of raw terminal output, called on the runner's single drain thread, in order. */
    fun onBytes(bytes: ByteArray)
    /** Called once, after the last [onBytes]. */
    fun onExit(exitCode: Int)
}

interface TerminalProcess {
    val isAlive: Boolean
    val pid: Long?
    /** Keyboard input, as the terminal would send it. */
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun terminate()
    fun killTree()
}

interface TerminalRunner {
    /** Starts [spec] (an argv, never a shell string) in a terminal of [cols] x [rows]. */
    fun start(spec: ProcessSpec, cols: Int, rows: Int, listener: TerminalListener): TerminalProcess
}

/**
 * pty4j: the POSIX pty on macOS and Linux; on Windows winpty, or ConPTY when [useConPty] (CLI-PTY-1: what an interactive agent CLI is run in by
 * a terminal app; the Run console keeps pty4j's default).
 */
class Pty4jTerminalRunner(private val useConPty: Boolean = false) : TerminalRunner {
    override fun start(spec: ProcessSpec, cols: Int, rows: Int, listener: TerminalListener): TerminalProcess {
        val env = HashMap(System.getenv())
        env.putAll(spec.env)
        // The emulator on the other end is an xterm-compatible one (JediTerm, or the text fallback).
        env.putIfAbsent("TERM", "xterm-256color")
        // pty4j's own Windows join quotes on a space but leaves an embedded `"` bare; build the line ourselves so
        // the child sees the same argv the piped runner (ProcessBuilder) would give it.
        val builder = if (WINDOWS) PtyProcessBuilder(Command.RawCommandString(windowsCommandLine(spec.command)))
        else PtyProcessBuilder(spec.command.toTypedArray())
        val process = builder
            .setDirectory((spec.workingDir ?: File(".")).absolutePath)
            .setEnvironment(env)
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .setRedirectErrorStream(true)
            .setConsole(false)
            .apply { if (useConPty) setUseWinConPty(true) }
            .start()
        val drain = Thread({
            val buf = ByteArray(8192)
            val input = process.inputStream
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) listener.onBytes(buf.copyOf(n))
                }
            } catch (_: Exception) {
                // The pty closes with the child; a read error here is the end of output.
            }
            listener.onExit(runCatching { process.waitFor() }.getOrDefault(-1))
        }, "infantry-run-pty").apply { isDaemon = true }
        drain.start()
        return Pty4jProcess(process)
    }
}

private class Pty4jProcess(private val process: PtyProcess) : TerminalProcess {
    override val isAlive: Boolean get() = process.isAlive
    override val pid: Long? get() = runCatching { process.pid() }.getOrNull()

    override fun write(bytes: ByteArray) {
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override fun resize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0) process.winSize = WinSize(cols, rows)
    }

    /** A Ctrl-C through the terminal first (what a user would press), then the same tree stop as a piped run. */
    override fun terminate() {
        runCatching { write(byteArrayOf(3)) }
        ProcessTreeKiller.terminate(process)
    }

    override fun killTree() = ProcessTreeKiller.kill(process)
}

private val WINDOWS = System.getProperty("os.name").orEmpty().lowercase().contains("win")

/**
 * [argv] as one Windows command line under the MSVCRT rules a child's argv parser uses: an argument with a space,
 * tab or quote (or an empty one) goes in quotes, an embedded `"` becomes `\"`, and backslashes before a quote (or
 * the closing quote) are doubled. Each argument comes back out of CommandLineToArgvW exactly as given.
 */
fun windowsCommandLine(argv: List<String>): String = argv.joinToString(" ") { arg ->
    if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) return@joinToString arg
    buildString {
        append('"')
        var slashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> slashes++
                '"' -> { repeat(slashes * 2 + 1) { append('\\') }; append('"'); slashes = 0 }
                else -> { repeat(slashes) { append('\\') }; append(c); slashes = 0 }
            }
        }
        repeat(slashes * 2) { append('\\') }
        append('"')
    }
}
