// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import com.superiorsoftllc.infantry.ui.protocol.AgentSwapped
import com.superiorsoftllc.infantry.ui.protocol.RunInFlight
import com.superiorsoftllc.infantry.ui.protocol.SessionSwapped
import com.superiorsoftllc.infantry.ui.protocol.AttentionItem
import com.superiorsoftllc.infantry.ui.protocol.CommandCatalogAnswer
import com.superiorsoftllc.infantry.ui.protocol.CommandResultAnswer
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.LayerSkillItem
import com.superiorsoftllc.infantry.ui.protocol.LayerSkillList
import com.superiorsoftllc.infantry.ui.protocol.Epic
import com.superiorsoftllc.infantry.ui.protocol.FinishedPayload
import com.superiorsoftllc.infantry.ui.protocol.MemoryHit
import com.superiorsoftllc.infantry.ui.protocol.MemoryIndexEntry
import com.superiorsoftllc.infantry.ui.protocol.MemoryNote
import com.superiorsoftllc.infantry.ui.protocol.PromptView
import com.superiorsoftllc.infantry.ui.protocol.SkillView
import com.superiorsoftllc.infantry.ui.protocol.TraitView
import com.superiorsoftllc.infantry.ui.protocol.PodStatus
import com.superiorsoftllc.infantry.ui.protocol.Project
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitBranchDiffstat
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitCheckoutResult
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitBranchList
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitHistory
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitPatch
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitStatus
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeList
import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeStatus
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.ProviderModels
import com.superiorsoftllc.infantry.ui.protocol.ProviderState
import com.superiorsoftllc.infantry.ui.protocol.RunUsage
import com.superiorsoftllc.infantry.ui.protocol.ProviderLimits
import com.superiorsoftllc.infantry.ui.protocol.SessionStats
import com.superiorsoftllc.infantry.ui.protocol.Role
import com.superiorsoftllc.infantry.ui.protocol.RoleBinding
import com.superiorsoftllc.infantry.ui.protocol.ReplayGap
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import com.superiorsoftllc.infantry.ui.protocol.StatusPayload
import com.superiorsoftllc.infantry.ui.protocol.Story
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketStart
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import com.superiorsoftllc.infantry.ui.protocol.AgentQueueItem
import com.superiorsoftllc.infantry.ui.protocol.AgentQueueAnswer
import com.superiorsoftllc.infantry.ui.protocol.TriggerEventView
import com.superiorsoftllc.infantry.ui.protocol.TriggerView
import com.superiorsoftllc.infantry.ui.protocol.WaitingOn
import com.superiorsoftllc.infantry.ui.protocol.Workspace
import com.superiorsoftllc.infantry.ui.protocol.WorktreeStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything the UI knows, as ONE immutable value, derived purely from the daemon's frames.
 *
 * There is deliberately no UI-side source of truth: the screen is `reduce(state, frame)` applied to
 * whatever the daemon said. That is what lets the model be tested without a window, and what keeps
 * the client from drifting into guessing (a needs-input badge that is not backed by a frame would be
 * exactly the noisy signal the ticket warns about).
 */
enum class ConnState { IDLE, CONNECTING, CONNECTED, RECONNECTING, REFUSED, FAILED }

/** What we can honestly say about the link. [CONNECTED] means the daemon's `ready` frame arrived. */
data class ConnectionInfo(
    val state: ConnState = ConnState.IDLE,
    val url: String = "",
    /** Human-readable reason for any state that is not CONNECTED. Never blank for a failure. */
    val detail: String = "",
    val attempt: Int = 0,
    /** SWITCH-1: epoch millis the next automatic retry fires (RECONNECTING only), for the countdown. */
    val retryAtMillis: Long? = null,
    /** Contract version the daemon announced in `ready`, e.g. "1.2.0". */
    val contract: String? = null,
    val daemonProvider: String? = null,
    /** Epoch millis the link last carried a frame; shows how stale the screen could be. */
    val lastFrameAt: Long? = null,
    /**
     * Contract 1.11.0: the daemon PROCESS behind the link. Stream sequences restart with the process, so a
     * cursor is only meaningful against the instance that issued it. Null from an older daemon.
     */
    val instance: String? = null,
    /** Contract 1.11.0: epoch millis from which [instance] holds replay history; null from an older daemon. */
    val historySince: Long? = null,
    /** BUILD-STAMP-1: the daemon's build string (`ready` build=), e.g. 1.0.1+20261023.1432.e85b223; null from an older daemon. */
    val daemonBuild: String? = null,
    /**
     * DEMO-MERGE-1: every `key=value` field of the last `ready`, verbatim. Core reads only the generic ones (`access`);
     * a module that adapts the UI to a particular deployment reads its own.
     */
    val readyFields: Map<String, String> = emptyMap(),
    /**
     * DEMO-MERGE-1: refusals the daemon declared as notices (`ready` notices=code,code): the latest message per code. Such a
     * refusal is not an error banner; a module shows it.
     */
    val notices: Map<String, String> = emptyMap(),
    /**
     * POLICY-1 (docs/POLICY.md): the daemon's active policy (the `policy` frame): which named actions are off and why.
     * [PolicyWire.Production] (nothing off) until the frame arrives, and from a daemon too old to send it.
     */
    val policy: com.superiorsoftllc.infantry.plugin.api.PolicyWire = com.superiorsoftllc.infantry.plugin.api.PolicyWire.Production,
) {
    val isLive: Boolean get() = state == ConnState.CONNECTED

    /**
     * DEMO-MERGE-1: the daemon serves this link a restricted surface (`ready` access=restricted): it refuses the owner-only
     * reads (configuration, host processes, the command catalog), so the UI does not ask for them.
     */
    val restricted: Boolean get() = readyFields["access"] == "restricted"

    /**
     * DEMO-CHECK-1: the daemon refuses the owner's configuration and update reads on this link: `ready` says so first
     * ([restricted]), the `policy` frame ([policy]) follows after the link is already live. Either one is enough, so a
     * window that waited for the `policy` frame no longer makes two refused calls in between.
     */
    val ownerReadsClosed: Boolean get() = restricted || policy.restricted

    /**
     * The GENERIC write action (an undeclared write, POLICY-1) may be enabled: live, the daemon's [policy] does not turn
     * undeclared writes off, and the nearest [com.superiorsoftllc.infantry.ui.shell.WritePolicy] allows writes. A control
     * with a named action asks [com.superiorsoftllc.infantry.ui.shell.rememberGate] instead.
     */
    val canWrite: Boolean @androidx.compose.runtime.Composable get() =
        isLive && policy.allows("") && com.superiorsoftllc.infantry.ui.shell.LocalWritePolicy.current.canWrite

    /**
     * Contract 1.11.0: the daemon puts every prompt on its session's stream as a `turn`, so the transcript
     * shows what was asked without the window echoing it. An older daemon does not, and gets a local echo.
     */
    val echoesTurns: Boolean get() = contractAtLeast(contract, 1, 11)

    /** Contract 1.46.0 (role-first S2): the daemon stores an agent's effort; an older one is never sent the key. */
    val sendsAgentEffort: Boolean get() = contractAtLeast(contract, 1, 46)

    /** ROLE-ICON-1: a daemon on contract >= 1.93.0 stores a role's icon; an older one refuses a `role_save` naming it. */
    val sendsRoleIcon: Boolean get() = contractAtLeast(contract, 1, 93)

    /** ROLE-EDIT-1: a daemon on contract >= 1.108.0 stores a role description and answers `role_delete`; an older one refuses both. */
    val sendsRoleEdit: Boolean get() = contractAtLeast(contract, 1, 108)

    /** ASSISTANT-1 (contract 1.115.0): the daemon has the global Assistant (`assistant` frame, `SessionRecord.global`). */
    val hasAssistant: Boolean get() = contractAtLeast(contract, 1, 115)

    /** BASICS-KILL-1 (contract 1.118.0): the daemon stores `Project.autoRemoveDoneWorkers`. */
    val autoRemovesDoneWorkers: Boolean get() = contractAtLeast(contract, 1, 118)

    /** Contract 1.47.0 (role-first S3): the daemon takes `agent_swap`; below it the Swap action is unavailable. */
    val swapsAgents: Boolean get() = contractAtLeast(contract, 1, 47)
    /** UX-HARNESS-2 (contract 1.83.0): the daemon takes `session_swap`, so a session with no agent can switch harness. */
    val swapsSessions: Boolean get() = contractAtLeast(contract, 1, 84)
    /** SWAP-AGENT-1 (contract 1.86.0): `session_swap` takes a `role`, so the Switch agent picker can change the role in place. */
    val swapsRoles: Boolean get() = contractAtLeast(contract, 1, 86)
    /** Contract 1.60.0 (M2-17): an edit's empty `epicId` clears the ticket's epic (an older daemon keeps it). */
    val clearsTicketEpic: Boolean get() = contractAtLeast(contract, 1, 60)
    /** CONFIG-8 (contract 1.142.0): the daemon serves `config_schema`; below it the dialog uses the hand-written daemon sections. */
    val servesConfigSchema: Boolean get() = contractAtLeast(contract, 1, 142)
    /** CHAT-QUEUE-1 (contract 1.150.0): the daemon holds the queue (`queue_add` / `queue_remove` / `queue_force`); below it this window does. */
    val servesQueue: Boolean get() = contractAtLeast(contract, 1, 150)
}

/** Insert [p], or replace the stored profile with its id. */
private fun AppState.withProfile(p: AgentProfile): AppState =
    copy(profiles = if (profiles.any { it.id == p.id }) profiles.map { if (it.id == p.id) p else it } else profiles + p)

/** Whether [contract] ("major.minor.patch") is at least [major].[minor]. An unknown version is not. */
fun contractAtLeast(contract: String?, major: Int, minor: Int): Boolean {
    val parts = contract?.trim()?.split('.') ?: return false
    val ma = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val mi = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return ma > major || (ma == major && mi >= minor)
}

/** [WRITE] (UX-WRITE-1): a file-writing tool call; its text is the call's [FileWrite] as JSON, drawn as a change box. */
enum class LineKind { USER, TEXT, REASONING, STATUS, ERROR, FINISHED, LOG, WRITE }

/**
 * What the chat pane shows under the transcript while the agent works. Derived only from frames the
 * daemon already sends on the session stream (see CONTRACT.md): a `turn` (the human's line, 1.11.0)
 * precedes the `text_delta` it caused, so "a turn with no reply on the transcript yet" is exactly the
 * spawned-but-no-output window `STARTING` describes -- no new frame is needed.
 */
sealed interface ChatStatus {
    /**
     * The agent is working on a turn ("Thinking..." / "Running <tool>"), for as long as its session is in
     * flight (1c24fdc4). [elapsed] is how long the turn has been open, formatted coarsely ("3s");
     * it is carried on the status so the label needs no clock of its own.
     *
     * Contract 1.26.0 (AGENT-UX-12): [activity] is what the run is DOING right now ("bash: ls -la"),
     * read off the latest tool `status` frame, or null before any tool call. [stalledFor] is how long
     * since the last sign of life of ANY kind, set only once it passes [STALE_AFTER_MS] -- this is what
     * makes a stalled run LOOK different from one that is producing activity.
     */
    data class Thinking(
        val elapsed: String = "",
        val activity: String? = null,
        val stalledFor: String? = null,
        /** Contract 1.99.0: the run is compacting its context; the label reads "Compacting…" instead of "Thinking...". */
        val compacting: Boolean = false,
    ) : ChatStatus

    /** A turn was answered with an error/failure. */
    data object Failure : ChatStatus
    /** A turn was answered with a finished outcome (killed/failed). */
    data object Stopped : ChatStatus
}

/**
 * Contract 1.26.0: the latest tool ACTIVITY a session reported on a `status` frame -- the tool's name
 * plus its salient argument -- and when it arrived (the clock the stall indicator measures against).
 */
data class AgentActivity(val tool: String, val detail: String?, val at: Long) {
    /** "bash: ls -la", or just the tool name when the call carried no salient argument. */
    val label: String get() = if (detail.isNullOrBlank()) tool else "$tool: $detail"
}

/** One rendered line of a session's transcript. Consecutive text deltas are coalesced into one line. */
data class OutputLine(val kind: LineKind, val text: String, val at: Long, val origin: MessageOrigin = MessageOrigin.Owner)

/**
 * One transcript entry as it arrived, and where it sits in its session's stream.
 *
 * [seq] is the daemon's per-session sequence. It is null for a local echo, for a frame from a daemon that
 * predates sequences, and for history kept from a PREVIOUS daemon process (whose numbering means nothing
 * now). Sequenced entries are kept in sequence order, so a replayed frame that arrives after newer live
 * frames still lands where it belongs; unsequenced entries keep their arrival position.
 */
data class TranscriptEntry(val seq: Long?, val kind: LineKind, val text: String, val at: Long, val closed: Boolean = false, val origin: MessageOrigin = MessageOrigin.Owner)

/**
 * Which of one session's sequenced frames this window has applied, so a frame is applied exactly once
 * however it arrives (live, replayed, or both).
 *
 * [contiguous]: every sequence `1..contiguous` was applied, or reported evicted by a `replay_gap`. It is
 * the exclusive cursor to replay after. [ranges]: applied sequences above `contiguous + 1`, sorted,
 * disjoint and non-adjacent -- normally empty, and one range while live frames run ahead of a replay.
 */
data class StreamCursor(val contiguous: Long = 0, val ranges: List<LongRange> = emptyList()) {
    /** The newest sequence applied; a frame above it is newer than anything on screen. */
    val highest: Long get() = ranges.lastOrNull()?.last ?: contiguous

    fun seen(seq: Long): Boolean = seq <= contiguous || ranges.any { seq in it }

