// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.credential

import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.util.concurrent.ConcurrentHashMap

/**
 * ONBOARD-1: one in-app sign-in of a harness's CLI. [state] is "" (none), `running`, `done` or `failed`; [url] the sign-in page the
 * CLI printed (the first https link of its output); [message] a short plain line for the owner (a failure's reason). Never a token.
 */
data class HarnessLoginState(val state: String = "", val url: String = "", val message: String = "") {
    companion object {
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        val NONE = HarnessLoginState()
    }
}

/**
 * ONBOARD-1: runs a harness's declared sign-in ([HarnessCredential.loginArgv], e.g. `claude auth login`) for the app, so a new user
 * signs in from the first-run screen instead of a terminal. The CLI opens the browser itself and usually finishes by its own
 * callback; when the page shows a code instead, [input] writes the pasted line to the CLI's stdin. Stdin stays open for that.
 * On exit the harness's probe is re-run ([verify]) and the result is what counts: exit 0 and a probe that says signed in = `done`.
 *
 * One sign-in per harness at a time (a new start cancels the old). [onChange] fires on every state change (the caller pushes the
 * secret rows). Output lines are never logged or kept beyond the link and the last line (a pasted code is never echoed).
 */
class HarnessLogins(
    private val credentials: HarnessCredentials,
    /** Re-probes the harness after its sign-in exits; true = signed in. */
    private val verify: (String) -> Boolean? = { credentials.checkAuth(it).signedIn },
    /** How long one sign-in may wait for the owner, in ms; 0 = no limit (the setting `limits.harness.loginTimeoutMs`). */
    private val timeoutMs: () -> Long = { 0L },
    private val runner: ProcessRunner = PipedProcessRunner(),
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
    private val onChange: () -> Unit = {},
) {
    private class Running(@Volatile var state: HarnessLoginState) {
        @Volatile var process: RunningProcess? = null
        @Volatile var cancelled: Boolean = false
        fun kill() { cancelled = true; runCatching { process?.killTree() } }
    }

    private val runs = ConcurrentHashMap<String, Running>()

    /** The sign-in state of [harnessId] ([HarnessLoginState.NONE] when it never started one). */
    fun state(harnessId: String): HarnessLoginState = runs[harnessId]?.state ?: HarnessLoginState.NONE

    /** Starts the declared sign-in of [harnessId]. Refused (IllegalArgumentException) for a harness that declares none. */
    fun start(harnessId: String) {
        val c = credentials.declaration(harnessId) ?: throw IllegalArgumentException("unknown harness '${harnessId.take(40)}'")
        require(c.loginArgv.isNotEmpty()) { "${c.label} has no in-app sign-in; run its login command in a terminal" }
        runs.remove(harnessId)?.kill()
        // The run exists before the child does: its first lines (the sign-in link) may arrive before start() returns.
        val run = Running(HarnessLoginState(HarnessLoginState.RUNNING))
        runs[harnessId] = run
        val listener = object : ProcessListener {
            override fun onStdout(line: String) = seen(run, line)
            override fun onStderr(line: String) = seen(run, line)
            override fun onExit(exitCode: Int) = ended(harnessId, run, exitCode)
        }
        // A subscription sign-in must not see the API key variable (it would bill that account): blanked, as a subscription session is.
        val argv = if (windows) listOf("cmd", "/c") + c.loginArgv else c.loginArgv
        val process = try {
            runner.start(ProcessSpec(argv, env = mapOf(c.env to "")), listener)
        } catch (e: Exception) {
            run.state = run.state.copy(state = HarnessLoginState.FAILED, message = "The sign-in could not start: ${e.message.orEmpty().take(SHOWN_MESSAGE_LENGTH)}")
            onChange()
            return
        }
        run.process = process
        if (run.cancelled) runCatching { process.killTree() }
        onChange()
        val limit = timeoutMs()
        if (limit > 0) Thread({
            runCatching { Thread.sleep(limit) }
            if (runs[harnessId] === run && run.state.state == HarnessLoginState.RUNNING) {
                run.kill()
                run.state = run.state.copy(state = HarnessLoginState.FAILED, message = "The sign-in timed out. Start it again.")
                onChange()
            }
        }, "infantry-login-timeout-$harnessId").apply { isDaemon = true; start() }
    }

    /** Writes the line the owner pasted (the code the sign-in page shows) to the running sign-in. Refused when none runs. */
    fun input(harnessId: String, text: String) {
        val run = runs[harnessId]?.takeIf { it.state.state == HarnessLoginState.RUNNING && it.process?.isAlive == true }
            ?: throw IllegalArgumentException("no sign-in is running; start it again")
        val line = text.trim().replace("\r", "").replace("\n", "")
        require(line.isNotEmpty()) { "paste the code the sign-in page shows" }
        run.process?.writeInput(line + "\n")
        run.state = run.state.copy(message = "Checking the code...")
        onChange()
    }

    /** Stops the running sign-in of [harnessId], if any, and forgets its state. */
    fun cancel(harnessId: String) {
        runs.remove(harnessId)?.kill() ?: return
        onChange()
    }

    private fun seen(run: Running, raw: String) {
        val line = plain(raw)
        if (run.state.url.isEmpty()) LINK.find(line)?.value?.let { run.state = run.state.copy(url = it); onChange() }
        // A refused code ("Invalid code...") is the one line worth showing while the sign-in still runs.
        if (line.startsWith("Invalid code", ignoreCase = true)) { run.state = run.state.copy(message = line.take(SHOWN_MESSAGE_LENGTH)); onChange() }
        else if (line.startsWith("Login failed", ignoreCase = true)) run.state = run.state.copy(message = line.take(SHOWN_MESSAGE_LENGTH))
    }

    private fun ended(harnessId: String, run: Running, exitCode: Int) {
        if (run.cancelled || runs[harnessId] !== run) return
        val signedIn = runCatching { verify(harnessId) }.getOrNull()
        run.state = when {
            exitCode == 0 && signedIn != false -> run.state.copy(state = HarnessLoginState.DONE, message = "Signed in.")
            else -> run.state.copy(state = HarnessLoginState.FAILED, message = run.state.message.ifBlank { "The sign-in did not finish. Start it again." })
        }
        onChange()
    }

    companion object {
        /** A failure line shown to the owner is cut here (layout, not a limit). */
        private const val SHOWN_MESSAGE_LENGTH = 300
        private val LINK = Regex("""https://[^\s"'<>\x1b\x07]+""")
        private val ANSI = Regex("""\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b\[[0-9;?]*[ -/]*[@-~]""")

        /** [raw] without terminal escapes (colour codes and OSC 8 hyperlinks, whose link text repeats the address). */
        fun plain(raw: String): String = ANSI.replace(raw, "").trim()
    }
}
