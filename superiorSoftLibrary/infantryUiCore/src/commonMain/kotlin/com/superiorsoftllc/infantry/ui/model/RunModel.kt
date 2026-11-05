// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * In-app build (contract 1.33.0): the pure run model. No Compose, no platform APIs.
 *
 * Rules (docs/INFANTRY-IN-APP-BUILD-DESIGN.md section 4):
 *  - state is keyed by `runId`;
 *  - a `run_output` is applied only when `sequence == lastSequence + 1`;
 *  - a lower-or-equal sequence is a duplicate and is dropped;
 *  - a higher sequence is a GAP: the line is not applied and [RunState.gap] says what was expected, so the UI
 *    shows a warning. A gap is never skipped silently, and later lines are not applied past it;
 *  - at most [MAX_RUN_LINES] lines are kept in memory, oldest dropped first.
 */
const val MAX_RUN_LINES = 2000

/**
 * A saved command as the daemon lists it. [argv] and [graceMillis] arrive from contract 1.94.0 (RUN-CMD-1) for the editor;
 * [secrets] from 1.135.0 (SECRETS-2): the keystore secrets whose fields the run gets as environment variables (names only).
 */
@Serializable
data class CommandSpecView(
    val id: String,
    val name: String,
    val cwd: String? = null,
    val argv: List<String> = emptyList(),
    val graceMillis: Long? = null,
    val secrets: List<String> = emptyList(),
    /** RUN-SCOPE-1: the project the command belongs to; null = unassigned (or a daemon from before per-project commands). */
    val project: String? = null,
    /** RUN-SCOPE-1: false when no folder resolves for it (its project has none registered): listed, but it cannot start. */
    val rooted: Boolean = true,
)

/**
 * RUN-SCOPE-1: [commands] as project [projectId]'s Run tab shows them: its own, then the unassigned ones apart. A daemon
 * from before per-project commands sends no project at all; then every command is the project's own (nothing to assign).
 */
data class ProjectCommands(val own: List<CommandSpecView>, val unassigned: List<CommandSpecView>)

fun projectCommands(commands: List<CommandSpecView>, projectId: String?): ProjectCommands {
    if (projectId == null || commands.none { it.project != null }) return ProjectCommands(commands, emptyList())
    return ProjectCommands(commands.filter { it.project == projectId }, commands.filter { it.project == null })
}

@Serializable
data class RunRecordPayload(
    val runId: String,
    val commandId: String,
    val state: String,
    val startedAt: Long? = null,
    val pid: Long? = null,
    val lastSequence: Long = 0,
    /** 1.134.0: the command's name at start, whether the run has a pty, whether it takes keys. */
    val name: String? = null,
    val terminal: Boolean = false,
    val interactive: Boolean = false,
)

/** 1.134.0 `runs`: one kept run, for its pill. */
@Serializable
data class RunSummaryPayload(
    val runId: String,
    val commandId: String,
    val name: String? = null,
    val state: String,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val outcome: String? = null,
    val terminal: Boolean = false,
    val interactive: Boolean = false,
)

/**
 * 1.134.0 `run_bytes`: raw terminal output, [data] base64. Not kept in [AppState] (a console is the only reader): the
 * controller hands each one to its console as it arrives. A [replay] is the scrollback up to chunk [sequence].
 */
@Serializable
data class RunBytesPayload(
    val runId: String,
    val sequence: Long,
    val data: String = "",
    val replay: Boolean = false,
    val droppedBytes: Long = 0,
)

@Serializable
data class RunOutputPayload(
    val runId: String,
    val sequence: Long,
    val stream: String = "stdout",
    val line: String = "",
    val truncated: Boolean = false,
)

@Serializable
data class RunFinishedPayload(
    val runId: String,
    val exitCode: Int? = null,
    val outcome: String,
    val endedAt: Long? = null,
    val detail: String? = null,
)

data class RunLine(val sequence: Long, val stream: String, val text: String, val truncated: Boolean)

/** A sequence hole: [expected] was due, [got] arrived instead. */
data class RunGap(val expected: Long, val got: Long)

data class RunState(
    val runId: String,
    val commandId: String? = null,
    /** `STARTING`, `RUNNING` or `FINISHED`. */
    val state: String = "STARTING",
    val lines: List<RunLine> = emptyList(),
    val lastSequence: Long = 0,
    /** Lines evicted by the memory cap, so the pane can say "N earlier lines hidden". */
    val droppedLines: Int = 0,
    val gap: RunGap? = null,
    val finished: RunFinishedPayload? = null,
    /** 1.134.0: the pill's label and clock, and what its console may do. */
    val name: String? = null,
    val startedAt: Long? = null,
    val terminal: Boolean = false,
    val interactive: Boolean = false,
)

data class RunsState(
    val commands: List<CommandSpecView> = emptyList(),
    val runs: Map<String, RunState> = emptyMap(),
    val currentRunId: String? = null,
    /** RUN-CMD-2: the daemon's last `command_save` / `command_delete` refusal, shown inline; a new list clears it. */
    val commandError: String? = null,
    /** RUN-CMD-2: bumped on every `commands` frame AND every command_* refusal, so the editor knows its save was answered. */
    val commandsRevision: Int = 0,
) {
    val current: RunState? get() = currentRunId?.let { runs[it] }
}

