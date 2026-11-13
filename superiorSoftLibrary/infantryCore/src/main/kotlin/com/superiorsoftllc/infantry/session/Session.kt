// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.session.host.KeptProcess
import com.superiorsoftllc.infantry.worktree.WorktreeRef
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * How a kill resolved, recorded so "it was stopped" is never conflated with "it died on us" and so a
 * graceful stop is distinguishable from a forced one.
 *
 * Note this is a separate axis from [SessionState]: a deliberate kill always leaves the session
 * [SessionState.EXITED] (we ended it, cleanly or not); only an *unrequested* end is [SessionState.FAILED].
 */
@Serializable
enum class KillOutcome {
    /** The process ended inside the grace window after the graceful signal (stdin EOF). */
    GRACEFUL,

    /** The process ignored the grace window and was killed by force (process tree). */
    FORCED,
}

/**
 * One managed session, and the whole of the durable state. Every field here survives a daemon
 * restart; nothing else about a session does (the process handle cannot be recovered).
 *
 * This single record is both the internal model and the wire model: it is what [SessionManager]
 * mutates, what persists to disk, and what a `session`/`sessions` frame carries to a client. There
 * is deliberately no second DTO for the wire -- one model, both providers, both directions.
 *
 * The one field that makes resume possible is [providerSessionId]: it is the CLI's own session handle
 * (`opencode run --session <id>` / `claude --resume <id>`), it is persisted the moment it is observed
 * on the stream -- not at exit -- and it is what lets a session come back with its context intact
 * after a kill or a daemon crash.
 */
/** SCRIPT-VIS-1b: which script hook currently works for a session (display only). */
/** CHAT-QUEUE-1: one message held for a busy session: its daemon-given [id], the [text] as typed, and when it was queued ([at], epoch millis). */
@Serializable
data class QueuedTurn(val id: String, val text: String, val at: Long)

@Serializable
data class SessionAutomation(val script: String, val hookId: String, val since: Long)

