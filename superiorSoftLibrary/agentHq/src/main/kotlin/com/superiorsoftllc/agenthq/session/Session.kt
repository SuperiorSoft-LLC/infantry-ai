package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.protocol.MessageOrigin
import com.superiorsoftllc.agenthq.session.host.KeptProcess
import com.superiorsoftllc.agenthq.worktree.WorktreeRef
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
 * Phase 3: the lifecycle of one **managed session**.
 *
 * A session is provider-neutral and logical. It is NOT a process: both supported CLIs (`opencode
 * run`, `claude -p`) are one-shot -- they consume a prompt, answer, and exit -- so one logical
 * session is a *sequence of runs* that share one provider-side session id. The daemon owns the
 * sequence; the provider CLI owns the conversational context behind [SessionRecord.providerSessionId].
 *
 * States are deliberately few, and each one means something a client can act on:
 *
 *  - [STARTING]       legacy: a run spawned with no output yet. M2-12: the daemon no longer assigns it (a
 *                     record persisted by an older daemon may still carry it, and is read as in flight).
 *  - [BUSY]           WORKING: a turn is in progress -- from the moment it is accepted, through reasoning,
 *                     tool calls and the streamed reply, until it ends. Process start/exit never sets it.
 *  - [WAITING_INPUT]  the provider signalled it needs a human answer before it can continue.
 *  - [IDLE]           no run in flight; the session is alive, persisted and resumable. The resting
 *                     state between prompts.
 *  - [EXITED]         the session ended on purpose or its run ended while no daemon was watching
 *                     (a kill, or a daemon restart whose child did not survive). Terminal, resumable.
 *  - [ORPHANED]       a daemon restart found this session's child process still alive: the run
 *                     outlived the daemon that owned it. Terminal for this daemon (it does not adopt
 *                     a pid it cannot reattach to), resumable, and recorded so the mismatch between
 *                     "daemon thought it was running" and "the process is gone" is never silent.
 *  - [FAILED]         abnormal end: non-zero exit, or a run that could not be started. Terminal but
 *                     resumable when a provider session id is known.
 *
 * INFERENCE, stated plainly: every state except [WAITING_INPUT] is *observed* -- it follows directly
 * from the process lifecycle and its exit code, which the runner reports. There is no guesswork.
 * [WAITING_INPUT] is DEFINED but not currently produced: neither CLI, run non-interactively (no
 * TTY), ever signals "I need input" -- it either finishes or blocks. The state exists so a future
 * interactive/PTY transport or an HTTP provider can raise it; today the daemon never assigns it.
 * See `README.md` ("Status reporting") for the full argument.
 */
@Serializable
enum class SessionState {
    STARTING,
    BUSY,
    WAITING_INPUT,
    IDLE,
    EXITED,
    ORPHANED,
    FAILED,
}

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
    /** How many runs this logical session has performed. */
    val runCount: Int = 0,
    /** How many times this session was explicitly resumed. */
    val resumeCount: Int = 0,
    /** Exit code of the most recent run, when there was one. */
    val lastExitCode: Int? = null,
    /** How a kill resolved, when the session was killed. */
    val killOutcome: KillOutcome? = null,
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
     * Jobs the session's agent registered itself through `agenthq bg run` ([BgJob]); unlike [backgroundWork] they do not
     * depend on the OS process tree. Memory only in effect (a daemon start clears them; the wrapper re-registers on its
     * next heartbeat). Present = the session reads as running; omitted when empty.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bgJobs: List<BgJob> = emptyList(),
    /** A short text for what the session last did ("Bash: gradlew test", "replying"); stamped from [SessionActivityLog] when served. Omitted when none yet. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastAction: String? = null,
    /** Epoch millis of the session's last stdout line, stamped from [SessionActivityLog]; omitted until it has produced output. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastOutputAt: Long? = null,
    /** Epoch millis of the newest event of any kind the daemon saw for the session; omitted until there was one. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastEventAt: Long? = null,
    /** PIPELINE-WATCHDOG: what the daemon derived about this agent's pending work ([AgentWatch]); derived, cleared on a daemon start. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val watch: AgentWatch? = null,
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
 * Contract 1.6.0: one source the start composed into a session's prompt, and how many characters it
 * contributed. [source] is `instructions`, `ticket` or `linkedTickets`.
 */
@Serializable
data class ContextSource(
    val source: String,
    val chars: Int,
)

/**
 * A lifecycle event the daemon broadcasts. [SessionManager] emits these and never touches a socket;
 * the daemon turns them into [com.superiorsoftllc.agenthq.protocol.Envelope] frames.
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
    data class Turn(val sessionId: String, val prompt: String, val origin: MessageOrigin = MessageOrigin.Owner) : SessionEvent
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
 * The worktree identity to re-register with the [com.superiorsoftllc.agenthq.worktree.GitWorktreeManager]
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

/** The sessions whose run is in a named phase right now (`compacting`), kept by the run tracker, read when a record goes on the wire. */
object RunPhaseOverlay {
    private val phases = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun phaseOf(sessionId: String): String? = phases[sessionId]
    fun set(sessionId: String, phase: String?) { if (phase == null) phases.remove(sessionId) else phases[sessionId] = phase }
}