fun reduceRuns(s: RunsState, env: Envelope, json: Json): RunsState = when (env.type) {
    Envelope.COMMANDS ->
        s.copy(
            commands = json.decodeFromString(ListSerializer(CommandSpecView.serializer()), env.data),
            commandError = null,
            commandsRevision = s.commandsRevision + 1,
        )

    Envelope.RUN -> {
        val r = json.decodeFromString(RunRecordPayload.serializer(), env.data)
        val prev = s.runs[r.runId] ?: RunState(r.runId)
        val next = prev.copy(
            commandId = r.commandId,
            // A late STARTING (the requester's answer) never takes a run back from RUNNING or FINISHED.
            state = if (prev.state == "FINISHED" || (prev.state == "RUNNING" && r.state == "STARTING")) prev.state else r.state,
            name = r.name ?: prev.name,
            startedAt = r.startedAt ?: prev.startedAt,
            terminal = r.terminal || prev.terminal,
            interactive = r.interactive || prev.interactive,
        )
        s.copy(runs = s.runs + (r.runId to next), currentRunId = r.runId)
    }

    // 1.134.0: the daemon's kept runs. Each fills or refreshes its RunState (lines already here are kept); the newest
    // becomes current only when this window has none.
    Envelope.RUNS -> {
        val list = json.decodeFromString(ListSerializer(RunSummaryPayload.serializer()), env.data)
        var runs = s.runs
        for (r in list) {
            val prev = runs[r.runId] ?: RunState(r.runId)
            val fin = if (r.state == "FINISHED" && r.outcome != null) RunFinishedPayload(r.runId, r.exitCode, r.outcome, r.endedAt) else prev.finished
            runs = runs + (r.runId to prev.copy(
                commandId = r.commandId, state = if (prev.state == "FINISHED") prev.state else r.state, finished = fin,
                name = r.name ?: prev.name, startedAt = r.startedAt ?: prev.startedAt, terminal = r.terminal, interactive = r.interactive,
            ))
        }
        s.copy(runs = runs, currentRunId = s.currentRunId ?: list.firstOrNull()?.runId)
    }

    Envelope.RUN_OUTPUT -> {
        val o = json.decodeFromString(RunOutputPayload.serializer(), env.data)
        val prev = s.runs[o.runId] ?: RunState(o.runId)
        s.copy(runs = s.runs + (o.runId to applyOutput(prev, o)))
    }

    Envelope.RUN_FINISHED -> {
        val f = json.decodeFromString(RunFinishedPayload.serializer(), env.data)
        val prev = s.runs[f.runId] ?: RunState(f.runId)
        s.copy(runs = s.runs + (f.runId to prev.copy(state = "FINISHED", finished = f)))
    }

    else -> s
}

private fun applyOutput(run: RunState, o: RunOutputPayload): RunState = when {
    o.sequence == run.lastSequence + 1 && run.gap == null -> {
        val all = run.lines + RunLine(o.sequence, o.stream, o.line, o.truncated)
        val over = (all.size - MAX_RUN_LINES).coerceAtLeast(0)
        run.copy(lines = all.drop(over), lastSequence = o.sequence, droppedLines = run.droppedLines + over)
    }
    o.sequence <= run.lastSequence -> run // duplicate: already applied
    run.gap == null -> run.copy(gap = RunGap(run.lastSequence + 1, o.sequence))
    else -> run // after a gap: keep the first warning, apply nothing past the hole
}

/** RUN-CONSOLE: what a run's pill shows. */
enum class RunPillKind { STARTING, RUNNING, DONE, FAILED, STOPPED }

/** One pill of the Run tab: [label] and [status] are its two texts, [kind] picks its icon and colour. */
data class RunPill(val runId: String, val label: String, val kind: RunPillKind, val status: String)

/** "0:42", "12:05", "1:02:09". */
fun runElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = (total % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/** The pill of [run] at [nowMs]: the label is the command's name (its id, if no name came), newest pills first in [runPills]. */
fun runPill(run: RunState, commands: List<CommandSpecView>, nowMs: Long): RunPill {
    val label = run.name ?: commands.firstOrNull { it.id == run.commandId }?.name ?: run.commandId ?: "run"
    val f = run.finished
    return when {
        f != null -> when (f.outcome) {
            "SUCCEEDED" -> RunPill(run.runId, label, RunPillKind.DONE, "done")
            "KILLED_GRACEFUL", "KILLED_FORCED" -> RunPill(run.runId, label, RunPillKind.STOPPED, "stopped")
            else -> RunPill(run.runId, label, RunPillKind.FAILED, f.exitCode?.let { "failed, exit $it" } ?: "failed")
        }
        run.state == "STARTING" -> RunPill(run.runId, label, RunPillKind.STARTING, "starting")
        else -> RunPill(run.runId, label, RunPillKind.RUNNING, "running " + runElapsed(nowMs - (run.startedAt ?: nowMs)))
    }
}

/** Every run this window knows, newest first (by start time; a run with no start time last). */
fun runPills(s: RunsState, nowMs: Long): List<RunPill> =
    s.runs.values.sortedByDescending { it.startedAt ?: Long.MIN_VALUE }.map { runPill(it, s.commands, nowMs) }