    fun mark(seq: Long): StreamCursor {
        if (seen(seq)) return this
        if (ranges.isEmpty() && seq == contiguous + 1) return StreamCursor(seq) // the live, in-order path
        val merged = mutableListOf<LongRange>()
        for (r in (ranges + listOf(seq..seq)).sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return StreamCursor(contiguous, merged).closed()
    }

    /** A `replay_gap`: sequences before [oldestAvailable] are gone for good, so stop waiting for them. */
    fun skipTo(oldestAvailable: Long): StreamCursor = StreamCursor(maxOf(contiguous, oldestAvailable - 1), ranges).closed()

    /** Fold every range that now touches [contiguous] into it. */
    private fun closed(): StreamCursor {
        var c = contiguous
        val rest = mutableListOf<LongRange>()
        for (r in ranges) {
            when {
                r.last <= c -> Unit
                r.first <= c + 1 && rest.isEmpty() -> c = r.last
                else -> rest += r
            }
        }
        return StreamCursor(c, rest)
    }
}

/**
 * One agent as the UI shows it: the daemon's session record plus its transcript.
 * [role] is read off the session frame if the daemon sends one; it is only ever rendered, never interpreted.
 */
data class AgentView(
    val session: SessionRecord,
    val role: String? = null,
    /** The transcript as received, in stream order; [output] is what it renders as. */
    val entries: List<TranscriptEntry> = emptyList(),
    val statusDetail: String = "",
    val finished: FinishedPayload? = null,
    val lastError: String? = null,
    /** Which of this session's sequenced frames are already applied (contract 1.7.0 replay). */
    val cursor: StreamCursor = StreamCursor(),
    /** Contract 1.13.0: the usage each run reported, in run order; a run that reported none is absent. */
    val usage: List<RunUsage> = emptyList(),
    /** Contract 1.26.0: the last tool activity seen on a `status` frame, or null before any. */
    val activity: AgentActivity? = null,
    /** UI-ACT-1: this session's activity history, one entry per tool frame, capped at the last 200. */
    val activityLog: List<AgentActivity> = emptyList(),
    /** Contract 1.95.0 (1c24fdc4): the daemon's latest run activity, or null from a daemon that predates it. */
    val runActivity: RunActivityView? = null,
    /** Contract 1.127.0 (TODO-OVERLAY-1): the agent's latest to-do list; every update replaces it. */
    val todos: AgentTodoList? = null,
) {
    val id: String get() = session.id

    /** The 8-char session id the UI shows when it has nothing better. */
    val shortId: String get() = session.id.take(8)

    /** Totals over the runs that reported usage; null when none did (no signal is not zero). */
    val usageTotals: UsageTotals?
        get() = if (usage.isEmpty()) null else UsageTotals(
            runs = usage.size,
            input = usage.sumOf { it.input },
            output = usage.sumOf { it.output },
            reasoning = usage.sumOf { it.reasoning },
            cacheRead = usage.sumOf { it.cacheRead },
            cacheWrite = usage.sumOf { it.cacheWrite },
            costUsd = usage.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum(),
        )

    /** The rendered transcript: consecutive text deltas coalesce into one line. */
    val output: List<OutputLine> by lazy { render(entries) }

    /** The daemon says the run is compacting its context, and the session is still in flight. */
    val isCompacting: Boolean get() = runActivity?.compacting == true && isRunning(session.state)

    /** The session state as a chip reads it: "compacting" while the run compacts, else [stateLabel]. */
    val stateText: String get() = if (isCompacting) "compacting" else bgJobsText(session) ?: stateLabel(session.state)

    /** One-line summary of the latest activity, for the sidebar row. */
    val lastActivity: String
        get() {
            if (isCompacting) return COMPACTING_LABEL
            val last = output.lastOrNull { it.kind != LineKind.STATUS } ?: return stateLabel(session.state)
            // UX-WRITE-1: a change box's text is JSON; the sidebar names the file instead.
            if (last.kind == LineKind.WRITE) return fileWriteOf(last.text)?.let { "✎ ${it.path}" } ?: stateLabel(session.state)
            return last.text.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty().ifEmpty { stateLabel(session.state) }
        }

    /**
     * What the chat pane shows while the agent works, or after a turn that failed/stopped; null otherwise.
     * [now] is the wall clock the elapsed label is measured against. See [chatStatusOf] for the exact rule.
     */
    fun chatStatus(now: Long): ChatStatus? = chatStatusOf(entries, now, activity, session.state, runActivity, session.turnActive)
}

/**
 * Contract 1.95.0 (1c24fdc4): the daemon's run activity as this window received it. [phase] is the wire `state`
 * (`thinking`, `tool_running`, ...); [since] is when this window received the change (its own clock, so a remote
 * daemon's clock skew never shows in an elapsed label).
 */
data class RunActivityView(val phase: String, val label: String?, val since: Long) {
    val toolRunning: Boolean get() = phase == "tool_running"
    val compacting: Boolean get() = phase == "compacting"
}

/**
 * The transient status for a transcript.
 *
 * 1c24fdc4: while [state] is in flight ([isRunning] -- the SAME rule the header `working` badge, the
 * sidebar Running group and the composer's "Agent is busy" read) the status is [ChatStatus.Thinking] for
 * the whole turn, whatever the transcript's last line is: an agent that already said a sentence and is
 * now in a tool-only stretch is still working. The transcript only decides the label: a tool reported
 * since the turn's newest text is "Running <tool>", otherwise "Thinking...". Elapsed counts from the
 * turn's opening (its [LineKind.USER] line, else its first entry since the last finish).
 *
 * Once the session is not in flight: a turn answered with an error is [ChatStatus.Failure], one whose
 * run failed or was killed is [ChatStatus.Stopped], anything else is null. [state] null (a caller with
 * no session record) keeps the transcript-only rule: Thinking while the newest turn-defining entry is
 * the human's [LineKind.USER] turn.
 *
 * [activity] (contract 1.26.0) is the latest tool activity, if any. It feeds two things: the "what is
 * it doing" label, and -- because it is a newer sign of life than the turn -- the stall clock. A run is
 * marked stalled ([ChatStatus.Thinking.stalledFor]) once NOTHING new (no activity, no frame) has arrived
 * for [STALE_AFTER_MS], which is what makes a stuck run look different from a working one.
 *
 * [run] (contract 1.95.0) is the daemon's harness-neutral run activity. When present it alone decides the label:
 * "Running <label>" while `tool_running` (timed from the tool's start), else "Thinking...". It never decides
 * whether the indicator shows -- that stays [state], the header badge's own.
 */
fun chatStatusOf(
    entries: List<TranscriptEntry>,
    now: Long,
    activity: AgentActivity? = null,
    state: SessionState? = null,
    run: RunActivityView? = null,
    turnActive: Boolean? = null,
): ChatStatus? {
    val newest = entries.lastOrNull { it.kind in TURN_DEFINING }
    // The daemon says no turn is in flight (a session parked WAITING_INPUT between turns): never "Thinking...".
    if (state != null && isRunning(state) && turnActive != false) return workingStatus(entries, now, activity, run)
    if (newest == null) return null
    return when (newest.kind) {
        // CHATSTATUS-3: the human's turn was answered with an error -> Failure, not an open wait.
        LineKind.ERROR -> ChatStatus.Failure
        // CHATSTATUS-3: a run that failed or was killed reads as Stopped; a clean finish is answered (null).
        LineKind.FINISHED -> when {
            newest.text.startsWith("finished: failed") ||
                newest.text.startsWith("finished: killed graceful") ||
                newest.text.startsWith("finished: killed forced") -> ChatStatus.Stopped
            else -> null
        }
        // The transcript-only rule, for a caller with no session state.
        LineKind.USER -> if (state == null) workingStatus(entries, now, activity, run) else null
        else -> null
    }
}

/**
 * 1c24fdc4: the [ChatStatus.Thinking] of a turn in flight. The turn is everything since the last
 * [LineKind.FINISHED]; it opened at its newest [LineKind.USER] line (else its first entry). [activity] is
 * "in flight" only when it arrived in this turn and no newer text or reasoning has: an agent that speaks
 * after a tool call is past that call, so the label falls back to "Thinking...".
 */
private fun workingStatus(entries: List<TranscriptEntry>, now: Long, activity: AgentActivity?, run: RunActivityView?): ChatStatus.Thinking {
    val turn = entries.subList(entries.indexOfLast { it.kind == LineKind.FINISHED } + 1, entries.size)
    val openedAt = (turn.lastOrNull { it.kind == LineKind.USER } ?: turn.firstOrNull())?.at ?: run?.since ?: activity?.at ?: now
    // 1.95.0: the daemon says whether a tool is in flight (it saw the tool END). Before 1.95.0: a tool reported in
    // this turn and newer than the newest text or reasoning is taken to be in flight.
    val label = if (run != null) {
        run.label?.takeIf { run.toolRunning }
    } else {
        val lastSpoke = turn.lastOrNull { it.kind == LineKind.TEXT || it.kind == LineKind.REASONING }?.at
        activity?.takeIf { it.at >= openedAt && (lastSpoke == null || it.at >= lastSpoke) }?.label
    }
    val startedAt = if (run != null && (label != null || run.compacting)) run.since else openedAt
    val lastSignOfLife = maxOf(activity?.at ?: Long.MIN_VALUE, run?.since ?: Long.MIN_VALUE, entries.maxOfOrNull { it.at } ?: openedAt)
    val sinceAlive = now - lastSignOfLife
    return ChatStatus.Thinking(
        elapsed = elapsedLabel(now - startedAt),
        activity = label,
        stalledFor = if (sinceAlive >= STALE_AFTER_MS) elapsedLabel(sinceAlive) else null,
        compacting = run?.compacting == true,
    )
}

/** Contract 1.26.0: after this long with no new frame of any kind, a run is shown as STALLED. */
const val STALE_AFTER_MS = 45_000L

/** Contract 1.99.0: what a compacting session reads as, in the sidebar row and the chat status. */
const val COMPACTING_LABEL = "Compacting…"

/**
 * How long a turn has been waiting, as a coarse label: whole seconds under a minute, then minutes, then
 * hours ("3s"). Clamped at zero so a clock running behind the turn's stamp never shows a negative.
 */
internal fun elapsedLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}

/** Kinds that answer a turn: a USER line opens the wait, the other three close it. */
private val TURN_DEFINING = setOf(LineKind.USER, LineKind.TEXT, LineKind.ERROR, LineKind.FINISHED)

/**
 * M2-3b: the harness, provider and model a session runs on; null = unknown (its chip is hidden). [fullModel] is the
 * id exactly as the daemon names it (e.g. `opencode/space-bunny-free`), for the model chip's tooltip.
 */
data class SessionExecutor(val harness: String?, val provider: String?, val model: String?, val fullModel: String?) {
    /** The model as the chip shows it: at most [MODEL_CHIP_MAX] characters, cut with an ellipsis. */
    val shortModel: String? get() = model?.let { if (it.length <= MODEL_CHIP_MAX) it else it.take(MODEL_CHIP_MAX - 3) + "…" }

    companion object {
        const val MODEL_CHIP_MAX = 24
    }
}

/** Usage summed over a session's runs that reported it. [costUsd] is null when no run reported a cost. */
data class UsageTotals(
    val runs: Int,
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val costUsd: Double?,
)

/** Fold entries into display lines: text deltas coalesce, a repeated status label collapses. */
internal fun render(entries: List<TranscriptEntry>): List<OutputLine> {
    val out = ArrayList<OutputLine>(entries.size)
    // A text entry the daemon's next tool/status frame closed ends its message: the next text starts a new line.
    var open = false
    for (e in entries) {
        val last = out.lastOrNull()
        val joins = e.kind == LineKind.TEXT && last?.kind == LineKind.TEXT && open
        open = e.kind == LineKind.TEXT && !e.closed
        when {
            joins -> out[out.lastIndex] = last!!.copy(text = last.text + e.text, at = e.at)
            e.kind == LineKind.STATUS && last?.kind == LineKind.STATUS && last.text == e.text -> Unit
            else -> out += OutputLine(e.kind, e.text, e.at, e.origin)
        }
    }
    return out
}

/**
 * One item the chat pane renders: a single line, or a run of consecutive [LineKind.REASONING] lines
 * shown as one collapsible block. Grouping is presentation only -- the transcript keeps each reasoning
 * line apart, so reasoning is never merged into the answer text.
 */
sealed interface ChatItem {
    /** KINDS-1: the string a row renderer is registered under: a line's kind, `reasoning`, or a contribution's own kind (blank = `automation`). */
    val kind: String
    data class Line(val line: OutputLine) : ChatItem { override val kind: String get() = line.kind.name.lowercase() }
    data class Reasoning(val lines: List<OutputLine>) : ChatItem { override val kind: String get() = "reasoning" }
    /** SCRIPT-VIS-1b: a line a plugin contributed (e.g. an automation run); never the agent's own output. */
    data class Contribution(val item: TimelineItem) : ChatItem { override val kind: String get() = item.kind.ifBlank { "automation" } }
}

/** SCRIPT-VIS-1b: [rows] with the plugin-contributed [items] placed by time (before the first row that is later; trailing ones last). */
fun withContributions(rows: List<ChatItem>, items: List<TimelineItem>): List<ChatItem> {
    @Suppress("NAME_SHADOWING") val items = items.filterNot { it.isStats }
    if (items.isEmpty()) return rows
    fun ChatItem.at(): Long = when (this) { is ChatItem.Line -> line.at; is ChatItem.Reasoning -> lines.firstOrNull()?.at ?: 0L; is ChatItem.Contribution -> item.at }
    val out = ArrayList<ChatItem>(rows.size + items.size)
    val pending = items.sortedBy { it.at }.toMutableList()
    for (r in rows) {
        while (pending.isNotEmpty() && pending.first().at < r.at()) out += ChatItem.Contribution(pending.removeAt(0))
        out += r
    }
    pending.forEach { out += ChatItem.Contribution(it) }
    return out
}

/** Group consecutive reasoning lines into one [ChatItem.Reasoning]; every other line stands alone. */
fun chatItems(output: List<OutputLine>): List<ChatItem> {
    val out = ArrayList<ChatItem>(output.size)
    for (line in output) {
        val last = out.lastOrNull()
        when {
            line.kind == LineKind.REASONING && last is ChatItem.Reasoning -> out[out.lastIndex] = ChatItem.Reasoning(last.lines + line)
            line.kind == LineKind.REASONING -> out += ChatItem.Reasoning(listOf(line))
            else -> out += ChatItem.Line(line)
        }
    }
    return out
}

/**
 * Contract 1.18.0 / MVP: one project as the shell shows it. Mirrors the daemon's `project` frame
 * ([Project] on the wire); the UI keeps only what it renders.
 */
data class ProjectView(
    val id: String,
    val name: String,
    val goal: String = "",
    val directory: String = "",
    /** BASE-1b (contract 1.81.0): the branch new ticket starts in this project cut from; `""` = the daemon's default. */
    val baseBranch: String = "",
    /** LANE-CAPS (contract 1.111.0): most concurrent agents per role id; a role not listed runs 1. */
    val laneCaps: Map<String, Int> = emptyMap(),
    /** BASICS-KILL-1 (contract 1.118.0): a done ticket's worker session is ended and removed. */
    val autoRemoveDoneWorkers: Boolean = false,
    /** EXPERIMENTS-1 (contract 1.126.0): named project switches, key to `on`; a key not listed is off. */
    val experiments: Map<String, String> = emptyMap(),
)

/**
 * Contract 1.18.0 / PW-1: one workspace inside a project. Mirrors the daemon's `workspace` frame:
 * a stable [id], the [projectId] it belongs to, a [name], and the optional checkout [path].
 */
data class WorkspaceView(val id: String, val projectId: String, val name: String, val path: String? = null)

/**
 * Contract 1.19.0 (EPIC-4) / UI-EPIC-1: one epic inside a project. Mirrors the daemon's `epic` frame
 * ([Epic] on the wire): a stable [id], the [projectId] that owns it, a [name], an optional [goal], and
 * the epic's ordered [storyIds] (empty on creation).
 */
data class EpicView(
    val id: String,
    val projectId: String,
    val name: String,
    val goal: String = "",
    val storyIds: List<String> = emptyList(),
)

/**
 * Contract 1.8.0 (MVP story board) / UI-STORY-1: one story inside a project. Mirrors the daemon's
 * `story` frame ([Story] on the wire): a stable [id], the [projectId] that owns it, a [title] and a
 * [status] (`todo`, `doing`, `done`). There is no position -- a story's place comes from its
 * project's [EpicView.storyIds], so a move is a status change and nothing more.
 */
data class StoryView(
    val id: String,
    val projectId: String,
    val title: String,
    val status: String,
)

/**
 * Contract 1.21.0 (MEM-8) / UI-MEM-1: one project-memory note. The `memory_notes` index only carries
 * [name] and [description] (its body is deliberately withheld), so an index row leaves [id], [folder]
 * and [body] empty; a `memory_note` frame (the `memory_save` answer) fills the whole note in.
 */
data class MemoryNoteView(
    val id: String = "",
    val projectId: String = "",
    val name: String,
    val description: String = "",
    val folder: String = "",
    val body: String = "",
    val updatedAt: String = "",
    val scope: String = "project",
    val ownerId: String = "",
)

/**
 * NOTES-1: which notes the Notes tab shows. [scope] is `project` (shared by every role), `role` (persists with
 * [role]) or `session` (temporary, deleted with [sessionId]). The state slices are keyed by [key], which for the
 * project scope is the bare project id, so every pre-existing reader keeps working.
 */
data class NoteScope(val scope: String = PROJECT, val role: String? = null, val sessionId: String? = null) {
    /** The wire `scope` field: null for the project scope, so a project request is byte-identical to before. */
    val wireScope: String? get() = scope.takeIf { it != PROJECT }
    fun key(projectId: String): String = noteKey(projectId, scope, ownerId)
    val ownerId: String get() = when (scope) { ROLE -> role.orEmpty(); SESSION -> sessionId.orEmpty(); else -> "" }

    companion object {
        const val PROJECT = "project"
        const val ROLE = "role"
        const val SESSION = "session"
        val Project = NoteScope()
    }
}

/** NOTES-1: the state-slice key of (project, scope, owner); the project scope is the bare project id. */
fun noteKey(projectId: String, scope: String, ownerId: String): String =
    if (scope == NoteScope.PROJECT) projectId else "$projectId|$scope|$ownerId"

/** Contract 1.20.0 (MEM-5) / UI-MEM-1: one ranked `memory_hits` result -- name, description, snippet. */
data class MemoryHitView(val name: String, val description: String = "", val snippet: String = "")

/**
 * Contract 1.20.0 (MEM-5) / UI-MEM-1: the latest memory search -- the [query] this window asked, the
 * [projectId] it belongs to, and the ranked [hits] the daemon returned. The `memory_hits` frame names
 * neither project nor query, so both come from the awaited request ([MemorySearchQuery]).
 */
data class MemorySearchView(
    val projectId: String,
    val query: String,
    val hits: List<MemoryHitView> = emptyList(),
)

/** Contract 1.20.0: a `memory_search` this window sent and has not seen an answer to yet. */
data class MemorySearchQuery(val projectId: String, val query: String)

