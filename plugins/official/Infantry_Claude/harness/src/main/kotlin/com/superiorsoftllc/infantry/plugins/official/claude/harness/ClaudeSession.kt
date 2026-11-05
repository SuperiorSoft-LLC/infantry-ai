// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.MCP_PID_FILE_ENV
import com.superiorsoftllc.infantry.harness.api.ApprovalReply
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
 * HARNESS-GAP G4 (ticket c2f05317): Tool approval ASK launches with `--permission-prompt-tool stdio`, so every call that would ask arrives on
 * stdout as a `control_request` `can_use_tool`; it is relayed to the owner through [SessionOutput.requestApproval] (off the read thread, it
 * blocks until the owner answers or the host's prompt timer ends it) and answered with a `control_response` on stdin: any allow answer
 * allows this call with its own input, anything else (deny, timeout, session gone) denies it with a message. Every other control request
 * claude sends is answered with an error so it never waits on one.
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
    /** G12: whether a process id still runs (the MCP server check); a test fakes it. */
    private val pidAlive: (Long) -> Boolean = ::processAlive,
) : HarnessSession {
    override val label: String = "claude"

    override fun idleMinutes(): Int = idleMinutes.invoke()

    /** G2: launches that start a stored id fresh (claude has no transcript for it), until [connect] takes them. */
    private val recoveries = IdentityHashMap<HarnessProcess, String>()

    private fun argv(
        exe: String, request: HarnessRequest, resume: String?, env: Map<String, String>, fresh: String? = null,
        extraMcpEnv: Map<String, String> = emptyMap(),
    ): List<String> {
        val (mcpCommand, baseMcpEnv) = mcp(env)
        val mcpEnv = baseMcpEnv + extraMcpEnv
        return ClaudeCommand.command(
            executable = exe,
            model = ClaudeCommand.effectiveModel(request.model),
            permissionMode = ClaudeCommand.permissionMode(request.toolApproval),
            mcpConfigs = ClaudeCommand.mcpConfigs(ClaudeCommand.userMcpConfigs(), mcpCommand, mcpEnv),
            strictMcp = ClaudeCommand.strictMcp(),
            resumeSessionId = resume,
            effort = request.effort,
            streamInput = true,
            permissionPromptTool = ClaudeCommand.PERMISSION_PROMPT_STDIO.takeIf { request.toolApproval == ToolApproval.ASK },
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
        // G12: the MCP server writes its pid here; the connection polls it and reports session.mcp (headless claude had no signal before).
        val mcpPid = File(File(stateDir?.invoke() ?: File(System.getProperty("java.io.tmpdir"), "infantry-claude"), "mcp").apply { mkdirs() }, "${UUID.randomUUID()}.pid")
        val process = HarnessProcess(
            command = argv(executable(), request, stored.takeIf { fresh == null }, env, fresh, mapOf(MCP_PID_FILE_ENV to mcpPid.absolutePath)),
            // Claude Code 2.1.30x has no TodoWrite headless; this enables TaskCreate/TaskUpdate.
            env = mapOf("CLAUDE_CODE_ENABLE_TODO_TOOLS" to "1") + ClaudeCommand.contextLimitEnv(request.contextLimit),
        )
        if (fresh != null) synchronized(recoveries) { recoveries[process] = fresh }
        synchronized(pidFiles) { pidFiles[process] = mcpPid }
        return process
    }

    private val pidFiles = IdentityHashMap<HarnessProcess, File>()

    override fun connect(out: SessionOutput): SessionConnection = Connection(out, requestId)

    override fun connect(out: SessionOutput, launched: HarnessProcess): SessionConnection {
        val pidFile = synchronized(pidFiles) { pidFiles.remove(launched) }
        val fresh = synchronized(recoveries) { recoveries.remove(launched) }
        return Connection(
            out, requestId,
            recovery = fresh?.let { it to stateDir?.let { dir -> ClaudeResume.firstPromptFile(dir(), it) } },
            mcpWatch = pidFile?.let { McpPidWatch(it, out, pidAlive) },
        )
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
        private val mcpWatch: McpPidWatch? = null,
    ) : SessionConnection {
        private val lock = Any()
        @Volatile private var watching = true
        private val mcpThread: Thread? = mcpWatch?.let { w ->
            Thread({
                while (watching) {
                    runCatching { w.check() }
                    try { Thread.sleep(McpPidWatch.MCP_CHECK_MS) } catch (_: InterruptedException) { break }
                }
            }, "infantry-claude-mcp-watch").apply { isDaemon = true; start() }
        }
        private var active: Turn? = null
        private var staleResults = 0
        /** CLI-PTY-4: turns claude began itself that were still running when a turn was sent; results end them first, in order. */
        private val ownAhead = ArrayDeque<Turn>()

        override fun onStdout(line: String) {
            val record = parse(line) ?: return
            val type = record.str("type")
            if (type == "control_request") {
                controlRequest(record)
                return
            }
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

        /** G4: claude asks about one tool call (`can_use_tool`); the owner answers on a thread of its own, the read loop goes on. */
        private fun controlRequest(record: JsonObject) {
            val id = record.str("request_id") ?: return
            val request = record["request"] as? JsonObject
            if (request?.str("subtype") != CAN_USE_TOOL) {
                send(controlError(id, "unsupported control request ${request?.str("subtype")}"))
                return
            }
            val tool = request.str("tool_name")?.takeIf { it.isNotBlank() } ?: "tool"
            val input = request["input"] as? JsonObject ?: JsonObject(emptyMap())
            Thread({
                val reply = runCatching { out.requestApproval(tool, approvalText(input)) }.getOrDefault(ApprovalReply.DENY)
                send(permissionResponse(id, reply != ApprovalReply.DENY, input, denyMessage(tool)))
            }, "infantry-claude-approval").apply { isDaemon = true; start() }
        }

        private fun send(line: String) { synchronized(lock) { out.write(line + "\n") } }

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

        override fun onExit(exitCode: Int) { watching = false; mcpThread?.interrupt() }

        override fun close(): Boolean {
            out.closeInput()
            return true
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** G4: the control request subtype claude sends for a tool call that needs the owner's approval. */
        const val CAN_USE_TOOL = "can_use_tool"

        fun denyMessage(tool: String): String = "The owner denied $tool (or did not answer in time). Do not retry it; carry on without it."

        /** What the owner reads for a call: a shell call's command, else the input JSON. */
        fun approvalText(input: JsonObject): String = input.str("command") ?: input.toString()

        /** G4: the answer to one `can_use_tool` request; an allow hands back the call's own input unchanged. */
        fun permissionResponse(requestId: String, allow: Boolean, input: JsonObject, message: String): String = buildJsonObject {
            put("type", "control_response")
            put("response", buildJsonObject {
                put("subtype", "success")
                put("request_id", requestId)
                put("response", buildJsonObject {
                    if (allow) { put("behavior", "allow"); put("updatedInput", input) } else { put("behavior", "deny"); put("message", message) }
                })
            })
        }.toString()

        fun controlError(requestId: String, error: String): String = buildJsonObject {
            put("type", "control_response")
            put("response", buildJsonObject { put("subtype", "error"); put("request_id", requestId); put("error", error) })
        }.toString()

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
