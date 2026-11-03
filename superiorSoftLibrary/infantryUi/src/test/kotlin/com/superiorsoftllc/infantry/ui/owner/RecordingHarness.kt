// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.owner

import com.superiorsoftllc.infantry.daemon.InfantryMcp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * E2E-OWNER-1: the recording stub harness. The daemon runs it through a harness descriptor (`<state>/harnesses/<id>.json`)
 * exactly as it runs a real CLI agent: `java @<argfile> --harness <id> --records <dir> --mode lead|worker
 * [--resume {sessionId}] [--model {model}]`, the prompt on stdin, the session env inherited, its stdout read as `jsonl`
 * (a `session` record first, as every real harness reports its session id). No provider is ever called.
 *
 * WHAT IT RECORDS, before anything else: the harness id it was launched as, the `--model` it was given, the model's
 * upstream provider (`acme/m2` -> `acme`), its working directory, its session env (every `INFANTRY_*` except the token,
 * PATH, JAVA_HOME) and the prompt. One `<session>.<pid>.properties` per run, written atomically, so the test reads what
 * the worker was REALLY asked to run instead of what the ticket row claims.
 *
 * A WORKER also probes its own checkout (`git branch --show-current`, HEAD, top level, git dir vs common dir), and,
 * when its brief carries [REPLY_LINE], acts like an agent reporting in: it starts its OWN `infantry mcp serve` the way a
 * harness does ([InfantryMcp.launch] on its env), reads its ticket, checks where it stands, and mails the session that
 * started the ticket (`to: "starter"`) its ticket, branch, base sha and cwd. Then it HOLDS (a run that ended would let
 * the board auto-complete the ticket mid-scenario) until the test releases it, the daemon goes away, or [HOLD_MS].
 *
 * A LEAD (the PM) records and exits: its session stays, idle, for the test to drive through MCP and the CLI.
 */
object RecordingHarness {
    /** The line of a ticket brief that asks the worker to report back to its starter (the pm-roundtrip scenario). */
    const val REPLY_LINE = "owner-smoke: mail the starter your branch, base sha and cwd."

    /** Releases every holding worker of the rig. */
    const val RELEASE_ALL = "release-all"

    const val MODE_WORKER = "worker"
    const val MODE_LEAD = "lead"

    private const val HOLD_MS = 240_000L
    private const val PROMPT_WAIT_MS = 5_000L

    /** `You are running inside Infantry AI. Ticket: <id>. Project: ...` (TicketContextComposer's first line). */
    private val TICKET = Regex("""Ticket: ([^\s.]+)\.""")

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList().windowed(2, 2, partialWindows = false).associate { (k, v) -> k to v }
        val records = File(options.getValue("--records")).apply { mkdirs() }
        val harness = options.getValue("--harness")
        val mode = options["--mode"] ?: MODE_LEAD
        val model = options["--model"]
        val resumed = options["--resume"]
        // A provider session id, as every real harness reports one: without it the session is not resumable, and mail
        // to it (the worker's reply to its PM) is UNDELIVERABLE. A resumed turn keeps the id it was given.
        emit("session", "id" to (resumed ?: "rec-${UUID.randomUUID()}"))
        val prompt = readPrompt(System.`in`)
        val session = System.getenv("INFANTRY_SESSION_ID")?.takeIf { it.isNotBlank() } ?: "no-session"
        val pid = ProcessHandle.current().pid()
        val stem = "$session.$pid"
        val cwd = File("").absoluteFile.canonicalFile

        val facts = linkedMapOf(
            "harness" to harness,
            "mode" to mode,
            "model" to model.orEmpty(),
            "modelProvider" to (model?.takeIf { '/' in it }?.substringBefore('/') ?: ""),
            "resume" to resumed.orEmpty(),
            "sessionId" to session,
            "cwd" to cwd.path,
            "pid" to pid.toString(),
            "parentPid" to (ProcessHandle.current().parent().map { it.pid().toString() }.orElse("")),
            "startedAt" to System.currentTimeMillis().toString(),
        )
        System.getenv().keys.filter { it.uppercase().startsWith("INFANTRY_") && it.uppercase() != "INFANTRY_TOKEN" }
            .forEach { key -> System.getenv(key)?.let { facts["env.${key.uppercase()}"] = it } }
        listOf("PATH", "JAVA_HOME").forEach { key -> System.getenv(key)?.let { facts["env.$key"] = it } }

        if (mode == MODE_WORKER) {
            facts["git.branch"] = git(cwd, "branch", "--show-current")
            facts["git.head"] = git(cwd, "rev-parse", "HEAD")
            facts["git.toplevel"] = canonical(git(cwd, "rev-parse", "--show-toplevel"))
            facts["git.dir"] = canonical(git(cwd, "rev-parse", "--absolute-git-dir"))
            facts["git.commonDir"] = canonical(git(cwd, "rev-parse", "--path-format=absolute", "--git-common-dir"))
            facts["ticketId"] = TICKET.find(prompt)?.groupValues?.get(1).orEmpty()
        }
        File(records, "$stem.prompt.txt").writeText(prompt)
        writeAtomically(File(records, "$stem.properties"), facts)
        emit("text", "text" to "[owner-smoke] $harness ($mode) recorded model=${model ?: "(none)"} cwd=${cwd.path}")