data class AppState(
    val connection: ConnectionInfo = ConnectionInfo(),
    val agents: Map<String, AgentView> = emptyMap(),
    val worktrees: Map<String, WorktreeStatus> = emptyMap(),
    val pods: Map<String, PodStatus> = emptyMap(),
    val waiting: List<WaitingOn> = emptyList(),
    val selectedId: String? = null,
    /** Frames the daemon refused or reported that belong to no session ("unknown pod", "resume failed"). */
    val notices: List<Notice> = emptyList(),
    /**
     * The newest project / workspace refusal, shown inline in the sidebar. Owned here: a successful project frame,
     * a project switch or the sidebar's dismiss clears it, so a stale refusal never stays pinned.
     */
    val projectRefusal: Notice? = null,
    /** Global bottom-right toasts, oldest first; the host draws the newest few. */
    val toasts: List<ToastEvent> = emptyList(),
    /**
     * Set while a spawn this window sent has not produced its session yet: the session ids that already
     * existed. The first session frame naming any OTHER id is the spawned one, and it is selected.
     */
    val pendingSpawn: Set<String>? = null,
    /** Sessions whose replay history was already requested from the current daemon instance. */
    val historyRequested: Set<String> = emptySet(),
    /**
     * Messages the human composed while their agent was busy, per session id, oldest first. Held in this window
     * only: a queued message is NOT in the transcript until the daemon takes it as the next turn.
     */
    val queued: Map<String, List<QueuedPrompt>> = emptyMap(),
    /**
     * ticket-b5aa01bc: queued messages sent as a turn whose acceptance is not known yet, by the `requestId` they went
     * out with. The daemon's typed busy refusal for one puts it back at the front of [queued]; its turn on the stream
     * (or a new `ready`) ends the wait.
     */
    val queueSending: Map<String, QueueSend> = emptyMap(),
    /** CHAT-SENDING-1: the messages sent and not yet shown by the daemon's `turn` echo, by client message id ([pendingSendsFor]). */
    val pendingSends: Map<String, PendingSend> = emptyMap(),
    /** CHAT-SENDING-1: `limits.chat.sendAckSeconds` from the `settings` frame: how long a send may wait before "not sent - retry". */
    val sendAckSeconds: Long = PendingSends.DEFAULT_ACK_SECONDS,
    /** Contract 1.12.0: the daemon's role catalog, in its order (canonical ids first). */
    val roles: List<Role> = emptyList(),
    /** Contract 1.40.0: what each role runs on, by role id; a role with no entry has no binding. */
    val roleBindings: Map<String, RoleBinding> = emptyMap(),
    /** ROLE-1c: the role the New agent box was told to spawn as; its binding seeds [pickedModel] / [pickedEffort], which stay overridable. */
    val spawnRole: String? = null,
    /** Contract 1.4.0: the agent profiles -- each binds a role to a provider/model and instructions. */
    val profiles: List<AgentProfile> = emptyList(),
    /** Contract 1.47.0 (role-first S8): the last `agent_swapped` push per agent id -- from this window or any other. */
    val agentSwaps: Map<String, AgentSwapped> = emptyMap(),
    /** Role-first S8: the daemon's last `agent_save` / `agent_swap` refusal; cleared when this window sends the next one. */
    val agentError: String? = null,
    /**
     * CMD-HARNESS-1: the `/harness` / header-chip switches this window sent, agent id -> harness, until `agent_swapped`
     * lands. UX-HARNESS-2: a session with no agent is keyed by its session id, until `session_swapped` lands.
     */
    val pendingHarnessSwitch: Map<String, String> = emptyMap(),
    /** UX-HARNESS-2: the last `session_swapped` push per session id -- from this window or any other. */
    val sessionSwaps: Map<String, SessionSwapped> = emptyMap(),
    /** UX-HARNESS-2: the daemon's last `session_swap` refusal, in plain words; cleared when this window sends the next one. */
    val sessionSwitchError: String? = null,
    /**
     * SWAP-AGENT-1: `/harness <name>` opens the Switch agent picker for that session with [HarnessPickerAsk.harness]
     * pre-selected (it switches nothing by itself). [HarnessPickerAsk.seq] makes asking twice open it twice.
     */
    val harnessPickerAsk: HarnessPickerAsk? = null,
    /** CMD-HARNESS-1: the new session a switch cut, selected as soon as its `session` frame arrives. */
    val followSession: String? = null,
    /** M2-17: the daemon's last `ticket_save` refusal; cleared when this window sends the next ticket edit. */
    val ticketError: String? = null,
    /** Contract 1.3.0: what a spawn may run on, the default first. */
    val providers: List<ProviderInfo> = emptyList(),
    /** Contract 1.34.0: every provider's models as the `models` frame listed them (empty providers included). */
    val modelCatalog: List<ProviderModels> = emptyList(),
    /** CP-4: the last `command_catalog` answer and the session it was asked for; [slashSuggestions] only trusts it while it still matches the box. */
    val slash: SlashAnswer? = null,
    /** PICKER 2/2: the provider+model the next spawn runs on (null = the daemon's defaults). UI-local, sent in the `new` frame. */
    val pickedModel: PickedModel? = null,
    /** CP-4(e): the reasoning effort the next spawn starts with, set by `/effort` in the New agent box (null = the harness default). */
    val pickedEffort: String? = null,
    /** Contract 1.9.0: the durable attention history, oldest first as the daemon lists it. */
    val attention: List<AttentionItem> = emptyList(),
    /** SCRIPT-VIS-1b: plugin-contributed chat lines per session id (newest last), from `timeline_item` / `timeline_items`. */
    val timeline: Map<String, List<TimelineItem>> = emptyMap(),
    /** Contract 1.13.0: each provider's last observed limit state, in the daemon's order. */
    val providerStates: Map<String, ProviderState> = emptyMap(),
    /** Contract 1.59.0 (U1): the last `limits` answer, one entry per provider in the daemon's order; null = never answered. */
    val planLimits: List<ProviderLimits>? = null,
    /** USAGE-PACE (contract 1.119.0): the last `usage_report`, plan meters and pacing allowance; null = never answered. */
    val usageReport: com.superiorsoftllc.infantry.ui.protocol.UsageReport? = null,
    /** STATS-2 (contract 1.92.0): the last `session_stats` answer per Infantry session id; absent = never answered. */
    val sessionStats: Map<String, SessionStats> = emptyMap(),
    /** CONFIG-8 (contract 1.142.0): the daemon's last `config_schema`; null = never sent (an older daemon, or none declared): the dialog keeps the hand-written daemon sections. */
    val daemonConfigSchema: com.superiorsoftllc.infantry.plugin.api.ConfigSchemaFrame? = null,
    /** CONFIG-8: the daemon's last `config_values`, kept current by `config_changed`. */
    val daemonConfigValues: com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame? = null,
    /** CONFIG-11: the daemon's last `config_set` refusal text (stale rev, validator), cleared by the next accepted write. */
    val daemonConfigError: String? = null,
    /** USAGE-1 (contract 1.141.0): the last `usage_meter` answer; null = never answered. */
    val usageMeter: com.superiorsoftllc.infantry.ui.protocol.UsageMeterReport? = null,
    /** PACE-TREND-1: the last `pace_report` answer as the daemon sent it (JSON text; the Infantry_Pace tab parses it); null = none. */
    val paceReport: String? = null,
    /**
     * BUG-M2-11e: the daemon's `session.keepAliveMinutes`, as its last `settings` frame said. Null = not read yet (or a
     * daemon older than contract 1.68.0, which has no such setting): the control says so instead of guessing a value.
     */
    val keepAliveMinutes: Int? = null,
    /** No-hard-coded-limits (1.133.0): every daemon limit, as its last `limit_catalog` said (values follow `settings`). Null = not read yet. */
    val limitCatalog: List<LimitInfo>? = null,
    /** DEMO-RESTRICT: the daemon's role allowlist (`limits.roles.allowed`); empty = every role may be chosen. */
    val allowedRoles: List<String> = emptyList(),
    /** DEMO-RESTRICT: `limits.concurrency.maxSessions` from the `settings` frame; 0 = no cap (the normal app). */
    val maxSessions: Long = 0,
    /** ROGUE-1 (1.120.0): the daemon's last `processes` answer or `process_pressure` push; null = never answered. */
    val processReport: com.superiorsoftllc.infantry.ui.protocol.ProcessReport? = null,
    /** ROGUE-1 (1.120.0): the daemon's `processes.autoReap` as its last `settings` frame said; null = not read yet. */
    val processAutoReap: Boolean? = null,
    /** USER-PORTRAIT (1.107.0): the daemon's `user.portrait` (base64 PNG); null = unset, the bundled default shows. */
    val userPortrait: String? = null,
    /** CONTRIB-1: the daemon's `urls.sourceRepo` as its last `settings` frame said; null = not read (About shows no links). */
    val sourceRepo: String? = null,
    /** ABOUT-SITE-1: the daemon's `urls.site` (the product website); null = not read (About shows no Website line). */
    val siteUrl: String? = null,
    /** CLIPATH-1 (1.73.0): where the daemon runs each CLI from, as its last `cli_locations` said. Null = not read yet. */
    val cliLocations: List<com.superiorsoftllc.infantry.ui.protocol.CliLocation>? = null,
    /** HARNESS-INSTALL-1 (1.100.0): the daemon's last harness check (null = not read / older daemon), whether an install is running, and its log. */
    val harnessInstall: com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport? = null,
    val harnessInstallBusy: Boolean = false,
    val harnessInstallLog: List<String> = emptyList(),
    /** BUG-PERM-1 (1.76.0): the global Tool approval, as the daemon's last `tool_approval` said. Null = not read yet. */
    val toolApproval: com.superiorsoftllc.infantry.ui.protocol.ToolApprovalInfo? = null,
    /** ASK-TIMER-2: the daemon's `prompts.timeoutSec` / `prompts.onTimeout`, as its last `settings` frame said. Null = not read yet. */
    val promptTimer: PromptTimerSetting.Value? = null,
    /** SECRETS-1 (1.100.0): the daemon keystore's last `secret_state` (set / not set and masked tails only). Null = not read yet. */
    val secrets: com.superiorsoftllc.infantry.ui.protocol.SecretState? = null,
    /**
     * PROMPT-2 (1.77.0): every prompt the daemon says is open, in arrival order. A `prompt_resolved` removes one,
     * whoever answered it; `ready` clears them all, because the daemon resends every still-open prompt after it.
     */
    val openPrompts: List<com.superiorsoftllc.infantry.ui.protocol.PromptRequestView> = emptyList(),
    /** PROMPT-2: question prompts hidden in this window by Esc (still open on the daemon). Cleared with [openPrompts]. */
    val dismissedPrompts: Set<String> = emptySet(),
    /** ASK-1 (1.97.0): every owner question the daemon holds (open and history), oldest first. */
    val questions: List<com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView> = emptyList(),
    /** ASK-1: open questions hidden in THIS window by "Later" (still open on the daemon); new ones show again. */
    val dismissedQuestions: Set<String> = emptySet(),
    /** PANEL-TOGGLE-1: which side panels are on screen. Client-only, so it survives reconnects and project switches. */
    val panels: PanelLayout = PanelLayout(),
    /** Contracts 1.5.0 / 1.6.0: the daemon-owned backlog, as the daemon last said it (any order). */
    val tickets: List<Ticket> = emptyList(),
    /** AGENT-TASKS-7: each agent profile's ticket queue (key = the `agent_queue` answer's agentId), head first, as last answered. */
    val agentQueues: Map<String, List<AgentQueueItem>> = emptyMap(),
    /** Contract 1.14.0: the manual triggers, oldest first as the daemon lists them. */
    val triggers: List<TriggerView> = emptyList(),
    /** Contract 1.14.0: each trigger's event rows, oldest first, as last requested. */
    val triggerEvents: Map<String, List<TriggerEventView>> = emptyMap(),
    /** Trigger ids whose `trigger_events` answer is awaited (an empty answer names no trigger itself). */
    val eventsAwaiting: List<String> = emptyList(),
    /** Contract 1.18.0: the projects the daemon listed (`projects` frame), in creation order. */
    val projects: List<ProjectView> = emptyList(),
    /** Contract 1.18.0 / PW-1: the workspaces the shell knows, whatever project subscribed them. */
    val workspaces: List<WorkspaceView> = emptyList(),
    /**
     * Contract 1.19.0 / UI-EPIC-1: each project's epics, keyed by project id. An `epics` frame
     * replaces only the project it names; a single `epic` frame upserts one epic by id.
     */
    val epics: Map<String, List<EpicView>> = emptyMap(),
    /** Contract 1.33.0: the saved commands and the runs of this window (see [RunsState]). */
    val runs: RunsState = RunsState(),
    /**
     * Contract 1.8.0 / UI-STORY-1: each project's stories, keyed by project id. A `stories` frame
     * replaces only the project(s) it names; a single `story` frame upserts one story by id, so a
     * `story_move` answer updates its status.
     */
    val stories: Map<String, List<StoryView>> = emptyMap(),
    /**
     * Contract 1.21.0 / UI-MEM-1: each project's memory index, keyed by project id. A `memory_notes`
     * frame names no project, so it is attributed to the oldest awaited `memory_list` ([memoryListAwaiting]);
     * a `memory_note` frame (a `memory_save` answer) upserts one note by name inside its own project.
     */
    val memoryNotes: Map<String, List<MemoryNoteView>> = emptyMap(),
    /** Contract 1.21.0 / UI-MEM-1: project ids whose `memory_list` answer this window is awaiting. */
    val memoryListAwaiting: List<String> = emptyList(),
    /**
     * Contract 1.20.0 / UI-MEM-1: the latest memory search -- the query this window asked and the hits
     * the daemon returned. The `memory_hits` frame names neither project nor query, so both are taken
     * from the awaited request ([memorySearchAwaiting]).
     */
    val memorySearch: MemorySearchView? = null,
    /** Contract 1.44.0 (P10-C): the saved prompt library, ordered by name. */
    val prompts: List<PromptView> = emptyList(),
    /** P10-C: a `prompt_list` this window sent has not been answered yet. */
    val promptsLoading: Boolean = false,
    /** P10-C: the daemon's last `prompt_*` refusal, shown inline; cleared by the next request or answer. */
    val promptError: String? = null,
    /** SKILLS-GLOBAL-1b: layered skills by [layerSkillKey] (global / project / role), as the daemon last sent them. */
    val layerSkills: Map<String, List<LayerSkillItem>> = emptyMap(),
    /** Contract 1.45.0 (P10-D): the saved skills library, ordered by name. */
    val skills: List<SkillView> = emptyList(),
    /** P10-D: a `skill_list` this window sent has not been answered yet. */
    val skillsLoading: Boolean = false,
    /** P10-D: the daemon's last `skill_*` refusal, shown inline; cleared by the next request or answer. */
    val skillError: String? = null,
    /** Contract 1.48.0 (TRAITS-T8): the trait library, ordered by name; empty from an old daemon. */
    val traits: List<TraitView> = emptyList(),
    /** Contract 1.49.0 (TRAITS-T8): each role's ordered trait ids, by role id; a role with none is absent. */
    val roleTraits: Map<String, List<String>> = emptyMap(),
    /** TRAITS-T8: a `trait_list` this window sent has not been answered yet. */
    val traitsLoading: Boolean = false,
    /** TRAITS-T8: the daemon's last `trait_*` / `role_traits_*` refusal, shown inline; cleared by the next request or answer. */
    val traitError: String? = null,
    /** Contract 1.20.0 / UI-MEM-1: `memory_search` requests whose answer this window is awaiting. */
    val memorySearchAwaiting: List<MemorySearchQuery> = emptyList(),
    /**
     * UI-BOARD-PROJ: project ids whose scoped `ticket_list` answer this window is awaiting. A `tickets`
     * frame is the WHOLE board -- the connect-time list, or a delete / reorder push -- unless a scoped
     * request is outstanding, in which case it is that one project's slice. The two are told apart the
     * same way a `memory_list` answer is attributed to the oldest awaited request ([memoryListAwaiting]),
     * so a scoped answer merges into the board and a whole-board frame still replaces it.
     */
    val ticketListAwaiting: List<String> = emptyList(),
    /** Contract 1.36.0 (CP-2): each project's last `project_git` answer, keyed by project id. */
    val projectGit: Map<String, ProjectGitStatus> = emptyMap(),
    /**
     * BUG-PGIT-LOAD: the last `project_git_status refused/failed: ...` error frame. It names no project, so it is shown
     * in place of "not read yet" for a project that has no answer yet; any `project_git` answer clears it.
     */
    val projectGitFailure: String? = null,
    /** Contract 1.36.0 (CP-2): each project's LAST `project_git_patch` answer (one file at a time), keyed by project id. */
    val projectGitPatch: Map<String, ProjectGitPatch> = emptyMap(),
    /** Contract 1.36.0 (CP-2): each project's last `project_git_history` answer, keyed by project id. */
    val projectGitHistory: Map<String, ProjectGitHistory> = emptyMap(),
    /** Contract 1.65.0 (BRANCH-VIEW-1): each project's last `project_git_branch_list` answer, keyed by project id. */
    val projectGitBranches: Map<String, ProjectGitBranchList> = emptyMap(),
    /** Contract 1.99.0 (PIPE-2): each project's last `pipeline_state`, keyed by project id (absent = never asked). */
    val pipelines: Map<String, com.superiorsoftllc.infantry.ui.protocol.PipelineStateView> = emptyMap(),
    /** Contract 1.65.0 (BRANCH-VIEW-1): each project's LAST `project_git_branch_diffstat` answer (one branch at a time). */
    val projectGitBranchChanges: Map<String, ProjectGitBranchDiffstat> = emptyMap(),
    /**
     * Contract 1.74.0 (BRANCH-SWITCH-1): each project's LAST `project_git_checkout_result`, keyed by project id. The Git
     * pane shows a refusal's reason inline beside the branch list; a switch that happened is followed by a `project_git`.
     */
    val projectGitCheckout: Map<String, ProjectGitCheckoutResult> = emptyMap(),
    /**
     * BRANCH-VIEW-2: the branch the right-hand Changes panel shows instead of the working tree, for ONE project.
     * UI-side selection like [activeProjectId]; it applies only while that project is active ([changesBranch]).
     */
    val changesBranchPick: ChangesBranchPick? = null,
    /** Contract 1.88.0 (UX-CHANGES-1): each project's last `project_git_worktree_list` answer, keyed by project id. */
    val projectWorktrees: Map<String, ProjectGitWorktreeList> = emptyMap(),
    /** Contract 1.88.0 (UX-CHANGES-1): each worktree's last `project_git_worktree` answer, keyed by [worktreeStatusKey]. */
    val worktreeStatus: Map<String, ProjectGitWorktreeStatus> = emptyMap(),
    /** UX-CHANGES-1: the worktree the user picked in the Changes header ([changesWorktree]). UI-side only. */
    val changesWorktreePick: ChangesWorktreePick? = null,
    /** BUG-CHANGES-WRONG-BRANCH: the inner picker's choice, keyed on the worktree it was made for ([changesShownBranch]). */
    val changesViewPick: ChangesViewPick? = null,
    /** The project the shell is scoped to: UI-side selection, like [selectedId] is for a session. */
    val activeProjectId: String? = null,
) {
    /** One board column, top first, of the tickets the active project shows. */
    fun column(status: String): List<Ticket> = visibleTickets.filter { it.status == status }.sortedBy { it.position }

    fun ticket(id: String?): Ticket? = id?.let { wanted -> tickets.firstOrNull { it.id == wanted } }

    fun trigger(id: String?): TriggerView? = id?.let { wanted -> triggers.firstOrNull { it.id == wanted } }

    val trippedTriggerCount: Int get() = triggers.count { it.state == "tripped" }

    fun role(id: String?): Role? = id?.let { wanted -> roles.firstOrNull { it.id == wanted } }

    /**
     * M2-3: what an agent is called in the agents list and the session header: its role's display name
     * ("Project Manager"), else the bare role id when the catalogue does not know it yet, else the
     * branch or short session id as before.
     */
    fun agentTitle(a: AgentView): String =
        a.role?.takeIf { it.isNotBlank() }?.let { r -> role(r)?.name?.takeIf { it.isNotBlank() } ?: r }
            ?: a.session.worktreeBranch ?: a.shortId

    /** M2-3: the short id shown as secondary text, or null when the title already is that id. */
    fun agentSecondaryId(a: AgentView): String? = a.shortId.takeIf { it != agentTitle(a) }

    /**
     * M2-3b: what [a] really runs on, for the session header's harness / provider / model chips. The model is the
     * one the daemon's "starting <harness> model <id>" boot line prints -- the session's own model, else the
     * harness default from the `providers` frame -- and, failing both, the model the last run reported using.
     * Any part that cannot be known is null, so its chip is hidden rather than guessed.
     */
    /**
     * CONTEXT-LIMIT-2: the session's context limit chip; null when it has none. The support rides on the session; the how
     * text is the harness's, from the `providers` frame.
     */
    fun effortChip(a: AgentView): EffortChip? = sessionEffortChip(a.session, providers)

    fun contextLimitChip(a: AgentView): ContextLimitChip? =
        sessionContextLimitChip(a.session, providers)

    fun sessionExecutor(a: AgentView): SessionExecutor {
        val harness = a.session.provider.takeIf { it.isNotBlank() }
        val full = a.session.model?.takeIf { it.isNotBlank() }
            ?: providers.firstOrNull { it.id == harness }?.defaultModel?.takeIf { it.isNotBlank() }
            ?: a.usage.lastOrNull()?.model?.takeIf { it.isNotBlank() }
        if (full == null) return SessionExecutor(harness, null, null, null)
        val slash = full.indexOf('/')
        return if (slash in 1 until full.lastIndex) {
            SessionExecutor(harness, full.substring(0, slash), full.substring(slash + 1), full)
        } else {
            // No "<provider>/<model>" prefix: the provider is only known when the models catalogue files this id under a vendor group.
            val group = modelCatalog.firstOrNull { it.provider == harness && full in it.models && it.group.isNotBlank() }?.group
            SessionExecutor(harness, group, full, full)
        }
    }

    /** The profiles bound to [roleId], in the daemon's order. */
    /** CP-3: the agents of the ACTIVE project only; another project's agents are never listed or addressable here. */
    val visibleProfiles: List<AgentProfile> get() = activeProjectId?.let { active -> profiles.filter { it.projectId == active } } ?: profiles

    fun profilesFor(roleId: String): List<AgentProfile> = visibleProfiles.filter { it.role == roleId }

    val attentionNewestFirst: List<AttentionItem> get() = attention.sortedByDescending { it.at }
    val attentionOpenCount: Int get() = attention.count { it.ackedAt == null }

    /** Newest first: the agent you just spawned is at the top, like a chat list. */
    val orderedAgents: List<AgentView>
        get() = agents.values.filter { !it.session.global }.sortedByDescending { it.session.spawnedAt }

    /** ASSISTANT-1: the one installation-wide Assistant, once the daemon has made it. Never in the agent list, never auto-selected. */
    val assistant: AgentView? get() = agents.values.firstOrNull { it.session.global }

    val selected: AgentView? get() = selectedId?.let { agents[it] }

    /** ASK-1: the owner questions still waiting for a pick, oldest first. */
    val openQuestions: List<com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView>
        get() = questions.filter { it.state == QUESTION_OPEN }

    /** ASK-1: what the question window shows: the open questions not hidden by "Later", oldest first. */
    val shownQuestions: List<com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView>
        get() = openQuestions.filter { it.id !in dismissedQuestions }

    /** ASK-1: one session's pending questions as its chat shows them inline: open, not put off, oldest first. */
    fun shownQuestionsFor(sessionId: String): List<com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView> =
        shownQuestions.filter { it.sessionId == sessionId }

    /** ASK-1: the sessions with an open owner question (they count as needing input). */
    val openQuestionSessions: Set<String> get() = openQuestions.mapTo(HashSet()) { it.sessionId }

    /** ASK-1: the question history for the attention tab, newest first. */
    val questionsNewestFirst: List<com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView>
        get() = questions.sortedByDescending { it.createdAt }

    /** Needs-input is backed by a frame or it is not shown. See [needsInput]. */
    fun needsInput(id: String): Boolean = agents[id]?.let { needsInput(it, waiting, openPrompts, openQuestionSessions) } ?: false

    val needsInputCount: Int get() = openQuestionSessions.let { q -> agents.values.count { needsInput(it, waiting, openPrompts, q) } }

    /** PROMPT-2: whether a prompt of [sessionId] is open on the daemon (the "needs you" badge). */
    fun hasOpenPrompt(sessionId: String): Boolean = openPrompts.any { it.sessionId == sessionId }

    /** PROMPT-2: the prompts the card of [sessionId] shows, oldest first; Esc-dismissed questions are left out. */
    fun promptsFor(sessionId: String?): List<com.superiorsoftllc.infantry.ui.protocol.PromptRequestView> =
        if (sessionId == null) emptyList() else openPrompts.filter { it.sessionId == sessionId && it.promptId !in dismissedPrompts }
    val workingCount: Int get() = agents.values.count { it.session.state in WORKING }
    val reviewQueue: List<ReviewEntry> get() = reviewQueueOf(pods, agents)

    /**
     * M2-4: which status group of the agents list [a] sits in. One group per agent, first match wins:
     * waiting on the human, then a run of it pending review, then working, else inactive.
     */
    fun agentGroup(a: AgentView, reviewSessions: Set<String> = reviewQueue.mapNotNullTo(HashSet()) { it.sessionId }): AgentGroup = when {
        needsInput(a, waiting, openPrompts, openQuestionSessions) -> AgentGroup.NEEDS_YOU
        a.id in reviewSessions -> AgentGroup.TO_REVIEW
        a.session.state in WORKING -> AgentGroup.WORKING
        a.session.automation != null -> AgentGroup.WORKING
        else -> AgentGroup.INACTIVE
    }

    /** M2-4: [visible] split into status groups, in [AgentGroup] order, each keeping [visible]'s order; empty groups are absent. */
    fun groupedAgents(visible: List<AgentView>): List<Pair<AgentGroup, List<AgentView>>> {
        val review = reviewQueue.mapNotNullTo(HashSet()) { it.sessionId }
        val byGroup = visible.groupBy { agentGroup(it, review) }
        return AgentGroup.entries.mapNotNull { g -> byGroup[g]?.let { g to it } }
    }

    /** The project the shell is scoped to, resolved from [projects] by [activeProjectId]. */
    val activeProject: ProjectView? get() = activeProjectId?.let { id -> projects.firstOrNull { it.id == id } }

    /** The workspaces under the active project, in the order the shell knows them. */
    val visibleWorkspaces: List<WorkspaceView> get() = workspaces.filter { it.projectId == activeProjectId }

    /**
     * UI-BOARD-PROJ: the tickets the Board shows -- the active project's, or the whole board when no
     * project is selected (the legacy global view, a null-project ticket included). The model keeps every
     * project's tickets; this is where the scoping is applied.
     */
    val visibleTickets: List<Ticket>
        get() = if (activeProjectId == null) tickets else tickets.filter { it.projectId == activeProjectId }
}

