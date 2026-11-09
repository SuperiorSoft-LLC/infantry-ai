// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.session.SessionWaiting
import com.superiorsoftllc.infantry.session.WaitingRequest
import com.superiorsoftllc.infantry.session.WaitingRules
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * WAITING-FLAG-1: `infantry waiting on --reason "..." [--minutes N]`, `infantry waiting off`, `infantry waiting show`.
 *
 * One plain command, no MCP, any harness: it sends the `waiting` frame for the session in `INFANTRY_SESSION_ID`. Unlike
 * `infantry progress` it is NOT silent on failure: an agent that believes it is flagged and is not would be nudged for
 * nothing, so a refusal, a missing session or an unreachable daemon prints why on stderr and exits 1.
 */
internal object WaitingCli {
    /** WAITING-FLAG-1: the subcommands; the CLI dispatcher sends `waiting <one of these>` here, a bare `waiting` stays the mailbox view. */
    val OPS = setOf("on", "off", "show", "get")
    val USAGE = """
        |infantry waiting on --reason "what you wait for" [--minutes N]   flag this session "active but waiting" (default N: setting limits.waiting.defaultMinutes)
        |infantry waiting off                                            turn the flag off
        |infantry waiting show                                           print the flag
        |The flag also clears by itself when your next turn starts, and expires N minutes (+ grace) after it was set.
    """.trimMargin()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun run(args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?, open: () -> FrameStream): Int {
        val op = args.firstOrNull()?.lowercase()
        if (op == null || op == "--help" || op == "-h" || op == "help") {
            out.appendLine(USAGE)
            return if (op == null) 2 else 0
        }
        val request = try {
            parse(op, args.drop(1))
        } catch (e: IllegalArgumentException) {
            err.appendLine("error: waiting: ${e.message}")
            err.appendLine(USAGE)
            return 2
        }
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        if (session == null) {
            err.appendLine("error: waiting: not running inside an Infantry AI session (no ${AccessPolicy.SESSION_ID_ENV}); nothing flagged")
            return 1
        }
        return try {
            val answer = runBlocking {
                val stream = open()
                try {
                    stream.request(
                        Envelope(Envelope.WAITING, json.encodeToString(WaitingRequest.serializer(), request), session),
                        answerType = Envelope.WAITING, what = "waiting", idempotent = false, timeoutMs = 5_000,
                    ).data
                } finally {
                    runCatching { stream.close() }
                }
            }
            val flag = answer.trim().takeIf { it.isNotEmpty() && it != "null" }?.let { json.decodeFromString(SessionWaiting.serializer(), it) }
            out.appendLine(WaitingRules.line(flag, System.currentTimeMillis()))
            0
        } catch (e: Exception) {
            err.appendLine("error: waiting: ${e.message ?: e::class.simpleName}")
            1
        }
    }

    private fun parse(op: String, rest: List<String>): WaitingRequest {
        require(op in listOf("on", "off", "show", "get")) { "unknown command '${op.take(40)}' (on, off, show)" }
        if (op == "off") return WaitingRequest("off")
        if (op != "on") return WaitingRequest("get")
        var reason: String? = null
        var minutes: Int? = null
        var i = 0
        val free = mutableListOf<String>()
        while (i < rest.size) {
            when (val a = rest[i]) {
                "--reason" -> reason = rest.getOrNull(++i) ?: throw IllegalArgumentException("--reason needs a value")
                "--minutes" -> minutes = rest.getOrNull(++i)?.toIntOrNull() ?: throw IllegalArgumentException("--minutes needs a whole number")
                else -> if (a.startsWith("--")) throw IllegalArgumentException("unknown option '${a.take(40)}'") else free += a
            }
            i++
        }
        if (reason == null && free.isNotEmpty()) reason = free.joinToString(" ")
        require(!reason.isNullOrBlank()) { "on needs --reason \"what you are waiting for\"" }
        return WaitingRequest("on", reason, minutes)
    }
}