        if (mode == MODE_WORKER) {
            if (REPLY_LINE in prompt) reportToStarter(records, stem, facts, cwd)
            hold(records, session)
        }
        exitProcess(0)
    }

    /**
     * The worker's report, through its own `infantry mcp serve`: read the ticket, check where it stands, mail the
     * starter. The outcome (or the failure) lands in `<stem>.reply.properties` for the test's diagnostics; the test's
     * real evidence is what the PM finds in its inbox.
     */
    private fun reportToStarter(records: File, stem: String, facts: Map<String, String>, cwd: File) {
        val outcome = linkedMapOf<String, String>()
        try {
            val launch = InfantryMcp.launch(System.getenv())
            outcome["mcp.argv"] = launch.argv.joinToString(" ")
            McpStdio.start(launch.argv, cwd, "worker", File(records, "$stem.mcp.err")) { env -> env.putAll(launch.env) }.use { mcp ->
                val ticketId = facts.getValue("ticketId")
                val ticket = mcp.call(TICKET_GET, buildJsonObject { put("ticketId", ticketId) })
                if (ticket.isError) throw IllegalStateException("$TICKET_GET refused: ${ticket.text}")
                val branch = facts.getValue("git.branch")
                val checks = linkedMapOf(
                    // its cwd is a LINKED worktree's top level, not a main checkout
                    "cwdIsLinkedWorktree" to (facts["git.toplevel"] == cwd.path && facts["git.dir"] != facts["git.commonDir"]),
                    "branchIsTicketBranch" to (branch == ticket.string("branch")),
                    "sessionIsTicketWorker" to (ticket.string("sessionId") == facts["sessionId"]),
                )
                outcome["ticket.branch"] = ticket.string("branch").orEmpty()
                outcome["ticket.sessionId"] = ticket.string("sessionId").orEmpty()
                outcome["ticket.status"] = ticket.string("status").orEmpty()
                checks.forEach { (k, v) -> outcome["check.$k"] = v.toString() }
                val body = buildJsonObject {
                    put("ticketId", ticketId)
                    put("sessionId", facts["sessionId"])
                    put("branch", branch)
                    put("baseSha", facts["git.head"])
                    put("cwd", cwd.path)
                    put("checks", buildJsonObject { checks.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
                }.toString()
                val sent = mcp.call(MESSAGE, buildJsonObject {
                    put("to", "starter")
                    put("subject", "owner-smoke reply $ticketId")
                    put("body", body)
                })
                outcome["mail.isError"] = sent.isError.toString()
                outcome["mail.answer"] = sent.text
            }
        } catch (e: Throwable) {
            outcome["error"] = e.toString()
        }
        writeAtomically(File(records, "$stem.reply.properties"), outcome)
    }

    /** Stays in the run until released ([RELEASE_ALL] or `<session>.release`), the daemon is gone, or [HOLD_MS]. */
    private fun hold(records: File, session: String) {
        val parent = ProcessHandle.current().parent().orElse(null)
        val end = System.currentTimeMillis() + HOLD_MS
        while (System.currentTimeMillis() < end) {
            if (File(records, RELEASE_ALL).exists() || File(records, "$session.release").exists()) return
            if (parent != null && !parent.isAlive) return
            Thread.sleep(200)
        }
    }

    /** The prompt the daemon wrote to stdin: everything up to EOF, or what arrived within [PROMPT_WAIT_MS]. */
    private fun readPrompt(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val reader = thread(isDaemon = true, name = "prompt") {
            runCatching {
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                }
            }
        }
        reader.join(PROMPT_WAIT_MS)
        return synchronized(buffer) { buffer.toString(Charsets.UTF_8) }
    }

    private fun git(cwd: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(cwd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
        return if (process.exitValue() == 0) out else "error(${process.exitValue()}): $out"
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** One stdout record of the descriptor's `jsonl` output (`{"kind": ..., <fields>}`). */
    private fun emit(kind: String, vararg fields: Pair<String, String>) {
        println(buildJsonObject {
            put("kind", kind)
            fields.forEach { (k, v) -> put(k, v) }
        })
        System.out.flush()
    }

    private fun writeAtomically(target: File, facts: Map<String, String>) {
        val properties = Properties().apply { facts.forEach { (k, v) -> setProperty(k, v) } }
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { properties.store(it, "owner-smoke recording") }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    const val TICKET_GET = "infantry_ticket_get"
    const val MESSAGE = "infantry_message"
}
