package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * In-app build (contract 1.33.0): the pure run model. No Compose, no platform APIs.
 *
 * Rules (docs/AGENTHQ-IN-APP-BUILD-DESIGN.md section 4):
 *  - state is keyed by `runId`;
 *  - a `run_output` is applied only when `sequence == lastSequence + 1`;
 *  - a lower-or-equal sequence is a duplicate and is dropped;
 *  - a higher sequence is a GAP: the line is not applied and [RunState.gap] says what was expected, so the UI
 *    shows a warning. A gap is never skipped silently, and later lines are not applied past it;
 *  - at most [MAX_RUN_LINES] lines are kept in memory, oldest dropped first.
 */
const val MAX_RUN_LINES = 2000

/** A saved command as the daemon lists it. [argv] and [graceMillis] arrive from contract 1.94.0 (RUN-CMD-1) for the editor. */
@Serializable
data class CommandSpecView(
    val id: String,
    val name: String,
    val cwd: String? = null,
    val argv: List<String> = emptyList(),
    val graceMillis: Long? = null,
)

@Serializable
data class RunRecordPayload(
    val runId: String,
    val commandId: String,
    val state: String,
    val startedAt: Long? = null,
    val pid: Long? = null,
    val lastSequence: Long = 0,
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
        s.copy(
            runs = s.runs + (r.runId to prev.copy(commandId = r.commandId, state = r.state)),
            currentRunId = r.runId,
        )
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