/**
 * NOTICE-COLOUR-1: how a notice is drawn. Red is opt-in: a notice is routine ([OK], the theme's success green)
 * unless its raiser says it is a [HEADS_UP] (amber: worth a look, nothing broke) or a real [ERROR] (red).
 */
enum class NoticeKind { OK, HEADS_UP, ERROR }

data class Notice(
    val at: Long,
    val text: String,
    val sessionId: String? = null,
    val kind: NoticeKind = NoticeKind.OK,
    /** The project the event happened in, or null for a notice that is not about one project. */
    val projectId: String? = null,
) {
    /** True for anything that is not a failure. */
    val info: Boolean get() = kind != NoticeKind.ERROR

    /** True when this notice is about a project other than [activeProjectId]: it belongs in the global toast, not the chat. */
    fun isCrossProject(activeProjectId: String?): Boolean = projectId != null && activeProjectId != null && projectId != activeProjectId
}

/** Notices for the current chat/project: everything that is not about another project. The strip shows these. */
fun AppState.localNotices(): List<Notice> = notices.filterNot { it.isCrossProject(activeProjectId) }

/** Notices about OTHER projects, oldest first: the global bottom-right toast host and its review list show these. */
fun AppState.crossProjectNotices(): List<Notice> = notices.filter { it.isCrossProject(activeProjectId) }

/**
 * NOTICE-COLOUR-1: a daemon `error` frame is a failure, except the refusals that only say the agent is mid-turn
 * (a prompt that met a run in flight, a stop that raced the run's own end). Those are heads-ups, not breakage.
 */
fun daemonErrorKind(text: String): NoticeKind =
    if (text.contains("already has a run in flight") || text.contains("no such run in flight")) NoticeKind.HEADS_UP else NoticeKind.ERROR

/**
 * NOTICE-COLOUR-1: a ticket start answer's colour. `started` and `queued` (waiting for a free lane) are routine,
 * `held` (an open prerequisite) and a start with a launch warning are heads-ups; `start_refused` is a failure.
 */
fun ticketStartKind(start: TicketStart): NoticeKind = when {
    start.reason == "start_refused" -> NoticeKind.ERROR
    start.reason == "held" -> NoticeKind.HEADS_UP
    start.detail?.contains(TICKET_LAUNCH_WARNING) == true -> NoticeKind.HEADS_UP
    start.ok || start.reason == "queued" -> NoticeKind.OK
    else -> NoticeKind.ERROR
}

/** How a launch warning in a started ticket's detail begins (the daemon's `TicketStart.LAUNCH_WARNING`). */
private const val TICKET_LAUNCH_WARNING = "launch warning: "

/**
 * M2-4: the agents list's status groups, in display order, with their header labels.
 *
 * ORIGIN-SCRUB-1c: the labels are display text only, written in sentence case. A group is worked out from
 * [SessionState] and the mailbox on every render, and neither the label nor the constant name is ever
 * stored or sent over the wire, so changing a label needs no migration. The constant names are code
 * identifiers (test tags use them), not text the user sees.
 *  - "Waiting on you": the same words [needsInput] uses for this case.
 *  - "Ready to check": a run that is finished and waiting for the human to look at it.
 *  - "Running": a common English word, kept because the session really is a running process and
 *    no other word says that as plainly.
 *  - "Quiet": covers idle, exited, failed and orphaned sessions together; "idle" alone would be wrong
 *    for the last three.
 */
enum class AgentGroup(val label: String) {
    NEEDS_YOU("Waiting on you"),
    TO_REVIEW("Ready to check"),
    WORKING("Running"),
    INACTIVE("Quiet"),
}

private val WORKING = setOf(SessionState.STARTING, SessionState.BUSY)
private val IN_FLIGHT = setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)

/**
 * The ONLY two signals that make an agent "waiting on a human":
 *  1. the daemon put the session in WAITING_INPUT, or
 *  2. the mailbox says it asked a question and has nothing else in flight (`mail_waiting`).
 * No heuristic over transcript text (a trailing "?" proves nothing). Today only (2) is produced by
 * a real provider; (1) is defined by the contract and lights up the moment a provider raises it.
 */
fun needsInput(
    agent: AgentView,
    waiting: List<WaitingOn>,
    prompts: List<com.superiorsoftllc.infantry.ui.protocol.PromptRequestView> = emptyList(),
    /** ASK-1: sessions with an open owner question ([AppState.openQuestionSessions]). */
    questionSessions: Set<String> = emptySet(),
): Boolean =
    agent.session.state == SessionState.WAITING_INPUT || waiting.any { it.session == agent.id } ||
        prompts.any { it.sessionId == agent.id } || agent.id in questionSessions

/** ASK-1: the `state` of an owner question still waiting for the owner. */
const val QUESTION_OPEN = "open"

/** ASK-1: the `state` of an owner question the daemon auto-picked after its `defaultAfterMin` (still overridable). */
const val QUESTION_AUTO = "auto"

fun stateLabel(state: SessionState): String = when (state) {
    SessionState.STARTING -> "starting"
    SessionState.BUSY -> "working"
    SessionState.WAITING_INPUT -> "needs input"
    SessionState.IDLE -> "idle"
    SessionState.EXITED -> "exited"
    SessionState.ORPHANED -> "orphaned"
    SessionState.FAILED -> "failed"
}

/**
 * UI-ACT-2: a session still has a turn in flight (working or awaiting input), i.e. it is not idle.
 * The Activity box starts expanded for such a session and collapsed for a terminal one.
 */
fun isRunning(state: SessionState): Boolean = state in IN_FLIGHT

/**
 * UI-ACT-2: an activity entry's arrival time as wall-clock `h:mm:ss AM/PM` in [timeZone] (the system zone
 * by default; injectable so a model test pins UTC). Each Activity-box line is prefixed with this.
 */
fun activityTimestamp(at: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(timeZone)
    return TimeFormat.clock12(t.hour, t.minute, t.second)
}

/**
 * Why the next message cannot go to [agent] right now, or null when it can. The daemon applies the same
 * rule (one run in flight per session; a turn continues the provider's own session id) and would refuse
 * with an `error`; saying it here keeps the text in the box and the reason next to it.
 */
fun promptBlocker(agent: AgentView): String? {
    val s = agent.session
    return when {
        turnInFlight(s) -> "the agent is still answering; send your next message when this turn has finished (or kill it)"
        // CMD-HARNESS-1: a bound-but-never-run session (what a harness switch cuts) takes its first turn, as the
        // daemon's `neverRun` rule does; only a session that ran without reporting an id has nothing to continue.
        // GAP-006b: and so does one AT REST whose run reported no id (its brief never landed): the daemon runs the message
        // as its first turn, with a ticket worker's brief ahead of it. Only one that FAILED that way has nothing to continue.
        s.providerSessionId == null && s.state != SessionState.IDLE -> "nothing to continue: the provider never reported a session id for this agent; start a new one"
        else -> null
    }
}

/**
 * QUEUE-STUCK-1: which queued agents to send their next queued message now. An agent at rest with a non-empty queue is
 * drained on ANY frame, not only on the busy-to-idle edge: a turn started by mail or automation can end between two
 * frames we saw, and the edge is then never observed. With request ids ([tracksSends]) a send in flight for the agent
 * ([AppState.queueSending]) holds the next one back; an older daemon has no such handle, so it keeps the edge rule.
 */
fun queueDrainTargets(before: AppState, after: AppState, tracksSends: Boolean): List<String> =
    after.queued.keys.filter { sessionId ->
        val agent = after.agents[sessionId] ?: return@filter false
        if (isBusy(agent) || agent.session.state != SessionState.IDLE || promptBlocker(agent) != null) return@filter false
        if (tracksSends) after.queueSending.values.none { it.sessionId == sessionId }
        else before.agents[sessionId]?.let { isBusy(it) } ?: false
    }

/**
 * A message held while its agent is busy: the text as the human typed it, and when it was queued. CHAT-QUEUE-1: [id] = the daemon
 * holds it (contract 1.150.0; it can be removed or forced by id); [requestId] = sent to the daemon with `queue_add`, shown here until
 * the daemon's session frame lists it (or refuses it). Neither = this window holds it (an older daemon).
 */
data class QueuedPrompt(val text: String, val at: Long, val id: String? = null, val requestId: String? = null)

/** A queued [prompt] sent to [sessionId] as its next turn, until the daemon takes or refuses it. */
data class QueueSend(val sessionId: String, val prompt: QueuedPrompt)

/**
 * ticket-b5aa01bc: the daemon refused a queued message because another run (mail, typically) started first: it goes
 * back to the FRONT of its queue, still visibly queued, and is not a failure to report. Null for any other frame.
 */
private fun requeueRefused(s: AppState, env: Envelope): AppState? {
    val requestId = env.requestId ?: return null
    val sending = s.queueSending[requestId] ?: return null
    if (!env.data.startsWith(RunInFlight.PREFIX + " ")) return null
    return s.copy(queueSending = s.queueSending - requestId)
        .withQueue(sending.sessionId, listOf(sending.prompt) + s.localQueue(sending.sessionId))
}

/** "running: gate, build" while the agent's registered `bg run` jobs are what keeps it busy; null otherwise. Desktop and mobile share it. */
fun bgJobsText(session: SessionRecord): String? =
    session.bgJobs.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "running: ") { it.label }

/** The agent has a run in flight: a message composed now must wait for it, and Interrupt can stop it. */
fun isBusy(agent: AgentView): Boolean = turnInFlight(agent.session)

/**
 * A TURN is in flight (not just background work, which also reads state BUSY on the wire). Contract 1.112.0
 * `turnActive` says so; an older daemon omits it and background work then counts as not busy.
 */
fun turnInFlight(s: SessionRecord): Boolean =
    s.turnActive ?: (s.state in IN_FLIGHT && s.backgroundWork == null && s.bgJobs.isEmpty())

/** Why Interrupt cannot be used on [agent] right now, or null when it can. Mirrors "a run is in flight". */
fun interruptBlocker(agent: AgentView): String? =
    if (isBusy(agent)) null else "nothing to interrupt: the agent is not busy"

/**
 * BRANCH-PER-TASK 2/3: the started ticket whose session is [sessionId], when that ticket holds a branch. A ticket
 * that never started holds none, so nothing about a branch is shown for its agent.
 */
fun AppState.ticketBranchFor(sessionId: String): Ticket? =
    tickets.firstOrNull { it.sessionId == sessionId && !it.branch.isNullOrBlank() }

/**
 * BRANCH-PER-TASK 2/3: the read-only line a ticket shows for its branch, `<branch> · base <8 chars of base>`, or null
 * when the ticket holds no branch (then nothing is shown, not a placeholder).
 */