@Serializable
data class SessionRecord(
    val id: String,
    val provider: String,
    val workingDir: String,
    /** Epoch millis when the session was created. */
    val spawnedAt: Long,
    /** Epoch millis of the last observed activity (any output line, or a state transition). */
    val lastHeartbeat: Long,
    val state: SessionState,
    /** The provider's own session handle, once observed. Null until the CLI reveals it. */
    val providerSessionId: String? = null,
    /**
     * OS pid of the running child while a run is in flight, cleared when the run ends. Persisted so a
     * restarted daemon can probe whether the child outlived it (see [SessionState.ORPHANED]).
     */
    val childPid: Long? = null,
    /**
     * PROC-REAP-COPY-1: the OS start instant (epoch millis) of [childPid], read when the run reported it. A watch roots a session
     * at [childPid] only while the live process still has this instant (a recycled pid has another). Null = an older record.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val childStartedAt: Long? = null,
    /**
     * PROC-REAP-COPY-1: the InstanceId of the daemon state dir that last ran a process for this session (stamped with [childPid]
     * and with a kept process). A daemon treats the session's pids and worktree as its own only when this is its own id, so a
     * copied `sessions.json` never makes a daemon track or reap the processes its original's sessions started. Never cleared;
     * null = an older record (never anybody's to reap).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ownerId: String? = null,
    /** How many runs this logical session has performed. */
    val runCount: Int = 0,
    /** How many times this session was explicitly resumed. */
    val resumeCount: Int = 0,
    /** Exit code of the most recent run, when there was one. */
    val lastExitCode: Int? = null,
    /** How a kill resolved, when the session was killed. */
    val killOutcome: KillOutcome? = null,
    /** Ticket 7e9bed2a: WHO ended the session (watchdog, client request, done-worker reaper, daemon shutdown...); set on a kill, cleared when a new run starts. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val killedBy: String? = null,
    /** Ticket 7e9bed2a: why [killedBy] did it, in words. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val killReason: String? = null,
    /** Last daemon-side failure for this session (e.g. the process could not be started). */
    val lastError: String? = null,
    /**
     * Phase 4: the git worktree this session works in, when one was provisioned. [workingDir] equals
     * this path for a worktree-backed session, so resume lands the CLI back in the same checkout.
     */
    val worktreePath: String? = null,
    /** Branch checked out in [worktreePath]; named and owned by the worktree manager. */
    val worktreeBranch: String? = null,
    /** The repository the worktree was cut from. */
    val repoRoot: String? = null,
    /** Immutable commit the worktree was cut from (the archive point). */
    val worktreeBaseRef: String? = null,
    /**
     * Contract 1.3.0: the model this session's spawn named (the `new` frame's `model`), reused by
     * every run and every resume of the session. Null = the spawn named none, so each run uses the
     * provider's configured default at the time it runs, which is exactly the pre-1.3.0 behaviour
     * and what a record persisted before 1.3.0 decodes to. The `providers` frame says what that
     * default currently is.
     */
    val model: String? = null,
    /**
     * Contract 1.39.0 (CP-4 slice d): the reasoning effort `/effort` set for this session's next runs, one of its
     * harness's own levels. Null = never set, so each run uses the CLI's own default (and what a record persisted
     * before 1.39.0 decodes to).
     */
    val effort: String? = null,
    /**
     * CONTEXT-LIMIT-1 (contract 1.146.0): the context limit in tokens this session was started with (a ticket's or the spawn's
     * `contextLimit`), reused by every run. Null = none (the harness's own window, and what an older record decodes to).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val contextLimit: Int? = null,
    /**
     * CONTEXT-LIMIT-1: how the session's harness honours [contextLimit] (`supported` / `best_effort` / `unsupported`), set
     * with it; `unsupported` = recorded, not applied. Kept with the session so usage can compare runs with and without a limit.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val contextLimitSupport: String? = null,
    /**
     * EFFORT-1 (contract 1.148.0): how the session's harness honours [effort] (`supported` / `best_effort` / `unsupported`),
     * set when an effort is; `unsupported` = recorded, not applied. Null = no effort set (and what an older record decodes to).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val effortSupport: String? = null,
    /**
     * Contract 1.4.0: the agent profile this session was spawned from (`new` with an `agentId`), or
     * null for a plain spawn. Attribution only: the profile can later change or disappear, and this
     * session keeps the provider, model and role it was spawned with.
     */
    val agentId: String? = null,
    /** Contract 1.4.0: the role of [agentId]'s profile when the session was spawned; null with no profile. */
    val role: String? = null,
    /**
     * Contract 1.6.0: the ticket this session was started for (a `handOut`), or null for a plain
     * spawn. Attribution only: the ticket can later change who it is assigned to, and this session
     * keeps what it was started with.
     */
    val ticketId: String? = null,
    /**
     * Contract 1.6.0: what the start composed into the prompt, and how many characters each source
     * contributed, so the token goal ("a session knows its piece, not the epic") is measured per run
     * rather than asserted. Sources are `instructions`, `ticket` and `linkedTickets`.
     */
    val contextSources: List<ContextSource> = emptyList(),
    /** MAIL-1: the project this session belongs to, for role-based mail routing. Null = no project. */
    val projectId: String? = null,
    /** True when started by an unattended trigger, ticket hand-out or pod. */
    val unattended: Boolean = false,
    /**
     * TRAITS-T6 (contract 1.52.0): the REPORT BACK obligation of this session, or null when it has none (an interactive
     * session, an anonymous one, or an instance with no `report` trait). `pending` from spawn; see [SessionReport].
     */
    val report: SessionReport? = null,
    /**
     * Whether the child process was observed alive the last time we checked. Updated by [SessionManager]
     * when probing the pid (heartbeat, reconciliation, kill). Not a guarantee of current liveness, but
     * the best signal we have without a live process handle.
     */
    val isProcessAlive: Boolean = false,
    /**
     * CLI-PTY-5: the daemon restarted since this session last ran, and nothing has attached its CLI since. A client shows
     * a "Resume session" action for it; [SessionManager.attach] (or any turn) clears it. Set by [SessionManager.loadAndReconcile]
     * for every session that has a provider conversation to resume.
     */
    val dormant: Boolean = false,
    /** RUNNER-POOL-4: the runner this session lives on; the wire frame fills it from `RunnerPool.runnerOf` (see [SessionRecordSerializer.runnerOf]). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val runner: String = "local",
    /**
     * BUG-M2-11f: the OS process the session host keeps for this session between turns (pid + start instant), set at
     * launch and cleared when it ends. Persisted so the next daemon start can reap a process that outlived a hard-killed
     * daemon (opencode serve does), killing it only when BOTH pid and start instant still match. Omitted when null, so
     * a record with no kept process serializes exactly as before.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val keptProcess: KeptProcess? = null,
    /**
     * Processes the session's shell still runs after its turn ended, derived from the OS process tree ([BackgroundWork]).
     * Memory only in effect: a daemon start clears it. Present = the session counts as busy ([isBusy]) and the wire state
     * reads BUSY ([SessionRecordSerializer]); omitted when null.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val backgroundWork: BackgroundWork? = null,
    /**
     * Jobs the session's agent registered itself through `infantry bg run` ([BgJob]); unlike [backgroundWork] they do not
     * depend on the OS process tree. Memory only in effect (a daemon start clears them; the wrapper re-registers on its
     * next heartbeat). Present = the session reads as running; omitted when empty.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bgJobs: List<BgJob> = emptyList(),
    /**
     * SCRIPT-VIS-1b: set while a script hook works for this session (set by the scripting plugin through `Sessions.setAutomation`). Display
     * only: it never changes [state] or [isBusy]; clients group the session under Running with an automation badge. Memory only (cleared on a
     * daemon start); omitted when null.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val automation: SessionAutomation? = null,
    /** A short text for what the session last did ("Bash: gradlew test", "replying"); stamped from [SessionActivityLog] when served. Omitted when none yet. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastAction: String? = null,
    /** Epoch millis of the session's last stdout line, stamped from [SessionActivityLog]; omitted until it has produced output. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastOutputAt: Long? = null,
    /** Epoch millis of the newest event of any kind the daemon saw for the session; omitted until there was one. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastEventAt: Long? = null,
    /**
     * LAST-TURN-1: epoch millis of the session's last finished turn (any harness, any outcome), stamped from [SessionActivityLog] at the
     * daemon's `finished`. Unlike [lastOutputAt] (the last output line, which also moves mid-turn) it moves once per turn. Omitted until one ended.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastTurnAt: Long? = null,
    /** PIPELINE-WATCHDOG: what the daemon derived about this agent's pending work ([AgentWatch]); derived, cleared on a daemon start. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val watch: AgentWatch? = null,
    /** STALL-1 (ticket 802c64d5): the job-watch state for the session list (`watching gate (quiet 2m)`, `idle`, `stalled`, `busy, silent 7m`, `watch OFF: <reason>`); derived, cleared on a daemon start. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val watchLabel: String? = null,
    /** The run phase worth naming beside [state]; only `compacting` today. Filled on the wire from the run tracker, never persisted. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val runPhase: String? = null,
    /** Contract 1.114.0: the session that spawned this one (the `new` frame's origin); persisted, so a kill rule survives an app restart. Omitted when none. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val spawnerSessionId: String? = null,
    /** Contract 1.115.0: the ONE installation-wide Assistant session (no project, role `assistant`, started by the `assistant` frame). Omitted when false. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val global: Boolean = false,
    /**
     * Contract 1.117.0: the agent's own progress report on its long job ([SessionProgress]); memory only in effect (a
     * daemon start clears it), cleared when the session ends or a turn ends with no report in it. Omitted when none.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val progress: SessionProgress? = null,
    /** WAITING-FLAG-1: the session's own "active but waiting" flag ([SessionWaiting]); cleared when its next turn starts. Omitted when none. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val waiting: SessionWaiting? = null,
    /**
     * CLI-PTY-3: the live process's own MCP server link as its harness reports it (`connected`, `disconnected`, `reconnecting`); omitted
     * when the harness reports nothing (headless runs start their servers per turn). Recorded only: the daemon acts on it nowhere; a project
     * script reads it (or the `session.mcp` event) and calls `session_act mcp_reconnect`. Cleared when the process ends and on a daemon start.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mcp: String? = null,
    /** CLI-PTY-3: epoch millis of the last change of [mcp]; omitted with it. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mcpChangedAt: Long? = null,
    /**
     * Contract 1.150.0 (CHAT-QUEUE-1): messages held while a turn runs, oldest first. The daemon sends the oldest as the next turn when
     * the session comes to rest; `queue_remove` takes one back, `queue_force` delivers one into the running turn. Persisted; omitted when empty.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val queued: List<QueuedTurn> = emptyList(),
) {
    /**
     * Whether this session is LIVE (a run is in flight) or HISTORY (none is).
     *
     * `sessions.json` keeps old records -- IDLE, childPid null, exit 0 -- beside running ones, and an
     * agent reading them had to tell live agents from history with only the state and a pid. The pid
     * cannot answer it: an [SessionState.ORPHANED] session KEEPS [childPid] for a process that is
     * already gone. The state can, because it is the observed process lifecycle: a record is
     * [SessionState.STARTING] / [SessionState.BUSY] / [SessionState.WAITING_INPUT] exactly while its
     * run is in flight, and IDLE / EXITED / ORPHANED / FAILED are history however their pid reads.
     * No record holds a process handle to poll, and none should: this is durable state.
     */
    val live: Boolean
        get() = (state == SessionState.STARTING ||
            state == SessionState.BUSY ||
            state == SessionState.WAITING_INPUT) ||
            (childPid != null && isProcessAlive)

    /**
     * Whether the session can be brought back. A session with no provider session id cannot be
     * resumed: there is nothing on the CLI side to continue. Only a session with a run in flight
     * (starting/busy/waiting for input) is not resumable *right now*. Not serialized -- the wire
     * model simply carries [providerSessionId], and this is the rule for reading it.
     */
    val resumable: Boolean
        get() = providerSessionId != null &&
            state != SessionState.STARTING &&
            state != SessionState.BUSY &&
            state != SessionState.WAITING_INPUT

    /**
     * BIND-AT-CREATION: a session created (so addressable by agent id, role and project) that has never
     * had a run. It has no provider session id to resume, yet it is not dead: its FIRST turn is simply a
     * first run. Mail and the roster treat it as available.
     */
    val neverRun: Boolean
        get() = providerSessionId == null && runCount == 0 && state == SessionState.IDLE
}

