// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.runs

import java.io.File

/**
 * CMD-1 (DaemonApi 1.31.0, additive): the run surface. A cwd-bound child process (argv, never a shell string) in a project's
 * directory, with a pseudo-terminal or pipes, stdin, resize, stop and an output listener. Official / core tier, or a community
 * module with the grant `runs`. A [cwd] outside the root (a `..`, an absolute path, a link that leaves it) is refused with
 * [IllegalArgumentException] before anything starts; a daemon without the surface refuses with [IllegalStateException].
 */
interface Runs {
    /** The daemon's own working root (its repository), the root of a run that names no project; null = none. */
    val workRoot: File?

    /**
     * RUN-CMD-REPO-1: the canonical root a command of [projectId] is validated and run in: that project's registered directory,
     * else [workRoot]; null when neither resolves. Additive: the default is [workRoot].
     */
    fun rootOf(projectId: String?): File? = workRoot

    /** The daemon's configuration directory (where a plugin keeps files the owner may hand-edit). */
    val configDir: File

    /**
     * Starts [spec]. The root is the directory of project [RunSpec.projectId], else [workRoot]; [RunSpec.cwd] is relative to it
     * (null = the root itself). With [RunSpec.terminal] the child gets a pty when one can load here, else pipes; see [RunHandle.terminal].
     */
    fun start(spec: RunSpec, listener: RunListener): RunHandle

    /** The fields of keystore secret [name] (a built-in one is refused with [IllegalArgumentException]); null = no such secret. */
    fun secretFields(name: String): Map<String, String>?
}

data class RunSpec(
    val argv: List<String>,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val projectId: String? = null,
    val terminal: Boolean = false,
    val cols: Int = 120,
    val rows: Int = 32,
)

/** Output callbacks, on the run's own drain threads. A pty run calls [onBytes]; a piped run calls [onLine]. [onExit] is last, once. */
interface RunListener {
    fun onBytes(bytes: ByteArray) {}
    fun onLine(stream: String, line: String) {}
    fun onExit(exitCode: Int)
}

interface RunHandle {
    val pid: Long?
    val isAlive: Boolean
    /** The child has a pseudo-terminal (bytes, keyboard, resize); false = pipes (lines). */
    val terminal: Boolean
    /** Keyboard bytes into a terminal run; refused with [IllegalStateException] on a piped one. */
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    /** Asks the child to end (SIGTERM-like). */
    fun terminate()
    /** Kills the child and everything it started. */
    fun killTree()
}

/** The default of a daemon with no run surface. */
object NoRuns : Runs {
    private fun no(): Nothing = throw IllegalStateException("this daemon offers no runs")
    override val workRoot: File? get() = null
    override val configDir: File get() = no()
    override fun start(spec: RunSpec, listener: RunListener): RunHandle = no()
    override fun secretFields(name: String): Map<String, String>? = no()
}
