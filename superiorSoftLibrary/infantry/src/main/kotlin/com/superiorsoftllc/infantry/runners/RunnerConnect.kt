// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * RUNNER-PAIR-1: "Connect a runner" in one step (the `runner_admin` op `connect`, owner-only like every runner admin op).
 *
 * The owner gives a name and [the command that reaches the runner machine's shell][splitVia] (their own text: whatever runs a
 * command there, e.g. an exec into a container or a remote shell; no default names a stack, OS or host). The orchestrator runs
 * `<via> infantry runner pair --name <name> --allow-projects * --allow-harnesses *` through it, reads the pairing link the
 * runner prints, and redeems it with the same code path as `runner add '<link>'`. The link (it carries the one-time code) stays
 * in this process's memory: it is never logged, returned, or shown, and no line of the child's output that carries it is put
 * into an error text ([safeLine]).
 *
 * Default grant: every project and every harness (`*`), as the owner chose (a runner is just another place to send any harness
 * and any project); the owner narrows it later on the runner with `runner.projects` / `runner.harnesses` when they want to.
 */
object RunnerConnect {
    /**
     * The words the runner side runs after [splitVia]: the existing `runner pair` with only the flags every runner version reads.
     * The address this machine reaches the runner on is not sent there: [reachAt] applies it here, so an older runner pairs too.
     */
    fun pairCommand(via: List<String>, name: String): List<String> {
        require(via.isNotEmpty()) { NEED_VIA }
        return via + listOf("infantry", "runner", "pair", "--name", name, "--allow-projects", ANY, "--allow-harnesses", ANY)
    }

    /**
     * What makes a just-paired runner serve its paired link now: `runner up` asks the runner daemon's `pair_status`, which settles
     * the listener once the pairing ended, and exits at once on a paired runner. Every runner version reads it.
     */
    fun settleCommand(via: List<String>, name: String): List<String> = via + listOf("infantry", "runner", "up", "--name", name)

    /** The owner's optional address and port, checked: blank = null. */
    fun address(host: String?, port: String?): Pair<String?, Int?> {
        val h = host?.trim()?.takeIf { it.isNotEmpty() }
        val p = port?.trim()?.takeIf { it.isNotEmpty() }
        require(p == null || p.toIntOrNull()?.let { it in 1..MAX_PORT } == true) { "the port must be a number from 1 to $MAX_PORT (the one this machine reaches the runner on)" }
        require(h == null || h.none { it.isWhitespace() || it == '\'' || it == '"' }) { "the address must be one host name or IP address" }
        return h to p?.toInt()
    }

    /**
     * The printed link, reached the way this machine reaches the runner: [host] tried first (the link's own hosts stay as the
     * fallback) and [port] in place of the link's when given (a published container port). Code and fingerprint are unchanged,
     * so the pairing is exactly the one the runner issued.
     */
    fun reachAt(link: RunnerPairLink, host: String?, port: Int?): RunnerPairLink =
        if (host == null && port == null) link
        else RunnerPairLink(
            link.name, (listOfNotNull(host) + link.hosts).distinct(), port ?: link.port, link.code, link.fingerprint, link.plain, link.grant,
        )

