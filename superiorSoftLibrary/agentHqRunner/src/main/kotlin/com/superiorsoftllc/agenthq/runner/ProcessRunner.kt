package com.superiorsoftllc.agenthq.runner

import java.io.File

/** What to launch: the argv, an optional working directory, and environment overrides. */
data class ProcessSpec(
    val command: List<String>,
    val workingDir: File? = null,
    val env: Map<String, String> = emptyMap(),
    /** HARNESS H6b: sandbox additions a harness contributed (already checked by the daemon); read by [SandboxPlan] when the sandbox is on. */
    val extraReadDirs: List<String> = emptyList(),
    val extraWriteDirs: List<String> = emptyList(),
)

/**
 * Output callbacks, invoked on the runner's own drain threads. Implementations must be
 * thread-safe: the daemon pushes straight into a channel or onto stdout from here.
 *
 * [onStdout] and [onStderr] are line-oriented: `opencode run --format json` emits exactly one JSON
 * object per line, so a line is the natural unit.
 */
interface ProcessListener {
    fun onStdout(line: String)
    fun onStderr(line: String)
    fun onExit(exitCode: Int)
}

/** A started child. The caller drives stdin, waits for exit, and can kill the whole tree. */
interface RunningProcess {
    val isAlive: Boolean

    /**
     * OS pid of the direct child, when the transport can expose it. Phase 3 needs this to reconcile
     * a session whose daemon died while the child kept running (an orphan): the pid is the only way
     * to tell "still alive" from "gone". Optional and defaulted so a transport without the notion
     * (a future non-subprocess provider) stays valid.
     */
    val pid: Long? get() = null

    /** Write bytes to the child's stdin. The caller is responsible for [closeInput]. */
    fun writeInput(text: String)

    /**
     * Close the child's stdin. This is not optional for `opencode run`: it blocks on stdin EOF and
     * emits nothing while the pipe stays open (Phase 0 finding #2).
     */
    fun closeInput()

    /**
     * Ask the child tree to stop without force (the graceful step of a Stop). Default: nothing, so the
     * caller falls through to [killTree] after its grace window.
     */
    fun terminate() {}

    /** Kill the child AND its descendants (`opencode` may fork; `Process.destroy()` does not). */
    fun killTree()

    /** Block until the child exits and every drained line has been delivered. Returns the exit code. */
    fun awaitExit(): Int
}

/** The seam. The concrete implementation is chosen by flag; nothing above this cares which. */
interface ProcessRunner {
    val id: String

    fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess
}