/**
 * Serializes [SessionRecord] with its computed [SessionRecord.live] flag carried as a real field.
 *
 * WHY A CUSTOM SERIALIZER: one model is the daemon's memory, `sessions.json` AND the
 * `session`/`sessions` frames, so the flag a client reads has to travel with the record itself.
 * [SessionRecord.live] is computed -- a computed getter is invisible to the plugin-generated
 * serializer -- and copying the rule into a second stored field would let the two drift. Wrapping
 * the generated serializer keeps ONE rule and puts the flag on the wire, at the sites that SERVE
 * records (`DaemonMain`, `JsonSessionStore`); decoding is unchanged, so an old record on disk or on
 * the wire decodes exactly as before and `live` is re-derived from the decoded state.
 */
object SessionRecordSerializer : KSerializer<SessionRecord> {

    /** RUNNER-POOL-4: the daemon sets this to `RunnerPool::runnerOf`; until then every session is on `local`. */
    @Volatile var runnerOf: (sessionId: String) -> String = { "local" }

    private val generated = SessionRecord.serializer()

    override val descriptor: SerialDescriptor = generated.descriptor

    override fun serialize(encoder: Encoder, value: SessionRecord) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("SessionRecord is serialized as JSON only (session frames, sessions.json)")
        val base = jsonEncoder.json.encodeToJsonElement(generated, SessionActivityLog.overlay(value))
        // base is a JsonElement; for a data class it should be a JsonObject
        val baseObj = base as? JsonObject ?: error("Expected JsonObject for SessionRecord, got $base")
        val extended = baseObj.toMutableMap().apply {
            put("live", JsonPrimitive(value.live))
            // RUNNER-POOL-4: `local` stays off the wire (older clients decode the default).
            runnerOf(value.id).takeIf { it != "local" }?.let { put("runner", JsonPrimitive(it)) } // runner-ok: the wire record names the runner a session lives on (absent for this daemon's own)
            // Contract 1.112.0: whether a TURN is in flight, before the display-only BUSY rewrite below.
            put("turnActive", JsonPrimitive(value.isBusy()))
            if (value.state == SessionState.BUSY) RunPhaseOverlay.phaseOf(value.id)?.let { put("runPhase", JsonPrimitive(it)) }
            // Background work counts as running: an at-rest session with live processes reads BUSY on the wire.
            if ((value.backgroundWork != null || value.bgJobs.isNotEmpty()) && value.state == SessionState.IDLE) put("state", JsonPrimitive(SessionState.BUSY.name))
        }
        jsonEncoder.encodeJsonElement(JsonObject(extended))
    }

    /**
     * BASICS-KILL-1: undoes the display-only BUSY rewrite above. A record served with `turnActive=false` and state BUSY
     * is an at-rest session whose leftover child (a Gradle daemon, a bg job) made it READ busy; decoded as BUSY it
     * looked live to every client and `session_remove` refused it forever ("stale BUSY"). Its true state is IDLE, which
     * is also what the daemon's own record says. A record without the flag (an older daemon) decodes as before.
     */
    override fun deserialize(decoder: Decoder): SessionRecord {
        val jsonDecoder = decoder as? JsonDecoder ?: return generated.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        val record = jsonDecoder.json.decodeFromJsonElement(generated, element)
        val turnActive = ((element as? JsonObject)?.get("turnActive") as? JsonPrimitive)?.booleanOrNull
        return if (turnActive == false && record.state == SessionState.BUSY) record.copy(state = SessionState.IDLE) else record
    }
}