    /** The owner's command, split into words; `"..."` and `'...'` keep spaces in one word. Blank = [NEED_VIA]. */
    fun splitVia(via: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var any = false
        for (c in via.trim()) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> word.append(c)
                c == '"' || c == '\'' -> { quote = c; any = true }
                c.isWhitespace() -> { if (word.isNotEmpty() || any) words += word.toString(); word.clear(); any = false }
                else -> word.append(c)
            }
        }
        require(quote == null) { "the command has an unclosed quote" }
        if (word.isNotEmpty() || any) words += word.toString()
        require(words.isNotEmpty()) { NEED_VIA }
        return words
    }

    /** The pairing link in one line of the runner's output, or null. */
    fun findLink(line: String): String? = LINK.find(line)?.value

    /** A child-output line that may go into an error text: never one that carries the link or its code. */
    fun safeLine(line: String): Boolean =
        !line.contains(RunnerPairLink.PREFIX) && !line.contains("pair code:", ignoreCase = true) && !line.contains("--code")

    /**
     * The plain reason and the next step when the runner printed no link: from its own `error:` line when it gave one (the
     * runner's text is about the runner, so it is kept), else from the exit or the wait.
     */
    fun explain(viaWord: String, lines: List<String>, exit: Int?, waitedSec: Long, startError: String?): String {
        if (startError != null) {
            return "could not run '$viaWord' on this machine ($startError). Check the command that reaches the runner machine."
        }
        val said = lines.filter(::safeLine).map { it.trim() }.filter { it.isNotEmpty() }
        val error = said.lastOrNull { it.startsWith("error:") }?.removePrefix("error:")?.trim()
        if (error != null) {
            if (error.contains("already paired with orchestrator")) {
                return "that runner is already paired with another orchestrator ($error). To move it here, run `infantry runner detach` " +
                    "on the runner machine (through the same command), then Connect again."
            }
            if (error.contains("cannot serve the runner link on port")) {
                return "the runner could not open its link port ($error). Free that port on the runner machine, then Connect again."
            }
            return "the runner refused: $error"
        }
        if (exit == null) return "the runner printed no pairing link within $waitedSec s (limits.runners.connectSec). Is a runner daemon running there?"
        val last = said.takeLast(SHOWN_LINES).joinToString(" | ").take(SHOWN_CHARS)
        return "the command ended (exit $exit) without a pairing link" + (if (last.isNotEmpty()) ": $last" else "") +
            ". Check that it reaches the runner machine and that `infantry` runs there."
    }

    /** What an add that reached no host of the link adds: the address and port fields are the fix. */
    fun unreachableHint(message: String?, host: String?, port: String?): String =
        (message ?: "no host of the runner's link answered") +
            if (host.isNullOrBlank()) ". Fill in the address this machine reaches the runner on (and the port, if it is published under another one), then Connect again."
            else if (port.isNullOrBlank()) ". If the runner's link port is published under another port, fill in that port, then Connect again."
            else ". Check that $host:$port reaches the runner's link port from this machine."

    /** One issued pairing: the [link] (or why there is none, [failure]) and the child, finished by [finish]. */
    class Issued(val link: String?, val failure: String?, private val finishBy: (waitMs: Long) -> Unit = {}) {
        /** Lets the runner's `pair` see the pairing and exit by itself; kills it after [waitMs]. */
        fun finish(waitMs: Long) = finishBy(waitMs)
    }

    /** Runs the runner-side command and returns once it printed the link, ended, or [timeoutMs] passed. */
    fun interface Issuer {
        fun issue(argv: List<String>, timeoutMs: Long): Issued
    }

    /** The [Issuer] over a [ProcessRunner] (the daemon's plain pipes by default). */
    fun processIssuer(processes: ProcessRunner = PipedProcessRunner()): Issuer = Issuer { argv, timeoutMs ->
        val link = CompletableFuture<String?>()
        val ended = CompletableFuture<Int>()
        // Only what [explain] can use: the last `error:` line and the last few safe lines (never the link's line).
        val lines = ArrayDeque<String>()
        var lastError: String? = null
        var exit: Int? = null
        val listener = object : ProcessListener {
            override fun onStdout(line: String) = take(line)
            override fun onStderr(line: String) = take(line)
            private fun take(line: String) {
                val found = findLink(line)
                if (found != null) { link.complete(found); return }
                if (!safeLine(line) || line.isBlank()) return
                synchronized(lines) {
                    if (line.trim().startsWith("error:")) lastError = line
                    lines.addLast(line)
                    while (lines.size > SHOWN_LINES) lines.removeFirst()
                }
            }
            override fun onExit(exitCode: Int) { exit = exitCode }
        }
        val child: RunningProcess = try {
            processes.start(ProcessSpec(argv), listener)
        } catch (e: Exception) {
            return@Issuer Issued(null, explain(argv.first(), emptyList(), null, 0, e.message ?: e.javaClass.simpleName))
        }
        runCatching { child.closeInput() }
        // A child that ends is seen by [awaitExit] too, so the wait never hangs on a listener that is not called.
        Thread({ runCatching { val code = child.awaitExit(); exit = code; ended.complete(code); link.complete(null) } }, "runner-connect-wait").apply { isDaemon = true }.start()
        val got = runCatching { link.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
        val finish: (Long) -> Unit = { waitMs ->
            runCatching { ended.get(waitMs, TimeUnit.MILLISECONDS) }
            if (child.isAlive) runCatching { child.killTree() }
        }
        if (got != null) Issued(got, null, finish)
        else {
            if (child.isAlive) runCatching { child.killTree() }
            val said = synchronized(lines) { listOfNotNull(lastError?.takeIf { it !in lines }) + lines }
            Issued(null, explain(argv.first(), said, exit, timeoutMs / MS_PER_SECOND, null))
        }
    }

    const val ANY = "*"
    const val NEED_VIA = "say how to reach the runner machine: the command that runs a command there (for example an exec into its container, or a remote shell)"
    private val LINK = Regex("""infantry://runner\?[^\s'"]+""")
    private const val MAX_PORT = 65535
    private const val SHOWN_LINES = 3
    private const val SHOWN_CHARS = 400
    private const val MS_PER_SECOND = 1000L
}
