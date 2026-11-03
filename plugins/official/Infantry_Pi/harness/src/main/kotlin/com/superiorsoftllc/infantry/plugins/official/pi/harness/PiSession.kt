package com.superiorsoftllc.infantry.plugins.official.pi.harness

import com.superiorsoftllc.infantry.harness.api.ContextLimitCapability
import com.superiorsoftllc.infantry.harness.api.ContextLimitSupport
import com.superiorsoftllc.infantry.harness.api.HarnessEvent
import com.superiorsoftllc.infantry.harness.api.HarnessHost
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessSession
import com.superiorsoftllc.infantry.harness.api.SessionConnection
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * BUG-M2-11d (docs/INFANTRY-SESSION-HOST-PLAN.md, probe: docs/INFANTRY-SESSION-HOST-PROBE.md "pi 1.3.0") / HARNESS H6c: Pi's persistent-mode
 * PROTOCOL (`--mode rpc`), and nothing else. The lifecycle (one process per session, reuse, the restart after a crash, close, shutdown, the
 * chat boot line) is the daemon's session host's (harness API 2.8 [HarnessSession]).
 *
 *  - [launch]: the one-shot argv [PiCommand] builds with `--mode rpc` instead of `--mode json`, plus `--session <id>` when the session has a
 *    provider id, plus the to-do extension. stdin stays OPEN. pi buffers a command written right after the spawn (the probe wrote its first
 *    prompt at once and got the ack at +8.5 s), so the launch returns as soon as the process exists. The daemon adds the watch extension and
 *    the sandbox dir from this harness's own `watch()` / `sandbox()` contributions;
 *  - [PiConnection.sendTurn]: `{"id":...,"type":"prompt","message":...}`, then `get_state`, because RPC mode prints no `session` header and
 *    `get_state`'s `sessionId` is the only place the provider session id appears;
 *  - end of turn: `agent_settled` (NOT `agent_end`: retries / compaction can follow it). The verdict is the same one the one-shot run uses
 *    ([EventStreamTranslator.terminal] over the turn's lines), so a `stopReason: "error"` is FAILED; `response/prompt` with `success:false` is
 *    FAILED at once, and `disposition:"handled"` (no run started, so no `agent_settled` will come) is COMPLETED at once;
 *  - [PiConnection.abortTurn]: `{"type":"abort"}`; the turn ends on the `agent_settled {"aborted":true}` it produces (its `response/abort` may
 *    arrive later and is ignored); the process lives on;
 *  - a dialog `extension_ui_request` (select / confirm / input / editor) is answered cancelled: no one is at a TUI;
 *  - [PiConnection.close]: stdin EOF (pi exits 0 on it, in 26 ms in the probe);
 *  - CONTEXT-LIMIT-1 ([CAPABILITY], best effort): with a context limit on the run, a turn that settles cleanly is held for
 *    `get_session_stats`; when its `contextUsage.tokens` is over the limit the connection sends `compact` and ends the turn on the compact's
 *    response, so the next turn starts compacted. pi has no flag or env for its threshold (only settings files: `compaction.reserveTokens`),
 *    so one turn can still grow past the limit.
 *
 * Every stdout line still goes out VERBATIM (the host forwards it to the raw stream); the agent event records are the ones `--mode json`
 * prints, and the extra `response` / `extension_ui_request` records map to nothing.
 */
internal class PiSession(
    private val host: HarnessHost?,
    private val launcher: () -> List<String>,
) : HarnessSession {
    override val label: String = "pi"

    /**
     * The launch argv without `--session`, + cwd + env, so a model or thinking change restarts and resumes. The launcher is a constant here:
     * it does not vary per session, and resolving it can throw outside the host's launch guard.
     */
    override fun fingerprint(request: HarnessRequest, env: Map<String, String>): String =
        argv(listOf("pi"), request, resume = null).joinToString("\u0000") + "\u0000" + request.project + "\u0000" + env.toSortedMap()

    override fun launch(request: HarnessRequest, env: Map<String, String>): HarnessProcess {
        val resume = request.sessionId?.takeIf { it.isNotBlank() }
        val todo = host?.let { PiExtensions.launchArgs(it.stateDir, PiExtensions.TODO) }.orEmpty()
        return HarnessProcess(argv(launcher(), request, resume) + todo)
    }

    override fun connect(out: SessionOutput): SessionConnection = PiConnection(out)

    private fun argv(launcher: List<String>, request: HarnessRequest, resume: String?): List<String> =
        PiCommand.command(launcher, request.model, resume, request.effort, rpc = true)

    companion object {
        /** CONTEXT-LIMIT-1: what pi's persistent session does with a context limit (see the class comment). */
        val CAPABILITY = ContextLimitCapability(
            ContextLimitSupport.BEST_EFFORT,
            "pi compacts after a turn that ends over the limit (rpc get_session_stats + compact); one turn can pass it",
        )

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** pi's dialog methods, which block until answered (pi docs/rpc-extension-ui.md). */
        private val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")

        /** The one stdin line a turn is. */
        fun promptLine(id: String, prompt: String): String = buildJsonObject {
            put("id", id); put("type", "prompt"); put("message", prompt)
        }.toString()

        fun stateLine(id: String): String = buildJsonObject { put("id", id); put("type", "get_state") }.toString()

        /** CONTEXT-LIMIT-1: pi's current context use (`data.contextUsage.tokens`, docs/rpc-commands.md "get_session_stats"). */
        fun statsLine(id: String): String = buildJsonObject { put("id", id); put("type", "get_session_stats") }.toString()

        /** CONTEXT-LIMIT-1: pi's manual compaction (docs/rpc-commands.md "compact"). */
        fun compactLine(id: String): String = buildJsonObject { put("id", id); put("type", "compact") }.toString()

        /** Stops the current run and keeps the process (`agent_settled {"aborted":true}` in 9 ms in the probe). */
        fun abortLine(id: String): String = buildJsonObject { put("id", id); put("type", "abort") }.toString()

        fun dialogCancelLine(id: String): String = buildJsonObject {
            put("type", "extension_ui_response"); put("id", id); put("cancelled", true)
        }.toString()

        private fun parse(line: String): JsonObject? =
            runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull()

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private class Turn(private val out: SessionOutput, val promptId: String, val contextLimit: Int?) {
        private val ended = AtomicBoolean(false)
        val abortRequested = AtomicBoolean(false)

        /** The one-shot verdict, fed only this turn's lines. */
        val verdict = EventStreamTranslator()

        fun end(end: SessionTurnEnd, exitCode: Int?, reason: String?) {
            if (ended.compareAndSet(false, true)) out.turnEnded(end, exitCode, reason)
        }
    }

    /** The protocol state of ONE `pi --mode rpc` process. */
    internal class PiConnection(private val out: SessionOutput) : SessionConnection {
        private val ids = AtomicLong()
        @Volatile private var active: Turn? = null
        @Volatile private var knownSession: String? = null

        /** CONTEXT-LIMIT-1: a settled turn held for the context check (stats, then maybe compact), with the id we wait for. */
        @Volatile private var held: Turn? = null
        @Volatile private var heldFor: String? = null

        private fun nextId(kind: String) = "infantry-$kind-${ids.incrementAndGet()}"

        override fun onStdout(line: String) {
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
                        turn.abortRequested.get() || aborted -> turn.end(SessionTurnEnd.INTERRUPTED, null, null)
                        else -> when (val v = turn.verdict.terminal(0, "")) {
                            is HarnessEvent.Error -> turn.end(SessionTurnEnd.FAILED, null, v.message)
                            else -> if (turn.contextLimit == null || !hold(turn, statsLine(nextId("stats")))) turn.end(SessionTurnEnd.COMPLETED, 0, null)
                        }
                    }
                }
                else -> active?.verdict?.translate(line)
            }
        }

        /** Holds [turn] until the response to [line] arrives; false (end it now) when the line cannot be written. */
        private fun hold(turn: Turn, line: String): Boolean {
            held = turn
            heldFor = parse(line)?.str("id")
            return out.write(line + "\n").also { if (!it) { held = null; heldFor = null } }
        }

        private fun release(detail: String? = null) {
            val turn = held ?: return
            held = null
            heldFor = null
            detail?.let(out::detail)
            turn.end(SessionTurnEnd.COMPLETED, 0, null)
        }

        private fun onContextResponse(record: JsonObject) {
            val turn = held ?: return
            val data = record["data"] as? JsonObject
            when (record.str("command")) {
                "get_session_stats" -> {
                    val tokens = (data?.get("contextUsage") as? JsonObject)?.str("tokens")?.toLongOrNull()
                    val limit = turn.contextLimit ?: return release()
                    if (record.str("success") == "false" || tokens == null || tokens <= limit) return release()
                    out.detail("context limit: $tokens tokens is over $limit, compacting")
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
                        out.providerSession(id)
                    }
                }
                "prompt" -> {
                    val turn = active ?: return
                    if (record.str("id") != null && record.str("id") != turn.promptId) return
                    when {
                        record.str("success") == "false" -> {
                            active = null
                            turn.end(SessionTurnEnd.FAILED, null, record.str("error")?.takeIf { it.isNotBlank() } ?: "pi refused the prompt")
                        }
                        (record["data"] as? JsonObject)?.str("disposition") == "handled" -> {
                            active = null
                            turn.end(SessionTurnEnd.COMPLETED, 0, null)
                        }
                    }
                }
            }
        }

        /** A dialog would wait forever for a TUI answer; fire-and-forget methods (setStatus, notify, ...) need none. */
        private fun answerDialog(record: JsonObject) {
            if (record.str("method") !in DIALOG_METHODS) return
            val id = record.str("id") ?: return
            out.write(dialogCancelLine(id) + "\n")
        }

        override fun sendTurn(request: HarnessRequest) {
            val turn = Turn(out, nextId("prompt"), request.contextLimit?.takeIf { it > 0 }).also { active = it }
            // A write that fails means the process is gone; the host sees its exit and ends the turn FAILED, never retried.
            if (!out.write(promptLine(turn.promptId, request.prompt) + "\n" + stateLine(nextId("state")) + "\n")) {
                active = null
                turn.end(SessionTurnEnd.FAILED, null, "could not send the turn to pi: the process is not running")
            }
        }

        /** Stops the TURN; the process lives on. The turn ends on the `agent_settled` pi prints for it. */
        override fun abortTurn(): Boolean {
            held?.let { h -> held = null; heldFor = null; h.end(SessionTurnEnd.INTERRUPTED, null, null); return true }
            val turn = active ?: return true
            turn.abortRequested.set(true)
            return out.write(abortLine(nextId("abort")) + "\n")
        }

        override fun close(): Boolean {
            out.closeInput()
            return true
        }
    }
}
