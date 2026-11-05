// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessSession
import com.superiorsoftllc.infantry.harness.api.SessionConnection
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HARNESS H7b-2: Claude Code's persistent-mode PROTOCOL on the harness API (the twin of the daemon's `ClaudeSessionAdapter`). The lifecycle
 * (one process per session, reuse, restart after a crash, close) is the host's. Per turn: one stdin line
 * `{"type":"user","message":{"role":"user","content":[{"type":"text","text":...}]}}`; the turn ends on the `result` record (COMPLETED, or
 * FAILED with the CLI's reason, INTERRUPTED after [SessionConnection.abortTurn]); abort is a `control_request` interrupt and the process
 * lives on; a `result` that belongs to an interrupted turn a newer prompt superseded ends nothing.
 *
 * NOT carried over (the harness API has no prompt relay for it): the `ask` approval relay (`--permission-prompt-tool stdio`, `can_use_tool`
 * answered by the owner). Until the plugin takes over production that stays in the daemon's adapter; here `ask` behaves like `deny`.
 */
class ClaudeSession(
    private val executable: () -> String,
    private val mcp: (Map<String, String>) -> Pair<List<String>?, Map<String, String>>,
    private val watchSettings: (Map<String, String>) -> String? = { null },
    private val requestId: () -> String = { UUID.randomUUID().toString() },
    /** `harness.claude.liveIdleMinutes`: 0 = never stop a resting process (the default, a live CLI stays up). */
    private val idleMinutes: () -> Int = { 0 },
    /** G2: claude's config folder (its transcripts), to tell a resumable id from one claude never recorded. */
    private val configDir: () -> File = { ClaudeTranscripts.configDir() },
    /** G2: the plugin's state folder, where the interactive session keeps a new session's first prompt; null = none to send again. */
    private val stateDir: (() -> File)? = null,
) : HarnessSession {
    override val label: String = "claude"

    override fun idleMinutes(): Int = idleMinutes.invoke()

    /** G2: launches that start a stored id fresh (claude has no transcript for it), until [connect] takes them. */
    private val recoveries = IdentityHashMap<HarnessProcess, String>()

    private fun argv(exe: String, request: HarnessRequest, resume: String?, env: Map<String, String>, fresh: String? = null): List<String> {
        val (mcpCommand, mcpEnv) = mcp(env)
        return ClaudeCommand.command(
            executable = exe,
            model = ClaudeCommand.effectiveModel(request.model),
            permissionMode = ClaudeCommand.permissionMode(request.toolApproval),
            mcpConfigs = ClaudeCommand.mcpConfigs(ClaudeCommand.userMcpConfigs(), mcpCommand, mcpEnv),
            strictMcp = ClaudeCommand.strictMcp(),
            resumeSessionId = resume,
            effort = request.effort,
            streamInput = true,
            settings = watchSettings(env).takeIf { request.toolApproval == ToolApproval.ALLOW_ALL },
            sessionId = fresh,
        )
    }

    /** The launch argv without `--resume`, the working dir and the env: a model, effort or approval change restarts and resumes. */
    override fun fingerprint(request: HarnessRequest, env: Map<String, String>): String =
        argv("claude", request, resume = null, env).joinToString("\u0000") + "\u0000" + request.project + "\u0000" +
            (env + ClaudeCommand.contextLimitEnv(request.contextLimit)).toSortedMap()

    override fun launch(request: HarnessRequest, env: Map<String, String>): HarnessProcess {
        val stored = request.sessionId?.takeIf { it.isNotBlank() }
        // G2: `--resume` of an id claude never recorded fails on every start; start that id fresh instead (the daemon keeps the same id).
        val fresh = stored?.takeUnless { ClaudeResume.resumable(configDir(), request.project, it) }
        val process = HarnessProcess(
            command = argv(executable(), request, stored.takeIf { fresh == null }, env, fresh),
            // Claude Code 2.1.30x has no TodoWrite headless; this enables TaskCreate/TaskUpdate.
            env = mapOf("CLAUDE_CODE_ENABLE_TODO_TOOLS" to "1") + ClaudeCommand.contextLimitEnv(request.contextLimit),
        )
        if (fresh != null) synchronized(recoveries) { recoveries[process] = fresh }
        return process
    }

    override fun connect(out: SessionOutput): SessionConnection = Connection(out, requestId)

    override fun connect(out: SessionOutput, launched: HarnessProcess): SessionConnection {
        val fresh = synchronized(recoveries) { recoveries.remove(launched) } ?: return connect(out)
        return Connection(out, requestId, recovery = fresh to stateDir?.let { ClaudeResume.firstPromptFile(it(), fresh) })
    }

    /** [own] = CLI-PTY-4: claude began it by itself; no sendTurn stands behind it. */
    private class Turn(private val out: SessionOutput, val own: Boolean = false) {
        private val ended = AtomicBoolean(false)
        val abortRequested = AtomicBoolean(false)

        fun end(end: SessionTurnEnd, exitCode: Int?, reason: String?) {
            if (ended.compareAndSet(false, true)) out.turnEnded(end, exitCode, reason)
        }
    }

    /** [recovery] = G2: the id this process starts fresh and where its lost first prompt is kept; its first turn sends that prompt again. */
    private class Connection(
        private val out: SessionOutput,
        private val requestId: () -> String,
        private var recovery: Pair<String, File?>? = null,
    ) : SessionConnection {
        private val lock = Any()
        private var active: Turn? = null
        private var staleResults = 0
        /** CLI-PTY-4: turns claude began itself that were still running when a turn was sent; results end them first, in order. */
        private val ownAhead = ArrayDeque<Turn>()

        override fun onStdout(line: String) {
            val record = parse(line) ?: return
            val type = record.str("type")
            if (type == OWN_TURN_RECORD) {
                // CLI-PTY-4: claude answers with no turn sent (it took up a notice of its own, e.g. a background task that ended): a turn
                // the host must track like a sent one, ended by its `result` below.
                val own = synchronized(lock) { if (active == null && staleResults == 0) Turn(out, own = true).also { active = it } else null }
                own?.let { out.turnStarted("claude began a turn of its own") }
                return
            }
            if (type != "result") return
            val turn = synchronized(lock) {
                if (staleResults > 0) {
                    staleResults--
                    return
                }
                ownAhead.removeFirstOrNull() ?: active.also { active = null }
            } ?: return
            when {
                turn.abortRequested.get() -> turn.end(SessionTurnEnd.INTERRUPTED, null, null)
                isError(record) -> turn.end(SessionTurnEnd.FAILED, null, errorReason(record))
                else -> turn.end(SessionTurnEnd.COMPLETED, 0, null)
            }
        }

        override fun sendTurn(request: HarnessRequest) {
            val turn = Turn(out)
            val failure = synchronized(lock) {
                if (active?.abortRequested?.get() == true) staleResults++
                else active?.takeIf { it.own }?.let { ownAhead.addLast(it) }
                active = turn
                if (out.write(userLine(prompt(request.prompt)) + "\n")) null else { active = null; "could not send the turn to claude" }
            }
            failure?.let { turn.end(SessionTurnEnd.FAILED, null, it) }
        }

        /** G2: the first turn of a recovered process carries the session's lost first prompt ahead of its own. Under [lock]. */
        private fun prompt(prompt: String): String {
            val (id, file) = recovery ?: return prompt
            recovery = null
            val saved = file?.let(ClaudeResume::savedFirstPrompt)
            out.detail(ClaudeResume.recoveredDetail(id, briefResent = saved != null && saved != prompt))
            return ClaudeResume.recoveryPrompt(saved, prompt)
        }

        override fun abortTurn(): Boolean = synchronized(lock) {
            val turn = active ?: return true
            turn.abortRequested.set(true)
            out.write(interruptLine(requestId()) + "\n")
        }

        /**
         * CHAT-QUEUE-1: a user message on the open input stream while a turn runs; claude takes it into that turn at its next step. A
         * `result` it may answer it with after the turn's own is a turn of its own (CLI-PTY-4 path above). Refused with no turn running.
         */
        override fun injectInput(text: String): Boolean = synchronized(lock) {
            val turn = active ?: return false
            if (turn.abortRequested.get()) return false
            out.write(userLine(text) + "\n")
        }

        override fun close(): Boolean {
            out.closeInput()
            return true
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** CLI-PTY-4: the stream-json record type that shows a turn is running (a reply); seen with no turn open, claude began one itself. */
        const val OWN_TURN_RECORD = "assistant"

        /** The one stdin line a turn is. */
        fun userLine(prompt: String): String = buildJsonObject {
            put("type", "user")
            put("message", buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", prompt) }) })
            })
        }.toString()

        /** Stops the current turn and keeps the process. */
        fun interruptLine(requestId: String): String = buildJsonObject {
            put("type", "control_request")
            put("request_id", requestId)
            put("request", buildJsonObject { put("subtype", "interrupt") })
        }.toString()

        private fun parse(line: String): JsonObject? = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun isError(record: JsonObject): Boolean = record.str("is_error") == "true" || record.str("subtype")?.startsWith("error") == true

        private fun errorReason(record: JsonObject): String =
            record.str("result")?.takeIf { it.isNotBlank() } ?: record.str("subtype")?.takeIf { it.isNotBlank() } ?: "claude reported an error"
    }
}