fun Ticket.branchLine(): String? {
    val name = branch?.takeIf { it.isNotBlank() } ?: return null
    val commit = base?.takeIf { it.isNotBlank() }?.take(8) ?: return name
    return "$name · base $commit"
}

/**
 * The messages queued for [sessionId], oldest first (empty when none): CHAT-QUEUE-1, the ones the daemon holds (its session record,
 * each with its [QueuedPrompt.id]) then the ones this window holds or has just sent with `queue_add`.
 */
fun AppState.queuedFor(sessionId: String): List<QueuedPrompt> =
    agents[sessionId]?.session?.queued.orEmpty().map { QueuedPrompt(it.text, it.at, id = it.id) } + localQueue(sessionId)

/** The messages THIS window holds for [sessionId] (an older daemon's queue, or `queue_add`s not yet listed by the daemon). */
fun AppState.localQueue(sessionId: String): List<QueuedPrompt> = queued[sessionId].orEmpty()

/** CHAT-QUEUE-1: whether [sessionId]'s harness takes a forced message INTO its running turn (`providers` entry `midTurnInput`). */
fun AppState.forcesMidTurn(sessionId: String): Boolean {
    val provider = agents[sessionId]?.session?.provider ?: return false
    return providers.firstOrNull { it.id == provider }?.midTurnInput == true
}

/**
 * CHAT-QUEUE-1: a `queue_add` this window sent is shown until the daemon answers: dropped once the daemon's queue lists a message with
 * its text (each daemon item settles one), or when the daemon refused that request ([env] an `error` with its requestId). A message
 * the daemon sent as a turn at once is dropped by its `turn` frame ([queueAfterTurn]).
 */
fun settlePendingQueue(s: AppState, env: Envelope): AppState {
    if (s.queued.values.none { q -> q.any { it.requestId != null } }) return s
    var out = s
    for ((sessionId, items) in s.queued) {
        if (items.none { it.requestId != null }) continue
        val held = s.agents[sessionId]?.session?.queued.orEmpty().map { it.text }.toMutableList()
        val kept = items.filter { q ->
            when {
                q.requestId == null -> true
                env.type == Envelope.ERROR && env.requestId == q.requestId -> false
                held.remove(q.text) -> false
                else -> true
            }
        }
        if (kept != items) out = out.withQueue(sessionId, kept)
    }
    return out
}

/** Replace [sessionId]'s queue; an empty list removes the entry, so "no queue" has one representation. */
fun AppState.withQueue(sessionId: String, items: List<QueuedPrompt>): AppState =
    copy(queued = if (items.isEmpty()) queued - sessionId else queued + (sessionId to items))

/** A `replay_request` to send: [sessionId]'s retained frames after the exclusive [afterSequence]. */
data class HistoryRequest(val sessionId: String, val afterSequence: Long)

/**
 * The history to ask for when an agent is on screen: once per daemon instance ([requested]), and only for
 * a session active since that instance's history began -- older sessions have nothing to replay, and
 * asking would only draw a no-replay-history error.
 */
fun historyRequest(state: AppState, requested: Set<String>): HistoryRequest? {
    if (!state.connection.isLive) return null
    val since = state.connection.historySince ?: return null
    val agent = state.selected ?: return null
    if (agent.id in requested || agent.session.lastHeartbeat < since) return null
    return HistoryRequest(agent.id, agent.cursor.contiguous)
}

/** After a reconnect to the SAME daemon instance: fill whatever streamed while the link was down. */
fun resyncRequests(state: AppState, requested: Set<String>): List<HistoryRequest> {
    if (!state.connection.isLive || state.connection.historySince == null) return emptyList()
    return requested.mapNotNull { id -> state.agents[id]?.let { HistoryRequest(id, it.cursor.contiguous) } }
}

/** A converged pod's node awaiting a human verdict. The diff itself is not on the wire (see report). */
data class ReviewEntry(
    val podId: String,
    val podGoal: String,
    val nodeId: String,
    val nodeName: String,
    val sessionId: String?,
    val branch: String?,
    val workingDir: String?,
)

fun reviewQueueOf(pods: Map<String, PodStatus>, agents: Map<String, AgentView>): List<ReviewEntry> =
    pods.values.flatMap { status ->
        status.nodes.filter { it.reviewState == "PENDING" }.map { node ->
            val lastRun = status.runs[node.id].orEmpty().lastOrNull { it.isFinished } ?: status.runs[node.id].orEmpty().lastOrNull()
            val session = lastRun?.sessionId?.let { agents[it]?.session }
            ReviewEntry(
                podId = status.pod.id,
                podGoal = status.pod.goal,
                nodeId = node.id,
                nodeName = node.name,
                sessionId = lastRun?.sessionId,
                branch = session?.worktreeBranch,
                workingDir = lastRun?.workingDir,
            )
        }
    }

private val json = Json { ignoreUnknownKeys = true }
private const val MAX_LINES = 2_000
private const val MAX_NOTICES = 50
/** UI-ACT-1: the per-session activity history keeps at most the last this many tool frames. */
private const val MAX_ACTIVITY_LOG = 200

/** Pure: apply one daemon frame. Unknown frame types are ignored, which is what makes a minor bump safe. */
fun reduce(state: AppState, env: Envelope, now: Long): AppState {
    val stamped = state.copy(connection = state.connection.copy(lastFrameAt = now))
    return try {
        reduceTraitFrame(stamped, env, now) ?: apply(stamped, env, now)
    } catch (e: Exception) {
        // A frame we cannot decode must be visible, not silently dropped, but must not kill the stream.
        stamped.withNotice(now, "could not read a '${env.type}' frame: ${e.message?.take(120)}", env.sessionId, NoticeKind.ERROR)
    }
}

