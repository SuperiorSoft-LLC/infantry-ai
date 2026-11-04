// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ProcessRunner] whose child runs in a pseudo-terminal ([TerminalRunner], pty4j; ConPTY on Windows), for a program that
 * must be run interactively (CLI-PTY-1: an agent CLI as a person runs it). Phase 0 chose pipes for every line protocol, and
 * `INFANTRY_RUNNER=pty` still selects this runner for one-shot runs.
 *
 * A terminal has no lines and no stderr: [ProcessListener.onStdout] gets each output chunk decoded as UTF-8 (a character
 * split across two reads is kept for the next chunk), escape sequences included; [ProcessListener.onStderr] is never
 * called. [RunningProcess.writeInput] is keyboard input. [RunningProcess.closeInput] does nothing: a terminal's input stays
 * open for the life of the child (stop it with [RunningProcess.terminate] or [RunningProcess.killTree]).
 */
class PtyProcessRunner(
    private val terminal: TerminalRunner = Pty4jTerminalRunner(useConPty = true),
    private val cols: Int = DEFAULT_COLS,
    private val rows: Int = DEFAULT_ROWS,
) : ProcessRunner {
    override val id: String = "pty"

    override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
        val decoder = Utf8Chunks()
        val exit = AtomicInteger(-1)
        val done = CountDownLatch(1)
        val child = terminal.start(spec, cols, rows, object : TerminalListener {
            override fun onBytes(bytes: ByteArray) {
                decoder.decode(bytes).takeIf { it.isNotEmpty() }?.let(listener::onStdout)
            }

            override fun onExit(exitCode: Int) {
                exit.set(exitCode)
                listener.onExit(exitCode)
                done.countDown()
            }
        })
        return object : RunningProcess {
            override val isAlive: Boolean get() = child.isAlive
            override val pid: Long? get() = child.pid
            override fun writeInput(text: String) = child.write(text.toByteArray(Charsets.UTF_8))
            override fun closeInput() {}
            override fun terminate() = child.terminate()
            override fun killTree() = child.killTree()
            override fun awaitExit(): Int {
                done.await()
                return exit.get()
            }
        }
    }

    companion object {
        /** The terminal's size: wide enough that an interactive CLI does not wrap its own status lines. */
        const val DEFAULT_COLS = 200
        const val DEFAULT_ROWS = 50
    }
}

/** UTF-8 decoding across chunk boundaries: bytes of a character cut by a read wait for the next chunk. One drain thread. */
internal class Utf8Chunks {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
    }
}
