package com.superiorsoftllc.agenthq.claude

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.prompts.PromptAsker
import com.superiorsoftllc.agenthq.prompts.QuestionSpec
import com.superiorsoftllc.agenthq.protocol.PromptOption
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.host.AdapterSink
import com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter
import com.superiorsoftllc.agenthq.session.host.HostedProcess
import com.superiorsoftllc.agenthq.session.host.TurnEnd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BUG-M2-11c (docs/AGENTHQ-SESSION-HOST-PLAN.md section 6.2): Claude Code's persistent-mode PROTOCOL, and nothing else.
 * The lifecycle (one process per session, reuse, the restart after a crash, close, shutdown, the chat boot line) is
 * [com.superiorsoftllc.agenthq.session.host.SessionHost]'s, the same for every harness.
 *
 *  - [launch]: the one-shot argv [ClaudeCommand] builds, plus `--input-format stream-json`, plus `--resume <id>` when the
 *    session has a provider id. stdin stays OPEN. There is no ready signal to wait for: claude buffers a turn written
 *    right after the spawn (probe, claude 2.1.303), so the launch returns as soon as the process exists;
 *  - [sendTurn]: one stdin line, `{"type":"user","message":{"role":"user","content":[{"type":"text","text":…}]}}`;
 *  - end of turn: the `result` record. COMPLETED when it is not an error, otherwise FAILED with the CLI's reason;
 *    INTERRUPTED when it follows [abortTurn];
 *  - [abortTurn]: a `control_request` interrupt; the process lives on. The manager settles an interrupt at once, so the
 *    owner's next prompt can reach claude before claude prints the interrupted turn's `result`: that late result is the
 *    old turn's, so it ends nothing and is not forwarded (it would read as an ERROR of the new turn);
 *  - [close]: stdin EOF (claude exits 0 on it).
 *  - PROMPT-3, Tool approval `ask` only: the launch adds `--permission-prompt-tool stdio`, so a call outside the
 *    allowlist arrives as a `control_request` `can_use_tool`. An ordinary tool goes to [AdapterSink.prompts]
 *    `requestApproval` (allow_once / allow_session -> `allow` with the input unchanged; deny or timeout -> `deny`);
 *    `AskUserQuestion` goes to `askQuestion`, one card per question, and the answers return as `allow` with
 *    `updatedInput.answers` = { question text: chosen label(s), comma-joined }. `allow_session` is remembered by the
 *    asker for the daemon session. The `agenthq` CLI stays on `--allowedTools`, so it is never asked about. Each
 *    request is answered off the stdout thread; an abort or the process's death cancels the ones still open.
 *
 * Every other stdout line still goes out VERBATIM ([AdapterSink.output]), so the daemon's normaliser and
 * `sessionIdFromLine` see exactly what the one-shot run printed. `system/init` arriving again on each turn is harmless
 * there: the normaliser maps `system` to nothing, and the session id it carries is the same.
 */