private fun apply(s: AppState, env: Envelope, now: Long): AppState = when (env.type) {
    Envelope.READY -> ready(s, env)
    Envelope.POLICY -> policy(s, env)

    Envelope.SESSIONS -> {
        val raw = json.parseToJsonElement(env.data) as JsonArray
        val roles = raw.mapNotNull { (it as? JsonObject)?.let { o -> o["id"]?.jsonPrimitive?.contentOrNull?.let { id -> id to roleOf(o) } } }.toMap()
        val records = json.decodeFromString(ListSerializer(SessionRecord.serializer()), env.data)
        // UI-AGENT-DEL: `sessions` is the daemon's full authoritative list (it pushes the whole
        // `manager.list()` on connect, on request and after every list mutation, including a delete),
        // so any agent the fresh list no longer names was deleted and must leave the sidebar. The
        // upserts below keep the transcript of a still-present session; this drops the others.
        var next = s.copy(agents = s.agents.filterKeys { it in records.map { r -> r.id } })
        for (r in records) next = next.upsert(r, roles[r.id])
        next.autoSelect()
    }

    Envelope.SESSION -> {
        val record = json.decodeFromString(SessionRecord.serializer(), env.data)
        val role = roleOf(json.parseToJsonElement(env.data).jsonObject)
        val existing = s.agents[record.id]
        val seq = env.sequence
        when {
            existing == null -> {
                val created = s.upsert(record, role).let { n ->
                    if (seq == null) n else n.copy(agents = n.agents + (record.id to n.agents.getValue(record.id).let { a -> a.copy(cursor = a.cursor.mark(seq)) }))
                }
                created.selectSpawned(record.id).selectFollowed(record.id).autoSelect()
            }
            seq != null && existing.cursor.seen(seq) -> s
            else -> {
                val marked = if (seq == null) s else s.copy(agents = s.agents + (record.id to existing.copy(cursor = existing.cursor.mark(seq))))
                marked.upsert(record, role).autoSelect()
            }
        }
    }

    Envelope.TURN -> s.stream(env) { a, _ -> a.withEntry(TranscriptEntry(env.sequence, LineKind.USER, env.data, now, origin = env.origin ?: LegacyOriginAdapter.originOf(env.data))) }
        // A queued message on the stream was taken: its wait for a refusal is over.
        .let { t -> t.copy(queueSending = t.queueSending.filterValues { it.sessionId != env.sessionId || !turnCarries(env.data, it.prompt.text) }) }
        // QUEUE-STUCK-2: a turn the agent took by ANY path (an interrupt, an injected input, another window) delivers the
        // queued messages whose text it carries: they leave the queue now, so the at-rest drain never sends them twice.
        .let { t -> env.sessionId?.let { id -> t.withQueue(id, queueAfterTurn(t.localQueue(id), env.data)) } ?: t }

    Envelope.TEXT_DELTA -> s.stream(env) { a, _ -> a.withEntry(TranscriptEntry(env.sequence, LineKind.TEXT, env.data, now)) }

    Envelope.REASONING -> s.stream(env) { a, _ -> a.withEntry(TranscriptEntry(env.sequence, LineKind.REASONING, env.data, now)) }

    Envelope.STATUS -> s.stream(env) { a0, inOrder ->
        val a = a0.closeLastText()
        val p = json.decodeFromString(StatusPayload.serializer(), env.data)
        // Only a provider-supplied label is worth a transcript line; plain lifecycle is in the row state.
        val withDetail = if (p.detail.isNotBlank()) a.withEntry(TranscriptEntry(env.sequence, LineKind.STATUS, p.detail, now)) else a
        // Contract 1.80.0 (UX-WRITE-1): a file-writing tool call IS a transcript line -- the change box the chat shows
        // in the turn where it happened. Every other tool stays out of the transcript, as before.
        val withLine = p.write?.let { withDetail.withEntry(TranscriptEntry(env.sequence, LineKind.WRITE, fileWriteText(it), now)) } ?: withDetail
        // Contract 1.26.0: remember the latest tool activity (name + salient argument). It is NOT a
        // transcript line (that would pollute the log); it feeds the live status row and its stall clock.
        // UI-ACT-1: it is also the newest entry of the session's activity history, which keeps one entry
        // per tool frame, capped at the last MAX_ACTIVITY_LOG; `activity` is its last entry that is a real tool call.
        // Contract 1.64.0 (BUG-M2-11b-1): the session's own per-turn plumbing (`starting …`, `process started …`)
        // rides `status` with `tool` = the session's provider id. It belongs in the activity history, but it is not
        // a running tool, so it leaves `activity` alone and the status row keeps reading "Thinking...".
        val withActivity = if (p.tool != null) {
            val entry = AgentActivity(p.tool, p.activity, now)
            val log = (withLine.activityLog + entry).takeLast(MAX_ACTIVITY_LOG)
            if (p.tool == a.session.provider) withLine.copy(activityLog = log) else withLine.copy(activity = entry, activityLog = log)
        } else withLine
        // Contract 1.95.0 (1c24fdc4): the run activity is a state, so only an in-order frame may replace it.
        val withRun = if (inOrder && p.runActivity != null) {
            withActivity.copy(runActivity = RunActivityView(p.runActivity.state, p.runActivity.label, now))
        } else withActivity
        // Contract 1.127.0 (TODO-OVERLAY-1): a to-do call carries the FULL list, so the newest in-order one replaces it.
        val withTodos = if (inOrder && p.todos != null) withRun.copy(todos = AgentTodoList.of(p.todos, now)) else withRun
        if (inOrder) withTodos.copy(statusDetail = p.detail) else withTodos
    }

    // DEMO-MERGE-1: a refusal whose code the daemon declared a notice (`ready` notices=) is kept for a module to show, not an error banner.
    Envelope.ERROR -> declaredNotice(s.connection, env.data)?.let { (code, msg) -> s.copy(connection = s.connection.copy(notices = s.connection.notices + (code to msg))) }
        ?: requeueRefused(s, env) ?: if (env.sessionId == null) {
        // A refused command. If it was this window's spawn, no session is coming: stop waiting for one.
        // P10-C: a `prompt_*` refusal also lands inline in the Prompts tab and ends its loading state.
        val promptRefusal = env.data.startsWith("prompt_")
        val skillRefusal = env.data.startsWith("skill_")
        // Role-first S8: an `agent_save` / `agent_swap` refusal is kept for the agent dialogs; nothing else changes.
        val agentRefusal = env.data.startsWith("agent_save ") || env.data.startsWith("agent_swap ")
        // M2-17: a `ticket_save` refusal is kept for the ticket edit form, which stays open to show it.
        val ticketRefusal = env.data.startsWith("ticket_save ")
        // RUN-CMD-2: a `command_save` / `command_delete` refusal is kept for the Run tab's editor.
        val commandRefusal = env.data.startsWith(Envelope.COMMAND_SAVE + " ") || env.data.startsWith(Envelope.COMMAND_DELETE + " ")
        // BUG-PGIT-LOAD: a git status the daemon could not answer is kept for the git panes, not only as a notice.
        val gitStatusFailure = env.data.startsWith(Envelope.PROJECT_GIT_STATUS + " ")
        // UX-HARNESS-2: a `session_swap` refusal is shown in plain words, never the wire's frame name.
        val sessionSwitchRefusal = HarnessSwitch.plainRefusal(env.data)
        val projectFailure = env.data.startsWith("project_") || env.data.startsWith("workspace_")
        s.copy(
            projectRefusal = if (projectFailure) Notice(now, env.data, null, daemonErrorKind(env.data)) else s.projectRefusal,
            projectGitFailure = if (gitStatusFailure) env.data else s.projectGitFailure,
            pendingSpawn = null,
            agentError = if (agentRefusal) env.data else s.agentError,
            pendingHarnessSwitch = if (env.data.startsWith("agent_swap ") || sessionSwitchRefusal != null) emptyMap() else s.pendingHarnessSwitch,
            sessionSwitchError = sessionSwitchRefusal ?: s.sessionSwitchError,
            ticketError = if (ticketRefusal) env.data else s.ticketError,
            runs = if (commandRefusal) s.runs.copy(commandError = env.data, commandsRevision = s.runs.commandsRevision + 1) else s.runs,
            promptError = if (promptRefusal) env.data else s.promptError,
            daemonConfigError = if (env.data.startsWith("config_set ")) env.data.substringAfter(": ", env.data) else s.daemonConfigError,
            promptsLoading = if (promptRefusal) false else s.promptsLoading,
            skillError = if (skillRefusal) env.data else s.skillError,
            skillsLoading = if (skillRefusal) false else s.skillsLoading,
            harnessInstallBusy = if (env.data.startsWith("harness_install")) false else s.harnessInstallBusy,
        ).withNotice(now, sessionSwitchRefusal ?: env.data, null, if (sessionSwitchRefusal != null) NoticeKind.ERROR else daemonErrorKind(env.data))
    } else {
        s.stream(env) { a, inOrder ->
            val withLine = a.withEntry(TranscriptEntry(env.sequence, LineKind.ERROR, env.data, now))
            if (inOrder) withLine.copy(lastError = env.data) else withLine
        }
    }

    Envelope.FINISHED -> s.stream(env) { a, inOrder ->
        val p = json.decodeFromString(FinishedPayload.serializer(), env.data)
        val label = "finished: ${p.outcome.name.lowercase().replace('_', ' ')}" + (p.exitCode?.let { " (exit $it)" } ?: "")
        val withLine = a.withEntry(TranscriptEntry(env.sequence, LineKind.FINISHED, label, now))
        if (inOrder) withLine.copy(finished = p) else withLine
    }

    // UI-LOG-1: raw provider stdout stays unrendered (its JSON is parsed elsewhere), but stderr lines
    // and the process exit are the reasons a run went wrong, so they become muted log lines on the
    // transcript instead of being silently dropped. They still count as applied (sequenced on the stream).
    Envelope.STDOUT -> s.stream(env) { a, _ -> a }
    Envelope.STDERR -> s.stream(env) { a, _ -> a.withEntry(TranscriptEntry(env.sequence, LineKind.LOG, env.data, now)) }
    Envelope.EXIT -> s.stream(env) { a, _ ->
        a.withEntry(TranscriptEntry(env.sequence, LineKind.LOG, "process exited with code ${env.data}", now))
    }

    Envelope.REPLAY_GAP -> s.withAgent(env.sessionId) { a ->
        val gap = json.decodeFromString(ReplayGap.serializer(), env.data)
        val missing = gap.oldestAvailableSequence - 1 - gap.requestedAfterSequence
        val skipped = a.copy(cursor = a.cursor.skipTo(gap.oldestAvailableSequence))
        if (missing <= 0) skipped else skipped.withEntry(
            TranscriptEntry(
                seq = gap.oldestAvailableSequence - 1,
                kind = LineKind.STATUS,
                text = "$missing earlier frames of this session are no longer retained by the daemon",
                at = now,
            ),
        )
    }

    Envelope.WORKTREES -> {
        val list = json.decodeFromString(ListSerializer(WorktreeStatus.serializer()), env.data)
        s.copy(worktrees = list.filter { !it.removed }.associateBy { it.sessionId })
    }

    Envelope.WORKTREE -> {
        val w = json.decodeFromString(WorktreeStatus.serializer(), env.data)
        s.copy(worktrees = if (w.removed) s.worktrees - w.sessionId else s.worktrees + (w.sessionId to w))
    }

    Envelope.POD -> {
        val p = json.decodeFromString(PodStatus.serializer(), env.data)
        s.copy(pods = s.pods + (p.pod.id to p))
    }

    Envelope.MAIL_WAITING ->
        s.copy(waiting = json.decodeFromString(ListSerializer(WaitingOn.serializer()), env.data))

    Envelope.MAIL_REFUSED -> s.withNotice(now, "mail refused: ${env.data.take(160)}", null, NoticeKind.ERROR)

    // Contract 1.12.0: the catalog, then each saved role pushed to every window (updated in place or appended).
    Envelope.ROLES -> s.copy(roles = json.decodeFromString(ListSerializer(Role.serializer()), env.data))
    Envelope.ROLE -> {
        val r = json.decodeFromString(Role.serializer(), env.data)
        s.copy(roles = if (s.roles.any { it.id == r.id }) s.roles.map { if (it.id == r.id) r else it } else s.roles + r)
    }

    // Contract 1.108.0 (ROLE-EDIT-1): a deleted custom role is gone from every window; its binding and traits go with the pick.
    Envelope.ROLE_REMOVED -> s.copy(roles = s.roles.filterNot { it.id == env.data.trim() })

    // Contract 1.40.0: the stored bindings, then each set pushed to every window (an unpinned one removes the entry).
    Envelope.ROLE_BINDINGS -> s.copy(roleBindings = json.decodeFromString(ListSerializer(RoleBinding.serializer()), env.data).associateBy { it.roleId })
    Envelope.ROLE_BINDING -> {
        val b = json.decodeFromString(RoleBinding.serializer(), env.data)
        val pinned = b.provider != null || b.model != null || b.effort != null
        s.copy(roleBindings = if (pinned) s.roleBindings + (b.roleId to b) else s.roleBindings - b.roleId)
    }

    // Contract 1.4.0: `agents` is the whole list (after a list or a delete); `agent` is one saved profile.
    Envelope.AGENTS -> s.copy(profiles = json.decodeFromString(ListSerializer(AgentProfile.serializer()), env.data))
    Envelope.AGENT -> s.withProfile(json.decodeFromString(AgentProfile.serializer(), env.data))

    // Contract 1.47.0: a swap, pushed to EVERY window (whoever asked): the agent as stored, and what became of its session.
    Envelope.SESSION_SWAPPED -> SessionSwapped.decode(env.data).let { w ->
        s.copy(sessionSwaps = s.sessionSwaps + (w.sessionId to w)).withSessionSwitched(w, now)
    }

    Envelope.AGENT_SWAPPED -> AgentSwapped.decode(env.data).let { w ->
        val id = w.agent.id
        if (id == null) s else s.withProfile(w.agent).copy(agentSwaps = s.agentSwaps + (id to w)).withHarnessSwitched(id, w, now)
    }

    // CP-4 slice d: a `/` command ran. It is not a chat line (it was never a prompt), so it is shown as a notice.
    Envelope.COMMAND_RESULT -> json.decodeFromString(CommandResultAnswer.serializer(), env.data).let { r ->
        s.withNotice(now, r.message, env.sessionId, if (r.ok) NoticeKind.OK else NoticeKind.ERROR)
    }

    Envelope.COMMAND_CATALOG -> s.copy(slash = SlashAnswer(env.sessionId, json.decodeFromString(CommandCatalogAnswer.serializer(), env.data)))

    Envelope.MODELS -> s.copy(modelCatalog = json.decodeFromString(ListSerializer(ProviderModels.serializer()), env.data))

    Envelope.PROVIDERS -> s.copy(providers = json.decodeFromString(ListSerializer(ProviderInfo.serializer()), env.data))

    // Contract 1.9.0: history on request; every new item and every acknowledgement is pushed as `attention`.
    // SCRIPT-VIS-1b: a plugin's chat line (no turn): one pushed, or the held history. Same id replaces; bounded per session.
    Envelope.TIMELINE_ITEM -> s.withTimeline(listOf(json.decodeFromString(TimelineItem.serializer(), env.data)))
    Envelope.TIMELINE_ITEMS -> s.withTimeline(json.decodeFromString(ListSerializer(TimelineItem.serializer()), env.data))
    Envelope.ATTENTION_ITEMS -> s.copy(attention = json.decodeFromString(ListSerializer(AttentionItem.serializer()), env.data))
    Envelope.ATTENTION -> {
        val item = json.decodeFromString(AttentionItem.serializer(), env.data)
        s.copy(attention = if (s.attention.any { it.id == item.id }) s.attention.map { if (it.id == item.id) item else it } else s.attention + item)
    }

    // Contract 1.13.0: one run's usage, on the session stream; a replayed copy of the same run replaces it.
    Envelope.USAGE -> s.stream(env) { a, _ ->
        val u = json.decodeFromString(RunUsage.serializer(), env.data)
        a.copy(usage = (a.usage.filter { it.runId != u.runId } + u).sortedBy { it.runId.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE })
    }

    // Contracts 1.5.0 / 1.6.0 / UI-BOARD-PROJ: a `tickets` frame is the WHOLE board -- the connect-time
    // list, or a delete / reorder push -- and replaces it, UNLESS this window has a scoped `ticket_list`
    // outstanding, in which case the frame is that one project's slice: replace exactly that project's
    // tickets and keep every other project's (the `workspaces` rule), clearing the slice when it is
    // empty. Telling the two apart needs the awaited request, because a slice names its project only
    // through its rows and an emptied project names none. `ticket` is one ticket (an answer or a push).
    Envelope.TICKETS -> {
        val incoming = json.decodeFromString(ListSerializer(Ticket.serializer()), env.data)
        val awaited = s.ticketListAwaiting.firstOrNull()
        if (awaited == null) {
            s.copy(tickets = incoming)
        } else {
            s.copy(
                tickets = s.tickets.filter { it.projectId != awaited } + incoming,
                ticketListAwaiting = s.ticketListAwaiting.drop(1),
            )
        }
    }
    Envelope.AGENT_QUEUE -> {
        val q = json.decodeFromString(AgentQueueAnswer.serializer(), env.data)
        s.copy(agentQueues = s.agentQueues + (q.agentId to q.tickets.sortedBy { it.rank }))
    }
    Envelope.TICKET -> {
        val t = json.decodeFromString(Ticket.serializer(), env.data)
        val before = s.tickets.firstOrNull { it.id == t.id }
        val upserted = s.copy(tickets = if (before != null) s.tickets.map { if (it.id == t.id) t else it } else s.tickets + t)
        // Another project's ticket appearing is a toast (project name + title); the current project's board shows its own.
        val elsewhere = t.projectId != s.activeProjectId && s.activeProjectId != null && t.start == null
        // A start that did not start the ticket must be said: the card alone would just sit in its column.
        val start = t.start
        if (before == null && elsewhere) upserted.withNotice(now, "ticket created: \"${t.title.take(60)}\"", null, NoticeKind.OK, t.projectId)
        else if (start != null && !start.ok) upserted.withNotice(now, "start of \"${t.title.take(60)}\": ${start.reason}${start.detail?.let { " - $it" } ?: ""}", null, ticketStartKind(start))
        // BASE-1b: a start that cut a branch says what it was cut from (`cut from <ref> <sha8>`), as an info line.
        else if (start != null && start.reason == "started" && !start.detail.isNullOrBlank()) {
            val text = "started \"${t.title.take(60)}\": ${start.detail}"
            // The answer and the board push can both carry the same start: say it once.
            // The toast is the global signal; the inline line is the owning session's record only (typed ticket.sessionId, never a global notice).
            val toasted = upserted.withToast(ToastEvent("started-${t.id}-${start.detail}", now, ticketStartKind(start).toToastKind(), "Ticket started", text, t.id, t.sessionId, t.projectId))
            if (t.sessionId == null || s.notices.lastOrNull()?.text == text) toasted else toasted.withNotice(now, text, t.sessionId, ticketStartKind(start))
        } else upserted
    }

    // Contract 1.14.0: `triggers` is the whole list (list, delete); `trigger` is one (save, fire, arm, disable).
    Envelope.TRIGGERS -> {
        val list = json.decodeFromString(ListSerializer(TriggerView.serializer()), env.data)
        s.copy(triggers = list, triggerEvents = s.triggerEvents.filterKeys { id -> list.any { it.id == id } })
    }
    Envelope.TRIGGER -> {
        val t = json.decodeFromString(TriggerView.serializer(), env.data)
        s.copy(triggers = if (s.triggers.any { it.id == t.id }) s.triggers.map { if (it.id == t.id) t else it } else s.triggers + t)
    }
    Envelope.TRIGGER_EVENTS -> {
        val rows = json.decodeFromString(ListSerializer(TriggerEventView.serializer()), env.data)
        // The answer carries no trigger id of its own: rows name it, and an empty answer is matched to the oldest request.
        val id = rows.firstOrNull()?.triggerId ?: s.eventsAwaiting.firstOrNull()
        if (id == null) s
        else s.copy(
            triggerEvents = s.triggerEvents + (id to rows),
            eventsAwaiting = s.eventsAwaiting.indexOf(id).let { i -> if (i < 0) s.eventsAwaiting else s.eventsAwaiting.filterIndexed { j, _ -> j != i } },
        )
    }

    // Contract 1.18.0: `projects` is the whole list. The first project becomes active when none is
    // chosen yet; the current active project is kept while it is still in the list.
    Envelope.PROJECTS -> {
        val views = json.decodeFromString(ListSerializer(Project.serializer()), env.data)
            .map { ProjectView(it.id, it.name, it.goal, it.directory, it.baseBranch, it.laneCaps, it.autoRemoveDoneWorkers, it.experiments) }
        val active = s.activeProjectId?.takeIf { id -> views.any { it.id == id } } ?: views.firstOrNull()?.id
        s.copy(projects = views, activeProjectId = active)
    }

    // Contract 1.18.0 (PW-5): a `workspaces` frame is ONE project's slice; each item names its own
    // projectId. Replace exactly the projects the frame names and keep every other project's
    // workspaces, so listing project B never drops project A's. An empty frame names no project and
    // clears the list (UI-WS-2: an empty list reduces to empty).
    Envelope.WORKSPACES -> {
        val incoming = json.decodeFromString(ListSerializer(Workspace.serializer()), env.data)
            .map { WorkspaceView(it.id, it.projectId, it.name, it.path) }
        if (incoming.isEmpty()) s.copy(workspaces = emptyList())
        else {
            val named = incoming.map { it.projectId }.toSet()
            s.copy(workspaces = s.workspaces.filter { it.projectId !in named } + incoming)
        }
    }

    // Contract 1.19.0 (UI-EPIC-1): an `epics` frame is ONE project's slice; each epic names its own
    // projectId. Replace exactly the projects the frame names and keep every other project's epics, so
    // listing project B never drops project A's. An empty frame names no project and clears the epics
    // (mirrors the `workspaces` rule). A single `epic` frame upserts that epic by id inside its project.
    Envelope.EPICS -> {
        val incoming = json.decodeFromString(ListSerializer(Epic.serializer()), env.data)
            .map { EpicView(it.id, it.projectId, it.name, it.goal, it.storyIds) }
        if (incoming.isEmpty()) s.copy(epics = emptyMap())
        else {
            val named = incoming.map { it.projectId }.toSet()
            s.copy(epics = s.epics.filterKeys { it !in named } + named.associateWith { pid -> incoming.filter { it.projectId == pid } })
        }
    }

    Envelope.EPIC -> {
        val e = json.decodeFromString(Epic.serializer(), env.data)
        val view = EpicView(e.id, e.projectId, e.name, e.goal, e.storyIds)
        val existing = s.epics[e.projectId].orEmpty()
        val upserted = if (existing.any { it.id == e.id }) existing.map { if (it.id == e.id) view else it } else existing + view
        s.copy(epics = s.epics + (e.projectId to upserted))
    }

    // The daemon's answer to project_save / workspace_save: ONE stored row, upserted by id (a create appends,
    // a re-send of a known id replaces it), so a new project or workspace appears without a re-list.
    Envelope.PROJECT -> {
        val p = json.decodeFromString(Project.serializer(), env.data).let { ProjectView(it.id, it.name, it.goal, it.directory, it.baseBranch, it.laneCaps, it.autoRemoveDoneWorkers, it.experiments) }
        val known = s.projects.any { it.id == p.id }
        s.copy(
            projects = if (known) s.projects.map { if (it.id == p.id) p else it } else s.projects + p,
            activeProjectId = s.activeProjectId ?: p.id,
            // A saved project means the last project request went through: an older refusal is no longer news.
            projectRefusal = null,
        )
    }

    // Contract 1.36.0 (CP-2): the three git answers each name their project, so each replaces exactly that
    // project's slot and no other. The patch slot holds ONE file at a time (the newest answer); the view shows it
    // only while it matches the file the user picked ([projectPatchView]).
    Envelope.PROJECT_GIT -> {
        val g = json.decodeFromString(ProjectGitStatus.serializer(), env.data)
        s.copy(projectGit = s.projectGit + (g.projectId to g), projectGitFailure = null)
    }

    Envelope.PROJECT_GIT_PATCH -> {
        val p = json.decodeFromString(ProjectGitPatch.serializer(), env.data)
        s.copy(projectGitPatch = s.projectGitPatch + (p.projectId to p))
    }

    Envelope.PROJECT_GIT_HISTORY -> {
        val h = json.decodeFromString(ProjectGitHistory.serializer(), env.data)
        s.copy(projectGitHistory = s.projectGitHistory + (h.projectId to h))
    }

    // Contract 1.99.0 (PIPE-2): an answer and a push alike replace only their project's slot.
    Envelope.PIPELINE_STATE -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.PipelineStateView.serializer(), env.data)
    }.getOrNull()?.let { p -> s.copy(pipelines = s.pipelines + (p.projectId to p)) } ?: s

    // Contract 1.65.0 (BRANCH-VIEW-1): both branch answers name their project and replace only its slot.
    Envelope.PROJECT_GIT_BRANCH_LIST -> {
        val b = json.decodeFromString(ProjectGitBranchList.serializer(), env.data)
        s.copy(projectGitBranches = s.projectGitBranches + (b.projectId to b))
    }

    Envelope.PROJECT_GIT_BRANCH_DIFFSTAT -> {
        val d = json.decodeFromString(ProjectGitBranchDiffstat.serializer(), env.data)
        s.copy(projectGitBranchChanges = s.projectGitBranchChanges + (d.projectId to d))
    }

    // Contract 1.74.0 (BRANCH-SWITCH-1): a checkout answer names its project and replaces only its slot.
    Envelope.PROJECT_GIT_CHECKOUT_RESULT -> {
        val r = json.decodeFromString(ProjectGitCheckoutResult.serializer(), env.data)
        s.copy(projectGitCheckout = s.projectGitCheckout + (r.projectId to r))
    }

    // Contract 1.88.0 (UX-CHANGES-1): the worktree list names its project, a worktree answer its project AND path;
    // each replaces exactly its own slot.
    Envelope.PROJECT_GIT_WORKTREE_LIST -> {
        val l = json.decodeFromString(ProjectGitWorktreeList.serializer(), env.data)
        s.copy(projectWorktrees = s.projectWorktrees + (l.projectId to l))
    }

    Envelope.PROJECT_GIT_WORKTREE -> {
        val w = json.decodeFromString(ProjectGitWorktreeStatus.serializer(), env.data)
        s.copy(worktreeStatus = s.worktreeStatus + (worktreeStatusKey(w.projectId, w.path) to w))
    }

    Envelope.WORKSPACE -> {
        val w = json.decodeFromString(Workspace.serializer(), env.data).let { WorkspaceView(it.id, it.projectId, it.name, it.path) }
        val known = s.workspaces.any { it.id == w.id }
        s.copy(workspaces = if (known) s.workspaces.map { if (it.id == w.id) w else it } else s.workspaces + w)
    }

    // Contract 1.8.0 (UI-STORY-1): a `stories` frame is one project's board slice; each story names
    // its own projectId. Replace exactly the projects the frame names and keep every other project's
    // stories, so listing project B never drops project A's. An empty frame names no project and
    // clears the stories (mirrors the `epics` / `workspaces` rule). A single `story` frame upserts
    // that story by id inside its project, so a `story_move` answer updates its status in place.
    Envelope.STORIES -> {
        val incoming = json.decodeFromString(ListSerializer(Story.serializer()), env.data)
            .map { StoryView(it.id, it.projectId, it.title, it.status) }
        if (incoming.isEmpty()) s.copy(stories = emptyMap())
        else {
            val named = incoming.map { it.projectId }.toSet()
            s.copy(stories = s.stories.filterKeys { it !in named } + named.associateWith { pid -> incoming.filter { it.projectId == pid } })
        }
    }

    Envelope.STORY -> {
        val st = json.decodeFromString(Story.serializer(), env.data)
        val view = StoryView(st.id, st.projectId, st.title, st.status)
        val existing = s.stories[st.projectId].orEmpty()
        val upserted = if (existing.any { it.id == st.id }) existing.map { if (it.id == st.id) view else it } else existing + view
        s.copy(stories = s.stories + (st.projectId to upserted))
    }

    // Contract 1.44.0 (P10-C): `prompts` is the whole library (a list answer or the post-delete push).
    Envelope.PROMPTS -> {
        val rows = json.decodeFromString(ListSerializer(PromptView.serializer()), env.data)
        s.copy(prompts = rows.sortedBy { it.name.lowercase() }, promptsLoading = false, promptError = null)
    }

    Envelope.LAYER_SKILLS -> {
        val l = json.decodeFromString(LayerSkillList.serializer(), env.data)
        s.copy(layerSkills = s.layerSkills + (l.key to l.skills.sortedBy { it.name.lowercase() }))
    }

    // Contract 1.45.0 (P10-D): `skills` is the whole library (a list answer or the post-delete push).
    Envelope.SKILLS -> {
        val rows = json.decodeFromString(ListSerializer(SkillView.serializer()), env.data)
        s.copy(skills = rows.sortedBy { it.name.lowercase() }, skillsLoading = false, skillError = null)
    }

    // Contract 1.45.0 (P10-D): `skill_item` is one stored skill; upsert by id (a rename keeps the id).
    Envelope.SKILL_ITEM -> {
        val k = json.decodeFromString(SkillView.serializer(), env.data)
        val merged = s.skills.filterNot { it.id == k.id } + k
        s.copy(skills = merged.sortedBy { it.name.lowercase() }, skillError = null)
    }

    // Contract 1.44.0 (P10-C): `prompt_item` is one stored prompt; upsert by id (a rename keeps the id).
    Envelope.PROMPT_ITEM -> {
        val p = json.decodeFromString(PromptView.serializer(), env.data)
        val merged = s.prompts.filterNot { it.id == p.id } + p
        s.copy(prompts = merged.sortedBy { it.name.lowercase() }, promptError = null)
    }

    // Contract 1.21.0 (MEM-8) / UI-MEM-1: a `memory_notes` frame is ONE project's index, but names no
    // project, so it is attributed to the oldest awaited `memory_list` (`withMemoryList`). An empty
    // answer still lands (clearing that project's index) rather than being dropped as "nothing to do".
    Envelope.MEMORY_NOTES -> {
        val id = s.memoryListAwaiting.firstOrNull()
        if (id == null) s
        else {
            val rows = json.decodeFromString(ListSerializer(MemoryIndexEntry.serializer()), env.data)
                .map { MemoryNoteView(name = it.name, description = it.description) }
            s.copy(memoryNotes = s.memoryNotes + (id to rows), memoryListAwaiting = s.memoryListAwaiting.drop(1))
        }
    }

    // Contract 1.21.0 (MEM-8) / UI-MEM-1: a `memory_note` frame is the whole stored note (a `memory_save`
    // answer). It carries its own project, and upserts by name -- a note's identity inside its project.
    Envelope.MEMORY_NOTE -> {
        val n = json.decodeFromString(MemoryNote.serializer(), env.data)
        val view = MemoryNoteView(n.id, n.projectId, n.name, n.description, n.folder, n.body, n.updatedAt, n.scope, n.ownerId)
        val key = noteKey(n.projectId, n.scope, n.ownerId)
        val existing = s.memoryNotes[key].orEmpty()
        val upserted = if (existing.any { it.name == n.name }) existing.map { if (it.name == n.name) view else it } else existing + view
        s.copy(memoryNotes = s.memoryNotes + (key to upserted))
    }

    // Contract 1.20.0 (MEM-5) / UI-MEM-1: a `memory_hits` frame names neither project nor query, so it
    // answers the oldest awaited `memory_search` (`withMemorySearch`) and is stored as the latest search.
    Envelope.MEMORY_HITS -> {
        val asked = s.memorySearchAwaiting.firstOrNull()
        if (asked == null) s
        else {
            val hits = json.decodeFromString(ListSerializer(MemoryHit.serializer()), env.data)
                .map { MemoryHitView(it.name, it.description, it.snippet) }
            s.copy(
                memorySearch = MemorySearchView(asked.projectId, asked.query, hits),
                memorySearchAwaiting = s.memorySearchAwaiting.drop(1),
            )
        }
    }

    Envelope.PROVIDER_STATES ->
        s.copy(providerStates = json.decodeFromString(ListSerializer(ProviderState.serializer()), env.data).associateBy { it.provider })

    Envelope.PROVIDER_STATE -> {
        val p = json.decodeFromString(ProviderState.serializer(), env.data)
        s.copy(providerStates = s.providerStates + (p.provider to p))
    }

    // Contract 1.59.0 (U1): the plan-limit meters, replaced whole by each answer or push.
    Envelope.LIMITS -> s.copy(planLimits = json.decodeFromString(ListSerializer(ProviderLimits.serializer()), env.data))

    // USAGE-PACE (1.119.0): the plan meters and allowances, replaced whole by each answer.
    Envelope.USAGE_REPORT -> s.copy(usageReport = json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.UsageReport.serializer(), env.data))

    // CONFIG-8 (1.142.0): the daemon's declared config, replaced whole by each schema / values frame.
    Envelope.CONFIG_SCHEMA -> runCatching { json.decodeFromString(com.superiorsoftllc.infantry.plugin.api.ConfigSchemaFrame.serializer(), env.data) }
        .getOrNull()?.let { s.copy(daemonConfigSchema = it) } ?: s
    Envelope.CONFIG_VALUES -> runCatching { json.decodeFromString(com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame.serializer(), env.data) }
        .getOrNull()?.let { s.copy(daemonConfigValues = it, daemonConfigError = null) } ?: s
    Envelope.CONFIG_CHANGED -> runCatching { json.decodeFromString(com.superiorsoftllc.infantry.plugin.api.ConfigChangedFrame.serializer(), env.data) }
        .getOrNull()?.let { c ->
            val old = s.daemonConfigValues ?: com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame(c.rev)
            val rows = old.rows.filterNot { it.nodeId == c.node && it.key == c.key } +
                (c.value?.let { listOf(com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry(c.node, c.key, it, c.by)) } ?: emptyList())
            s.copy(daemonConfigValues = old.copy(rev = c.rev, rows = rows), daemonConfigError = null)
        } ?: s

    Envelope.PACE_REPORT -> s.copy(paceReport = env.data)

    Envelope.USAGE_METER -> runCatching { json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.UsageMeterReport.serializer(), env.data) }
        .getOrNull()?.let { s.copy(usageMeter = it) } ?: s

    // STATS-2 (1.92.0): one session's statistics, replacing that session's previous answer whole.
    Envelope.SESSION_STATS -> runCatching { json.decodeFromString(SessionStats.serializer(), env.data) }
        .getOrNull()?.let { s.copy(sessionStats = s.sessionStats + (it.sessionId to it)) } ?: s

    // BUG-M2-11e: a `settings` answer (to this window's get/set, or another client's set) carries the lifecycle setting.
    // A frame without the key (a get of another key) leaves the known value alone.
    // ROGUE-1 (1.120.0): the report replaces the last one; a reap names what it killed or why it refused.
    Envelope.PROCESSES -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.ProcessesAnswer.serializer(), env.data)
    }.getOrNull()?.let { a ->
        val next = s.copy(processReport = a.report, processAutoReap = a.report.autoReap)
        when {
            a.refused != null -> next.withNotice(now, "reap refused: ${a.refused.take(160)}", null, NoticeKind.ERROR)
            a.reaped.isNotEmpty() -> next.withNotice(now, "reaped ${a.reaped.size} process${if (a.reaped.size == 1) "" else "es"}: " +
                a.reaped.joinToString { "pid ${it.pid}" }.take(160), null, NoticeKind.OK)
            else -> next
        }
    } ?: s
    Envelope.PROCESS_PRESSURE -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.ProcessReport.serializer(), env.data)
    }.getOrNull()?.let { r ->
        s.copy(processReport = r, processAutoReap = r.autoReap).withNotice(
            now, "low memory: ${r.freeMb?.let(ProcessWatchView::mb) ?: "?"} free (floor ${ProcessWatchView.mb(r.floorMb)}), " +
                "${r.rogue.size} rogue process${if (r.rogue.size == 1) "" else "es"}: see the badge in the status bar",
            null, NoticeKind.HEADS_UP,
        )
    } ?: s

    Envelope.SETTINGS -> {
        val withAutoReap = ProcessWatchView.autoReapFromSettingsFrame(env.data)?.let { s.copy(processAutoReap = it) } ?: s
        val withKeepAlive = LifecycleSetting.fromSettingsFrame(env.data)?.let { withAutoReap.copy(keepAliveMinutes = it) } ?: withAutoReap
        val withPortrait = UserPortrait.fromSettingsFrame(env.data)?.let { withKeepAlive.copy(userPortrait = it.base64) } ?: withKeepAlive
        val withTimer = PromptTimerSetting.fromSettingsFrame(env.data, withPortrait.promptTimer)?.let { withPortrait.copy(promptTimer = it) } ?: withPortrait
        withTimer.copy(
            limitCatalog = LimitsSetting.withSettingsFrame(withTimer.limitCatalog, env.data),
            allowedRoles = AllowedRoles.fromSettingsFrame(env.data) ?: withTimer.allowedRoles,
            maxSessions = AllowedRoles.maxSessionsFromSettingsFrame(env.data) ?: withTimer.maxSessions,
            sendAckSeconds = PendingSends.ackSecondsFromSettingsFrame(env.data) ?: withTimer.sendAckSeconds,
            sourceRepo = SourceLinks.repoFromSettingsFrame(env.data) ?: withTimer.sourceRepo,
            siteUrl = SourceLinks.siteFromSettingsFrame(env.data) ?: withTimer.siteUrl,
        )
    }

    // No-hard-coded-limits (1.133.0): the whole catalog, replaced by each answer.
    Envelope.LIMIT_CATALOG -> LimitsSetting.fromCatalogFrame(env.data)?.let { s.copy(limitCatalog = it) } ?: s

    // CLIPATH-1 (1.73.0): the whole list, replaced by each answer (a get, or this window's set).
    Envelope.CLI_LOCATIONS -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.CliLocations.serializer(), env.data).tools
    }.getOrNull()?.let { s.copy(cliLocations = it) } ?: s

    // HARNESS-INSTALL-1 (1.100.0): a check replaces the report; an install result carries the report after the run plus its log.
    Envelope.HARNESS_INSTALL_STATUS -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.HarnessInstallReport.serializer(), env.data)
    }.getOrNull()?.let { s.copy(harnessInstall = it, harnessInstallBusy = false) } ?: s
    Envelope.HARNESS_INSTALL_RESULT -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.HarnessInstallResult.serializer(), env.data)
    }.getOrNull()?.let { r ->
        val lines = r.log + r.outcomes.map { o -> "${o.id}: ${o.result}" + (o.version?.let { " $it" } ?: "") + (o.message?.let { " - $it" } ?: "") }
        s.copy(harnessInstall = r.report, harnessInstallBusy = false, harnessInstallLog = lines)
    } ?: s

    // BUG-PERM-1 (1.76.0): replaced by each answer (a get, or this window's set).
    Envelope.SECRET_STATE -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.SecretState.serializer(), env.data)
    }.getOrNull()?.let { s.copy(secrets = it) } ?: s
    Envelope.TOOL_APPROVAL -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.ToolApprovalInfo.serializer(), env.data)
    }.getOrNull()?.let { s.copy(toolApproval = it) } ?: s

    // PROMPT-2 (1.77.0): open a card (a resend of an open prompt replaces it in place), or close one.
    Envelope.PROMPT_REQUEST -> {
        val p = json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.PromptRequestView.serializer(), env.data)
        val known = s.openPrompts.any { it.promptId == p.promptId }
        val next = s.copy(openPrompts = if (known) s.openPrompts.map { if (it.promptId == p.promptId) p else it } else s.openPrompts + p)
        // BUG f422b3a1: the card docks only in that session's composer, so a prompt of a session the owner is not looking
        // at was a tiny sidebar badge (none at all for another project's session). A new one is also a global toast that
        // opens the session on click, and stays until the prompt resolves (removed below) or its own long timer ends.
        if (known || p.sessionId == s.selectedId) next else next.withToast(promptToast(s, p, now))
    }
    Envelope.PROMPT_RESOLVED -> {
        val r = json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.PromptResolvedView.serializer(), env.data)
        s.copy(
            openPrompts = s.openPrompts.filter { it.promptId != r.promptId }, dismissedPrompts = s.dismissedPrompts - r.promptId,
            toasts = s.toasts.filterNot { it.id == promptToastId(r.promptId) },
        )
    }

    // ASK-1 (1.97.0): the whole list (on connect, or a `question_list` answer), or one question added / replaced in place.
    Envelope.QUESTIONS -> runCatching {
        json.decodeFromString(ListSerializer(com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView.serializer()), env.data)
    }.getOrNull()?.let { list -> s.copy(questions = list, dismissedQuestions = s.dismissedQuestions intersect list.mapTo(HashSet()) { it.id }) } ?: s
    Envelope.QUESTION -> runCatching {
        json.decodeFromString(com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView.serializer(), env.data)
    }.getOrNull()?.let { q ->
        s.copy(questions = if (s.questions.any { it.id == q.id }) s.questions.map { if (it.id == q.id) q else it } else s.questions + q)
    } ?: s

    // Contract 1.33.0 (in-app build): saved commands and run frames, applied by the pure run model.
    // 1.134.0: `runs` (the kept runs) fills the Run tab's pills; `run_bytes` goes to a console, not into the state.
    Envelope.COMMANDS, Envelope.RUN, Envelope.RUN_OUTPUT, Envelope.RUN_FINISHED, Envelope.RUNS ->
        s.copy(runs = reduceRuns(s.runs, env, json))

    // mail bodies, worktree_remove answers, repo echo: not rendered here. A SEQUENCED session frame of a type
    // this build does not know is still marked applied, so a newer daemon's frame never leaves a replay hole.
    else -> if (env.sessionId != null && env.sequence != null) s.stream(env) { a, _ -> a } else s
}

