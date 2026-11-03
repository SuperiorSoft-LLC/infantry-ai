// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.Tools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One queued action as the dispatcher handled it. [tool] is the registry name after the alias table; [ok] is null when nothing ran (dry-run). */
data class ActionRecord(val requested: String, val tool: String, val args: Map<String, Any?>, val phase: Int, val ok: Boolean? = null, val text: String? = null)

data class RunReport(
    val outcome: String,
    val records: List<ActionRecord>,
    val logs: List<String>,
    val phases: Int,
    val dryRun: Boolean,
    val error: String? = null,
    /** 1-based index in [records] of the action that failed (or could not be resolved), else null. */
    val failedAt: Int? = null,
    /** MAIL-HOOK-1: the script queued the reserved action `handled` (Lua `ih.handled()`) in its final phase. */
    val handled: Boolean = false,
    /** MAIL-HOOK-1: the script queued `deliver_text{text=...}`: the text to deliver instead. */
    val deliverText: String? = null,
    /** SCRIPT-LINE-TS-1: how long the run took (wall clock), for the chat line. */
    val durationMs: Long? = null,
) {
    val ok: Boolean get() = error == null

    /** The compact text a caller sees: the actions taken (or planned), each result, the log, and the failure if any. */
    fun render(name: String): String = buildString {
        append("script $name ${if (dryRun) "DRY-RUN " else ""}outcome=$outcome phases=$phases actions=${records.size}\n")
        records.forEachIndexed { i, r ->
            val shown = if (r.requested != r.tool) "${r.requested} (${r.tool})" else r.tool
            val status = when (r.ok) { null -> "planned"; true -> "ok"; false -> "FAILED" }
            append("${i + 1}. $shown ${ScriptEngineWire.toJson(r.args)} -> $status")
            r.text?.let { append(": ").append(it.take(RESULT_SHOWN).replace('\n', ' ')) }
            append('\n')
        }
        if (logs.isNotEmpty()) append("log:\n").append(logs.joinToString("\n") { "  $it" }).append('\n')
        error?.let { append("error: $it\n") }
    }.trimEnd()

    private companion object { const val RESULT_SHOWN = 600 }
}

/**
 * SCRIPT-4: runs a script's queued actions through the live tool registry ([Tools]), engine-agnostic (any [ScriptEngine]'s [QueuedAction]s).
 *
 * Results flow back TWO-PHASE: a run executes the actions the script queued, in order. A script that wants to read what they returned
 * queues the reserved action `again` (Lua: `ih.again()`); the dispatcher then re-runs it with `input.phase` = 2.. and `input.results` =
 * the list of every executed action's `{name, tool, ok, text, json}` so far. The re-run's own actions are the ones executed in that
 * phase (a script branches on `input.phase`). At most [maxPhases] runs; a dry-run is one phase (nothing executes, so there are no
 * results). An action's failure stops the run and the report names it.
 */
