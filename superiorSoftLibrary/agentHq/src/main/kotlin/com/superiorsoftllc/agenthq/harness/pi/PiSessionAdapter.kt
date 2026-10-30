package com.superiorsoftllc.agenthq.harness.pi

import com.superiorsoftllc.agenthq.harness.HarnessEvent
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.host.AdapterSink
import com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter
import com.superiorsoftllc.agenthq.session.host.HostedProcess
import com.superiorsoftllc.agenthq.session.host.TurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * BUG-M2-11d (docs/AGENTHQ-SESSION-HOST-PLAN.md, probe: docs/AGENTHQ-SESSION-HOST-PROBE.md "pi 1.3.0"): Pi's
 * persistent-mode PROTOCOL (`--mode rpc`), and nothing else. The lifecycle (one process per session, reuse, the restart
 * after a crash, close, shutdown, the chat boot line) is [com.superiorsoftllc.agenthq.session.host.SessionHost]'s.
 *
 *  - [launch]: the one-shot argv [PiCommand] builds with `--mode rpc` instead of `--mode json`, plus `--session <id>` when
 *    the session has a provider id. stdin stays OPEN. pi buffers a command written right after the spawn (the probe
 *    wrote its first prompt at once and got the ack at +8.5 s), so the launch returns as soon as the process exists;
 *  - [sendTurn]: `{"id":…,"type":"prompt","message":…}`, then `get_state`, because RPC mode prints no `session` header
 *    and `get_state`'s `sessionId` is the only place the provider session id appears;
 *  - end of turn: `agent_settled` (NOT `agent_end`: retries / compaction can follow it). The verdict is the same one the
 *    one-shot run uses ([PiStreamTranslator.terminal] over the turn's lines), so a `stopReason: "error"` is FAILED;
 *    `response/prompt` with `success:false` is FAILED at once, and `disposition:"handled"` (no run started, so no
 *    `agent_settled` will come) is COMPLETED at once;
 *  - [abortTurn]: `{"type":"abort"}`; the turn ends on the `agent_settled {"aborted":true}` it produces (its
 *    `response/abort` may arrive later and is ignored); the process lives on;
 *  - a dialog `extension_ui_request` (select / confirm / input / editor) is answered cancelled: no one is at a TUI;
 *  - [close]: stdin EOF (pi exits 0 on it, in 26 ms in the probe);
 *  - CONTEXT-LIMIT-1 ([CAPABILITY], best effort): with a context limit on the run, a turn that settles cleanly is held for
 *    `get_session_stats`; when its `contextUsage.tokens` is over the limit the adapter sends `compact` and ends the turn on
 *    the compact's response, so the next turn starts compacted. pi has no flag or env for its threshold (only settings
 *    files: `compaction.reserveTokens`), so one turn can still grow past the limit.
 *
 * Every stdout line still goes out VERBATIM ([AdapterSink.output]); the agent event records are the ones `--mode json`
 * prints, and the extra `response` / `extension_ui_request` records map to nothing.
 */