/** BASICS-KILL-1: a `sessions` frame decoded to the records' TRUE state (see [SessionRecordSerializer.deserialize]). */
val SessionListWire: KSerializer<List<SessionRecord>> = kotlinx.serialization.builtins.ListSerializer(SessionRecordSerializer)

/**
 * A lifecycle event the daemon broadcasts. [SessionManager] emits these and never touches a socket;
 * the daemon turns them into [com.superiorsoftllc.infantry.protocol.Envelope] frames.
 *
 *  - [Status]   a session's record changed in a way a client must react to (state transition).
 *  - [Stdout]   one stdout line from a run, tagged with its session.
 *  - [Stderr]   one stderr line from a run, tagged with its session.
 *  - [Exit]     a run ended, with its optional exit code, tagged with its session. The code is null
 *               when the transport could not report one (for example, a forced kill); [killOutcome]
 *               says HOW a deliberate stop resolved, without making the client re-read the session.
 *  - [Turn]     contract 1.11.0: a run was accepted for this session with [Turn.prompt], exactly what the
 *               provider is given. Emitted before the run's process starts, so it precedes every line the
 *               run produces: the human side of the conversation, on the same stream as the agent's side.
 */
sealed interface SessionEvent {
    data class Status(val session: SessionRecord) : SessionEvent
    /** [origin] (contract 1.125.0): who the turn came from; [MessageOrigin.Owner] unless its creator said otherwise. */
    data class Turn(
        val sessionId: String,
        val prompt: String,
        val origin: MessageOrigin = MessageOrigin.Owner,
        /** CHAT-QUEUE-1: a message delivered INTO the turn already running (`queue_force`), not a new turn: shown in the transcript, never counted as a turn start. */
        val midTurn: Boolean = false,
    ) : SessionEvent
    /** [afterInterrupt]: BUG-M2-11c-INTERRUPT, the line belongs to a turn the owner interrupted. */
    data class Stdout(val sessionId: String, val line: String, val afterInterrupt: Boolean = false) : SessionEvent
    data class Stderr(val sessionId: String, val line: String) : SessionEvent
    data class Exit(
        val sessionId: String,
        val code: Int?,
        val killOutcome: KillOutcome? = null,
        /**
         * M2-11: false when only the TURN ended and the provider's process lives on (a session transport). The
         * daemon then sends no `exit` frame, because "process exited with code 0" would be false.
         */
        val processExited: Boolean = true,
        /** FIRSTRUN-FAIL-1: why a failed turn failed (the harness's reason, or the exit code); null when it did not fail. */
        val reason: String? = null,
    ) : SessionEvent

    /**
     * M2-11: a detail about what the daemon did to run a turn (process started, pid, resumed provider session),
     * shown in the Activity box (a `status` frame's tool/activity) instead of as a chat line.
     */
    data class Activity(val sessionId: String, val tool: String, val detail: String) : SessionEvent

    /**
     * BUG-M2-11b-1 (contract 1.64.0): the session's hosted process ended without the daemon asking, with no turn in
     * flight. Mapped to a raw `exit` frame only; a turn's end is always [Exit] (`finished`).
     */
    data class ProcessExit(val sessionId: String, val code: Int) : SessionEvent
}

/**
 * The worktree identity to re-register with the [com.superiorsoftllc.infantry.worktree.GitWorktreeManager]
 * at daemon startup, or null when this session has no worktree. Status is never persisted -- it is
 * recomputed from git on demand -- so re-registering this identity is all a restart needs.
 */
fun SessionRecord.toWorktreeRef(): WorktreeRef? {
    val path = worktreePath ?: return null
    val branch = worktreeBranch ?: return null
    val repo = repoRoot ?: return null
    val base = worktreeBaseRef ?: return null
    return WorktreeRef(id, repo, path, branch, base)
}
