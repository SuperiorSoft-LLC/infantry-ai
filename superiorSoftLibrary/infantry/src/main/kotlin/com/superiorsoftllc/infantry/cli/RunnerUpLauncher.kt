// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.io.File
import java.net.URI

/**
 * RUNNER-UP-1: the part of `infantry runner up` that runs before any daemon answers. It starts a daemon in runner mode in the
 * background (the same main class the launcher's daemon uses, `--mode runner`), writing its output to `<stateDir>/runner-up.log`,
 * and waits for it to accept a connection for `limits.runners.upStartSec`.
 */
internal object RunnerUpLauncher {
    private const val DAEMON_MAIN = "com.superiorsoftllc.infantry.daemon.DaemonMainKt"
    private const val DEFAULT_JAVA_OPTS = "-Xms32m -Xmx512m"
    private const val MS_PER_SECOND = 1000L

    /** The seconds to wait: `INFANTRY_LIMIT_RUNNERS_UP_START_SEC`, else the registry default (no daemon to ask yet). */
    fun waitSeconds(getenv: (String) -> String?): Long =
        getenv(LimitRegistry.RUNNERS_UP_START_SEC.env)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: LimitRegistry.RUNNERS_UP_START_SEC.default

    /** Starts the runner-mode daemon and returns the open stream, or null (with the reason on [err]) when it never answered. */
    fun startAndConnect(resolveTarget: () -> Target, getenv: (String) -> String?, stateDir: File, err: Appendable): FrameStream? {
        val log = File(stateDir, "runner-up.log").apply { parentFile?.mkdirs() }
        val java = File(File(System.getProperty("java.home"), "bin"), if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").path
        val opts = (getenv("JAVA_OPTS")?.takeIf { it.isNotBlank() } ?: DEFAULT_JAVA_OPTS).split(Regex("[ \t]+")).filter { it.isNotBlank() }
        val command = listOf(java) + opts + listOf("-Djava.awt.headless=true", "--add-opens=java.base/java.util=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), DAEMON_MAIN, "--mode", "runner")
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).redirectInput(ProcessBuilder.Redirect.from(File(if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")))
        // The daemon binds the port the CLI would connect to.
        runCatching { URI.create(resolveTarget().url).port }.getOrNull()?.takeIf { it > 0 }?.let { builder.environment()["INFANTRY_PORT"] = it.toString() }
        val process = try { builder.start() } catch (e: Exception) { err.appendLine("error: could not start the runner daemon: ${e.message}"); return null }
        err.appendLine("no daemon answered; started one in runner mode (pid ${process.pid()}, log $log)")
        val deadline = System.currentTimeMillis() + waitSeconds(getenv) * MS_PER_SECOND
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) { err.appendLine("error: the runner daemon exited with ${process.exitValue()}; see $log"); return null }
            try { return FrameStream.open(resolveTarget(), err) } catch (_: Exception) { Thread.sleep(MS_PER_SECOND / 2) }
        }
        err.appendLine("error: the runner daemon did not answer within ${waitSeconds(getenv)} s (limits.runners.upStartSec); see $log")
        return null
    }
}