/**
 * POLICY-1 (docs/POLICY.md): the daemon's active policy. Its `ready` contributions (restricted, notices, the preset's own
 * keys, the notice) are folded into [ConnectionInfo.readyFields] in place of the previous policy's, so the modules that
 * read ready fields follow a runtime switch exactly as they follow a fresh `ready`.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun policy(s: AppState, env: Envelope): AppState {
    val wire = json.decodeFromString(com.superiorsoftllc.infantry.plugin.api.PolicyWire.serializer(), env.data)
    fun contributed(w: com.superiorsoftllc.infantry.plugin.api.PolicyWire): Map<String, String> = w.readyFields() +
        listOfNotNull(w.notice?.takeIf { it.isNotBlank() }?.let {
            POLICY_NOTICE_FIELD to kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).encode(it.encodeToByteArray())
        })
    val c = s.connection
    val stale = contributed(c.policy).keys + POLICY_NOTICE_FIELD
    val fields = c.readyFields.filterKeys { it !in stale || it in POLICY_KEPT_FIELDS } + contributed(wire)
    return s.copy(connection = c.copy(policy = wire, readyFields = fields))
}

/** The `ready` key the daemon puts the policy's notice in (base64url UTF-8). */
private const val POLICY_NOTICE_FIELD = "notice"
/** `ready` keys a policy never owns, whatever it declares. */
private val POLICY_KEPT_FIELDS = setOf("contract", "provider", "instance", "since", "build")

/**
 * `ready`: the link is live. When it comes from a different daemon PROCESS than the cursors were built
 * against (or from a daemon too old to say), the sequence numbering restarted, so every cursor is reset
 * and the transcript already on screen is kept as unsequenced history that new frames follow.
 */
private fun ready(s: AppState, env: Envelope): AppState {
    val fields = env.data.split(' ').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }
    val instance = fields["instance"]
    val restarted = instance == null || instance != s.connection.instance
    val connected = s.copy(
        // A refusal for a send made on an earlier link can no longer arrive.
        queueSending = emptyMap(),
        connection = s.connection.copy(
            state = ConnState.CONNECTED,
            detail = "",
            attempt = 0,
            contract = fields["contract"],
            daemonProvider = fields["provider"],
            instance = instance,
            historySince = fields["since"]?.toLongOrNull(),
            daemonBuild = fields["build"]?.takeIf { it.isNotBlank() },
            readyFields = fields,
            notices = emptyMap(),
        ),
        // PROMPT-2: the daemon resends every prompt still open right after `ready`; one resolved while the link was
        // down must not keep its card.
        openPrompts = emptyList(),
        dismissedPrompts = emptySet(),
    )
    if (!restarted) return connected
    return connected.copy(
        agents = connected.agents.mapValues { (_, a) ->
            a.copy(cursor = StreamCursor(), entries = a.entries.map { if (it.seq == null) it else it.copy(seq = null) })
        },
        historyRequested = emptySet(),
    )
}

/**
 * Apply one session-stream frame to its agent exactly once. A sequenced frame already applied is
 * dropped; [f] learns whether the frame is the newest so far (`inOrder`), because a late replayed frame
 * belongs in the transcript but must not overwrite "current" fields (status detail, last error, outcome).
 */
private fun AppState.stream(env: Envelope, f: (AgentView, Boolean) -> AgentView): AppState {
    val a = env.sessionId?.let { agents[it] } ?: return this
    val seq = env.sequence
    if (seq != null && a.cursor.seen(seq)) return this
    val inOrder = seq == null || seq > a.cursor.highest
    val marked = if (seq == null) a else a.copy(cursor = a.cursor.mark(seq))
    return copy(agents = agents + (a.id to f(marked, inOrder)))
}

/** Insert [e] by sequence (the common live case is an append), keeping the newest [MAX_LINES] entries. */
/** A tool/status frame ends the agent's current message: mark the newest entry so the next text is a new message. */
private fun AgentView.closeLastText(): AgentView {
    val last = entries.lastOrNull() ?: return this
    if (last.kind != LineKind.TEXT || last.closed) return this
    return copy(entries = entries.dropLast(1) + last.copy(closed = true))
}

private fun AgentView.withEntry(e: TranscriptEntry): AgentView {
    val seq = e.seq
    val lastSeq = entries.lastOrNull { it.seq != null }?.seq
    val list = if (seq == null || lastSeq == null || seq > lastSeq) {
        entries + e
    } else {
        val i = entries.indexOfFirst { it.seq != null && it.seq > seq }
        if (i < 0) entries + e else entries.subList(0, i) + e + entries.subList(i, entries.size)
    }
    return copy(entries = list.takeLast(MAX_LINES))
}