class ScriptDispatcher(
    private val tools: Tools,
    /** short name -> registry tool name, read at each run (data: the `scripting.aliases` setting). */
    private val aliases: () -> Map<String, String>,
    private val maxPhases: () -> Int = { 3 },
    /** SCRIPT-TICK-1: the project a session belongs to (null = unknown); lets a script's progress call target another session of the same project. */
    private val sessionProject: (String) -> String? = { null },
) {
    fun run(
        engine: ScriptEngine, source: String, args: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller,
        invoker: ToolInvoker, dryRun: Boolean, progressTo: String? = null, onPlanned: (List<ActionRecord>) -> Unit = {},
    ): RunReport {
        val started = System.nanoTime()
        System.err.println("[infantry] script run start (dryRun=$dryRun)")
        return try {
            runUntimed(engine, source, args, limits, caller, invoker, dryRun, progressTo, onPlanned)
                .copy(durationMs = (System.nanoTime() - started) / 1_000_000)
        } finally {
            System.err.println("[infantry] script run end after ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    private fun runUntimed(
        engine: ScriptEngine, source: String, args: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller,
        invoker: ToolInvoker, dryRun: Boolean,
        /** SCRIPT-TICK-1: the session a hook acts for (its `to`); `infantry_progress` from the script lands on its card unless the call names a `sessionId`. */
        progressTo: String? = null,
        /** SCRIPT-5: told the resolved actions of each phase BEFORE any of them runs (the hook loop guard attributes the events they cause). */
        onPlanned: (List<ActionRecord>) -> Unit = {},
    ): RunReport {
        val known = tools.catalog().map { it.name }.toSet()
        val table = aliases()
        val records = ArrayList<ActionRecord>()
        val logs = ArrayList<String>()
        val results = ArrayList<Map<String, Any?>>()
        val phases = if (dryRun) 1 else maxPhases().coerceIn(1, HARD_MAX_PHASES)
        var phase = 0
        var handled = false
        var deliverText: String? = null
        while (phase < phases) {
            phase++
            val input = buildMap<String, Any?> {
                put("args", args); put("phase", phase.toLong()); put("caller", mapOf("id" to caller.id, "role" to caller.role))
                if (phase > 1) put("results", results.toList())
            }
            val r = engine.run(source, input, limits, caller)
            logs += r.logs.map { if (phase > 1) "[phase $phase] $it" else it }
            if (r.outcome != ScriptOutcome.OK) return RunReport(r.outcome.name, records, logs, phase, dryRun, r.error ?: r.outcome.name)
            // Resolve every name first: an unknown tool fails the run before this phase executes anything.
            var again = false
            val planned = ArrayList<ActionRecord>()
            for (a in r.actions) {
                if (a.name == AGAIN) { again = true; continue }
                if (a.name == HANDLED) { handled = true; continue }
                if (a.name == DELIVER_TEXT) { deliverText = (a.args["text"] as? String)?.takeIf { it.isNotEmpty() }; continue }
                val tool = if (a.name in known) a.name else (table[a.name] ?: BUILTIN_ALIASES[a.name] ?: a.name).let(::legacyToolName).takeIf { it in known }
                    ?: return RunReport("ERROR", records + planned, logs, phase, dryRun, "unknown tool or alias '${a.name}' (see script_api)", records.size + planned.size + 1)
                planned += ActionRecord(a.name, tool, a.args, phase)
            }
            onPlanned(planned)
            if (dryRun) {
                records += planned
                return RunReport("OK", records, logs, phase, true)
            }
            for (p in planned) {
                // An action may say `_optional = true`: its failure is recorded (ok=false in input.results) but does not stop the run.
                val optional = p.args[OPTIONAL] == true
                val t0 = System.nanoTime()
                val res = if (p.tool == PROGRESS) progress(p, optional, invoker, progressTo) else tools.invoke(p.tool, toObject(if (optional) p.args - OPTIONAL else p.args), invoker)
                // START-FAST-1: per-action wall time in the run log, so a slow hook names its slow step.
                logs += "timing ${p.tool} ${(System.nanoTime() - t0) / 1_000_000}ms"
                records += p.copy(ok = !res.isError, text = res.text)
                results += mapOf("name" to p.requested, "tool" to p.tool, "ok" to !res.isError, "text" to res.text, "json" to jsonOf(res.text))
                if (res.isError && !optional) return RunReport("ACTION_FAILED", records, logs, phase, false, "action ${records.size} (${p.tool}) failed: ${res.text.take(300)}", records.size)
            }
            if (!again) return RunReport("OK", records, logs, phase, false, handled = handled, deliverText = deliverText)
        }
        return RunReport("PHASES_EXHAUSTED", records, logs, phase, dryRun, "ih.again() ignored: maxPhases ($phases) reached")
    }

    /** SCRIPT-TICK-1: runs `infantry_progress` for the session the script acts for: `sessionId` arg, else [progressTo], else the invoker's own. Same project only. */
    private fun progress(p: ActionRecord, optional: Boolean, invoker: ToolInvoker, progressTo: String?): ToolResult {
        val named = (p.args[SESSION_ID] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val args = p.args - SESSION_ID - (if (optional) setOf(OPTIONAL) else emptySet())
        val target = named ?: progressTo
        if (target == null || target == invoker.sessionId) return tools.invoke(p.tool, toObject(args), invoker)
        val project = sessionProject(target)
        if (project == null || project != invoker.projectId) return ToolResult("$PROGRESS refused: session '$target' is not a session of this project", true)
        val as2 = invoker.copy(sessionId = target, caller = if (invoker.caller.owner) invoker.caller else invoker.caller.copy(activeSession = target))
        return tools.invoke(p.tool, toObject(args), as2)
    }

    private fun toObject(m: Map<String, Any?>): JsonObject = ScriptEngineWire.toJson(m) as JsonObject

    private fun jsonOf(text: String): Any? {
        val t = text.trim()
        if (!(t.startsWith("{") || t.startsWith("["))) return null
        return runCatching { Json.parseToJsonElement(t).let { if (it is JsonObject || it is JsonArray) ScriptEngineWire.fromJson(it) else null } }.getOrNull()
    }

    /** RENAME-2: a pre-rename `agenthq_*` tool name (in a script or in the alias table) is the `infantry_*` tool. */
    private fun legacyToolName(n: String): String = if (n.startsWith("agenthq_")) "infantry_" + n.removePrefix("agenthq_") else n

    companion object {
        /** The reserved action name: re-run the script with the results so far. */
        const val AGAIN = "again"
        const val HANDLED = "handled"
        const val DELIVER_TEXT = "deliver_text"
        /** Names every project has without configuring: the process bridge (SCRIPT-CMD-1). */
        val BUILTIN_ALIASES = mapOf("exec" to "infantry_exec", "cli" to "infantry_exec", "exec_status" to "infantry_exec_status", "progress" to "infantry_progress", "chat_note" to "infantry_chat_note", "chat_image" to "infantry_chat_image",
            "session_read" to "infantry_session_read", "session_act" to "infantry_session_act", "notice_suppress" to "infantry_notice_suppress")
        const val OPTIONAL = "_optional"
        const val PROGRESS = "infantry_progress"
        const val SESSION_ID = "sessionId"
        const val HARD_MAX_PHASES = 10
    }
}