class ClaudeSessionAdapter(
    private val executable: () -> String = ClaudeCommand::resolveExecutable,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) : HarnessSessionAdapter {
    override val label: String = "claude"

    /**
     * The launch argv without `--resume`, + cwd + env. A model or effort change therefore restarts and resumes (plan
     * decision D5). The executable is a constant here: it does not vary per session, and resolving it could throw
     * outside the host's launch guard.
     */
    override fun fingerprint(request: RunRequest, env: Map<String, String>): String =
        argv("claude", request, resume = null, env).joinToString("\u0000") + "\u0000" +
            request.workingDir?.absolutePath + "\u0000" + (env + ClaudeContextLimit.env(request.contextLimit)).toSortedMap()

    override fun launch(request: RunRequest, env: Map<String, String>, runner: ProcessRunner, sink: AdapterSink): HostedProcess {
        val resume = request.resumeSessionId?.takeIf { it.isNotBlank() }
        val command = argv(executable(), request, resume, env)
        val process = Process(sink)
        // Claude Code 2.1.30x has no TodoWrite headless; this enables TaskCreate/TaskUpdate (mapped to `todos` by ClaudeTaskTools).
        // CONTEXT-LIMIT-1: the limit rides the env (in the fingerprint above, so a change restarts + resumes).
        process.start(runner, command, request, env + ("CLAUDE_CODE_ENABLE_TODO_TOOLS" to "1") + ClaudeContextLimit.env(request.contextLimit))
        sink.detail(
            "claude stream-json session (${process.pid?.let { "pid $it" } ?: "no OS process"})" +
                (resume?.let { ", resuming provider session $it" } ?: ""),
        )
        return process
    }

    override fun sendTurn(process: HostedProcess, request: RunRequest) = (process as Process).send(request)

    override fun abortTurn(process: HostedProcess): Boolean = (process as Process).abort()

    override fun close(process: HostedProcess): Boolean = (process as Process).close()

    private fun argv(executable: String, request: RunRequest, resume: String?, env: Map<String, String>): List<String> = ClaudeCommand.command(
        executable = executable,
        model = ClaudeCommand.effectiveModel(request.model),
        permissionMode = ClaudeCommand.permissionMode(request.toolApproval), // BUG-PERM-1: in the fingerprint, so a change restarts + resumes
        mcpConfigs = ClaudeCommand.sessionMcpConfigs(env), // MCP-1: the session's own `agenthq mcp serve`
        strictMcp = ClaudeCommand.strictMcp(),
        resumeSessionId = resume,
        effort = request.effort,
        streamInput = true,
        // PROMPT-3: in the fingerprint too, so switching into / out of `ask` restarts + resumes.
        permissionPromptTool = ClaudeCommand.PERMISSION_PROMPT_STDIO.takeIf { request.toolApproval == ToolApproval.ASK },
        // STALL-2: the watch PreToolUse hook (allowAll = bypassPermissions only; the daemon checks the mode again).
        settings = com.superiorsoftllc.agenthq.daemon.WatchHooks.claudeSettingsArg(env).takeIf { request.toolApproval == ToolApproval.ALLOW_ALL },
    )

    private class Turn(private val sink: AdapterSink) {
        private val ended = AtomicBoolean(false)
        val abortRequested = AtomicBoolean(false)

        fun end(end: TurnEnd, exitCode: Int?, reason: String?) {
            if (ended.compareAndSet(false, true)) sink.turnEnded(end, exitCode, reason)
        }
    }

    /** One `claude -p --input-format stream-json` process. */
    private inner class Process(private val sink: AdapterSink) : HostedProcess {
        private val exitLock = Any()
        private val exitHandlers = mutableListOf<(Int) -> Unit>()
        @Volatile private var exitCode: Int? = null
        @Volatile private var child: RunningProcess? = null
        /** Guards [active] and [staleResults], and orders a turn's user line before its interrupt on stdin. */
        private val turnLock = Any()
        private var active: Turn? = null
        /** `result` records still owed to interrupted turns a newer prompt superseded. */
        private var staleResults = 0
        /** PROMPT-3: the `can_use_tool` requests waiting on the owner, by request id. */
        private val promptScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) } // only `ask` ever needs it
        private val openPrompts = java.util.concurrent.ConcurrentHashMap<String, Job>()

        override val pid: Long? get() = child?.pid
        override val isAlive: Boolean get() = exitCode == null && child?.isAlive == true

        override fun onExit(handler: (exitCode: Int) -> Unit) {
            val code = synchronized(exitLock) { exitCode ?: run { exitHandlers += handler; null } } ?: return
            handler(code)
        }

        override fun killTree() {
            child?.let { runCatching { it.killTree() } }
        }

        fun start(runner: ProcessRunner, command: List<String>, request: RunRequest, env: Map<String, String>) {
            child = runner.start(
                com.superiorsoftllc.agenthq.runner.ProcessSpec(command, request.workingDir, env),
                object : ProcessListener {
                    override fun onStdout(line: String) = onLine(line)

                    override fun onStderr(line: String) = sink.diagnostic(line)

                    override fun onExit(exitCode: Int) = died(exitCode)
                },
            )
        }

        /** The turn's end is the `result` record; every other line is only forwarded. */
        private fun onLine(line: String) {
            val record = parse(line)
            when (record?.str("type")) {
                "control_request" ->
                    if ((record["request"] as? JsonObject)?.str("subtype") == "can_use_tool") return canUseTool(record)
                "control_cancel_request" -> record.str("request_id")?.let { openPrompts[it]?.cancel() }
            }
            if (record?.str("type") != "result") return sink.output(line)
            val turn = synchronized(turnLock) {
                if (staleResults > 0) {
                    staleResults--
                    return
                }
                active.also { active = null }
            }
            sink.output(line)
            if (turn == null) return
            when {
                turn.abortRequested.get() -> turn.end(TurnEnd.INTERRUPTED, null, null)
                isError(record) -> turn.end(TurnEnd.FAILED, null, errorReason(record))
                else -> turn.end(TurnEnd.COMPLETED, 0, null)
            }
        }

        /** PROMPT-3: ask the owner off this (stdout) thread, then answer claude on stdin. */
        private fun canUseTool(record: JsonObject) {
            val id = record.str("request_id") ?: return
            val request = record["request"] as JsonObject
            val asker = sink.prompts
            val session = sink.sessionId
            val job = promptScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val response = try {
                        decide(asker, session, request)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        deny("Infantry AI could not relay this request: ${e.message ?: e.javaClass.simpleName}")
                    }
                    write(controlResponseLine(id, response))
                } finally {
                    openPrompts.remove(id)
                }
            }
            openPrompts[id] = job
            job.start()
        }

        private fun write(line: String) {
            val process = child ?: return
            synchronized(turnLock) { runCatching { process.writeInput(line + "\n") } }
        }

        private fun died(code: Int) {
            openPrompts.values.toList().forEach { it.cancel() } // PROMPT-3: nobody is left to answer; the asker closes the cards.
            // A turn in flight is the host's to end (FAILED, never retried): it sees the exit through onExit.
            val handlers = synchronized(exitLock) {
                if (exitCode != null) return
                exitCode = code
                exitHandlers.toList().also { exitHandlers.clear() }
            }
            handlers.forEach { runCatching { it(code) } }
        }

        fun send(request: RunRequest) {
            val turn = Turn(sink)
            val process = child
            if (process == null || !isAlive) return turn.end(TurnEnd.FAILED, exitCode, "the claude process is not running")
            val failure = synchronized(turnLock) {
                // An interrupted turn still waiting for its `result`: the host already settled it, the result is its own.
                if (active?.abortRequested?.get() == true) staleResults++
                active = turn
                try {
                    process.writeInput(userLine(request.prompt) + "\n")
                    null
                } catch (e: Exception) {
                    active = null
                    "could not send the turn to claude: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            failure?.let { turn.end(TurnEnd.FAILED, null, it) }
        }

        /** Stops the TURN; the process lives on. The turn ends on the `result` claude prints for it. */
        fun abort(): Boolean = synchronized(turnLock) {
            val turn = active ?: return true
            turn.abortRequested.set(true)
            openPrompts.values.toList().forEach { it.cancel() } // PROMPT-3: the stopped turn's cards close
            val process = child ?: return false
            runCatching { process.writeInput(interruptLine(requestId()) + "\n") }.isSuccess
        }

        fun close(): Boolean {
            val process = child ?: return false
            runCatching { process.closeInput() }
            return true
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** PROMPT-3: Claude's question tool, answered through `updatedInput.answers` instead of a terminal UI. */
        internal const val ASK_USER_QUESTION = "AskUserQuestion"

        /** PROMPT-3: the `response` a `can_use_tool` [request] gets, after asking the owner through [asker]. */
        internal suspend fun decide(asker: PromptAsker, session: String, request: JsonObject): JsonObject {
            val tool = request.str("tool_name").orEmpty()
            val input = request["input"] as? JsonObject ?: JsonObject(emptyMap())
            if (tool == ASK_USER_QUESTION) return answerQuestions(asker, session, input)
            val outcome = asker.requestApprovalOutcome(session, tool, approvalSummary(input))
            if (outcome.decision.allows) return allow(input)
            // ASK-TIMER: the worker is told the timer decided, so it carries on instead of waiting for the owner.
            return deny(
                if (outcome.timedOut) "The owner did not answer the approval prompt for $tool within ${outcome.timeoutSec}s, so " +
                    "Infantry AI denied it automatically. Do not wait for approval: continue without this call or take another approach."
                else "The user denied permission to use $tool (or the prompt timed out).",
            )
        }

        private suspend fun answerQuestions(asker: PromptAsker, session: String, input: JsonObject): JsonObject {
            val questions = (input["questions"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            if (questions.isEmpty()) return deny("AskUserQuestion carried no questions.")
            val answers = LinkedHashMap<String, JsonElement>()
            for (q in questions) {
                val text = q.str("question").orEmpty()
                val options = (q["options"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                    .map { PromptOption(it.str("label").orEmpty(), it.str("description").orEmpty()) }
                // Claude's own question UI always offers "Other", so the card does too.
                // ASK-TIMER-2: the option Claude labelled "(Recommended)" is what the prompt timer auto-selects.
                val spec = QuestionSpec(
                    text, options, multiSelect = q.str("multiSelect") == "true", allowOther = true,
                    recommended = QuestionSpec.recommendedIn(options),
                )
                val answer = asker.askQuestion(session, spec)
                if (!answer.answered) return deny("The user did not answer the question (or the prompt timed out).")
                answers[text] = JsonPrimitive((answer.answers + listOfNotNull(answer.otherText)).joinToString(", "))
            }
            return allow(JsonObject(input + ("answers" to JsonObject(answers))))
        }

        /** What an approval card shows: the call's most telling field, else its whole input (the asker trims it). */
        internal fun approvalSummary(input: JsonObject): String =
            SUMMARY_FIELDS.firstNotNullOfOrNull { input.str(it)?.takeIf(String::isNotBlank) } ?: input.toString()

        private val SUMMARY_FIELDS = listOf("command", "file_path", "notebook_path", "url", "pattern", "path", "query")

        private fun allow(input: JsonObject): JsonObject = buildJsonObject {
            put("behavior", "allow")
            put("updatedInput", input)
        }

        private fun deny(message: String): JsonObject = buildJsonObject {
            put("behavior", "deny")
            put("message", message)
        }

        /** The stdin line that answers `can_use_tool` [requestId] with [response]. */
        internal fun controlResponseLine(requestId: String, response: JsonElement): String = buildJsonObject {
            put("type", "control_response")
            put(
                "response",
                buildJsonObject {
                    put("subtype", "success")
                    put("request_id", requestId)
                    put("response", response)
                },
            )
        }.toString()

        /** The one stdin line a turn is. */
        internal fun userLine(prompt: String): String = buildJsonObject {
            put("type", "user")
            put(
                "message",
                buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", prompt) }) })
                },
            )
        }.toString()

        /** Stops the current turn and keeps the process (`control_response` came in 12 ms in the probe). */
        internal fun interruptLine(requestId: String): String = buildJsonObject {
            put("type", "control_request")
            put("request_id", requestId)
            put("request", buildJsonObject { put("subtype", "interrupt") })
        }.toString()

        private fun parse(line: String): JsonObject? =
            runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun isError(record: JsonObject): Boolean =
            record.str("is_error") == "true" || record.str("subtype")?.startsWith("error") == true

        private fun errorReason(record: JsonObject): String =
            record.str("result")?.takeIf { it.isNotBlank() }
                ?: record.str("subtype")?.takeIf { it.isNotBlank() }
                ?: "claude reported an error"
    }
}