private fun roleOf(o: JsonObject): String? = o["role"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * Insert or refresh a session record. A record OLDER than the one held (by `lastHeartbeat`, which the
 * daemon advances on every change) is ignored: a replayed `session` frame is history, not the present.
 */
private fun AppState.upsert(record: SessionRecord, role: String?): AppState {
    val old = agents[record.id]
    if (old != null && record.lastHeartbeat < old.session.lastHeartbeat) return this
    val view = (old ?: AgentView(record, role)).copy(session = record, role = role ?: old?.role)
    return copy(agents = agents + (record.id to view))
}

/**
 * CMD-HARNESS-1: a switch THIS window asked for landed: say so in one line and, when it cut a new session, follow
 * the agent onto it (now if its `session` frame is already in, else when it arrives), so the next turn and `/model`
 * address the new harness. A swap another window made changes nothing here beyond the profile.
 */
private fun AppState.withHarnessSwitched(agentId: String, w: AgentSwapped, now: Long): AppState {
    val harness = pendingHarnessSwitch[agentId] ?: return this
    val provider = w.agent.provider ?: harness
    val defaultModel = providers.firstOrNull { it.id == provider }?.defaultModel
    val name = w.agent.name.takeIf { it.isNotBlank() } ?: role(w.agent.role)?.name
    val next = copy(pendingHarnessSwitch = pendingHarnessSwitch - agentId)
        .withNotice(now, HarnessSwitch.switchedNotice(provider, w.agent.model, defaultModel, name), selectedId)
    val newSession = w.session.sessionId?.takeIf { w.session.outcome == "new" } ?: return next
    return if (newSession in agents) next.copy(selectedId = newSession, followSession = null) else next.copy(followSession = newSession)
}

/** UX-HARNESS-2: a session switch THIS window asked for landed: one line naming the provider and the model. The session stays selected. */
private fun AppState.withSessionSwitched(w: SessionSwapped, now: Long): AppState {
    // SWAP-AGENT-1: a role swap (from any window) is the session's new role, also when it was cleared to none.
    val reroled = if (w.role != w.fromRole) withAgent(w.sessionId) { it.copy(role = w.role) } else this
    if (w.sessionId !in pendingHarnessSwitch) return reroled
    val defaultModel = w.defaultModel ?: providers.firstOrNull { it.id == w.provider }?.defaultModel
    val name = w.role?.let { r -> role(r)?.name ?: r }
    return reroled.copy(pendingHarnessSwitch = pendingHarnessSwitch - w.sessionId)
        .withNotice(now, HarnessSwitch.switchedNotice(w.provider, w.model, defaultModel, name), w.sessionId)
}

private fun AppState.selectFollowed(id: String): AppState =
    if (followSession == id) copy(selectedId = id, followSession = null) else this

/** The first session that did not exist when this window spawned is the one it spawned: show it. */
private fun AppState.selectSpawned(id: String): AppState {
    val known = pendingSpawn ?: return this
    if (id in known) return this
    return copy(selectedId = id, pendingSpawn = null)
}

/**
 * Keep the user's selection while it exists. Otherwise select the newest LIVE session, never a stale EXITED one:
 * landing on a session with nothing to show is an empty console. With no live session nothing is selected and the
 * centre pane offers "Start a session".
 */
private fun AppState.autoSelect(): AppState {
    if (selectedId != null && selectedId in agents) return this
    return copy(selectedId = orderedAgents.firstOrNull { it.session.state != SessionState.EXITED }?.id)
}

private fun AppState.withAgent(sessionId: String?, f: (AgentView) -> AgentView): AppState {
    val a = sessionId?.let { agents[it] } ?: return this
    return copy(agents = agents + (a.id to f(a)))
}

fun AppState.withNotice(now: Long, text: String, sessionId: String?, kind: NoticeKind = NoticeKind.OK, projectId: String? = null): AppState =
    copy(notices = (notices + Notice(now, text, sessionId, kind, projectId)).takeLast(MAX_NOTICES))

/**
 * Scope the shell to [projectId]; an id the daemon never listed leaves the active project
 * unchanged.
 */
fun AppState.selectProject(projectId: String): AppState =
    if (projects.any { it.id == projectId }) {
        copy(
            activeProjectId = projectId,
            projectRefusal = null,
            // The chat pane follows the project: an agent of another project is no longer the open one.
            selectedId = selectedId?.takeIf { id -> agents[id]?.session?.projectId.let { it == null || it == projectId } },
            // BRANCH-VIEW-2: a different project starts on its working tree.
            changesBranchPick = changesBranchPick?.takeIf { it.projectId == projectId },
            changesViewPick = changesViewPick?.takeIf { it.projectId == projectId },
        )
    } else this

/** The sidebar's refusal was dismissed: hide it until the next project refusal. */
fun AppState.dismissProjectRefusal(): AppState = copy(projectRefusal = null)

/** Local echo of what the human sent, for a daemon that does not echo turns itself (before 1.11.0). */
fun AppState.withUserLine(sessionId: String, text: String, now: Long): AppState =
    withAgent(sessionId) { it.withEntry(TranscriptEntry(null, LineKind.USER, text, now)) }

/** This window asked for [triggerId]'s event rows: remember it, so an empty answer can be matched. */
fun AppState.withEventsAwaiting(triggerId: String): AppState = copy(eventsAwaiting = (eventsAwaiting + triggerId).takeLast(20))

/** P10-C: this window asked for the prompt list (`prompt_list`): show loading, drop a stale error. */
fun AppState.withPromptsRequested(): AppState = copy(promptsLoading = true, promptError = null)

/** P10-D: this window asked for the skill list (`skill_list`): show loading, drop a stale error. */
fun AppState.withSkillsRequested(): AppState = copy(skillsLoading = true, skillError = null)

/** P10-D: filter the library by name, description or tag with the shared ranked search. */
fun filterSkills(skills: List<SkillView>, query: String): List<SkillView> =
    filterRanked(skills, query) { SearchText(id = it.name, label = it.name, group = it.tags.joinToString(" ") + " " + it.description) }

/** P10-C: split the tag field ("a, b,,c") into trimmed non-blank tags; the daemon normalises case and duplicates. */
fun parsePromptTags(csv: String): List<String> = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** P10-C: filter the library by name or tag with the shared ranked search (the id is the name, tags are the group). */
fun filterPrompts(prompts: List<PromptView>, query: String): List<PromptView> =
    filterRanked(prompts, query) { SearchText(id = it.name, label = it.name, group = it.tags.joinToString(" ")) }

/** P10-C: Insert puts [body] in the agent prompt box: it replaces an empty box, otherwise goes on a new line after the text. */
fun insertIntoPrompt(current: String, body: String): String =
    if (current.isBlank()) body else current.trimEnd() + "\n" + body

/**
 * This window asked for [projectId]'s note index (`memory_list`): remember it, because the answering
 * `memory_notes` frame names no project, so it is matched to the oldest awaited request.
 */
fun AppState.withMemoryList(projectId: String, at: NoteScope = NoteScope.Project): AppState =
    copy(memoryListAwaiting = (memoryListAwaiting + at.key(projectId)).takeLast(20))

/**
 * UI-BOARD-PROJ: this window asked for [projectId]'s tickets (`ticket_list`): remember it, because a
 * `tickets` frame from a scoped request names its project only through its rows -- an emptied project
 * names none -- so it is matched to the oldest awaited scoped request.
 */
fun AppState.withTicketList(projectId: String): AppState = copy(ticketListAwaiting = (ticketListAwaiting + projectId).takeLast(20))

/**
 * E1-d (one work model): the tickets filed under [epicId], one entry per [TicketColumns.ALL] column in board order,
 * each column in board position order. An epic's work is its tickets; story frames are no longer read.
 */
fun AppState.epicTicketColumns(epicId: String): Map<String, List<Ticket>> {
    val filed = tickets.filter { it.epicId == epicId }
    return TicketColumns.ALL.associateWith { column -> filed.filter { it.status == column }.sortedBy { it.position } }
}


/**
 * This window searched [projectId]'s memory for [query] (`memory_search`): remember it, because the
 * answering `memory_hits` frame names neither project nor query, so both come from the oldest awaited request.
 */
fun AppState.withMemorySearch(projectId: String, query: String, at: NoteScope = NoteScope.Project): AppState =
    copy(memorySearchAwaiting = (memorySearchAwaiting + MemorySearchQuery(at.key(projectId), query)).takeLast(20))

/** This window is about to spawn: remember which sessions already exist, so the new one can be selected. */
fun AppState.withPendingSpawn(): AppState = copy(pendingSpawn = agents.keys.toSet())

/** `#rrggbb` -> opaque ARGB, or null when it is not that shape (the catalog's colours always are). */
fun parseHexColor(hex: String?): Int? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    if (h.length != 6 || h.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    return (0xFF000000L or h.toLong(16)).toInt()
}

/** Role -> a stable hue, so the same role is the same colour everywhere with no registry or assets. */
fun roleHue(role: String): Float = ((role.hashCode().toLong() and 0x7fffffff) % 360).toFloat()

/** Filter + search used by the sidebar; pure so keyboard navigation and the list agree on the order. */
fun visibleAgents(
    state: AppState,
    query: String,
    roleFilter: String?,
    needsInputOnly: Boolean = false,
    agentScope: AgentScope = AgentScope.PROJECT,
): List<AgentView> {
    val q = query.trim().lowercase()
    return state.orderedAgents.filter { a ->
        agentScope.admits(a.session.projectId, state.activeProjectId) &&
        (roleFilter == null || a.role == roleFilter) &&
            (!needsInputOnly || needsInput(a, state.waiting, state.openPrompts, state.openQuestionSessions)) &&
            (q.isEmpty() || listOfNotNull(a.id, a.session.provider, a.role, a.session.worktreeBranch, a.lastActivity)
                .any { it.lowercase().contains(q) })
    }
}

/** Longest title a ticket tag shows before it is cut with an ellipsis. */
const val TICKET_TAG_TITLE_MAX = 28

/** The tag text on an agent card: `<first 8 chars of the id> <title, cut to [TICKET_TAG_TITLE_MAX]>`; the id alone when the title is unknown. */
fun ticketTagLabel(ticketId: String, title: String?): String {
    val short = ticketId.removePrefix("ticket-").take(8)
    val t = title?.trim().orEmpty()
    if (t.isEmpty()) return short
    return if (t.length > TICKET_TAG_TITLE_MAX) "$short ${t.take(TICKET_TAG_TITLE_MAX - 1)}…" else "$short $t"
}

/** The untruncated tag text, shown on hover and read by screen readers. */
fun ticketTagFull(ticketId: String, title: String?): String =
    title?.trim()?.takeIf { it.isNotEmpty() }?.let { "$ticketId $it" } ?: ticketId

/**
 * DEMO-MERGE-1: [data] is `<code> <message>` with a code the daemon declared in `ready` notices= (comma separated): the
 * code and the message, else null.
 */
internal fun declaredNotice(c: ConnectionInfo, data: String): Pair<String, String>? {
    val declared = c.readyFields["notices"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val code = data.substringBefore(' ')
    val message = data.substringAfter(' ', "").trim()
    return if (code in declared && message.isNotEmpty()) code to message else null
}

/**
 * SCRIPT-VIS-1b: one line a plugin put into a session's chat without a turn (the generic contribution seam). [source] is the plugin's
 * kind of contribution (`automation`), [label] its heading ("AUTOMATION · script"), [text] the one-line summary, [details] the expandable
 * lines; [ok] false marks a failure. Never shown as the agent's own output.
 */
@Serializable
data class TimelineItem(
    val id: String, val sessionId: String, val at: Long, val source: String = "", val label: String = "", val text: String = "",
    val details: List<String> = emptyList(), val ok: Boolean = true,
    /** SCRIPT-VIS-1c: structured values of a non-row item (the stats item: turnsSaved, wakes, wakesByReason, lastWake, project*). */
    val meta: Map<String, String> = emptyMap(),
    /** KINDS-1: which renderer draws this item in a session's feed (a string; blank = the automation row). Any plugin may push its own kind with a [meta] payload. */
    val kind: String = "",
) {
    /** True for an item that is data for a badge / panel, not a chat row. */
    val isStats: Boolean get() = source == AUTOMATION_STATS_SOURCE

    /**
     * CHAT-NOTE-WARN-1: the note's level from `meta.level` (info, ok, warn, fail). [ok] false is always fail; a level this client
     * does not know (a newer daemon) or none at all reads as info, never as an error.
     */
    val noteLevel: String get() = if (!ok) NOTE_LEVEL_FAIL else meta["level"]?.lowercase()?.takeIf { it in NOTE_LEVELS && it != NOTE_LEVEL_FAIL } ?: NOTE_LEVEL_INFO
}

const val NOTE_LEVEL_INFO = "info"
const val NOTE_LEVEL_OK = "ok"
const val NOTE_LEVEL_WARN = "warn"
const val NOTE_LEVEL_FAIL = "fail"
val NOTE_LEVELS = setOf(NOTE_LEVEL_INFO, NOTE_LEVEL_OK, NOTE_LEVEL_WARN, NOTE_LEVEL_FAIL)

const val AUTOMATION_STATS_SOURCE = "automation-stats"

const val TIMELINE_MAX_PER_SESSION = 200

/** SCRIPT-VIS-1c: the session's automation stats item (turns saved, wakes by reason), or null. */
fun AppState.automationStats(sessionId: String): TimelineItem? = timeline[sessionId].orEmpty().lastOrNull { it.isStats }

/** SCRIPT-VIS-1c: "owner 1, mail 2" from the stats item's wakesByReason, or "". */
fun TimelineItem.wakeReasons(): String = meta["wakesByReason"].orEmpty().split(',').filter { ':' in it }.joinToString(", ") { it.substringBefore(':') + " " + it.substringAfter(':') }

/** SCRIPT-VIS-1c: the line on a session's activity row: the last wake reason ("woken by owner"), or null. */
fun AppState.wakeLine(sessionId: String): String? = automationStats(sessionId)?.meta?.get("lastWake")?.takeIf { it.isNotBlank() }?.let { "woken by $it" }

/** Merges [items] into the per-session timeline: same id replaces, order by time, at most [TIMELINE_MAX_PER_SESSION] newest kept. */
fun AppState.withTimeline(items: List<TimelineItem>): AppState {
    if (items.isEmpty()) return this
    var next = timeline
    for ((sid, group) in items.groupBy { it.sessionId }) {
        val have = next[sid].orEmpty().filter { o -> group.none { it.id == o.id } }
        next = next + (sid to (have + group).sortedBy { it.at }.takeLast(TIMELINE_MAX_PER_SESSION))
    }
    return copy(timeline = next)
}

/** QUEUE-STUCK-1: the queued messages, oldest first, then the newly typed [text], as the one turn a send delivers. */
fun queuedTurnText(held: List<QueuedPrompt>, text: String): String = (held.map { it.text } + text).joinToString("\n\n")

/**
 * QUEUE-STUCK-2: the queue left after the agent took a turn reading [turn]. A queued message is delivered when the turn
 * is its text, or a "\n\n"-joined turn (see [queuedTurnText]) or an interrupt that carries it as one of its parts.
 * CHAT-DUP-1: a message of several paragraphs (its text and an `[image: ...]` line are two) is carried when its paragraphs
 * stand in the turn as one unbroken run; compared with a single paragraph it never matched, so its "sent" bubble stayed.
 */
fun turnCarries(turn: String, text: String): Boolean {
    val want = paragraphs(text)
    if (want.isEmpty()) return text.trim() in turn.trim().split("\n\n").map { it.trim() }
    return paragraphs(turn).windowed(want.size).any { it == want }
}

/** CHAT-DUP-1: [s]'s paragraphs (split on blank lines, CRLF or LF), each trimmed, empty ones dropped. */
private fun paragraphs(s: String): List<String> =
    s.replace("\r\n", "\n").split(BLANK_LINE).map { it.trim() }.filter { it.isNotEmpty() }

private val BLANK_LINE = Regex("\n[ \t]*\n")

/**
 * QUEUE-STUCK-3: a send in flight ([AppState.queueSending]) can never outlive the turn it started. When an agent goes
 * from a run in flight to rest, its pending entries are dropped: otherwise an echo that did not match (an interrupt
 * marker, an origin wrapper) leaves one behind and [queueDrainTargets] holds that agent's queue back forever.
 */
fun settleQueueSends(before: AppState, after: AppState): AppState {
    if (after.queueSending.isEmpty()) return after
    val ended = after.queueSending.values.map { it.sessionId }.toSet().filter { id ->
        val b = before.agents[id]; val a = after.agents[id]
        b != null && isBusy(b) && (a == null || !isBusy(a))
    }
    return if (ended.isEmpty()) after else after.copy(queueSending = after.queueSending.filterValues { it.sessionId !in ended })
}

fun queueAfterTurn(held: List<QueuedPrompt>, turn: String): List<QueuedPrompt> {
    if (held.isEmpty()) return held
    return held.filter { q -> !turnCarries(turn, q.text) }
}


/** AGENT-TASKS-7: what an agent card shows of its work: the in-progress ticket it runs (null = none) and its queue, head first. */
data class AgentWork(val current: Ticket?, val queue: List<AgentQueueItem>)

/**
 * AGENT-TASKS-7: the work of agent profile [agentId]; its running ticket is the in-progress one assigned to it.
 * CARD-TICKET-1: [sessionTicketId] (the card's session's own ticket, the one its header shows) wins: a board or role start
 * keeps the ticket under its role (`role:<id>`), never under the profile, so the profile match alone missed it. A session
 * ticket that is on the board but no longer in progress falls back to the profile's; one not on the board yet shows its id.
 */
fun AppState.agentWork(agentId: String?, sessionTicketId: String? = null): AgentWork {
    val own = sessionTicketId?.takeIf { it.isNotBlank() }?.let { id ->
        val t = ticket(id)
        when {
            t == null -> Ticket(id = id, title = id.removePrefix("ticket-").take(8), status = TicketColumns.IN_PROGRESS)
            t.status == TicketColumns.IN_PROGRESS -> t
            else -> null
        }
    }
    if (agentId == null) return AgentWork(own, emptyList())
    val current = own ?: tickets.firstOrNull { it.agentId == agentId && it.status == TicketColumns.IN_PROGRESS }
    return AgentWork(current, agentQueues[agentId].orEmpty())
}