class PiSessionAdapter(
    private val launcher: () -> List<String> = PiCommand::resolveLauncher,
) : HarnessSessionAdapter {
    override val label: String = "pi"

    private val ids = AtomicLong()

    /**
     * The launch argv without `--session`, + cwd + env, so a model or thinking change restarts and resumes. The launcher
     * is a constant here: it does not vary per session, and resolving it can throw outside the host's launch guard.
     */
    override fun fingerprint(request: RunRequest, env: Map<String, String>): String =
        argv(listOf("pi"), request, resume = null).joinToString("\u0000") + "\u0000" +
            request.workingDir?.absolutePath + "\u0000" + env.toSortedMap()

    override fun launch(request: RunRequest, env: Map<String, String>, runner: ProcessRunner, sink: AdapterSink): HostedProcess {
        val resume = request.resumeSessionId?.takeIf { it.isNotBlank() }
        val process = Process(sink)
        process.start(runner, argv(launcher(), request, resume) + PiLaunchExtras.all(request.workingDir, env), request, env)
        sink.detail(
            "pi rpc session (${process.pid?.let { "pid $it" } ?: "no OS process"})" +
                (resume?.let { ", resuming provider session $it" } ?: ""),
        )
        return process
    }

    override fun sendTurn(process: HostedProcess, request: RunRequest) = (process as Process).send(request)

    override fun abortTurn(process: HostedProcess): Boolean = (process as Process).abort()

    override fun close(process: HostedProcess): Boolean = (process as Process).close()

    private fun argv(launcher: List<String>, request: RunRequest, resume: String?): List<String> =
        PiCommand.command(launcher, request.model, resume, request.effort, rpc = true, toolApproval = request.toolApproval)

    private fun nextId(kind: String) = "agenthq-$kind-${ids.incrementAndGet()}"

    private class Turn(private val sink: AdapterSink, val promptId: String, val contextLimit: Int?) {
        private val ended = AtomicBoolean(false)
        val abortRequested = AtomicBoolean(false)

        /** The one-shot verdict, fed only this turn's lines. */
        val verdict = PiStreamTranslator()

        fun end(end: TurnEnd, exitCode: Int?, reason: String?) {
            if (ended.compareAndSet(false, true)) sink.turnEnded(end, exitCode, reason)
        }
    }

    /** One `pi --mode rpc` process. */
    private inner class Process(private val sink: AdapterSink) : HostedProcess {
        private val exitLock = Any()
        private val exitHandlers = mutableListOf<(Int) -> Unit>()
        @Volatile private var exitCode: Int? = null
        @Volatile private var child: RunningProcess? = null
        @Volatile private var active: Turn? = null
        @Volatile private var knownSession: String? = null

        /** CONTEXT-LIMIT-1: a settled turn held for the context check (stats, then maybe compact), with the id we wait for. */
        @Volatile private var held: Turn? = null
        @Volatile private var heldFor: String? = null

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
                ProcessSpec(command, request.workingDir, env),
                object : ProcessListener {
                    override fun onStdout(line: String) {
                        sink.output(line)
                        onLine(line)
                    }

                    override fun onStderr(line: String) = sink.diagnostic(line)

                    override fun onExit(exitCode: Int) = died(exitCode)
                },
            )
        }

        private fun onLine(line: String) {
            val record = parse(line) ?: return
            when (record.str("type")) {
                "response" -> onResponse(record)
                "extension_ui_request" -> answerDialog(record)
                "agent_settled" -> {
                    val turn = active ?: return
                    turn.verdict.translate(line)
                    active = null
                    val aborted = record.str("aborted") == "true"
                    when {
                        turn.abortRequested.get() || aborted -> turn.end(TurnEnd.INTERRUPTED, null, null)
                        else -> when (val v = turn.verdict.terminal(0, "")) {
                            is HarnessEvent.Error -> turn.end(TurnEnd.FAILED, null, v.message)
                            else -> if (turn.contextLimit == null || !hold(turn, statsLine(nextId("stats")))) turn.end(TurnEnd.COMPLETED, 0, null)
                        }
                    }
                }
                else -> active?.verdict?.translate(line)
            }
        }

        /** Holds [turn] until the response to [line] arrives; false (end it now) when the line cannot be written. */
        private fun hold(turn: Turn, line: String): Boolean {
            val process = child ?: return false
            held = turn
            heldFor = parse(line)?.str("id")
            return runCatching { process.writeInput(line + "\n") }.isSuccess.also { if (!it) { held = null; heldFor = null } }
        }

        private fun release(detail: String? = null) {
            val turn = held ?: return
            held = null
            heldFor = null
            detail?.let(sink::detail)
            turn.end(TurnEnd.COMPLETED, 0, null)
        }

        private fun onContextResponse(record: JsonObject) {
            val turn = held ?: return
            val data = record["data"] as? JsonObject
            when (record.str("command")) {
                "get_session_stats" -> {
                    val tokens = (data?.get("contextUsage") as? JsonObject)?.str("tokens")?.toLongOrNull()
                    val limit = turn.contextLimit ?: return release()
                    if (record.str("success") == "false" || tokens == null || tokens <= limit) return release()
                    sink.detail("context limit: $tokens tokens is over $limit, compacting")
                    if (!hold(turn, compactLine(nextId("compact")))) release()
                }
                "compact" -> release(
                    if (record.str("success") == "false") "context limit: pi refused the compaction (${record.str("error") ?: "no reason"})"
                    else "context limit: compacted ${data?.str("tokensBefore") ?: "?"} -> ~${data?.str("estimatedTokensAfter") ?: "?"} tokens",
                )
                else -> release()
            }
        }

        private fun onResponse(record: JsonObject) {
            if (held != null && record.str("id") == heldFor) return onContextResponse(record)
            when (record.str("command")) {
                "get_state" -> {
                    val id = (record["data"] as? JsonObject)?.str("sessionId")?.takeIf { it.isNotBlank() } ?: return
                    if (id != knownSession) {
                        knownSession = id
                        sink.providerSession(id)
                    }
                }
                "prompt" -> {
                    val turn = active ?: return
                    if (record.str("id") != null && record.str("id") != turn.promptId) return
                    when {
                        record.str("success") == "false" -> {
                            active = null
                            turn.end(TurnEnd.FAILED, null, record.str("error")?.takeIf { it.isNotBlank() } ?: "pi refused the prompt")
                        }
                        (record["data"] as? JsonObject)?.str("disposition") == "handled" -> {
                            active = null
                            turn.end(TurnEnd.COMPLETED, 0, null)
                        }
                    }
                }
            }
        }

        /** A dialog would wait forever for a TUI answer; fire-and-forget methods (setStatus, notify, ...) need none. */
        private fun answerDialog(record: JsonObject) {
            if (record.str("method") !in DIALOG_METHODS) return
            val id = record.str("id") ?: return
            runCatching { child?.writeInput(dialogCancelLine(id) + "\n") }
        }

        private fun died(code: Int) {
            // A turn in flight is the host's to end (FAILED, never retried): it sees the exit through onExit.
            val handlers = synchronized(exitLock) {
                if (exitCode != null) return
                exitCode = code
                exitHandlers.toList().also { exitHandlers.clear() }
            }
            handlers.forEach { runCatching { it(code) } }
        }

        fun send(request: RunRequest) {
            val turn = Turn(sink, nextId("prompt"), request.contextLimit?.takeIf { it > 0 }).also { active = it }
            val process = child
            if (process == null || !isAlive) {
                active = null
                return turn.end(TurnEnd.FAILED, exitCode, "the pi process is not running")
            }
            try {
                process.writeInput(promptLine(turn.promptId, request.prompt) + "\n" + stateLine(nextId("state")) + "\n")
            } catch (e: Exception) {
                active = null
                turn.end(TurnEnd.FAILED, null, "could not send the turn to pi: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        /** Stops the TURN; the process lives on. The turn ends on the `agent_settled` pi prints for it. */
        fun abort(): Boolean {
            held?.let { h -> held = null; heldFor = null; h.end(TurnEnd.INTERRUPTED, null, null); return true }
            val turn = active ?: return true
            turn.abortRequested.set(true)
            val process = child ?: return false
            return runCatching { process.writeInput(abortLine(nextId("abort")) + "\n") }.isSuccess
        }

        fun close(): Boolean {
            val process = child ?: return false
            runCatching { process.closeInput() }
            return true
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** pi's dialog methods, which block until answered (pi docs/rpc-extension-ui.md). */
        private val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")

        /** The one stdin line a turn is. */
        internal fun promptLine(id: String, prompt: String): String = buildJsonObject {
            put("id", id); put("type", "prompt"); put("message", prompt)
        }.toString()

        internal fun stateLine(id: String): String = buildJsonObject { put("id", id); put("type", "get_state") }.toString()

        /** CONTEXT-LIMIT-1: pi's current context use (`data.contextUsage.tokens`, docs/rpc-commands.md "get_session_stats"). */
        internal fun statsLine(id: String): String = buildJsonObject { put("id", id); put("type", "get_session_stats") }.toString()

        /** CONTEXT-LIMIT-1: pi's manual compaction (docs/rpc-commands.md "compact"). */
        internal fun compactLine(id: String): String = buildJsonObject { put("id", id); put("type", "compact") }.toString()

        /** CONTEXT-LIMIT-1: what pi's persistent session does with a context limit (see the class comment). */
        val CAPABILITY = com.superiorsoftllc.agenthq.harness.ContextLimitCapability(
            com.superiorsoftllc.agenthq.harness.ContextLimitSupport.BEST_EFFORT,
            "pi compacts after a turn that ends over the limit (rpc get_session_stats + compact); one turn can pass it",
        )

        /** Stops the current run and keeps the process (`agent_settled {"aborted":true}` in 9 ms in the probe). */
        internal fun abortLine(id: String): String = buildJsonObject { put("id", id); put("type", "abort") }.toString()

        internal fun dialogCancelLine(id: String): String = buildJsonObject {
            put("type", "extension_ui_response"); put("id", id); put("cancelled", true)
        }.toString()

        private fun parse(line: String): JsonObject? =
            runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
