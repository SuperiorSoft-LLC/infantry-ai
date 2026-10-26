package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.MessageOrigin
import com.superiorsoftllc.agenthq.session.isBusy
import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.attention.AttentionItem
import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.agents.KnowledgeCounter
import com.superiorsoftllc.agenthq.agents.KnowledgeLines
import com.superiorsoftllc.agenthq.memory.MemoryIndexEntry
import com.superiorsoftllc.agenthq.agents.KnowledgeCounts
import com.superiorsoftllc.agenthq.agents.firstTurn
import com.superiorsoftllc.agenthq.agents.section
import com.superiorsoftllc.agenthq.backlog.BoardAnswer
import com.superiorsoftllc.agenthq.backlog.BoardEvent
import com.superiorsoftllc.agenthq.backlog.TicketAnswer
import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.context.TicketContextComposer
import com.superiorsoftllc.agenthq.harness.CommandLines
import com.superiorsoftllc.agenthq.harness.HarnessPluginSource
import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.ServiceLoaderPluginSource
import com.superiorsoftllc.agenthq.harness.descriptor.DescriptorNote
import com.superiorsoftllc.agenthq.harness.descriptor.HarnessDescriptorLoader
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.epics.EpicHandler
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.epics.SqlEpicPersistence
import com.superiorsoftllc.agenthq.protocol.RUN_IN_FLIGHT
import com.superiorsoftllc.agenthq.protocol.AgentListRequest
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.AgentSwap
import com.superiorsoftllc.agenthq.protocol.AgentSwapped
import com.superiorsoftllc.agenthq.protocol.SessionSwap
import com.superiorsoftllc.agenthq.protocol.SessionSwapped
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventNormalizer
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.protocol.RosterRequest
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.protocol.StatusPayload
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketListQuery
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketReorder
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.SettingsSet
import com.superiorsoftllc.agenthq.settings.LimitRegistry
import com.superiorsoftllc.agenthq.settings.ProjectSettings
import com.superiorsoftllc.agenthq.settings.SettingsService
import com.superiorsoftllc.agenthq.mailbox.MailStore
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.mailbox.MailEvent
import com.superiorsoftllc.agenthq.mailbox.MailRefused
import com.superiorsoftllc.agenthq.mailbox.MailResult
import com.superiorsoftllc.agenthq.mailbox.MailRouter
import com.superiorsoftllc.agenthq.mvp.BoardHandler
import com.superiorsoftllc.agenthq.mvp.ProjectHandler
import com.superiorsoftllc.agenthq.decisions.DecisionAuthor
import com.superiorsoftllc.agenthq.decisions.DecisionHandler
import com.superiorsoftllc.agenthq.decisions.DecisionStore
import com.superiorsoftllc.agenthq.pipeline.PipelineHandler
import com.superiorsoftllc.agenthq.pipeline.PipelineStore
import com.superiorsoftllc.agenthq.issues.IssueActor
import com.superiorsoftllc.agenthq.issues.IssueHandler
import com.superiorsoftllc.agenthq.issues.IssueStore
import com.superiorsoftllc.agenthq.memory.MemoryCaller
import com.superiorsoftllc.agenthq.memory.MemoryHandler
import com.superiorsoftllc.agenthq.memory.MemoryStore
import com.superiorsoftllc.agenthq.memory.SqlMemoryPersistence
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.mvp.SqlProjectPersistence
import com.superiorsoftllc.agenthq.mvp.SqlWorkspacePersistence
import com.superiorsoftllc.agenthq.mvp.WorkspaceHandler
import com.superiorsoftllc.agenthq.mvp.WorkspaceStore
import com.superiorsoftllc.agenthq.mvp.StoryHandler
import com.superiorsoftllc.agenthq.mvp.StoryHandler.Companion.asStory
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.session.isInSession
import com.superiorsoftllc.agenthq.mailbox.Mailbox
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.mailbox.MessageState
import com.superiorsoftllc.agenthq.mailbox.ThreadSummary
import com.superiorsoftllc.agenthq.mailbox.WaitingOn
import com.superiorsoftllc.agenthq.orchestration.Orchestrator
import com.superiorsoftllc.agenthq.orchestration.PodFirstTurn
import com.superiorsoftllc.agenthq.orchestration.PodStatus
import com.superiorsoftllc.agenthq.orchestration.PodSubmitRequest
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.persistence.traitReferences
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.ProviderFactory
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.RunnerFactory
import com.superiorsoftllc.agenthq.session.SessionStore
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionRecordSerializer
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.RunInFlightException
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.stats.SessionStats
import com.superiorsoftllc.agenthq.stats.SessionStatsRequest
import com.superiorsoftllc.agenthq.stats.SessionStatsService
import com.superiorsoftllc.agenthq.session.KillOutcome
import com.superiorsoftllc.agenthq.keystore.Keystore
import com.superiorsoftllc.agenthq.keystore.KeystoreHandler
import com.superiorsoftllc.agenthq.session.stateDir
import com.superiorsoftllc.agenthq.session.agentHqSetting
import com.superiorsoftllc.agenthq.session.toWorktreeRef
import com.superiorsoftllc.agenthq.stories.StoryMigration
import com.superiorsoftllc.agenthq.projectgit.ProjectGitHandler
import com.superiorsoftllc.agenthq.projectgit.ProjectGitService
import com.superiorsoftllc.agenthq.worktree.CleanupResult
import com.superiorsoftllc.agenthq.worktree.GitWorktreeManager
import com.superiorsoftllc.agenthq.worktree.SystemGitCli
import com.superiorsoftllc.agenthq.worktree.TicketBaseRef
import com.superiorsoftllc.agenthq.worktree.TicketBranches
import com.superiorsoftllc.agenthq.worktree.WorktreeStatus
import kotlinx.serialization.json.JsonArray
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import com.superiorsoftllc.agenthq.protocol.RoleSave
import com.superiorsoftllc.agenthq.protocol.TriggerEventView
import com.superiorsoftllc.agenthq.protocol.TriggerSave
import com.superiorsoftllc.agenthq.protocol.TriggerView
import com.superiorsoftllc.agenthq.triggers.TriggerNudges
import com.superiorsoftllc.agenthq.triggers.TriggerService
import com.superiorsoftllc.agenthq.triggers.WebhookIngress
import com.superiorsoftllc.agenthq.usage.GateDecision
import com.superiorsoftllc.agenthq.usage.LaunchGate
import com.superiorsoftllc.agenthq.usage.LaunchOrigin
import com.superiorsoftllc.agenthq.usage.OperatorBudget
import com.superiorsoftllc.agenthq.usage.ProviderState
import com.superiorsoftllc.agenthq.usage.ProviderStateTracker
import com.superiorsoftllc.agenthq.usage.RunUsage
import com.superiorsoftllc.agenthq.usage.UsageRecorder
import com.superiorsoftllc.agenthq.usage.LimitService
import com.superiorsoftllc.agenthq.usage.ProviderLimits
import com.superiorsoftllc.agenthq.usage.UsageMeters
import com.superiorsoftllc.agenthq.usage.UsagePacing
import com.superiorsoftllc.agenthq.usage.UsageReport
import com.superiorsoftllc.agenthq.role.Role
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.role.RoleBinding
import com.superiorsoftllc.agenthq.role.RoleBindingHandler
import com.superiorsoftllc.agenthq.protocol.PromptSave
import com.superiorsoftllc.agenthq.protocol.PromptView
import com.superiorsoftllc.agenthq.protocol.SkillSave
import com.superiorsoftllc.agenthq.protocol.SkillView
import com.superiorsoftllc.agenthq.protocol.TraitSave
import com.superiorsoftllc.agenthq.protocol.TraitView
import com.superiorsoftllc.agenthq.protocol.RoleTraits
import com.superiorsoftllc.agenthq.protocol.RoleTraitsSave
import com.superiorsoftllc.agenthq.role.RoleTraitHandler
import com.superiorsoftllc.agenthq.protocol.RoleBindingSave
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

private const val DEFAULT_PORT = DaemonPort.DEFAULT
private const val DEFAULT_DB_NAME = "agenthq.db"

/** Ticket b785f383: how long a closed session's own exit handlers get before its leftover processes are killed. */
private const val SESSION_CLOSE_GRACE_MS = 3_000L
private const val SESSION_REPLAY_REQUEST_TYPE = "replay_request"
private const val SESSION_REPLAY_GAP_TYPE = "replay_gap"
private const val SESSION_REPLAY_BUFFER_CAPACITY = 256
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** STATS-1: one per daemon process, so the per-session resolved-path cache outlives a single connection. */
private val sessionStats = SessionStatsService()

/** PROMPT-1: numbers each WebSocket connection, so `prompt_resolved.resolvedBy` names the one that answered. */
private val promptClientCounter = java.util.concurrent.atomic.AtomicLong()
private val SESSION_STREAM_FRAME_TYPES = setOf(
    Envelope.SESSION,
    Envelope.STATUS,
    Envelope.STDOUT,
    Envelope.STDERR,
    Envelope.EXIT,
    Envelope.TEXT_DELTA,
    Envelope.ERROR,
    Envelope.FINISHED,
    // Contract 1.23.0: the model's reasoning streams on the same replayable session channel.
    Envelope.REASONING,
    // Contract 1.11.0: the human side of the conversation is replayable like the agent's side.
    Envelope.TURN,
    // Contract 1.13.0: a run's usage is part of its session's replayable stream.
    Envelope.USAGE,
)

/**
 * Contract 1.11.0: which daemon PROCESS a client is talking to, announced on `ready`.
 *
 * Session-stream sequence numbers live in memory and restart at 1 with the process, so a client's replay
 * cursor is only meaningful against the [instance] that issued it. [historySince] is the epoch millis
 * from which this process holds replay history: a session whose `lastHeartbeat` is older has nothing to
 * replay here, so a client need not ask (and draw a no-replay-history error).
 */
private data class DaemonIdentity(val instance: String, val historySince: Long)

/**
 * The `/health` body, as a typed and `@Serializable` shape.
 *
 * NOT a `mapOf(...)`: a Kotlin `mapOf` mixing `String` and `Int` values is a `Map<String, Any>`, and
 * Ktor's kotlinx-serialization JSON refuses heterogeneous collections at runtime
 * ("Serializing collections of different element types is not yet supported"), turning `/health` into
 * a 500 the moment a non-String value is added. A data class cannot drift into that trap.
 *
 * Phase 5b ADDS [pods] and [runsInFlight] (contract 1.1.0). Both default to 0 so the shape stays
 * constructible by anything that predates them, and every field 1.0.0 published keeps its name and
 * meaning -- adding a field to a response is not a break, because every client decodes with
 * `ignoreUnknownKeys`.
 */
@Serializable
private data class Health(
    val status: String,
    val provider: String,
    val runner: String,
    val sessions: Int,
    val worktrees: Int,
    /** Pods with no end time yet: what the orchestrator is still working on. */
    val pods: Int = 0,
    /** Runs with no end time: processes this daemon believes are in flight right now. */
    val runsInFlight: Int = 0,
    /** Version of the published boundary; see `EventVocabulary.VERSION` and `CONTRACT.md`. */
    val contract: String = EventVocabulary.VERSION,
    /** Contract 1.3.0: every provider a spawn may name, the default ([provider]) first. */
    val providers: List<String> = emptyList(),
    /** Contract 1.8.0: number of unacknowledged attention records. */
    val attentionOpen: Int = 0,
    /** Contract 1.13.0: each configured provider's observed limit status (`unknown` until observed). */
    val providerStatus: Map<String, String> = emptyMap(),
    /** Contract 1.14.0: triggers currently tripped (waiting for `trigger_arm`). */
    val triggersTripped: Int = 0,
)

/** States from which a session can no longer take a plain prompt: a new/last-known session is used. */
private val TERMINAL = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)

/**
 * AgentHQ daemon.
 *
 * Phase 1 proved a live session over the WebSocket; Phase 2 put two providers behind one seam;
 * **Phase 3 turns the spawned process into a managed session**. The daemon now owns a
 * [SessionManager]: sessions are persisted (JSON on disk), survive a daemon restart, can be killed
 * and resumed, and every state change is broadcast to connected clients as a `session` frame.
 *
 * The provider seam is untouched: the daemon never names a CLI, it asks [SessionManager] to manage
 * whatever [com.superiorsoftllc.agenthq.provider.AgentProvider] it was configured with.
 *
 * Phase 9 freezes the boundary in [com.superiorsoftllc.agenthq.protocol.EventVocabulary]: the raw
 * provider stream keeps flowing on `stdout`/`stderr`/`exit`, and a normalised
 * `text_delta`/`status`/`error`/`finished` stream is published beside it. `AGENTHQ_PROVIDER=stub`
 * runs the whole daemon with no provider CLI installed.
 *
 * Phase 5b makes the orchestrator REACHABLE. The daemon now owns the three things Phase 5 needed and
 * nothing outside could reach: a [SqlAgentHqStore] on the configured database, an [Orchestrator] fed
 * by the session event sink, and the `pod_submit` / `pod_query` / `pod_cancel` frames (contract
 * 1.1.0) that submit a pod and read its state back.
 */
fun main() {
    // BUG-PORT-1-STATEDIR: one daemon per state dir, decided before anything opens the database.
    val stateLock = try {
        StateDirLock.acquire(stateDir())
    } catch (refused: StateDirInUseException) {
        System.err.println("[agentHq] refusing to start: ${refused.message}")
        exitProcess(2)
    }
    val daemon = try {
        AgentHqDaemon.fromEnv()
    } catch (refused: AccessConfigurationException) {
        // Refused before anything was opened: the message names the setting to change.
        System.err.println("[agentHq] refusing to start: ${refused.message}")
        exitProcess(2)
    } catch (refused: PortInUseException) {
        // PORT-1: AGENTHQ_PORT_STRICT=true on a taken port. One readable line, no stack trace.
        System.err.println("[agentHq] refusing to start: ${refused.message}")
        exitProcess(2)
    }
    println("[agentHq] access: ${daemon.policy.describe()}")
    println("[agentHq] web: ${daemon.webBundle.describe()}")
    println(
        AgentHqBin.resolve()?.let { "[agentHq] sessions get the agenthq CLI on PATH from $it" }
            ?: "[agentHq] WARNING: agenthq CLI not found (run installDist or set AGENTHQ_BIN); sessions cannot run `agenthq knowledge`",
    )
    if (!daemon.policy.loopbackBind) {
        System.err.println(
            "[agentHq] WARNING: bound to ${daemon.host}, which other machines can reach. The bearer token " +
                "and every frame cross the network in cleartext unless a TLS proxy or tunnel is in front " +
                "of this daemon (see README, \"Remote daemon\")."
        )
    }
    // PORT-1: bind, record the real port in <stateDir>/daemon.port, and only THEN announce it: the ready line
    // and the file both carry the port actually bound, never the one that was asked for.
    val bound = try {
        daemon.start()
    } catch (failed: Exception) {
        // Taken between the probe and the bind: still one readable line. Ktor wraps the BindException in a
        // JobCancellationException, so the cause chain is searched rather than the thrown type.
        val taken = generateSequence<Throwable>(failed) { it.cause }.firstOrNull { it is java.net.BindException } ?: throw failed
        System.err.println("[agentHq] refusing to start: port ${daemon.port} could not be bound (${taken.message})")
        exitProcess(2)
    }
    stateLock.recordPort(bound)
    Runtime.getRuntime().addShutdownHook(Thread { daemon.releasePortFile(); stateLock.release() })
    println("[agentHq] listening on ws://${daemon.host}:$bound/ws")
    // The server runs on its own threads; this thread only keeps the process alive until it is stopped.
    Thread.currentThread().join()
}

/**
 * The assembled daemon, extracted from [main] so a test can boot the REAL thing -- same manager,
 * same mailbox, same router, same frames -- on an ephemeral port against the stub provider.
 *
 * This is what makes the Phase 9 acceptance criterion checkable: "a client connects to a daemon
 * running the stub provider and renders a scripted session end-to-end". A test that faked the
 * server would prove nothing about the boundary; this proves the boundary itself.
 */
class AgentHqDaemon(
    val host: String,
    val port: Int,
    val providerId: String,
    val runnerId: String,
    val manager: SessionManager,
    val orchestrator: Orchestrator,
    val store: AgentHqStore,
    internal val clients: MutableList<Channel<Envelope>>,
    val server: EmbeddedServer<*, *>,
    /** Who may connect, decided at startup. Read by [main] for the banner; see [AccessPolicy]. */
    val policy: AccessPolicy,
    /** WEB-1 (contract 1.122.0): the browser client bundle served at `/`; read by [main] for the banner. */
    val webBundle: WebBundle,
    /** Contract 1.4.0: the agent profiles a spawn can name, stored in [store]. */
    val agents: AgentProfiles,
    /** Contract 1.5.0: the backlog, stored in [store]; every change it commits is pushed to every client. */
    val board: TicketBoard,
    /** Contract 1.8.0: durable operator attention, with each change pushed to every client. */
    val attention: AttentionStore,
    /** Contract 1.12.0: the role catalog served by `role_list`, extended by `role_save`. */
    val roles: RoleService,
    /** Story 9.7b: the operator budget the launch gate reads. */
    val budget: OperatorBudget,
    /** Story 9.7b: observed limit state per provider. */
    val providerStates: ProviderStateTracker,
    /** Story 9.5: the daemon's triggers. */
    val triggers: TriggerService,
    /** PW-3: the workspaces, stored in [store] and checked against the project store. */
    val workspaces: WorkspaceStore,
    /** MEM-2: the project-memory notes, stored in [store] so a note outlives the daemon. */
    val memoryNotes: MemoryStore,
    /** P10-K4: the per-project append-only decisions log, stored in [store] so a decision outlives the daemon. */
    val decisions: DecisionStore,
    /** P10-K6: the per-project append-only issues log (attempts and fixes), stored in [store] so it outlives the daemon. */
    val issues: IssueStore,
    /** EPIC-3: the epics, stored in [store] so an epic and its ordered storyIds outlive the daemon. */
    val epics: EpicStore,
    /** IN-APP BUILD T2 (contract 1.33.0) + RUN-CMD-1 (1.108.0): saved commands from commands.json, editable; empty when absent or refused. */
    val commands: com.superiorsoftllc.agenthq.commands.CommandStore? = null,
    /** PORT-1: where [start] records the bound port (`<stateDir>/daemon.port`); null = recorded nowhere. */
    val portFile: File? = null,
    /** CLIPATH-1: puts back the executable resolver that was installed before this daemon's (see `CliExecutables.install`). */
    private val restoreExecutables: () -> Unit = {},
    /** PROMPT-1 (contract 1.77.0): the open tool-approval / question prompts; harness adapters ask through it. */
    val pendingPrompts: com.superiorsoftllc.agenthq.prompts.PendingPrompts = com.superiorsoftllc.agenthq.prompts.PendingPrompts(),
    /** BUG-FLAKE-1: the port [build] chose, held bound until [server] starts (BUG-PORT-0b); null = nothing held. */
    private val portReservation: DaemonPort.Reservation? = null,
    /** IMG-5: deletes chat uploads unused for `images.chatImageRetentionDays`; swept at [start], then every 6 h. */
    val chatImageRetention: com.superiorsoftllc.agenthq.images.ChatImageRetention? = null,
    /** ASK-1 (contract 1.97.0): non-blocking owner questions; [start] sweeps their `defaultAfterMin` auto-picks. */
    val ownerQuestions: com.superiorsoftllc.agenthq.questions.OwnerQuestions? = null,
    /** TICKET-HANDOVER: switches ticket workers across harnesses with a handoff turn; stopped first by [close]. */
    val handover: SessionHandover? = null,
    /** Ticket b785f383: [close] kills what ended or removed sessions left running (after the manager's own shutdown). */
    private val processWatch: com.superiorsoftllc.agenthq.session.ProcessWatch? = null,
    /**
     * Ticket 3b30398d: the schedulers [build] started (usage refresh, agent watchdog), stopped early by [close]; and the
     * process-watch scheduler, stopped by [close] after its last reap. A daemon that is closed leaves no thread running.
     */
    private val background: List<AutoCloseable> = emptyList(),
    private val processWatchScheduler: AutoCloseable? = null,
) {
    @Volatile private var recordedPort: Int? = null
    /** ASK-1: the `defaultAfterMin` sweep, started by [start], stopped by [close]. */
    @Volatile private var questionSweeper: AutoCloseable? = null

    /**
     * PORT-1: start the server WITHOUT blocking, wait until it has bound, record the bound port in [portFile],
     * and return that port. A client follows this port (or the file), never the one that was asked for.
     *
     * BUG-FLAKE-1 / BUG-PORT-0b: the port [build] chose stays held until the server's ApplicationStarted event lets
     * go of it, right before the engine binds; that holds for a direct `server.start` too (see [build]).
     */
    fun start(): Int {
        server.start(wait = false)
        // IMG-5: one sweep at start, then the 6-hour tick.
        chatImageRetention?.start()
        // ASK-1: auto-picks are minute-grained; a 15 s sweep (and one at once, for questions due across a restart).
        ownerQuestions?.let { questions ->
            runCatching { questions.sweep() }
            questionSweeper = com.superiorsoftllc.agenthq.images.ChatImageRetention.scheduledTicker(QUESTION_SWEEP_MS) {
                runCatching { questions.sweep() }.onFailure { System.err.println("[agentHq] question sweep failed: ${it.message}") }
            }
        }
        val bound = boundPort()
        portFile?.let { DaemonPort.write(it, bound) }
        recordedPort = bound
        return bound
    }

    /** The port the started [server] actually listens on (resolves an ephemeral 0). */
    fun boundPort(): Int = kotlinx.coroutines.runBlocking {
        repeat(250) {
            server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
            kotlinx.coroutines.delay(20)
        }
        error("the daemon never bound a port")
    }

    /** PORT-1: remove [portFile] if it still names this daemon's port. Idempotent; part of every clean shutdown. */
    fun releasePortFile() {
        val bound = recordedPort ?: return
        portFile?.let { runCatching { DaemonPort.release(it, bound) } }
    }

    /**
     * Release everything this daemon owns, in dependency order.
     *
     * The order is the whole point and is not cosmetic: the orchestrator goes FIRST so no further
     * event can enqueue a write against the store, then the session manager kills whatever children
     * it still owns, then the board DRAINS the session events those kills queued, and only then is the
     * SQLite handle closed. Closing the store first would turn every later event into a write against a
     * closed connection.
     *
     * The drain is not optional: `manager.shutdown()` kills runs while it holds the session manager's
     * monitor, so a killed run's outcome cannot be applied by the board inline -- it lands on the
     * board's own single-thread queue, and it is a `ticket_runs` write. Returning from close() with that
     * write still in flight means this daemon is not stopped, which is precisely what a test that boots a
     * SECOND daemon over the SAME database file (the crash-recovery and restart cases) must be able to
     * assume: without the drain the first daemon still writes the file the second one has just opened,
     * and a contended SQLite write fails with SQLITE_BUSY once its 5 s budget runs out.
     */
    fun close() {
        // Triggers stop observing FIRST: the kills below must not read as trigger-run failures; a trigger
        // still running is tripped at the next boot instead ("daemon restarted mid-run").
        runCatching { triggers.close() }
        runCatching { chatImageRetention?.close() }
        runCatching { questionSweeper?.close() }
        background.forEach { runCatching { it.close() } }
        // PROMPT-1: nobody can answer after this; every suspended asker gets deny / no answer.
        runCatching { pendingPrompts.closeAll() }
        releasePortFile()
        // BUG-FLAKE-1: a daemon built but never started still holds its port.
        runCatching { portReservation?.close() }
        board.stopQueueDrain()
        runCatching { handover?.close() }
        runCatching { orchestrator.close() }
        runCatching { manager.shutdown() }
        // Ticket b785f383: a clean stop leaves nothing of an ended or removed session behind; live sessions' bg jobs stay.
        runCatching { processWatch?.reapEnded("daemon shutdown") }
        // Ticket 3b30398d: then its scheduler goes (pending close-time reaps are covered by the reap above).
        runCatching { processWatchScheduler?.close() }
        // Everything the kills above queued is applied before the store is released; see the KDoc.
        runCatching { board.close() }
        // CLIPATH-1: launches after this no longer read this daemon's (about to close) store for overrides.
        runCatching { restoreExecutables() }
        runCatching { store.close() }
    }

    companion object {
        /**
         * Build the daemon from the environment, exactly as [main] does.
         *
         * @param port 0 binds an ephemeral port; read it back from `server.engine.resolvedConnectors()`.
         * @param provider injected so a test can boot the real daemon on the stub; defaults to the
         *   configured one so production behaviour is untouched.
             * @param sessionStore / mailStore / store injected for the same reason: a test must not write
             *   into the operator's real `~/.agenthq` state. (E1-c: the story board is the ticket board now.)
         * @param mailBaseUrl override for the URL rendered into delivered prompts (tests use their
         *   own ephemeral port rather than the default `127.0.0.1:<port>`).
         */
        fun build(
            port: Int = configuredPort(),
            host: String = RemoteAccess.resolveHost(stateDir()),
            provider: com.superiorsoftllc.agenthq.provider.AgentProvider =
                com.superiorsoftllc.agenthq.provider.ProviderFactory.fromEnv(),
            runner: ProcessRunner = provider.transport()
                ?: com.superiorsoftllc.agenthq.runner.RunnerFactory.fromFlag(),
            repo: File? = InfantryEnv.get("AGENTHQ_REPO")?.takeIf { it.isNotBlank() }?.let { File(it) },
             sessionStore: SessionStore = JsonSessionStore.default(),
             mailStore: MailStore = JsonMailStore.default(),
             store: AgentHqStore = SqlAgentHqStore.open(defaultDbPath()),
             /** E1-c (contract 1.62.0): IGNORED -- the story frames are shims over the ticket board. Kept so callers still compile. */
             @Suppress("UNUSED_PARAMETER") storyStore: com.superiorsoftllc.agenthq.stories.StoryStore? = null,
            /** Null (no `AGENTHQ_MAIL_BASE`) = `http://<agent-reachable host>:<the port actually chosen>` (PORT-1). */
            mailBaseUrl: String? = InfantryEnv.get("AGENTHQ_MAIL_BASE")?.takeIf { it.isNotBlank() },
            /**
             * The bearer token every route except `/health` requires. Defaults to the environment
             * (`AGENTHQ_TOKEN_FILE`, else `AGENTHQ_TOKEN`); null is loopback-only with no credential.
             * Validated before anything else in the body runs: a non-loopback bind without a token throws
             * [AccessConfigurationException] here.
             */
            token: String? = RemoteAccess.resolveToken(stateDir()),
            /**
             * Contract 1.3.0: the providers a spawn may name besides [provider], from `AGENTHQ_PROVIDERS`.
             * Empty (the default when the variable is unset) = this daemon serves [provider] only.
             */
            additionalProviders: List<AgentProvider> = ProviderFactory.additionalFromEnv(provider),
            context: TicketContext? = null,
            /** Contract 1.12.0: the role catalog and where custom roles are written (`AGENTHQ_ROLES_DIR`). */
            roles: RoleService = RoleService(RoleService.defaultDir()),
            /** Story 9.7b: the operator budget (`AGENTHQ_BUDGET_RUNS_PER_DAY` / `_TOKENS_PER_DAY`; unset = none). */
            budget: OperatorBudget = OperatorBudget.fromEnv(),
            /**
             * BRANCH-PER-TASK 1/3 + BUG-REGRESS-1: the ONE resolver of the ref a ticket start cuts its own branch
             * from. Defaults to `AGENTHQ_BASE_BRANCH`, else NONE (BUG-BASE-087: a start with no base configured is
             * refused, never cut from the main checkout's HEAD); BASE-1a plugs the project's own setting in there.
             */
            baseRef: TicketBaseRef = TicketBaseRef.fromEnv(orElse = TicketBaseRef.NONE),
            /** IN-APP BUILD T2: saved commands file, `<AGENTHQ_HOME>/commands.json` (home defaults to the state dir). */
            commandsFile: File = File(daemonConfigDir(), "commands.json"),
            /** WIRE-1: the harness plugins this daemon discovered (ServiceLoader by default); listed by `providers`. */
            harnesses: HarnessRegistry = loadHarnesses(),
            /** ROLE-FIRST S4: model listings for registered harnesses that have no provider of their own (Pi). */
            modelCatalogs: com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.builtIn(),
            /** BUG-CHANGES-BRANCH-2: the git the `project_git_*` frames read through; a test hands in a slow fake. */
            projectGitCli: com.superiorsoftllc.agenthq.worktree.GitCli = SystemGitCli(),
            /** PERF-3: the machine the executable resolver reads (PATH, file stats); a test hands in a slow fake. */
            executableEnvironment: com.superiorsoftllc.agenthq.executables.ExecutableEnvironment =
                com.superiorsoftllc.agenthq.executables.SystemExecutableEnvironment,
            /** BUG-G2: the `agenthq` CLI's bin dir, prepended to every spawned session's PATH; null = PATH untouched. */
            agentHqBin: File? = AgentHqBin.resolve(),
            /**
             * BUG-G2d: how many live sessions a session may grow its pod by, and
             * how deep spawns from inside sessions may nest. null (the real daemon) = the live settings `limits.concurrency.maxPerPod` / `maxDepth`; a test passes a fixed value. See [SpawnGuard].
             */
            podSpawnCap: Int? = null,
            podSpawnDepth: Int? = null,
            /** U1 (contract 1.59.0): the plan-limit meters; `AGENTHQ_LIMITS_LIVE=false` turns the live HTTP reads off. */
            limits: LimitService = LimitService.fromEnv(),
            /**
             * E1-a: the `stories.json` folded into the ticket board at startup ([StoryMigration]); null = no migration.
             * Only [fromEnv] (the real daemon) passes the state dir's file, so a test never migrates the operator's state.
             */
            storyMigrationFile: File? = null,
            /**
             * PORT-1: `AGENTHQ_PORT_STRICT=true` refuses to start on a taken [port] ([PortInUseException]) instead of
             * falling back to a free one. See [DaemonPort].
             */
            strictPort: Boolean = DaemonPort.strictFromEnv(),
            /**
             * PORT-1: where [start] records the port it bound (`<stateDir>/daemon.port`), removed again by [close].
             * Only [fromEnv] (the real daemon) passes the state dir's file, so a test never touches the operator's.
             */
            portFile: File? = null,
            /**
             * BUG-WT-1: the folder a session with no worktree runs in (the daemon's own directory by default). With
             * no [repo], a ticket start over a git checkout cuts its own worktree from that checkout instead, and a
             * start pinned to no worktree is refused there; tests pass a plain temp folder.
             */
            workingDir: File = File(System.getProperty("user.dir")),
            /**
             * INSTALL-CWD-1: `AGENTHQ_PROJECT_DIRS=true` (pinned by the installed app's InstallProfile) = with no [repo],
             * sessions and ticket starts run in their project's own directory and cut from its checkout, and a project
             * with no directory is refused; [workingDir] is then never used as a session's folder. Off = unchanged.
             */
            projectDirs: Boolean = com.superiorsoftllc.agenthq.session.ProjectFolders.enabledFromSettings(),
            /** IMG-5: "now" for upload mtimes and the chat image retention sweep; a test passes a fake clock. */
            clock: () -> Long = System::currentTimeMillis,
            /** IMG-5: runs the retention sweep every interval; a test passes one it fires by hand. */
            chatImageTicker: (Long, () -> Unit) -> AutoCloseable = com.superiorsoftllc.agenthq.images.ChatImageRetention::scheduledTicker,
            /**
             * INSTALL-REFOCUS-1: routes the embedding app adds to this server, behind the same [AccessPolicy] as every
             * other route. The daemon knows nothing about them (the installed desktop app adds its window activation).
             */
            extraRoutes: io.ktor.server.routing.Route.() -> Unit = {},
            /** SERVER-UPDATE-3: this daemon's own update state; null reads it from the environment (archive / Docker / dev). */
            updates: com.superiorsoftllc.agenthq.update.ServerUpdateService? = null,
            /** Contract 1.115.0 (ASSISTANT-1): where the global Assistant runs: a folder of its own under the app's data dir. */
            assistantDir: File = File(stateDir(), "assistant"),
            /** TICKET-HANDOVER: how long a switched ticket worker's old agent gets to write its handoff note. */
            handoffTimeoutMs: Long = SessionHandover.DEFAULT_TIMEOUT_MS,
            /** WEB-1 (contract 1.122.0): the browser client bundle served at `/` (none when it is not installed). */
            webBundle: WebBundle = WebBundle.load(WebBundle.locate()),
            /** TAB-SHOTS-DIFF: new session ids; the tab-shots renderer pins them so two renders are pixel-identical. */
            sessionIds: () -> String = { UUID.randomUUID().toString() },
        ): AgentHqDaemon {
            val policy = AccessPolicy.of(host, token)
            // PORT-1: FIRST, because the mail base and every session's AGENTHQ_URL below carry the port.
            // BUG-FLAKE-1: and HELD from here until start(), so no other socket on the host can take it meanwhile.
            val portReservation = DaemonPort.reserve(port, host, strictPort)
            val portChoice = portReservation.choice
            portChoice.note?.let { println("[agentHq] $it") }
            val boundPort = portChoice.port
            val mailBase = mailBaseUrl ?: "http://${AccessPolicy.agentReachableHost(host)}:$boundPort"
            val commandStore = com.superiorsoftllc.agenthq.commands.CommandStore(commandsFile, repo)
            val worktrees = GitWorktreeManager()
            // Every agent this daemon spawns receives the token as AGENTHQ_TOKEN, so the curl a delivered
            // prompt carries can authenticate, AND the daemon's agent-facing mail base as AGENTHQ_MAIL_BASE,
            // so a running agent can INITIATE a handoff to a colleague (its own session id is added per run
            // by SessionManager). Absent a token, only the mail base is added.
            val childEnv = buildMap {
                // BUG-G2: `agenthq knowledge` (P10-K9) must resolve inside every session, so the CLI's bin
                // dir goes first on the child's PATH (and is exported as AGENTHQ_BIN).
                putAll(AgentHqBin.childEnv(agentHqBin))
                // BUG-G2c: and the CLI must reach THIS daemon, not the default 8787 (AGENTHQ_URL; an inherited one wins).
                putAll(AgentHqBin.urlEnv(host, boundPort))
                if (token != null) put(AccessPolicy.TOKEN_ENV, token)
                put(AccessPolicy.MAIL_BASE_ENV, mailBase)
            }
            // An additional provider with no transport of its own is a CLI and needs a CLI runner. [runner]
            // is one whenever the default is a CLI; a default that brings its own transport (the stub) leaves
            // [runner] unable to spawn anything, so the configured runner is built for them -- only then.
            // WIRE-5: registry harnesses are CLIs too (HarnessProvider has no transport), so a stub default with any
            // harness registered needs the configured runner as well, or a harness-only spawn runs on the stub.
            val needsCliRunner = additionalProviders.any { it.transport() == null } || harnesses.harnessIds().isNotEmpty()
            val cliRunner = if (provider.transport() == null || !needsCliRunner) {
                runner
            } else {
                RunnerFactory.fromFlag()
            }
            // BUG-M2-11b-2: `session.keepAliveMinutes` decides, per launch, whether a session's process is kept (plan section 8).
            val settings = SettingsService(store)
            // CLIPATH-1: every launch (harness, session adapter, model listing, git) resolves its executable through
            // this one resolver, which reads the user's Settings overrides from the store.
            val executableOverrides = com.superiorsoftllc.agenthq.executables.StoredExecutableOverrides(store)
            val harnessToolsDir = File(daemonConfigDir(), "harness-tools")
            val executableResolver = com.superiorsoftllc.agenthq.executables.ExecutableResolver(executableEnvironment, executableOverrides, harnessToolsDir.path)
            val restoreExecutables = com.superiorsoftllc.agenthq.executables.CliExecutables.install(executableResolver)
            // HARNESS-INSTALL-1: npm installs of the harness CLIs go to <AGENTHQ_HOME>/harness-tools; launches resolve them from there.
            val harnessInstaller = com.superiorsoftllc.agenthq.harness.install.HarnessInstaller(
                harnessToolsDir, executableResolver, executableEnvironment, executableOverrides,
            )
            val cliLocations = com.superiorsoftllc.agenthq.executables.CliLocationService(
                executableResolver, executableOverrides, tools = { cliTools(harnesses) },
            )
            // BUG-PERM-1: every run resolves its Tool approval through this store-backed global value.
            val toolApproval = com.superiorsoftllc.agenthq.session.ToolApprovalService(store)
            val restoreToolApproval = com.superiorsoftllc.agenthq.session.ToolApprovals.install(toolApproval::stored)
            // BUG-REAP-OWNER: no id (unwritable dir) => nothing is ever reaped, which is the safe side.
            val instanceId = runCatching { InstanceId.loadOrCreate(com.superiorsoftllc.agenthq.session.stateDir()) }.getOrNull()
            val manager = SessionManager(
                provider,
                runner,
                sessionStore,
                worktrees = worktrees,
                childEnv = childEnv,
                additionalProviders = additionalProviders,
                cliRunner = cliRunner,
                harnesses = harnesses,
                instances = store.sessionInstances,
                lifecyclePolicy = { com.superiorsoftllc.agenthq.session.host.LifecyclePolicy(settings.keepAliveMinutes()) },
                defaultWorkingDir = workingDir,
                instanceId = instanceId,
                // Ticket 66153313: every worker run gets the build limits (priority, Gradle workers, forks) of the settings.
                buildEnv = { settings.buildLimits().sessionEnv(System.getenv("GRADLE_OPTS")) },
                // No-hard-coded-limits S2: the watchdog and silent sweeper read limits.watchdog.* live.
                limitSource = settings::limit,
                idFactory = sessionIds,
            )
            // BUG-M2-11f: a new keep-alive value applies to the processes already kept, not only to the next launch.
            settings.onKeepAliveChanged = manager::lifecyclePolicyChanged
            // Contract 1.4.0: profiles are validated against the providers [manager] was configured with.
            val agents = AgentProfiles(
                store.agents, manager, roleBindings = store.roleBindings, roleExists = { roles.role(it) != null },
                traitExists = { store.traits.get(it) != null }, roleTraits = store.roleTraits::get, trait = store.traits::get,
                requireKnownSkills = { ids -> com.superiorsoftllc.agenthq.settings.requireKnownSkills(store, ids, "skillIds") },
                // ROLE-EDIT-1: a role's (possibly edited) instructions reach every session started for it after the save.
                rolePersona = { id -> roles.role(id)?.persona.let { p -> if (id == AssistantService.ROLE) AssistantService.instructions(p) else p } },
            )
            // TRAITS-T4: the chokepoint's fallback, so a spawn path that resolves no instance itself still freezes one.
            manager.instanceFor = agents::instanceFor
            manager.assistantDir = assistantDir
            // PROGRESS-S5: the opt-in estimated mode's per-project label durations outlive a restart.
            manager.progressEstimates = com.superiorsoftllc.agenthq.session.FileProgressEstimates(
                File(com.superiorsoftllc.agenthq.session.stateDir(), com.superiorsoftllc.agenthq.session.FileProgressEstimates.FILE_NAME),
            )
            val clients = CopyOnWriteArrayList<Channel<Envelope>>()
            // ROGUE-1 (contract 1.120.0): the rogue process detector. The ledger records only what a session tree started
            // (pid + start instant, owned by this state dir); a slow timer feeds it and warns under memory pressure.
            val processWatch = com.superiorsoftllc.agenthq.session.ProcessWatch(
                com.superiorsoftllc.agenthq.session.ProcessLedger(
                    com.superiorsoftllc.agenthq.session.OsProcessSource,
                    com.superiorsoftllc.agenthq.session.host.OsProcessTable,
                    File(com.superiorsoftllc.agenthq.session.stateDir(), com.superiorsoftllc.agenthq.session.ProcessLedger.FILE_NAME),
                    instanceId,
                    isProtected = com.superiorsoftllc.agenthq.session.ProcessWatch::isProtected,
                ),
                manager::list,
                com.superiorsoftllc.agenthq.session.OsMemorySource,
                com.superiorsoftllc.agenthq.session.host.OsProcessTable,
                config = settings::processWatchConfig,
                cpuAlert = settings::cpuAlert,
                onPressure = { report ->
                    val envelope = Envelope(
                        Envelope.PROCESS_PRESSURE,
                        json.encodeToString(com.superiorsoftllc.agenthq.session.ProcessReport.serializer(), report),
                    )
                    println("[agentHq] memory pressure: ${report.freeMb} MB free (floor ${report.floorMb}), ${report.rogue.size} rogue process(es)")
                    for (c in clients.toList()) c.trySend(envelope)
                },
            )
            val processWatchExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "agenthq-process-watch").also { it.isDaemon = true }
            }
            // Ticket 3b30398d: shut down by close() (it used to outlive every daemon); the interval is read before each pass.
            val processWatchScheduler = AutoCloseable {
                processWatchExec.shutdownNow()
                runCatching { processWatchExec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
            }
            val watchIntervalMs = { settings.limit(com.superiorsoftllc.agenthq.settings.LimitRegistry.PROCESS_WATCH_INTERVAL_SECONDS) * 1000L }
            fun scheduleWatchPass() {
                runCatching {
                    processWatchExec.schedule(
                        {
                            runCatching { processWatch.tick() }.onFailure { System.err.println("[agentHq] process watch failed: ${it.message}") }
                            if (!processWatchExec.isShutdown) scheduleWatchPass()
                        },
                        watchIntervalMs(), java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                } // RejectedExecutionException once close() shut it down: nothing more to schedule.
            }
            scheduleWatchPass()
            // Ticket b785f383: a closing session's background processes die with it, after a grace for its own exit
            // handlers; deterministic and separate from the opt-in auto reap. The first pass catches up after a crash.
            manager.onSessionClosed = { c ->
                // After close() the scheduler refuses; the shutdown reap (reapEnded) already covered this session.
                runCatching {
                    processWatchExec.schedule(
                        {
                            runCatching { processWatch.reapSession(c.sessionId, c.at, c.keepPids, c.reason) }
                                .onFailure { System.err.println("[agentHq] close cleanup of ${c.sessionId} failed: ${it.message}") }
                        },
                        SESSION_CLOSE_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }
            }
            processWatchExec.schedule(
                {
                    runCatching { processWatch.reapEnded("daemon start") }
                        .onFailure { System.err.println("[agentHq] start-up cleanup failed: ${it.message}") }
                },
                watchIntervalMs(), java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            // PROMPT-1: `prompts.timeoutSec` is read at every open; every request / resolution goes to every client.
            val pendingPrompts = com.superiorsoftllc.agenthq.prompts.PendingPrompts(
                timeoutMillis = { settings.promptTimeoutSec() * 1000L },
                // PROMPT-6: "Always allow" rules live in the settings table (`toolApproval.rules`), scoped by project.
                rules = com.superiorsoftllc.agenthq.prompts.ApprovalRules.forStore(store),
                projectOf = { id -> manager.get(id)?.projectId },
            )
            pendingPrompts.sink = { envelope -> for (c in clients.toList()) c.trySend(envelope) }
            // PROMPT-6: a rule-answered call is one `[agenthq]` stderr line in that session's transcript (UI-BOOT-1's
            // convention), through the session stream so it reaches every client like any other session line.
            pendingPrompts.notice = { sessionId, text ->
                manager.eventSink?.invoke(com.superiorsoftllc.agenthq.session.SessionEvent.Stderr(sessionId, "[agenthq] $text"))
            }
            // PROMPT-3: a session adapter (Claude in `ask`) relays its CLI's approval / question requests here.
            // PROMPT-4: harness adapters (opencode serve) relay their tool approvals here.
            manager.promptAsker = pendingPrompts
            // IN-APP BUILD T3: one saved command at a time; every frame it produces goes to every client.
            // BUG-RUN-STUB-1: a saved command is a real process whatever the provider; [runner] is the stub's
            // ScriptedRunner under AGENTHQ_PROVIDER=stub and would only replay the script.
            // RUN-CONSOLE (1.134.0): the run gets a pty (pipes if pty4j cannot load here); its scrollback and the run
            // history follow `limits.run.*`; the demo lock refuses interactive runs and run_input (no raw shell).
            val runManager = com.superiorsoftllc.agenthq.commands.RunManager(
                { commandStore.registry }, com.superiorsoftllc.agenthq.runner.PipedProcessRunner(),
                emit = { env -> for (c in clients.toList()) c.trySend(env) },
                terminal = com.superiorsoftllc.agenthq.runner.Pty4jTerminalRunner(),
                limits = com.superiorsoftllc.agenthq.commands.RunManager.RunLimits(
                    scrollbackBytes = { settings.limitCap(LimitRegistry.RUN_SCROLLBACK_BYTES) },
                    history = { settings.limitCap(LimitRegistry.RUN_HISTORY) },
                ),
                demoLock = { com.superiorsoftllc.agenthq.plugin.host.PluginFolders.demoLock() },
            )
            val attention = AttentionStore(store.attention)
            attention.eventSink = { item -> broadcastAttention(clients, item) }
            // Ticket 66153313: agent processes held the CPU above `limits.cpu.alertPercent` for `limits.cpu.alertMinutes`.
            processWatch.onCpuSustained = { report ->
                val top = report.tracked.filter { it.cpuPercent != null }.sortedByDescending { it.cpuPercent }.take(3)
                    .joinToString("; ") { "pid ${it.pid} ${it.cpuPercent}% ${it.command.take(60)}" }
                val detail = "Agent processes have used ${report.agentCpuPercent}% of all CPU cores for ${report.cpuAlertMinutes}+ min " +
                    "(threshold ${report.cpuAlertPercent}%; settings limits.cpu.alertPercent / limits.cpu.alertMinutes, 0 min = off). " +
                    "Top: ${top.ifEmpty { "none sampled" }}"
                println("[agentHq] sustained CPU: $detail")
                runCatching { attention.raise(com.superiorsoftllc.agenthq.attention.AttentionKind.CEILING, outcome = "cpu_sustained", detail = detail) }
                    .onFailure { System.err.println("[agentHq] cpu attention not raised: ${it.message}") }
            }
            // Story 9.7b: observed limit state per provider -- persisted, pushed, and put back after a restart.
            val providerStates = ProviderStateTracker(
                sink = { state ->
                    runCatching { store.providerStates.upsert(state) }
                        .onFailure { System.err.println("[agentHq] provider state not stored: ${it.message}") }
                    broadcastProviderState(clients, state)
                },
            )
            providerStates.restore(store.providerStates.all())
            // Story 9.7b: today's budget is recounted from the runs this daemon stored, so a restart does not reset it.
            val dayStart = System.currentTimeMillis() / 86_400_000L * 86_400_000L
            store.usage.since(dayStart).groupBy { it.provider }.forEach { (providerId, runs) ->
                budget.restore(providerId, runs.size, runs.sumOf { r -> r.usage?.let { it.input + it.output + it.reasoning } ?: 0L })
            }
            val launchGate = LaunchGate(providerStates, budget, attention)
            manager.launchGate = { providerId, origin, ticketId -> launchGate.check(providerId, origin, ticketId = ticketId) }
            manager.silentSink = { session, minutes ->
                attention.raise(
                    kind = AttentionKind.STALLED,
                    ticketId = session.ticketId,
                    podId = null,
                    sessionId = session.id,
                    outcome = "silent",
                    detail = "Session is BUSY but has had no output for $minutes min. Not stopped: its process looks alive.",
                )
            }
            manager.watchdogSink = { session, expiry ->
                // A stalled run is reported as STALLED for its provider: the stream cannot tell it from
                // free-quota exhaustion, so it is never labelled exhausted (spec 6.3).
                if (expiry == com.superiorsoftllc.agenthq.session.WatchdogExpiry.STALLED) providerStates.markStalled(session.provider)
                val podId = store.runs.bySession(session.id).firstOrNull()?.podId
                val kind = when (expiry) {
                    com.superiorsoftllc.agenthq.session.WatchdogExpiry.DEADLINE -> AttentionKind.DEADLINE
                    com.superiorsoftllc.agenthq.session.WatchdogExpiry.STALLED -> AttentionKind.STALLED
                }
                val label = expiry.name.lowercase()
                attention.raise(
                    kind = kind,
                    ticketId = session.ticketId,
                    podId = podId,
                    sessionId = session.id,
                    outcome = label,
                    detail = "Unattended run $label after ${if (expiry == com.superiorsoftllc.agenthq.session.WatchdogExpiry.DEADLINE) "deadline" else "no output"}; stopped without retry.",
                )
            }
            // MVP Sprint-Zero, I0: the two slices meet HERE and only here. Slice A owns the project,
            // slice B owns the story, and a story names its project by id -- that join alone, with
            // no ordered list kept on the project side any more (RETIRED, contract 1.29.0: a story's
            // order used to also append onto `Project.storyIds` here; it now lives entirely on
            // `Epic.storyIds`, appended below when the create named an epic).
            val projectStore = ProjectStore(persistence = SqlProjectPersistence((store as SqlAgentHqStore).database))
            projectStore.laneDefault = { settings.limitCap(LimitRegistry.LANE_DEFAULT) }
            // EXPERIMENTS-1: the pi tool bridge (off unless the project setting or the env override says on). Removing it = this line.
            com.superiorsoftllc.agenthq.experiments.pitoolbridge.PiToolBridgeRegistration.register({ projectStore.experimentOn(it, "piToolBridge") }, sessionStats)
            // TODO-PROGRESS-1: every pi session loads the daemon's `todowrite` extension (pi has no to-do tool of its own).
            com.superiorsoftllc.agenthq.harness.pi.PiLaunchExtras.builtIn =
                com.superiorsoftllc.agenthq.harness.pi.PiTodoExtension.launchArgs(File(stateDir(), "pi"))
            // BASE-1a: `project_base` checks a branch in the repo ticket starts cut in -- the board's, read at answer
            // time through [boardRef] (built further down) -- with the same resolver a cut uses.
            var boardRef: TicketBoard? = null
            val baseCheck = com.superiorsoftllc.agenthq.mvp.ProjectBaseCheck(
                com.superiorsoftllc.agenthq.worktree.TicketBranches(),
                cutRepo = { project -> boardRef?.cutRepo(project.id) },
            )
            val projects = ProjectHandler(
                projectStore,
                baseCheck = baseCheck,
                onConfigChanged = { project ->
                    val frame = Envelope(Envelope.PROJECT, json.encodeToString(com.superiorsoftllc.agenthq.protocol.Project.serializer(), project))
                    for (client in clients) client.trySend(frame)
                    boardRef?.laneCapsChanged(project.id)
                },
                // ROUTING-1 (contract 1.128.0): a rule list is checked with the one executor check a profile save uses
                // (provider resolves, model listed by its harness, effort known) and the role catalog.
                routingValidator = { rules ->
                    com.superiorsoftllc.agenthq.backlog.RoutingRules.validate(
                        rules,
                        checkExecutor = { provider, model, effort -> agents.validateExecutor(provider, model, effort) },
                        roleKnown = { roles.role(it) != null },
                    )
                },
            )
            // INSTALL-CWD-1: the installed app runs each project's work in that project's own directory.
            if (projectDirs && repo == null) {
                manager.projectFolders = com.superiorsoftllc.agenthq.session.ProjectFolders(
                    directoryOf = { id -> runCatching { projectStore.get(id).directory }.getOrNull() },
                    defaultProjectId = { projectStore.list().firstOrNull()?.id },
                )
            }
            // SESSION-CWD-1: the dev daemon too runs a session that names a project in that project's directory.
            if (manager.projectFolders == null) {
                manager.namedProjectFolders = com.superiorsoftllc.agenthq.session.ProjectFolders(
                    directoryOf = { id -> runCatching { projectStore.get(id).directory }.getOrNull() },
                )
            }
            // CP-2: git over each project's own directory (`Project.directory`), read through the same
            // `git` CLI seam the worktree manager uses; the store answers which directory that is.
            val projectGit = ProjectGitHandler(
                projectStore, ProjectGitService(projectGitCli),
                push = { envelope -> for (client in clients) client.trySend(envelope) },
                // UX-CHANGES-1: a session's checkout is the directory it runs in (its worktree, or the shared one).
                sessionCheckout = { id -> manager.get(id)?.workingDir },
            )
            // EPIC-3: epics over the same database file as everything else, so an epic and its ordered
            // storyIds are read back after a restart. Wired the way projects are (their durable surface
            // over the daemon's own handle, here and only here).
            // E1-c: an epic's storyIds are DERIVED from the ticket board (the tickets filed under it, in board
            // order), read through [boardRef] at answer time -- the board is built further down.
            val epics = EpicStore(
                persistence = SqlEpicPersistence((store as SqlAgentHqStore).database),
                projects = projectStore,
                ticketIdsByEpic = {
                    boardRef?.list().orEmpty().filter { it.epicId != null }.groupBy({ it.epicId!! }, { it.id })
                },
            )
            // PW-3: workspaces live inside a project and are checked against the same project store, so a
            // workspace written before a restart is listed after one, under the project it was saved in.
            val workspaces = WorkspaceStore(
                projects = projectStore,
                persistence = SqlWorkspacePersistence((store as SqlAgentHqStore).database),
            )
            // MEM-2: project-memory notes, over the same database file as everything else, so a note
            // saved before a restart is read back (index and body) after one. Wired the way projects are:
            // constructed over the daemon's own handle, here and only here.
            val memoryNotes = MemoryStore(
                persistence = SqlMemoryPersistence((store as SqlAgentHqStore).database),
            )
            // NOTES-1: a session's notes die with it -- killed or removed, its session-scope notes are purged.
            manager.onSessionEnded = { sessionId -> memoryNotes.purgeSession(sessionId) }
            // P10-K4: the decisions log, over the same file. A decision_add's author comes from the declared session.
            val decisions = DecisionStore(store as SqlAgentHqStore)
            val decisionFrames = DecisionHandler(
                store = decisions,
                author = { sessionId ->
                    manager.get(sessionId)?.let { DecisionAuthor(sessionId = it.id, agentId = it.agentId, role = it.role) }
                },
                push = { envelope -> for (client in clients) client.trySend(envelope) },
            )
            // PIPE-1: the optional project pipeline, over the same file.
            val pipelineFrames = PipelineHandler(
                store = PipelineStore(store as SqlAgentHqStore),
                author = { sessionId -> manager.get(sessionId)?.let { "${it.role ?: "agent"} (${it.agentId})" } },
                push = { envelope -> for (client in clients) client.trySend(envelope) },
            )
            // VOICE-1: spoken attention alerts, pushed to every client; nothing is stored.
            val notifyFrames = com.superiorsoftllc.agenthq.notify.NotifyHandler(
                projectName = { id -> projectStore.get(id).name },
                push = { envelope -> for (client in clients) client.trySend(envelope) },
            )
            // SECRETS-1: the daemon-owned keystore, in the state dir.
            val keystoreFrames = KeystoreHandler(
                keystore = Keystore(stateDir()),
                push = { envelope -> for (client in clients) client.trySend(envelope) },
            )
            // P10-K6: the issues log, over the same file. The declared session is the author AND the scope of the active
            // issue an attempt or a fix without an issue ref goes to.
            val issues = IssueStore(store as SqlAgentHqStore)
            // P10-K9: the knowledge section's counts, read for a project whenever a first turn renders.
            // BUG-G2e: and the digest itself (what `agenthq knowledge` prints), built in-process from the same stores.
            val knowledge = object : KnowledgeCounter {
              override fun roleNotes(projectId: String, role: String) =
                  memoryNotes.index(projectId, com.superiorsoftllc.agenthq.memory.SCOPE_ROLE, role).map { it.name to it.description }
              override fun counts(projectId: String): KnowledgeCounts? {
                val notes = memoryNotes.index(projectId).map { MemoryIndexEntry(it.name, it.description, it.folder) }
                val active = decisions.list(projectId)
                val open = issues.list(projectId)
                return KnowledgeCounts(
                    notes = notes.size,
                    activeDecisions = active.size,
                    openIssues = open.size,
                    digest = KnowledgeLines.digest(notes, active, open),
                )
              }
            }
            val issueFrames = IssueHandler(
                store = issues,
                actor = { sessionId -> manager.get(sessionId)?.let { IssueActor(sessionId = it.id, agentId = it.agentId) } },
                push = { envelope -> for (client in clients) client.trySend(envelope) },
            )
            // Contract 1.5.0: the backlog. It belongs to the daemon, not to a connection: a change one client
            // makes is pushed to every client, the one that asked included.
            // P10-K15: the skills section's library and project switch, read whenever a first turn renders.
            val skillsSource = com.superiorsoftllc.agenthq.agents.SkillsSource(
                library = { com.superiorsoftllc.agenthq.persistence.SkillStore(store.skills).list(null) },
                includeSkillsContext = { settings.includeSkillsContext() },
            )
            val ticketContext = context ?: TicketContextComposer(
                settings, knowledge, skillsSource,
                roleNotes = com.superiorsoftllc.agenthq.context.RoleNotesSource { project, role ->
                    memoryNotes.index(project, com.superiorsoftllc.agenthq.memory.SCOPE_ROLE, role).map { it.name to it.description }
                },
            )
            // P10-K13: the effective skill ids each bound spawn freezes on its instance (K-D9 / K-D11), read once, at spawn.
            manager.skillsFor = { agentId, ticketId ->
                com.superiorsoftllc.agenthq.agents.SkillAttachment.effective(
                    agent = agentId?.let(agents::get),
                    project = com.superiorsoftllc.agenthq.agents.SkillAttachment.Project(settings.skillIds(), settings.includeSkillsContext()),
                    ticket = ticketId?.let(store.tickets::get),
                )
            }
            // USAGE-PACE (1.119.0 read, 1.121.0 manual / override / rule): plan meters -> allowance of ticket workers.
            val usagePacing = UsagePacing(
                limits = limits::cachedOrRefresh,
                rule = settings::usagePacing,
                manual = settings::usageManual,
                overrides = settings::usageOverrides,
                workerMeters = {
                    store.tickets.all()
                        .filter { it.status == com.superiorsoftllc.agenthq.protocol.TicketStatus.IN_PROGRESS }
                        .mapNotNull { row -> row.sessionId?.takeIf { it.isNotBlank() }?.let(manager::get) }
                        .filter { it.state !in DEAD_SESSION_STATES }
                        .map { r -> UsageMeters.meterFor(r.provider, r.model ?: manager.providerFor(r.provider)?.defaultModel()) }
                },
            )
            // USAGE-AUTO (1.124.0): every usage.refreshMinutes (default 5, 0 = off) a live read, pushed to every client.
            val usageRefresher = com.superiorsoftllc.agenthq.usage.UsageRefresher(
                read = { limits.snapshot(force = true) },
                minutes = settings::usageRefreshMinutes,
                onRefreshed = {
                    val frame = Envelope(Envelope.USAGE_REPORT, json.encodeToString(UsageReport.serializer(), usagePacing.report()))
                    for (c in clients.toList()) c.trySend(frame)
                },
            ).also { it.start() }
            val board = TicketBoard(
                store, context = ticketContext, agents = agents, sessions = manager, repo = repo,
                // BASE-1c: the ticket's epic's base branch, then its project's (BASE-1a), then the daemon's
                // (AGENTHQ_BASE_BRANCH, else none: the start is refused).
                baseRef = TicketBaseRef.epicFirst(epics::baseBranchOf, TicketBaseRef.projectFirst(projectStore::baseBranchOf, baseRef)),
                knowledge = knowledge, skills = skillsSource,
                exitBehavior = settings::ticketExitBehavior,
                autoDoneMinOutputs = { settings.limit(LimitRegistry.AUTO_DONE_MIN_OUTPUTS) },
                weightExecutors = settings::weightExecutors,
                // USAGE-PACE (1.121.0): an agent's / the daemon's ticket start beyond the plan allowance is refused.
                pacing = { providerId, model ->
                    val pid = providerId ?: manager.providers.firstOrNull()?.id ?: UsageMeters.CLAUDE
                    usagePacing.check(UsageMeters.meterFor(pid, model ?: manager.providerFor(pid)?.defaultModel()))
                },
                laneCap = projectStore::laneCapOf,
                // ROUTING-1: the project's routing rules pick a start's executor; a refused rule parks the ticket and is raised.
                routingRules = projectStore::routingRulesOf,
                routingRefused = { ticketId, _, detail ->
                    attention.raise(kind = AttentionKind.LAUNCH_BLOCKED, ticketId = ticketId, outcome = "routing_refused", detail = detail)
                },
                // NOGIT-1: a project's own directory decides where its start runs / cuts, also with project folders off.
                projectDirectory = { id -> id?.let { runCatching { projectStore.get(it).directory }.getOrNull() } },
                // BUG-ASSIGN-1: a refused role lists the catalog's ids (custom roles included; the fallback persona not).
                roleIds = { roles.all().map { it.id }.filter { it != RoleIds.CUSTOM } },
                // IMG-3: a ticket's images live under its project's directory (.agenthq/uploads/ticket/).
                images = com.superiorsoftllc.agenthq.backlog.TicketImages { projectId ->
                    runCatching { projectStore.get(projectId) }.getOrNull()
                        ?.directory?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
                },
            )
            board.eventSink = { event -> broadcastBoard(clients, event) }
            boardRef = board
            // TICKET-HANDOVER: a ticket worker switched to another harness hands off, then resumes with its ticket brief.
            val handover = SessionHandover(manager, board::brief, handoffTimeoutMs)
            // GAP-006b: a first turn of a worker whose brief never landed carries it; the sweep enforces the in-progress invariant.
            manager.briefFor = board::brief
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "agenthq-ticket-worker-sweep").also { it.isDaemon = true }
            }.scheduleWithFixedDelay({
                runCatching {
                    val graceMs = LimitRegistry.minutesMs(settings.limit(LimitRegistry.UNBRIEFED_GRACE_MINUTES), LimitRegistry.UNBRIEFED_GRACE_MINUTES)
                    board.sweepWorkers(graceMs) { ticketId, sessionId, detail ->
                        println("[agentHq] ticket worker sweep: $ticketId: $detail")
                        attention.raise(kind = AttentionKind.STALLED, ticketId = ticketId, podId = null, sessionId = sessionId, outcome = "unbriefed", detail = detail)
                    }
                }.onFailure { System.err.println("[agentHq] ticket worker sweep failed: ${it.message}") }
            }, 60L, 60L, java.util.concurrent.TimeUnit.SECONDS)
            // E1-c (contract 1.62.0): the story frames are deprecated shims over the ticket board. An old story id
            // resolves through E1-a's `story_migration`; a create that names an epic pushes that epic's frame (its
            // storyIds now list the new ticket) to every client, exactly the frame an `epic_save` answers with.
            val migrated = (store as SqlAgentHqStore).database.story_migrationQueries
            val stories = StoryHandler(
                board = board,
                resolve = { storyId -> migrated.ticketFor(storyId).executeAsOneOrNull() },
                onEpicChanged = { epicId -> epics.get(epicId)?.let { broadcastEpic(clients, it) } },
            )
            val storyBoard = BoardHandler(stories = { board.list().map { it.asStory() } })
            // Phase 6: the mailbox. Messages are persisted BEFORE any delivery attempt, so a message
            // to an offline agent is never lost, and the router hands queued messages to a session
            // whenever that session comes to rest (see MailRouter's delivery policy). Built before the
            // triggers (TRIGGER-NUDGE): a schedule's catch-up fire on boot mails through it.
            val mailbox = Mailbox(mailStore)
            // BUG-S3: mail to an agent swapped onto another harness opens a fresh session there (4.2, lazily).
            val bindFreshSession = { agentId: String ->
                bindSessionOnSpawn(manager, agents, SpawnOptions(agentId = agentId, projectId = agents.get(agentId)?.projectId), repo)
            }
            val mail = MailRouter(
                manager, mailbox, agents, mailBase,
                agentsSendToken = token != null, bindFreshSession = bindFreshSession,
                knowledge = knowledge, skills = skillsSource, starterOf = board::starterOf,
            )
            // Story 9.5: triggers -- stored, coalesced, tripped on any non-success, their starts unattended.
            // TRIGGER-NUDGE (contract 1.105.0): a `prompt` action is mail to the target in the trigger's project.
            val triggerNudges = TriggerNudges(
                sessions = manager::list,
                send = mail::send,
                startSession = { agentId, prompt, projectId -> board.startPromptUnattended(agentId, prompt, projectId) },
            )
            val triggers = TriggerService(
                repo = store.triggers,
                attention = attention,
                startNext = { filter -> board.startNextUnattended(filter) },
                startPrompt = { agentId, prompt -> board.startPromptUnattended(agentId, prompt) },
                sessions = manager::get,
                deliverPrompt = triggerNudges::deliver,
                mailPending = { mailId -> mailbox.message(mailId)?.state == MessageState.QUEUED },
                autoStart = false, // started below, after the mailbox and the sessions are loaded
            )
            triggers.eventSink = { view -> broadcastTrigger(clients, view) }
            // H12b: a committed move into done reaches the ship triggers, after the board commit has released.
            // BASICS-KILL-1 (contract 1.118.0): a project with autoRemoveDoneWorkers ends and removes a done ticket's worker.
            val doneWorkerReaper = DoneWorkerReaper(
                manager = manager,
                enabled = projectStore::autoRemovesDoneWorkers,
                ticketOf = { ticketId -> runCatching { board.get(ticketId) }.getOrNull()?.let { it.sessionId to it.projectId } },
                executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "agenthq-done-worker-reaper").also { it.isDaemon = true } },
                onRemoved = {
                    val pushed = Envelope(Envelope.SESSIONS, json.encodeToString(JsonArray(manager.list().map { json.encodeToJsonElement(SessionRecordSerializer, it) })))
                    for (client in clients) client.trySend(pushed)
                },
            )
            board.transitionSink = { ticketId, status ->
                triggers.onTicketTransition(ticketId, status)
                doneWorkerReaper.onTicketTransition(ticketId, status)
            }
            // E1-a: fold the story board into the ticket board, once; idempotent, resumes after a crash. A failure is
            // logged and the daemon still starts (the next start retries the stories not yet mapped).
            storyMigrationFile?.let { file ->
                runCatching { StoryMigration(file, board, (store as SqlAgentHqStore).database).run() }
                    .onFailure { System.err.println("[agentHq] story migration failed, will retry next start: $it") }
            }
            val clearedQueueMarks = board.clearQueueMarks()
            println("[agentHq] backlog queue marks cleared=$clearedQueueMarks (nothing starts on boot)")

            // ASK-1 (contract 1.97.0): non-blocking owner questions, stored, pushed to every client, answered by mail.
            val ownerQuestions = com.superiorsoftllc.agenthq.questions.OwnerQuestions(
                repository = com.superiorsoftllc.agenthq.questions.OwnerQuestionRepository.forStore(store),
                send = mail::send,
                clock = clock,
                maxOpen = settings::questionsMaxOpenPerAgent,
                maxPerHour = settings::questionsMaxPerHour,
            )
            ownerQuestions.sink = { question ->
                val frame = Envelope(Envelope.QUESTION, json.encodeToString(com.superiorsoftllc.agenthq.protocol.OwnerQuestion.serializer(), question))
                for (c in clients.toList()) c.trySend(frame)
            }
            val streamSequencer = SessionFrameSequencer()
            // Contract 1.95.0 (1c24fdc4): each session's harness-neutral run activity, carried on `status` frames.
            val runActivity = com.superiorsoftllc.agenthq.protocol.RunActivityTracker()
            // Story 9.7b: a run's usage is recorded when it ends and published on its session's stream.
            val usageRecorder = UsageRecorder(
                tracker = providerStates,
                budget = budget,
                sessions = manager::get,
                defaultModel = { providerId -> manager.providers.firstOrNull { it.id == providerId }?.defaultModel() },
                persist = { store.usage.insert(it) },
                publish = { sessionId, usage ->
                    streamSequencer.publish(clients, listOf(Envelope(Envelope.USAGE, json.encodeToString(RunUsage.serializer(), usage), sessionId)))
                    // U1: the limit meters follow each run's usage, read off this thread (at most once per 5 min).
                    limits.snapshotAsync { snapshot -> for (c in clients.toList()) c.trySend(limitsEnvelope(snapshot)) }
                },
            )

            // Phase 5b: the orchestrator Phase 5 built. Constructed here, over the daemon's own store
            // and session manager, and wired into the event sink below -- until this ticket it was a
            // library nothing outside the daemon could reach.
            // BUG-G2b: a pod node's first attempt is composed like `prompt new` (knowledge, then skills) for the pod's
            // project. A pod session is anonymous (no agent, no ticket), so its skills are the project's attachment.
            val orchestrator = Orchestrator(
                store, manager,
                firstTurn = PodFirstTurn { projectId, prompt ->
                    val anonymous = manager.withSkills(RoleInstance(agentId = null, role = null, instructions = null), null)
                    firstTurn(anonymous, null, null, prompt, knowledge.section(projectId), skillsSource.section(anonymous))
                },
            )
            orchestrator.schedulerStallSink = { podId, detail ->
                attention.raise(
                    kind = AttentionKind.STALLED,
                    podId = podId,
                    outcome = "scheduler_stalled",
                    detail = "$detail; pod remains stopped for operator review.",
                )
            }
            // BUG-G2d: every spawn that names the session it comes from is charged to that session's pod.
            val spawnGuard = SpawnGuard(
                sessions = manager::get,
                maxPerPod = podSpawnCap ?: SpawnGuard.DEFAULT_MAX_PER_POD, maxDepth = podSpawnDepth ?: SpawnGuard.DEFAULT_MAX_DEPTH,
                maxPerPodSource = if (podSpawnCap == null) ({ settings.limitCap(LimitRegistry.MAX_PER_POD) }) else null,
                maxDepthSource = if (podSpawnDepth == null) ({ settings.limitCap(LimitRegistry.MAX_DEPTH) }) else null,
                pendingOf = orchestrator::pendingNodes,
            )
            orchestrator.sessionStartedSink = { _, sessionId, parent -> spawnGuard.record(sessionId, parent); manager.setSpawner(sessionId, parent) }
            settings.limitEnvProblems().forEach { println("[agentHq] limit ignored: $it") }
            println("[agentHq] pod spawn cap=${podSpawnCap ?: settings.limit(LimitRegistry.MAX_PER_POD)} depth=${podSpawnDepth ?: settings.limit(LimitRegistry.MAX_DEPTH)} (cap 0 = unlimited; settings limits.concurrency.*; session-originated spawns only)")

            mailbox.load()
            mailbox.eventSink = { event -> broadcastMail(clients, event, attention) }
            // TRAITS-T6: REPORT BACK -- an unattended clone's run that ends by itself without its report mail is re-prompted
            // once by `system`, then raises one `unreported` attention item.
            val reportBack = com.superiorsoftllc.agenthq.mailbox.ReportBackMonitor(
                sessions = manager, mailbox = mailbox, send = mail::send, attention = attention,
                agentRole = { id -> agents.get(id)?.role },
                ticketOpen = { id -> store.transaction { store.tickets.get(id) }?.let { it.status != com.superiorsoftllc.agenthq.protocol.TicketStatus.DONE } == true },
            )
            // Tells a ticket's starter when its worker's run ends, so a silent or crashed worker never stalls the pipeline.
            // Ticket ef3356ae: a clean end of turn is waiting, not failing; one calm note after limits.watchdog.idleNoticeMinutes.
            val finishNotice = com.superiorsoftllc.agenthq.mailbox.FinishNoticeMonitor(
                sessions = manager, mailbox = mailbox, send = mail::send,
                ticketOf = { id -> store.transaction { store.tickets.get(id) } },
                idleNoticeMs = { LimitRegistry.minutesMs(settings.limit(LimitRegistry.IDLE_NOTICE_MINUTES), LimitRegistry.IDLE_NOTICE_MINUTES) },
            ).also { it.start() }
            // PIPELINE-WATCHDOG: derives per-agent pending/stalled/quiet state, nudges, then tells the PM and the owner.
            val agentWatchdog = com.superiorsoftllc.agenthq.mailbox.AgentWatchdog(
                sessions = manager, mailbox = mailbox, send = mail::send, attention = attention,
                config = com.superiorsoftllc.agenthq.session.WatchdogConfig.fromEnv(),
                isEnabledFor = { projectStore.experimentOn(it, "agentWatchdog") },
            ).also { it.start() }
            manager.eventSink = { event ->
                broadcast(clients, event, streamSequencer, runActivity, { id, seconds ->
                    // A `sleep N` in a tool call becomes a "waiting" countdown; the call returning (or the agent moving on) ends it.
                    if (seconds != null) manager.setWait(id, com.superiorsoftllc.agenthq.session.WaitHint(seconds, System.currentTimeMillis())) else manager.clearWait(id)
                }, { id, items -> manager.setTodoProgress(id, items) }) { id -> manager.toolApprovalFor(id).mode }
                // PROMPT-1: a turn's end or a terminal session resolves its open prompts (deny / no answer).
                pendingPrompts.onSessionEvent(event)
                mail.onSessionEvent(event)
                // THIRD ARM, and its position is load-bearing. The orchestrator schedules against the
                // session state it believes, so it must observe the same events that were broadcast
                // and mailed -- after broadcast, never before, or it would schedule on a snapshot the
                // clients have not been told about yet.
                orchestrator.onSessionEvent(event)
                // Story 9.7b: usage BEFORE the backlog, so a run that spends the budget is counted before its
                // exit drains a queue -- the drained (unattended) start is judged against the spent budget.
                usageRecorder.onSessionEvent(event)
                // FOURTH ARM: ticket outcomes are durable, then a freed profile lane is drained.
                board.onSessionEvent(event)
                // Story 9.5: a trigger's run ends with its session's run.
                triggers.onSessionEvent(event)
                // TRAITS-T6: LAST, so the board and the triggers have recorded the run's end before a re-prompt mail
                // can start the session's next run.
                reportBack.onSessionEvent(event)
                finishNotice.onSessionEvent(event)
                // BASICS-KILL-1: after everything else has seen the turn's end; the reap itself runs on its own thread.
                doneWorkerReaper.onSessionEvent(event)
            }

            println("[agentHq] provider=${provider.id} runner=${runner.id} stateDir=${stateDir()}")
            println(
                if (additionalProviders.isEmpty()) {
                    "[agentHq] providers: ${provider.id} only (set ${ProviderFactory.PROVIDERS_ENV} to let a spawn choose another)"
                } else {
                    "[agentHq] providers: ${manager.providers.joinToString { it.id }} (default ${provider.id})"
                },
            )
            println("[agentHq] harnesses: ${harnesses.harnessIds().ifEmpty { listOf("<none>") }.joinToString()}")
            println("[agentHq] contract=agenthq/${EventVocabulary.VERSION} (see CONTRACT.md)")
            println("[agentHq] reconcile: ${manager.loadAndReconcile()}")
            worktrees.reconcile(manager.list().mapNotNull { it.toWorktreeRef() })

            // Phase 5b -- CRASH RECOVERY, in this order and explicitly, before the port opens.
            //
            // 1. `recoverAfterCrash` closes every RUN that was still in flight when the previous daemon
            //    died, as ORPHANED with a terminal `finished(ORPHANED)` appended to its log.
            // 2. `Orchestrator.recover` then reconciles the PODS those runs belonged to: a RUNNING
            //    node FAILED, a PENDING one SKIPPED, and the pod itself INTERRUPTED.
            //
            // Crash recovery FIRST, deliberately. A pod interrupted mid-flight must have its runs
            // reconciled before the orchestrator decides what to do with the pod that owns them,
            // otherwise the pod would be judged against runs that still claim to be running.
            //
            // This is the reviewed change the earlier ruling asked for, so it is spelled out at the
            // call site rather than hidden in a helper: opening a SQLite file during production
            // startup is a real, deliberate act and its ordering is part of the contract.
            val now = System.currentTimeMillis()
            val recoveredRuns = store.recoverAfterCrash(now)
            val recoveredPods = orchestrator.recover(now)
            println(
                "[agentHq] recovery: runs=${recoveredRuns.size} pods=${recoveredPods.size} " +
                    "(runs closed ORPHANED, open pods INTERRUPTED)",
            )

            // Deliver anything that was queued before the last shutdown to a session already at rest.
            mail.onStartup()
            // TRIGGER-NUDGE: only now may a schedule fire (a slot missed while down fires on this first pump).
            triggers.start()
            // Contract 1.11.0: this process's identity, taken AFTER reconciliation and recovery so a session
            // they touched (and that streamed nothing here) reads as older than the replay history.
            val identity = DaemonIdentity(UUID.randomUUID().toString(), System.currentTimeMillis())
            println(
                if (projectDirs && repo == null) {
                    "[agentHq] worktree repo=<per project> (sessions and ticket starts run in their project's directory; " +
                        "a project with no directory is refused)"
                } else {
                    worktreeBootLine(repo, workingDir, baseRef.refFor(com.superiorsoftllc.agenthq.worktree.BaseScope()))
                },
            )

            // PLUGINS-1: the daemon reads the same plugins root and rules as the desktop (no UI here); GET /plugins lists them.
            val plugins = DaemonPlugins.resolve(stateDir())

            val server = embeddedServer(CIO, port = boundPort, host = host) {
                install(WebSockets)
                install(ContentNegotiation) { json() }
                // Before routing, so a refused request never reaches a handler (the mailbox included).
                installAccessPolicy(policy, webBundle::isAsset)
                routing {
                    get("/health") {
                        call.respond(
                            Health(
                                status = "ok",
                                provider = provider.id,
                                runner = runner.id,
                                sessions = manager.list().size,
                                worktrees = worktrees.list().count { !it.removed },
                                pods = store.pods.open().size,
                                runsInFlight = store.runs.unfinished().size,
                                providers = manager.providers.map { it.id },
                                attentionOpen = attention.openCount().toInt(),
                                providerStatus = manager.providers.associate { it.id to providerStates.state(it.id).status.wireName },
                                triggersTripped = triggers.trippedCount(),
                            ),
                        )
                    }
                    get("/attention") { call.respond(attention.list()) }
                    get("/plugins") { call.respond(plugins) }
                    // Phase 6 agent-facing mail API: how a spawned CLI agent answers a delivered message.
                    mailRoutes(mail, mailbox)
                    // SERVER-UPDATE-3 (1.110.0): status / check / apply for the headless daemon's own updates.
                    updateRoutes(
                        updates ?: com.superiorsoftllc.agenthq.update.ServerUpdateService.fromEnvironment(
                            runningAgents = { manager.list().count { it.isBusy() } },
                        ),
                    )
                    // P9.6: signature-admitted webhook ingress (exempt from the bearer in AccessPolicy, signed per hook).
                    hookRoutes(WebhookIngress(triggers))
                    extraRoutes()
                    // WEB-1: the browser client, one exact route per bundle file (no path resolution, nothing to traverse).
                    webBundleRoutes(webBundle)
                    webSocket("/ws") {
                        handleConnection(
                            this,
                            manager,
                            worktrees,
                            mail,
                            mailbox,
                            attention,
                            repo,
                            clients,
                            streamSequencer,
                            orchestrator,
                            agents,
                            board,
                            projects,
                            projectGit,
                            WorkspaceHandler(workspaces),
                            // BASE-1c: `epic_base` runs project_base's own check, against the epic's project.
                            EpicHandler(epics, baseCheck = { epic, branch -> baseCheck.check(projectStore.get(epic.projectId), branch) }),
                            MemoryHandler(
                                memoryNotes,
                                caller = { id -> manager.get(id)?.let { MemoryCaller(it.id, it.role.orEmpty()) } },
                                // ROLE-NOTES-1: a role note changed while sessions of that role run is announced to each by mail.
                                onRoleNoteChanged = { project, role, name, deleted ->
                                    for (s in manager.list().filter { it.role == role && it.projectId == project && it.isInSession() }) {
                                        mail.send(MailSendRequest(
                                            from = "owner", to = s.id, subject = "Role note ${if (deleted) "deleted" else "changed"}: $name",
                                            body = "The role note '$name' of your role '$role' was ${if (deleted) "deleted" else "added or changed"}. " +
                                                "Re-read it: `agenthq memory show $name --scope role` (index: `agenthq memory index --scope role`).",
                                        ))
                                    }
                                },
                            ),
                            decisionFrames,
                            pipelineFrames,
                            notifyFrames,
                            keystoreFrames,
                            issueFrames,
                            stories,
                            storyBoard,
                            settings,
                            identity,
                            roles,
                            providerStates,
                            triggers,
                            commandStore,
                            runManager,
                            harnesses,
                            RoleBindingHandler(store.roleBindings, roles, { providerInfos(manager, harnesses).map { it.id }.toSet() }, com.superiorsoftllc.agenthq.harness.claude.ClaudeCommands.EFFORT_LEVELS.toSet()),
                            com.superiorsoftllc.agenthq.persistence.PromptStore(store.prompts),
                            com.superiorsoftllc.agenthq.persistence.SkillStore(store.skills),
                            com.superiorsoftllc.agenthq.persistence.TraitStore(store.traits, { roles.role(it) != null }, boundBy = { id -> store.traitReferences(id) }),
                            RoleTraitHandler(store.roleTraits, store.traits, { roles.role(it) != null }),
                            knowledge,
                            modelCatalogs,
                            skillsSource,
                            spawnGuard,
                            limits = limits,
                            usagePacing = usagePacing,
                            cliLocations = cliLocations,
                            harnessInstaller = harnessInstaller,
                            toolApproval = toolApproval,
                            pendingPrompts = pendingPrompts,
                            // IMG-1 (contract 1.83.0): uploads land in the project's own directory.
                            imageUploads = com.superiorsoftllc.agenthq.images.ImageUploadHandler(projectStore, clock = clock),
                            ownerQuestions = ownerQuestions,
                            handover = handover,
                            processWatch = processWatch,
                        )
                    }
                }
            }
            // BUG-PORT-0b: give the held port back on EVERY start path, AgentHqDaemon.start() and a direct
            // server.start(...) alike. ApplicationStarted is the last event EmbeddedServer.start raises before
            // engine.start binds (after module setup), so the release-to-bind gap stays as small as it can be.
            // runCatching: it is raised with monitor.raise, so a throwing handler would abort the start.
            server.monitor.subscribe(ApplicationStarted) { runCatching { portReservation.close() } }
            val chatImageRetention = com.superiorsoftllc.agenthq.images.ChatImageRetention(
                projectRoots = { projectStore.list().mapNotNull { p -> p.directory.takeIf(String::isNotBlank)?.let(::File) } },
                retentionDays = settings::chatImageRetentionDays,
                clock = clock,
                ticker = chatImageTicker,
            )

            return AgentHqDaemon(
                host = host,
                port = boundPort,
                providerId = provider.id,
                runnerId = runner.id,
                manager = manager,
                orchestrator = orchestrator,
                store = store,
                clients = clients,
                server = server,
                policy = policy,
                webBundle = webBundle,
                agents = agents,
                board = board,
                attention = attention,
                roles = roles,
                budget = budget,
                providerStates = providerStates,
                triggers = triggers,
                workspaces = workspaces,
                memoryNotes = memoryNotes,
                decisions = decisions,
                issues = issues,
                epics = epics,
                commands = commandStore,
                portFile = portFile,
                // BUG-PERM-1: the Tool approval reader is put back with the executable resolver (both read this store).
                restoreExecutables = { restoreToolApproval(); restoreExecutables() },
                pendingPrompts = pendingPrompts,
                portReservation = portReservation,
                chatImageRetention = chatImageRetention,
                ownerQuestions = ownerQuestions,
                handover = handover,
                processWatch = processWatch,
                background = listOf(AutoCloseable { usageRefresher.stop() }, AutoCloseable { agentWatchdog.close() }, AutoCloseable { finishNotice.close() }),
                processWatchScheduler = processWatchScheduler,
            )
        }

        /**
         * The daemon's database file: `AGENTHQ_DB` when set, else `agenthq.db` inside [stateDir].
         *
         * Same convention as `sessions.json` and `mailbox.json` -- inside the state dir, so the file
         * is stable across restarts and survives a `git clean` because it lives outside the repo.
         */
        fun defaultDbPath(): String =
            agentHqSetting("AGENTHQ_DB")?.takeIf { it.isNotBlank() }
                ?: File(stateDir(), DEFAULT_DB_NAME).absolutePath

        /**
         * Build from the environment, exactly as [main] does, AFTER validating the access configuration.
         *
         * The validation runs before [build] because build's default `store` opens the SQLite file before its
         * body runs: a daemon that is about to refuse to start must not touch its state first.
         */
        fun fromEnv(extraRoutes: io.ktor.server.routing.Route.() -> Unit = {}): AgentHqDaemon {
            val host = RemoteAccess.resolveHost(stateDir())
            AccessPolicy.of(host, RemoteAccess.resolveToken(stateDir()))
            // PORT-1: a strict daemon on a taken port refuses here, before build() opens the database.
            if (DaemonPort.strictFromEnv()) DaemonPort.choose(configuredPort(), host, strict = true)
            return build(
                storyMigrationFile = File(stateDir(), "stories.json"),
                portFile = File(stateDir(), DaemonPort.FILE_NAME),
                extraRoutes = extraRoutes,
            )
        }

        /** `AGENTHQ_PORT` (system property first, PKG-1), else [DaemonPort.DEFAULT]. */
        fun configuredPort(): Int =
            agentHqSetting(DaemonPort.PORT_ENV)?.toIntOrNull() ?: RemoteAccess.filePort(stateDir()) ?: DEFAULT_PORT
    }
}

/** Holds a streamed (pi) reasoning block until it ends, so the wire and the replay carry it as ONE `reasoning` frame. */
private val streamedReasoning = com.superiorsoftllc.agenthq.protocol.ReasoningCoalescer()

/**
 * Turn one lifecycle event into frames and push them to every connected client.
 *
 * TWO PARALLEL STREAMS, deliberately:
 *  - the RAW one, unchanged since Phase 1: `stdout` / `stderr` carry the provider's own line, `exit`
 *    carries the exit code. Removing these would break an existing client, so they stay;
 *  - the NEUTRAL one from [EventVocabulary]: `text_delta` for streamed text, `status` for a
 *    lifecycle transition, `finished` for how the run ended. This is what a UI renders.
 *
 * Both are derived from the SAME event, so they can never disagree about what happened -- the
 * neutral vocabulary is a translation of the raw stream, not a second source of truth.
 */
private fun broadcast(
    clients: List<Channel<Envelope>>,
    event: SessionEvent,
    sequencer: SessionFrameSequencer,
    /** Contract 1.95.0 (1c24fdc4): the run activity each event moves; a change rides a `status` frame. */
    runActivity: com.superiorsoftllc.agenthq.protocol.RunActivityTracker,
    /** A sleep wait seen in a tool call (seconds), or null when it ended; see SleepHint. */
    onWait: (sessionId: String, seconds: Long?) -> Unit = { _, _ -> },
    /** TODO-PROGRESS-1: a to-do tool call carried the agent's full list (text to status); it drives the progress bar. */
    onTodos: (sessionId: String, items: List<Pair<String, String>>) -> Unit = { _, _ -> },
    /** BUG-PERM-1: the Tool approval a session runs under, named by its denial notice. */
    toolApprovalFor: (sessionId: String) -> com.superiorsoftllc.agenthq.harness.ToolApproval = { com.superiorsoftllc.agenthq.harness.ToolApproval.DENY },
) {
    val frames: List<Envelope> = when (event) {
        is SessionEvent.Status -> {
            println("[agentHq] session ${event.session.id.take(8)} -> ${event.session.state}")
            com.superiorsoftllc.agenthq.session.SessionActivityLog.event(event.session.id, "state ${event.session.state.name.lowercase()}")
            listOf(
                Envelope(
                    Envelope.SESSION,
                    json.encodeToString(SessionRecordSerializer, event.session),
                    event.session.id,
                ),
                Envelope(
                    Envelope.STATUS,
                    json.encodeToString(
                        StatusPayload.serializer(),
                        StatusPayload(event.session.state, runActivity = runActivity.sessionState(event.session.id, event.session.state)),
                    ),
                    event.session.id,
                ),
            )
        }

        is SessionEvent.Stdout -> {
            // Demo trick kept from Phase 1: the child's lines are visible in the daemon terminal.
            println("[${event.sessionId.take(8)}] ${event.line}")
            val raw = Envelope(Envelope.STDOUT, event.line, event.sessionId)
            val neutral = streamedReasoning.process(event.sessionId, EventNormalizer.normalise(event.line, afterInterrupt = event.afterInterrupt)).flatMap { line ->
                // 1.95.0: the line moves the run activity first; a tool call carries the change on its own status
                // frame, any other line on a bare `status` frame after its own.
                val moved = runActivity.line(event.sessionId, line)
                when (line) {
                    is EventNormalizer.Line.Tool -> {
                        onWait(event.sessionId, line.waitSeconds)
                        line.todos?.let { t -> onTodos(event.sessionId, t.map { it.text to it.status }) }
                    }
                    EventNormalizer.Line.ToolEnd, is EventNormalizer.Line.Text, is EventNormalizer.Line.Reasoning -> onWait(event.sessionId, null)
                    else -> Unit
                }
                com.superiorsoftllc.agenthq.session.SessionActivityLog.output(
                    event.sessionId,
                    when (line) {
                        is EventNormalizer.Line.Tool -> (if (line.activity.isNullOrBlank()) line.name else "${line.name}: ${line.activity}").take(120)
                        is EventNormalizer.Line.Text -> "replying"
                        is EventNormalizer.Line.Reasoning -> "thinking"
                        else -> null
                    },
                )
                val own = when (line) {
                    is EventNormalizer.Line.Text -> Envelope(Envelope.TEXT_DELTA, line.delta, event.sessionId)
                    is EventNormalizer.Line.Reasoning -> Envelope(Envelope.REASONING, line.text, event.sessionId)
                    // A provider only raises a status it can actually justify; the daemon keeps the
                    // real lifecycle, so a provider status never overrides `session` state.
                    is EventNormalizer.Line.Status -> Envelope(
                        Envelope.STATUS,
                        json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.BUSY, line.label)),
                        event.sessionId,
                    )
                    // Contract 1.25.0: a tool call rides the SAME status frame, with the tool named
                    // in `tool`. A UI reads it to show "Running read" instead of a bare spinner.
                    is EventNormalizer.Line.Tool -> Envelope(
                        Envelope.STATUS,
                        json.encodeToString(
                            StatusPayload.serializer(),
                            StatusPayload(SessionState.BUSY, tool = line.name, activity = line.activity, write = line.write, todos = line.todos, runActivity = moved),
                        ),
                        event.sessionId,
                    )
                    is EventNormalizer.Line.Failure -> Envelope(Envelope.ERROR, line.message, event.sessionId)
                    // BUG-PERM-1: a denied tool is a one-line chat notice (an additive `stderr` line, no new frame).
                    is EventNormalizer.Line.Denial -> denialEnvelope(event.sessionId, line, toolApprovalFor(event.sessionId))
                    EventNormalizer.Line.ToolEnd, EventNormalizer.Line.CompactionStart, EventNormalizer.Line.CompactionEnd -> null
                }
                val extra = moved?.takeIf { line !is EventNormalizer.Line.Tool }?.let { runActivityEnvelope(event.sessionId, it) }
                listOfNotNull(own, extra)
            }
            listOf(raw) + neutral
        }

        is SessionEvent.Stderr -> {
            System.err.println("[${event.sessionId.take(8)}] ${event.line}")
            listOf(Envelope(Envelope.STDERR, event.line, event.sessionId))
        }

        is SessionEvent.Exit -> buildList {
            streamedReasoning.flush(event.sessionId)?.let { add(Envelope(Envelope.REASONING, it.text, event.sessionId)) }
            // Contract 1.64.0 (BUG-M2-11b-1): `exit` reports a hosted process that ended unasked; a turn's end is `finished`.
            if (event.processExited) event.code?.let { add(Envelope(Envelope.EXIT, it.toString(), event.sessionId)) }
            add(finishedEnvelope(event.sessionId, event.code, event.killOutcome))
            // 1.95.0: a kill is a stop the owner asked for, not a failure of the run.
            runActivity.turnEnded(event.sessionId, failed = event.killOutcome == null && event.code != 0)
                .forEach { add(runActivityEnvelope(event.sessionId, it)) }
        }

        // Contract 1.64.0: a kept process died between turns. No turn ended, so no `finished`.
        is SessionEvent.ProcessExit -> listOfNotNull(
            streamedReasoning.flush(event.sessionId)?.let { Envelope(Envelope.REASONING, it.text, event.sessionId) },
            Envelope(Envelope.EXIT, event.code.toString(), event.sessionId),
        )

        // Contract 1.11.0: what this run was asked, sequenced ahead of anything it prints.
        is SessionEvent.Turn -> listOf(
            // Contract 1.125.0: an owner turn omits `origin` (absent = Owner), so it is byte-identical to before.
            Envelope(Envelope.TURN, event.prompt, event.sessionId, origin = event.origin.takeUnless { it == MessageOrigin.Owner }),
            // 1.95.0: an accepted turn is a run that is thinking, before the harness prints a single line.
            runActivityEnvelope(event.sessionId, runActivity.turnStarted(event.sessionId)),
        )

        // M2-11: run plumbing (process started, pid, resumed provider session) goes to the Activity box, on the
        // existing status frame's tool/activity fields, so the chat shows at most one boot line.
        is SessionEvent.Activity -> {
            System.err.println("[${event.sessionId.take(8)}] [agenthq] ${event.detail}")
            listOf(
                Envelope(
                    Envelope.STATUS,
                    json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.BUSY, tool = event.tool, activity = event.detail)),
                    event.sessionId,
                ),
            )
        }
    }
    sequencer.publish(clients, frames)
}

/** Contract 1.95.0: a bare `status` frame carrying only a run-activity change. */
private fun runActivityEnvelope(sessionId: String, activity: com.superiorsoftllc.agenthq.protocol.RunActivity): Envelope = Envelope(
    Envelope.STATUS,
    json.encodeToString(StatusPayload.serializer(), StatusPayload(activity.state.sessionState(), runActivity = activity)),
    sessionId,
)

/** The session state a run-activity frame stands for, so its `status.state` never claims BUSY after the turn ended. */
private fun com.superiorsoftllc.agenthq.protocol.RunPhase.sessionState(): SessionState = when (this) {
    com.superiorsoftllc.agenthq.protocol.RunPhase.THINKING, com.superiorsoftllc.agenthq.protocol.RunPhase.TOOL_RUNNING, com.superiorsoftllc.agenthq.protocol.RunPhase.COMPACTING -> SessionState.BUSY
    com.superiorsoftllc.agenthq.protocol.RunPhase.WAITING_FOR_INPUT -> SessionState.WAITING_INPUT
    else -> SessionState.IDLE
}

/** Assign each live session-stream frame exactly one increasing sequence before publishing it. */
private class SessionFrameSequencer {
    private class State {
        var latestSequence = 0L
        var evictedCount = 0L
        val frames = ArrayDeque<Envelope>()
    }

    private val sessions = ConcurrentHashMap<String, State>()

    fun publish(clients: List<Channel<Envelope>>, frames: List<Envelope>) {
        val sessionId = frames.firstNotNullOfOrNull { frame ->
            frame.sessionId?.takeIf { frame.type in SESSION_STREAM_FRAME_TYPES }
        }
        if (sessionId == null) {
            for (frame in frames) for (client in clients) client.trySend(frame)
            return
        }

        val state = sessions.computeIfAbsent(sessionId) { State() }
        synchronized(state) {
            for (frame in frames) {
                val published = if (frame.sessionId == sessionId && frame.type in SESSION_STREAM_FRAME_TYPES) {
                    frame.copy(sequence = ++state.latestSequence).also { sequenced ->
                        if (state.frames.size >= SESSION_REPLAY_BUFFER_CAPACITY) {
                            state.frames.removeFirst()
                            state.evictedCount++
                        }
                        state.frames.addLast(sequenced)
                    }
                } else {
                    frame
                }
                for (client in clients) client.trySend(published)
            }
        }
    }

    fun replayAfter(sessionId: String, lastSequence: Long): ReplayBatch? {
        val state = sessions[sessionId] ?: return null
        return synchronized(state) {
            val oldestAvailableSequence = state.frames.firstOrNull()?.sequence
            val latestAvailableSequence = state.frames.lastOrNull()?.sequence
            val gap = if (
                oldestAvailableSequence != null && latestAvailableSequence != null &&
                lastSequence < oldestAvailableSequence - 1L
            ) {
                SessionReplayGapPayload(
                    requestedAfterSequence = lastSequence,
                    oldestAvailableSequence = oldestAvailableSequence,
                    latestAvailableSequence = latestAvailableSequence,
                    evictedCount = state.evictedCount,
                )
            } else {
                null
            }
            ReplayBatch(
                gap = gap,
                frames = state.frames.filter { (it.sequence ?: Long.MIN_VALUE) > lastSequence },
            )
        }
    }
}

@Serializable
private data class SessionReplayRequestPayload(val lastSequence: Long)

@Serializable
private data class SessionReplayGapPayload(
    val requestedAfterSequence: Long,
    val oldestAvailableSequence: Long,
    val latestAvailableSequence: Long,
    val evictedCount: Long,
)

private data class ReplayBatch(
    val gap: SessionReplayGapPayload?,
    val frames: List<Envelope>,
)

/**
 * How a run ended, in neutral terms.
 *
 * The exit code is the raw fact; [FinishedPayload.outcome] is the daemon's reading of it, and the
 * two are NOT interchangeable. A deliberate kill and a crash both end a process with a code, but
 * they mean opposite things to a user, so the outcome is resolved from WHY the run stopped
 * ([killOutcome]) and only then from the code. The session record already carries the outcome, so
 * this reads it rather than inferring it a second time.
 */
private fun finishedEnvelope(sessionId: String, exitCode: Int?, kill: KillOutcome? = null): Envelope {
    val outcome = when {
        kill == KillOutcome.GRACEFUL -> FinishedPayload.Outcome.KILLED_GRACEFUL
        kill == KillOutcome.FORCED -> FinishedPayload.Outcome.KILLED_FORCED
        exitCode == 0 -> FinishedPayload.Outcome.SUCCEEDED
        else -> FinishedPayload.Outcome.FAILED
    }
    val payload = FinishedPayload(exitCode = exitCode, outcome = outcome)
    return Envelope(
        Envelope.FINISHED,
        json.encodeToString(FinishedPayload.serializer(), payload),
        sessionId,
    )
}

/**
 * Turn a mailbox event into frames. A refusal and a permanent delivery failure are both surfaced --
 * the phase requires that a delivery failure is visible rather than silent, so [MailEvent.Changed]
 * with an UNDELIVERABLE message is broadcast and logged, never swallowed.
 */
private fun broadcastMail(clients: List<Channel<Envelope>>, event: MailEvent, attention: AttentionStore) {
    val envelope = when (event) {
        is MailEvent.Sent ->
            Envelope(Envelope.MAIL, json.encodeToString(Message.serializer(), event.message), event.message.to)

        is MailEvent.Changed -> {
            val m = event.message
            if (m.failed) System.err.println("[agentHq] mail ${m.id.take(8)} UNDELIVERABLE: ${m.failureReason}")
            Envelope(Envelope.MAIL, json.encodeToString(Message.serializer(), m), m.to)
        }

        is MailEvent.Refused -> {
            System.err.println("[agentHq] mail refused ${event.from} -> ${event.to}: ${event.reason}")
            Envelope(
                Envelope.MAIL_REFUSED,
                json.encodeToString(MailRefused.serializer(), MailRefused(event.from, event.to, null, event.reason)),
            )
        }

        is MailEvent.CeilingReached -> {
            // A conversation ceiling needs a durable operator signal, not only a stdout line.
            println("[agentHq] conversation ceiling reached on thread ${event.threadId} (limit ${event.limit})")
            attention.raise(
                kind = AttentionKind.CEILING,
                outcome = "ceiling_reached",
                detail = "conversation ${event.threadId} reached its ${event.limit}-message ceiling",
            )
            null
        }
    }
    if (envelope != null) for (client in clients) client.trySend(envelope)
}

/** Contract 1.14.0: push a changed trigger to every connected client. */
private fun broadcastTrigger(clients: List<Channel<Envelope>>, view: TriggerView) {
    val envelope = Envelope(Envelope.TRIGGER, json.encodeToString(TriggerView.serializer(), view))
    for (client in clients) client.trySend(envelope)
}

private fun triggersEnvelope(all: List<TriggerView>): Envelope =
    Envelope(Envelope.TRIGGERS, json.encodeToString(ListSerializer(TriggerView.serializer()), all))

/** A trigger request: a refusal is an `error` naming the verb and the problem; nothing changes. */
private inline fun triggerRequest(type: String, block: () -> Envelope?): Envelope? = try {
    block()
} catch (e: IllegalArgumentException) {
    Envelope(Envelope.ERROR, "$type refused: ${e.message ?: "invalid request"}")
} catch (e: Exception) {
    Envelope(Envelope.ERROR, "$type failed: ${e.message ?: e::class.simpleName}")
}

/** USAGE-PACE: a ticket worker in one of these states no longer spends its meter. */
private val DEAD_SESSION_STATES = setOf(
    com.superiorsoftllc.agenthq.session.SessionState.EXITED,
    com.superiorsoftllc.agenthq.session.SessionState.FAILED,
    com.superiorsoftllc.agenthq.session.SessionState.ORPHANED,
)

/** Contract 1.13.0: push a provider's new observed limit state to every connected client. */

/** U1: the `limits` frame; [ProviderLimits] holds no credential by construction. */
private fun limitsEnvelope(snapshot: List<ProviderLimits>): Envelope =
    Envelope(Envelope.LIMITS, json.encodeToString(ListSerializer(ProviderLimits.serializer()), snapshot))

private fun broadcastProviderState(clients: List<Channel<Envelope>>, state: ProviderState) {
    val envelope = Envelope(Envelope.PROVIDER_STATE, json.encodeToString(ProviderState.serializer(), state))
    for (client in clients) client.trySend(envelope)
}

/**
 * Story 9.7b: a HUMAN start over a spent budget or an exhausted provider is warned, never refused. The
 * warning is an `error` frame to the requesting client only, sent before the run starts.
 */
private fun launchWarning(manager: SessionManager, providerId: String?): Envelope? {
    val decision = runCatching { manager.launchCheck(providerId, LaunchOrigin.HUMAN) }.getOrNull()
    return (decision as? GateDecision.Warn)?.let { Envelope(Envelope.ERROR, "launch warning: ${it.reason}; the run starts anyway") }
}

/**
 * BUG-REGRESS-1: the boot line naming where work runs. With no `AGENTHQ_REPO`, a ticket start still cuts its own
 * `ticket/` branch when [workingDir] is in a git checkout (BUG-WT-1): from the commit [baseRef] names in that
 * repository's MAIN checkout, into its `.worktrees/`, also when [workingDir] is a linked worktree. [mainRootOf] is
 * the resolution the board's [TicketBranches] cuts with, so the line names the checkout a start really uses. Only
 * other sessions run in [workingDir] itself. The line used to say every session shares the checkout.
 */
internal fun worktreeBootLine(
    repo: File?,
    workingDir: File,
    baseRef: String,
    mainRootOf: (File) -> File = { TicketBranches().mainRoot(it) },
): String {
    if (repo != null) return "[agentHq] worktree repo=${repo.absolutePath} (new sessions get their own worktree)"
    val checkout = TicketBoard.gitRootOf(workingDir)
        ?: return "[agentHq] worktree repo=<unset> (sessions and ticket starts run in ${workingDir.absolutePath}, " +
            "which is not a git checkout; set AGENTHQ_REPO to give each its own worktree)"
    val main = mainRootOf(checkout)
    // BUG-BASE-087: no daemon-wide base; each start takes its epic's or project's, and is refused with neither.
    val from = baseRef.ifBlank { "their epic's or project's base branch (refused when neither is set)" }
    return "[agentHq] worktree repo=<unset> (ticket starts cut their own ticket/ branch from $from of the main " +
        "checkout ${main.absolutePath}, in its .worktrees/; other sessions run in ${workingDir.absolutePath}; set " +
        "AGENTHQ_REPO to give them their own worktree too)"
}

/** Contract 1.12.0: push a saved role to every connected client; for the one that saved it, this IS the answer. */
private fun broadcastRole(clients: List<Channel<Envelope>>, role: Role) {
    val envelope = Envelope(Envelope.ROLE, json.encodeToString(Role.serializer(), role))
    for (client in clients) client.trySend(envelope)
}

/**
 * Contract 1.22.0: push an epic whose ordered `storyIds` just changed to every connected client,
 * the one whose `story_save` caused it included -- so a UI's epics list refreshes on the same `epic`
 * frame an `epic_save` answers with.
 */
private fun broadcastEpic(clients: List<Channel<Envelope>>, epic: Epic) {
    val envelope = Envelope(Envelope.EPIC, json.encodeToString(Epic.serializer(), epic))
    for (client in clients) client.trySend(envelope)
}

/** Push a committed attention record/update to every currently connected client. */
private fun broadcastAttention(clients: List<Channel<Envelope>>, item: AttentionItem) {
    val envelope = Envelope(Envelope.ATTENTION, json.encodeToString(AttentionItem.serializer(), item))
    for (client in clients) client.trySend(envelope)
}

/**
 * Contract 1.5.0: push a committed backlog change to every client, the one whose request made it included
 * -- for that client, this IS the answer. One `ticket` per changed ticket (the one the request named
 * first), or one `tickets` with the whole board after a delete or a reorder.
 */
private fun broadcastBoard(clients: List<Channel<Envelope>>, event: BoardEvent) {
    val frames = when (event) {
        is BoardEvent.Changed -> event.tickets.map { ticketEnvelope(it) }
        is BoardEvent.Replaced -> listOf(ticketsEnvelope(event.tickets))
    }
    for (envelope in frames) for (client in clients) client.trySend(envelope)
}

private suspend fun handleConnection(
    session: DefaultWebSocketServerSession,
    manager: SessionManager,
    worktrees: GitWorktreeManager,
    mail: MailRouter,
    mailbox: Mailbox,
    attention: AttentionStore,
    defaultRepo: File?,
    clients: MutableList<Channel<Envelope>>,
    streamSequencer: SessionFrameSequencer,
    orchestrator: Orchestrator,
    agents: AgentProfiles,
    board: TicketBoard,
    projects: ProjectHandler,
    projectGit: ProjectGitHandler,
    workspaceFrames: WorkspaceHandler,
    epicFrames: EpicHandler,
    memoryFrames: MemoryHandler,
    decisionFrames: DecisionHandler,
    pipelineFrames: PipelineHandler,
    notifyFrames: com.superiorsoftllc.agenthq.notify.NotifyHandler,
    keystoreFrames: KeystoreHandler,
    issueFrames: IssueHandler,
    stories: StoryHandler,
    storyBoard: BoardHandler,
    settings: ProjectSettings,
    identity: DaemonIdentity,
    roles: RoleService,
    providerStates: ProviderStateTracker,
    triggers: TriggerService,
    commands: com.superiorsoftllc.agenthq.commands.CommandStore,
    runManager: com.superiorsoftllc.agenthq.commands.RunManager,
    harnesses: HarnessRegistry,
    roleBindings: RoleBindingHandler,
    prompts: com.superiorsoftllc.agenthq.persistence.PromptStore,
    skills: com.superiorsoftllc.agenthq.persistence.SkillStore,
    traits: com.superiorsoftllc.agenthq.persistence.TraitStore,
    roleTraits: RoleTraitHandler,
    knowledge: KnowledgeCounter,
    modelCatalogs: com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
    /** P10-K15: the `skills` section every first turn renders after `knowledge`. */
    skillsSource: com.superiorsoftllc.agenthq.agents.SkillsSource = com.superiorsoftllc.agenthq.agents.SkillsSource.NONE,
    /** BUG-G2d: bounds the spawns a frame naming its session (`pod_submit` / `new`) may make. */
    spawnGuard: SpawnGuard = SpawnGuard(manager::get),
    /** U1: the plan-limit meters a `limits` request reads. */
    limits: LimitService = LimitService.NONE,
    /** USAGE-PACE (1.119.0): the meters and allowances a `usage_get` request reads. */
    usagePacing: UsagePacing = UsagePacing({ emptyList() }, { com.superiorsoftllc.agenthq.usage.PacingRule.DEFAULT }, { emptyMap() }, { emptyMap() }, { emptyList() }),
    /** CLIPATH-1 (contract 1.73.0): `cli_locations` / `cli_location_set`; null = this daemon serves neither. */
    cliLocations: com.superiorsoftllc.agenthq.executables.CliLocationService? = null,
    /** HARNESS-INSTALL-1 (contract 1.100.0): `harness_install_status` / `harness_install`; null = this daemon serves neither. */
    harnessInstaller: com.superiorsoftllc.agenthq.harness.install.HarnessInstaller? = null,
    /** BUG-PERM-1 (contract 1.76.0): `tool_approval` / `tool_approval_set`; null = served by neither. */
    toolApproval: com.superiorsoftllc.agenthq.session.ToolApprovalService? = null,
    /** PROMPT-1 (contract 1.77.0): open prompts replayed on connect, answered by `prompt_response`; null = none. */
    pendingPrompts: com.superiorsoftllc.agenthq.prompts.PendingPrompts? = null,
    /** IMG-1 (contract 1.83.0): `image_upload`; null = this daemon serves none. */
    imageUploads: com.superiorsoftllc.agenthq.images.ImageUploadHandler? = null,
    /** ASK-1 (contract 1.97.0): `question_*`; replayed on connect as `questions`; null = this daemon serves none. */
    ownerQuestions: com.superiorsoftllc.agenthq.questions.OwnerQuestions? = null,
    /** TICKET-HANDOVER: a ticket worker's harness switch goes through a handoff turn; null = switched at once. */
    handover: SessionHandover? = null,
    /** ROGUE-1 (contract 1.120.0): `processes`; null = this daemon serves none. */
    processWatch: com.superiorsoftllc.agenthq.session.ProcessWatch? = null,
) {
    println("[agentHq] ws connected (provider=${manager.providerId})")
    val outgoing = Channel<Envelope>(Channel.UNLIMITED)
    clients.add(outgoing)
    val sender = session.launch {
        for (envelope in outgoing) {
            session.send(Frame.Text(json.encodeToString(Envelope.serializer(), envelope)))
        }
    }

    // BUG-ModelsHeadOfLine: `models` (in every client's connect burst) can spawn host listings -- Pi's
    // `pi --list-models`, `opencode models` -- bounded at 20 s. Answering it inline on the request loop below made
    // every later request on this connection queue behind the child. One worker per connection answers it off the
    // loop, on IO, in the order the `models` requests arrived; other replies may now overtake a `models` reply.
    val modelsRequests = Channel<Unit>(Channel.UNLIMITED)
    val modelsWorker = session.launch(kotlinx.coroutines.Dispatchers.IO) {
        for (unused in modelsRequests) {
            val answer = try {
                Envelope(
                    Envelope.MODELS,
                    json.encodeToString(ListSerializer(ProviderModels.serializer()), modelCatalog(manager, harnesses, modelCatalogs)),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Envelope(Envelope.ERROR, "models failed: ${e.message ?: e::class.simpleName}")
            }
            outgoing.trySend(answer)
        }
    }

    // BUG-CHANGES-BRANCH-2: a `project_git_*` read shells out to git, ~12 s under load. Answered inline it held every
    // later frame on this connection, chat included (the BUG-ModelsHeadOfLine class). One worker per connection
    // answers them off the loop, on IO, in arrival order: git replies stay ordered among themselves (a checkout
    // before the status that follows it), and other replies may overtake them. The handler catches its own failures.
    val projectGitRequests = Channel<Envelope>(Channel.UNLIMITED)
    val projectGitWorker = session.launch(kotlinx.coroutines.Dispatchers.IO) {
        for (request in projectGitRequests) {
            val answer = projectGit.answer(request) ?: continue
            // BUG-MCP-RACE-1: the direct answer echoes the request's id, as the inline path did.
            outgoing.trySend(if (request.requestId != null && answer.requestId == null) answer.copy(requestId = request.requestId) else answer)
        }
    }

    // PERF-3 (FLAKE-1 F1): the ready burst's reads -- `providers` (a PATH scan per CLI), `cli_locations` (the same scan),
    // `settings_get` and `tool_approval` (store reads) -- did file I/O inline on the request loop below, so under I/O
    // load the first user command (`project_save`) waited 10-20 s behind them (the BUG-ModelsHeadOfLine class). One
    // worker per connection answers them off the loop, on IO, in arrival order; other replies may overtake them.
    fun readyBurstAnswer(envelope: Envelope): Envelope = when (envelope.type) {
        Envelope.PROVIDERS -> Envelope(
            Envelope.PROVIDERS,
            json.encodeToString(ListSerializer(ProviderInfo.serializer()), providerInfos(manager, harnesses)),
        )
        Envelope.SETTINGS_GET -> try {
            val all = settings.all()
            val key = envelope.data.trim()
            val selected = if (key.isEmpty()) {
                all
            } else {
                val value = all[key] ?: throw IllegalArgumentException("unknown setting '$key'")
                JsonObject(mapOf(key to value))
            }
            Envelope(Envelope.SETTINGS, json.encodeToString(JsonObject.serializer(), selected))
        } catch (e: IllegalArgumentException) {
            Envelope(Envelope.ERROR, "settings_get refused: ${e.message ?: "invalid request"}")
        } catch (e: Exception) {
            Envelope(Envelope.ERROR, "settings_get failed: ${e.message ?: e::class.simpleName}")
        }
        Envelope.CLI_LOCATIONS -> try {
            val service = cliLocations ?: throw IllegalArgumentException("this daemon has no CLI locations")
            cliLocationsEnvelope(service.list())
        } catch (e: IllegalArgumentException) {
            Envelope(Envelope.ERROR, "cli_locations refused: ${e.message ?: "invalid request"}")
        } catch (e: Exception) {
            Envelope(Envelope.ERROR, "cli_locations failed: ${e.message ?: e::class.simpleName}")
        }
        Envelope.TOOL_APPROVAL -> try {
            val service = toolApproval ?: throw IllegalArgumentException("this daemon has no Tool approval setting")
            toolApprovalEnvelope(service.describe())
        } catch (e: IllegalArgumentException) {
            Envelope(Envelope.ERROR, "tool_approval refused: ${e.message ?: "invalid request"}")
        } catch (e: Exception) {
            Envelope(Envelope.ERROR, "tool_approval failed: ${e.message ?: e::class.simpleName}")
        }
        Envelope.LIMIT_CATALOG -> try {
            val service = settings as? SettingsService ?: throw IllegalArgumentException("this daemon has no limit settings")
            Envelope(Envelope.LIMIT_CATALOG, json.encodeToString(JsonObject.serializer(), service.limitCatalog()))
        } catch (e: Exception) {
            Envelope(Envelope.ERROR, "limit_catalog failed: ${e.message ?: e::class.simpleName}")
        }
        else -> Envelope(Envelope.ERROR, "unexpected ready-burst frame ${envelope.type}")
    }
    val readyBurstRequests = Channel<Envelope>(Channel.UNLIMITED)
    val readyBurstWorker = session.launch(kotlinx.coroutines.Dispatchers.IO) {
        for (request in readyBurstRequests) {
            val answer = try {
                readyBurstAnswer(request)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Envelope(Envelope.ERROR, "${request.type} failed: ${e.message ?: e::class.simpleName}")
            }
            // BUG-MCP-RACE-1: the direct answer echoes the request's id, as the inline path did.
            outgoing.trySend(if (request.requestId != null && answer.requestId == null) answer.copy(requestId = request.requestId) else answer)
        }
    }

    outgoing.trySend(
        Envelope(
            Envelope.READY,
            "provider=${manager.providerId} contract=${EventVocabulary.VERSION} " +
                "instance=${identity.instance} since=${identity.historySince} " +
                // BUILD-STAMP-1: additive key (no contract bump): the daemon's build string, e.g. 1.0.1+20261023.1432.e85b223.
                "build=${com.superiorsoftllc.agenthq.update.BuildInfo.current}",
        ),
    )
    // Manually serialize sessions with SessionRecordSerializer to include computed `live` field
    val sessionsElements = manager.list().map { json.encodeToJsonElement(SessionRecordSerializer, it) }
    val sessionsJson = JsonArray(sessionsElements)
    outgoing.trySend(Envelope(Envelope.SESSIONS, json.encodeToString(sessionsJson)))
    outgoing.trySend(
        Envelope(
            Envelope.WORKTREES,
            json.encodeToString(ListSerializer(WorktreeStatus.serializer()), worktrees.statuses()),
        ),
    )

    // PROMPT-1: every prompt still open, so a client that (re)connects shows its card.
    pendingPrompts?.snapshot()?.forEach { request ->
        outgoing.trySend(
            Envelope(
                Envelope.PROMPT_REQUEST,
                json.encodeToString(com.superiorsoftllc.agenthq.protocol.PromptRequest.serializer(), request),
                request.sessionId,
            ),
        )
    }
    // ASK-1: every owner question (open and history), so a client that (re)connects shows the open ones at once.
    ownerQuestions?.let { questions ->
        runCatching { questionsEnvelope(questions.list()) }
            .onSuccess { outgoing.trySend(it) }
            .onFailure { System.err.println("[agentHq] questions replay failed: ${it.message}") }
    }
    // PROMPT-1: who answered a prompt from this connection, for `prompt_resolved.resolvedBy`.
    val promptClientLabel = "client:" + promptClientCounter.incrementAndGet()
    // PROMPT-5: the `prompt_ask` questions this connection is waiting on. They run off the request loop (an ask waits
    // for a human), and closing the connection cancels them, which withdraws their cards (`resolvedBy` cancelled).
    val askJobs = kotlinx.coroutines.SupervisorJob(session.coroutineContext[kotlinx.coroutines.Job])

    // The session this connection is driving. A first prompt starts a fresh session; later prompts
    // continue it (resume) until it reaches a terminal state, so context carries across turns.
    var active: String? = null
    // The repository new sessions are cut from for THIS connection; overridable with a `repo` frame.
    var repo: File? = defaultRepo
    // Contract 1.3.0: how the sessions this connection creates are spawned, set by a `new` frame. Null =
    // the daemon's default provider and its default model. [spawnRefused] holds why the last `new` was
    // refused: until a valid `new` replaces it, this connection creates NO session, because creating one
    // on the default provider would quietly ignore what the client asked for.
    var spawn: SpawnOptions? = null
    var spawnRefused: String? = null
    // BUG-G2d: the session the last `new` named as its origin; the session the next prompt creates is charged to it.
    var spawnOrigin: String? = null
    try {
        for (frame in session.incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull()
                ?: Envelope(Envelope.PROMPT, text)
            if (envelope.type == SESSION_REPLAY_REQUEST_TYPE) {
                val sessionId = envelope.sessionId
                val request = runCatching {
                    json.decodeFromString(SessionReplayRequestPayload.serializer(), envelope.data)
                }.getOrNull()
                when {
                    sessionId == null || sessionId.isBlank() -> outgoing.trySend(
                        Envelope(Envelope.ERROR, "replay_request requires a sessionId"),
                    )

                    request == null -> outgoing.trySend(
                        Envelope(Envelope.ERROR, "malformed replay_request payload"),
                    )

                    else -> {
                        val replay = streamSequencer.replayAfter(sessionId, request.lastSequence)
                        if (replay == null) {
                            outgoing.trySend(Envelope(Envelope.ERROR, "no replay history for session '$sessionId'"))
                        } else {
                            replay.gap?.let { gap ->
                                outgoing.trySend(
                                    Envelope(
                                        SESSION_REPLAY_GAP_TYPE,
                                        json.encodeToString(SessionReplayGapPayload.serializer(), gap),
                                        sessionId,
                                    ),
                                )
                            }
                            for (replayedFrame in replay.frames) outgoing.trySend(replayedFrame)
                        }
                    }
                }
                continue
            }
            // BUG-MCP-RACE-1 (1.87.0): a correlated backlog request is answered directly even when the push carried it.
            val direct = envelope.requestId != null
            val reply: Envelope? = when (envelope.type) {
                Envelope.PROMPT -> {
                    if (envelope.data.isBlank()) {
                        null
                    } else if (CommandLines.isCommand(envelope.data)) {
                        // CP-4: a leading `/` is a command, never a prompt: nothing below this point runs for it.
                        SlashCommands.execute(envelope, active, manager, harnesses)
                    } else if (envelope.sessionId != null) {
                        // A client that NAMES its session (a reconnecting client carries it on every frame)
                        // continues exactly that one. A missing or non-resumable session is refused here,
                        // never silently forked into a new session the client did not ask for.
                        try {
                            val named = manager.get(envelope.sessionId)
                            named?.let { launchWarning(manager, it.provider)?.let(outgoing::trySend) }
                            // A bound-but-never-run session takes its persona and identity with this first turn,
                            // from its OWN binding (not this connection's `new`), as a delivered message does.
                            val turn = if (named?.neverRun == true) {
                                // TRAITS-T5: from the session's FROZEN instance, never the live profile.
                                val instance = manager.instance(named.id)
                                firstTurn(instance, named.role, named.agentId, envelope.data, knowledge.section(named.projectId, named.role), skillsSource.section(instance))
                            } else {
                                envelope.data
                            }
                            active = manager.resume(envelope.sessionId, turn).id
                            null
                        } catch (e: RunInFlightException) {
                            // 1.96.0: typed, so the client that sent it (its requestId is echoed below) can queue it again.
                            Envelope(Envelope.ERROR, "$RUN_IN_FLIGHT ${e.sessionId}: ${e.message}")
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "resume failed")
                        }
                    } else {
                        val current = active?.let { manager.get(it) }
                        val refused = spawnRefused
                        if ((current == null || current.state in TERMINAL) && refused != null) {
                            Envelope(
                                Envelope.ERROR,
                                "no session started: the last new frame was refused ($refused); send a valid new first",
                            )
                        } else if (current == null || current.state in TERMINAL) {
                            // A new session is cut its OWN worktree before any process exists, so
                            // two sessions on one repo never share a checkout. branchHint = the
                            // task's first line; the manager still owns the final branch name.
                            // Provider and model come from this connection's last `new` (1.3.0), or from
                            // the agent profile it names (1.4.0), resolved NOW because a profile can change
                            // after `new`. A refusal is an `error` frame, never a session on another
                            // provider. The branch hint stays the client's own first line, not the persona's.
                            try {
                                val resolved = agents.resolveSpawn(spawn)
                                launchWarning(manager, resolved.providerId)?.let(outgoing::trySend)
                                // TRAITS-T4/T5: resolved first and passed in, so the frozen row is what this turn rendered.
                                // P10-K15: with the skill ids the row will freeze, so the turn renders exactly those.
                                val instance = agents.instanceFor(resolved.agentId, resolved.role)?.let { manager.withSkills(it, null) }
                                // BUG-G2d: the session the last `new` named as its origin is charged for this one; the
                                // check that counts is here, under the guard's lock, right before the session exists.
                                val origin = spawnOrigin
                                val created = spawnGuard.admit(origin) { placement ->
                                    manager.startNew(
                                        // P10-K9: the session carries the project its knowledge section names.
                                        prompt = firstTurn(
                                            instance, resolved.role, resolved.agentId, envelope.data,
                                            knowledge.section(resolved.projectId, resolved.role), skillsSource.section(instance),
                                        ),
                                        instance = instance,
                                        projectId = resolved.projectId,
                                        repo = repo,
                                        branchHint = envelope.data.lineSequence().firstOrNull(),
                                        providerId = resolved.providerId,
                                        model = resolved.model,
                                        agentId = resolved.agentId,
                                        role = resolved.role,
                                        effort = resolved.effort,
                                        // BUG-PERM-1: the spawn's own Tool approval; an unknown value refuses the spawn.
                                        toolApproval = com.superiorsoftllc.agenthq.harness.ToolApproval.parseStrict(spawn?.toolApproval),
                                    ).also { if (placement != null) spawnGuard.record(it.id, origin); manager.setSpawner(it.id, origin) }
                                }
                                active = created.id
                                worktrees.status(created.id)?.let { outgoing.trySend(worktreeEnvelope(it)) }
                                null
                            } catch (e: Exception) {
                                Envelope(Envelope.ERROR, e.message ?: "session start failed")
                            }
                        } else {
                            try {
                                launchWarning(manager, current.provider)?.let(outgoing::trySend)
                                // A bound-but-never-run session takes the persona with its first turn.
                                // TRAITS-T5: the text comes from the session's FROZEN instance, never the live profile. The
                                // spawn is still resolved first only as a gate: a profile deleted since `new` refuses the turn.
                                val turn = if (current.neverRun) {
                                    agents.resolveSpawn(spawn)
                                    val instance = manager.instance(current.id)
                                    firstTurn(instance, current.role, current.agentId, envelope.data, knowledge.section(current.projectId, current.role), skillsSource.section(instance))
                                } else {
                                    envelope.data
                                }
                                active = manager.resume(current.id, turn).id
                                null
                            } catch (e: Exception) {
                                Envelope(Envelope.ERROR, e.message ?: "resume failed")
                            }
                        }
                    }
                }

                Envelope.NEW -> {
                    active = null
                    spawnOrigin = null
                    val data = envelope.data.trim()
                    if (!data.startsWith("{")) {
                        // Not a JSON object: the pre-1.3.0 meaning, unchanged. The next prompt starts a
                        // fresh session on the default provider, and any earlier spawn options are cleared.
                        spawn = null
                        spawnRefused = null
                        null
                    } else {
                        // Checked NOW, so a client hears about a bad choice when it makes it. startNew
                        // checks again when the session is actually created, and that check is the one
                        // that counts.
                        try {
                            val options = SpawnOptions.decode(data)
                            agents.resolveSpawn(options)
                            // BUG-G2d (1.58.0): a `new` that names the session it comes from (`agenthq_spawn`) is
                            // charged to that session's pod: checked NOW, so a refusal binds nothing and leaves no
                            // spawn options behind, and again when the session is actually created.
                            val origin = envelope.sessionId
                            spawnGuard.check(origin)
                            spawn = options
                            spawnRefused = null
                            spawnOrigin = origin
                            // BIND AT CREATION: a console opened as a profile is addressable at once.
                            spawnGuard.admit(origin) { placement ->
                                bindSessionOnSpawn(manager, agents, options, repo)
                                    ?.also { if (placement != null) spawnGuard.record(it.id, origin); manager.setSpawner(it.id, origin) }
                            }?.let { active = it.id }
                            null
                        } catch (e: IllegalArgumentException) {
                            val reason = e.message ?: "invalid spawn options"
                            spawn = null
                            spawnRefused = reason
                            Envelope(Envelope.ERROR, "new refused: $reason")
                        }
                    }
                }

                // Contract 1.12.0 -- the role catalog. `role_save` is answered by the push of the stored `role`
                // to EVERY client (this one included); a refusal is an `error` naming the problem, nothing written.
                Envelope.ROLE_LIST -> Envelope(Envelope.ROLES, json.encodeToString(ListSerializer(Role.serializer()), roles.all()))

                // Contract 1.14.0 -- triggers. A change is pushed to every client by the service's sink, and for
                // the requester that push is the answer; `trigger_fire` also answers its sender directly.
                Envelope.TRIGGER_SAVE -> triggerRequest(envelope.type) { triggers.save(TriggerSave.decode(envelope.data)); null }
                Envelope.TRIGGER_LIST -> triggerRequest(envelope.type) { triggersEnvelope(triggers.list()) }
                Envelope.TRIGGER_FIRE -> triggerRequest(envelope.type) {
                    Envelope(Envelope.TRIGGER, json.encodeToString(TriggerView.serializer(), triggers.fire(envelope.data)))
                }
                Envelope.TRIGGER_ARM -> triggerRequest(envelope.type) { triggers.arm(envelope.data); null }
                Envelope.TRIGGER_DISABLE -> triggerRequest(envelope.type) { triggers.disable(envelope.data); null }
                Envelope.TRIGGER_DELETE -> triggerRequest(envelope.type) {
                    triggers.delete(envelope.data)
                    val all = triggersEnvelope(triggers.list())
                    for (client in clients) client.trySend(all)
                    null
                }
                Envelope.TRIGGER_EVENTS -> triggerRequest(envelope.type) {
                    Envelope(Envelope.TRIGGER_EVENTS, json.encodeToString(ListSerializer(TriggerEventView.serializer()), triggers.events(envelope.data)))
                }

                // Contract 1.13.0: every provider's latest observed limit state -- `unknown` until observed.
                Envelope.PROVIDER_STATES -> Envelope(
                    Envelope.PROVIDER_STATES,
                    json.encodeToString(
                        ListSerializer(ProviderState.serializer()),
                        (manager.providers.map { it.id } + providerStates.states().map { it.provider }).distinct()
                            .map { providerStates.state(it) },
                    ),
                )

                // Contract 1.40.0 (ROLE-1c): what a role runs on. A set is answered by the push of the stored `role_binding`
                // to EVERY client (this one included); a refusal is an `error`, nothing written.
                Envelope.ROLE_BINDING_LIST -> Envelope(Envelope.ROLE_BINDINGS, json.encodeToString(ListSerializer(RoleBinding.serializer()), roleBindings.all()))
                Envelope.ROLE_BINDING_GET -> Envelope(Envelope.ROLE_BINDING, json.encodeToString(RoleBinding.serializer(), roleBindings.get(envelope.data.trim())))
                Envelope.ROLE_BINDING_SET -> try {
                    val stored = roleBindings.set(RoleBindingSave.decode(envelope.data))
                    val push = Envelope(Envelope.ROLE_BINDING, json.encodeToString(RoleBinding.serializer(), stored))
                    for (client in clients) client.trySend(push)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "role_binding_set refused: ${e.message ?: "invalid binding"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "role_binding_set failed: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.44.0 (P10-A): the prompt library. A save pushes `prompt_item` and a delete pushes `prompts` to EVERY client;
                // a refusal is an `error`, nothing written.
                Envelope.PROMPT_SAVE -> try {
                    val saved = prompts.save(PromptSave.decode(envelope.data))
                    val push = Envelope(Envelope.PROMPT_ITEM, json.encodeToString(PromptView.serializer(), saved))
                    for (client in clients) client.trySend(push)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "prompt_save refused: ${e.message ?: "invalid prompt"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "prompt_save failed: ${e.message ?: e::class.simpleName}")
                }
                Envelope.PROMPT_LIST -> Envelope(Envelope.PROMPTS, json.encodeToString(ListSerializer(PromptView.serializer()), prompts.list(envelope.data)))
                Envelope.PROMPT_GET -> prompts.get(envelope.data.trim())
                    ?.let { Envelope(Envelope.PROMPT_ITEM, json.encodeToString(PromptView.serializer(), it)) }
                    ?: Envelope(Envelope.ERROR, "prompt_get refused: unknown prompt '${envelope.data.trim()}'")
                Envelope.PROMPT_DELETE -> try {
                    if (!prompts.delete(envelope.data.trim())) {
                        Envelope(Envelope.ERROR, "prompt_delete refused: unknown prompt '${envelope.data.trim()}'")
                    } else {
                        val push = Envelope(Envelope.PROMPTS, json.encodeToString(ListSerializer(PromptView.serializer()), prompts.list(null)))
                        for (client in clients) client.trySend(push)
                        null
                    }
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "prompt_delete failed: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.45.0 (P10-B): the skills library. A save pushes `skill_item` and a delete pushes `skills` to EVERY client;
                // a refusal is an `error`, nothing written.
                Envelope.SKILL_SAVE -> try {
                    val saved = skills.save(SkillSave.decode(envelope.data))
                    val push = Envelope(Envelope.SKILL_ITEM, json.encodeToString(SkillView.serializer(), saved))
                    for (client in clients) client.trySend(push)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "skill_save refused: ${e.message ?: "invalid skill"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "skill_save failed: ${e.message ?: e::class.simpleName}")
                }
                Envelope.SKILL_LIST -> Envelope(Envelope.SKILLS, json.encodeToString(ListSerializer(SkillView.serializer()), skills.list(envelope.data)))
                // Contract 1.56.0 (P10-K12): data is an id OR an exact name (ids are tried first).
                Envelope.SKILL_GET -> skills.find(envelope.data)
                    ?.let { Envelope(Envelope.SKILL_ITEM, json.encodeToString(SkillView.serializer(), it)) }
                    ?: Envelope(Envelope.ERROR, "skill_get refused: unknown skill '${envelope.data.trim()}'")
                Envelope.SKILL_DELETE -> try {
                    if (!skills.delete(envelope.data.trim())) {
                        Envelope(Envelope.ERROR, "skill_delete refused: unknown skill '${envelope.data.trim()}'")
                    } else {
                        val push = Envelope(Envelope.SKILLS, json.encodeToString(ListSerializer(SkillView.serializer()), skills.list(null)))
                        for (client in clients) client.trySend(push)
                        null
                    }
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "skill_delete failed: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.48.0 (TRAITS-T1): the trait library. A save pushes `trait_item` and a delete pushes `traits` to EVERY client;
                // a refusal is an `error` `trait_<op> refused: <reason>`, nothing written.
                Envelope.TRAIT_SAVE -> try {
                    val saved = traits.save(TraitSave.decode(envelope.data))
                    val push = Envelope(Envelope.TRAIT_ITEM, json.encodeToString(TraitView.serializer(), saved))
                    for (client in clients) client.trySend(push)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "trait_save refused: ${e.message ?: "invalid trait"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "trait_save failed: ${e.message ?: e::class.simpleName}")
                }
                Envelope.TRAIT_LIST -> Envelope(Envelope.TRAITS, json.encodeToString(ListSerializer(TraitView.serializer()), traits.list()))
                Envelope.TRAIT_GET -> traits.get(envelope.data.trim())
                    ?.let { Envelope(Envelope.TRAIT_ITEM, json.encodeToString(TraitView.serializer(), it)) }
                    ?: Envelope(Envelope.ERROR, "trait_get refused: unknown trait '${envelope.data.trim()}'")
                Envelope.TRAIT_DELETE -> try {
                    if (!traits.delete(envelope.data.trim())) {
                        Envelope(Envelope.ERROR, "trait_delete refused: unknown trait '${envelope.data.trim()}'")
                    } else {
                        val push = Envelope(Envelope.TRAITS, json.encodeToString(ListSerializer(TraitView.serializer()), traits.list()))
                        for (client in clients) client.trySend(push)
                        null
                    }
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "trait_delete refused: ${e.message ?: "trait is bound"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "trait_delete failed: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.49.0 (TRAITS-T2): a role's ordered trait list. A set is answered by the push of the stored
                // `role_traits` to EVERY client (this one included); a refusal is an `error`, nothing written.
                Envelope.ROLE_TRAITS_SET -> try {
                    val stored = roleTraits.set(RoleTraitsSave.decode(envelope.data))
                    val push = Envelope(Envelope.ROLE_TRAITS, json.encodeToString(RoleTraits.serializer(), stored))
                    for (client in clients) client.trySend(push)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "role_traits_set refused: ${e.message ?: "invalid trait list"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "role_traits_set failed: ${e.message ?: e::class.simpleName}")
                }
                Envelope.ROLE_TRAITS_GET -> Envelope(Envelope.ROLE_TRAITS, json.encodeToString(RoleTraits.serializer(), roleTraits.get(envelope.data.trim())))
                Envelope.ROLE_TRAITS_LIST -> Envelope(Envelope.ROLE_TRAITS_ALL, json.encodeToString(ListSerializer(RoleTraits.serializer()), roleTraits.all()))

                Envelope.ROLE_SAVE -> try {
                    val saved = roles.save(RoleSave.decode(envelope.data))
                    broadcastRole(clients, saved)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "role_save refused: ${e.message ?: "invalid role"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "role_save failed: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.108.0 (ROLE-EDIT-1): a reset override is pushed as `role`, a removed custom role as `role_removed`.
                Envelope.ROLE_DELETE -> try {
                    val id = envelope.data.trim()
                    val live = manager.list()
                        .filter { it.state != SessionState.EXITED && it.state != SessionState.FAILED && it.state != SessionState.ORPHANED }
                        .mapNotNull { it.role?.let(RoleIds::normalize) }.toSet()
                    val remaining = roles.delete(id, live)
                    val push = if (remaining != null) Envelope(Envelope.ROLE, json.encodeToString(Role.serializer(), remaining))
                    else Envelope(Envelope.ROLE_REMOVED, id)
                    for (client in clients) client.trySend(push)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "role_delete refused: ${e.message ?: "invalid role"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "role_delete failed: ${e.message ?: e::class.simpleName}")
                }

                Envelope.COMMAND_CATALOG -> SlashCommands.catalog(envelope, manager, harnesses)

                // Answered by [readyBurstWorker], off this loop (PERF-3).
                Envelope.PROVIDERS, Envelope.SETTINGS_GET, Envelope.CLI_LOCATIONS, Envelope.TOOL_APPROVAL, Envelope.LIMIT_CATALOG -> {
                    readyBurstRequests.trySend(envelope)
                    null
                }

                // Answered by [modelsWorker], off this loop (BUG-ModelsHeadOfLine).
                Envelope.MODELS -> {
                    modelsRequests.trySend(Unit)
                    null
                }

                // U1 (contract 1.59.0): a live read can take 12 s per source, so it is answered off the request loop.
                Envelope.LIMITS -> {
                    session.launch(kotlinx.coroutines.Dispatchers.IO) { outgoing.trySend(limitsEnvelope(limits.snapshot())) }
                    null
                }

                // USAGE-PACE (1.119.0): a fresh live read (at most once per 5 min), then the meters, off the request loop.
                Envelope.USAGE_GET -> {
                    session.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val answer = try {
                            limits.snapshot(force = envelope.data.trim() == "force")
                            Envelope(Envelope.USAGE_REPORT, json.encodeToString(UsageReport.serializer(), usagePacing.report()))
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, "usage_get failed: ${e.message ?: e::class.simpleName}")
                        }
                        outgoing.trySend(answer)
                    }
                    null
                }

                // USAGE-PACE (1.121.0): the owner's manual percent / override / rule; answered by the fresh report.
                Envelope.USAGE_SET -> try {
                    val service = settings as? com.superiorsoftllc.agenthq.settings.SettingsService
                        ?: throw IllegalArgumentException("this daemon has no usage settings")
                    service.applyUsage(com.superiorsoftllc.agenthq.usage.UsageSet.decode(envelope.data))
                    Envelope(Envelope.USAGE_REPORT, json.encodeToString(UsageReport.serializer(), usagePacing.report()))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "usage_set refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "usage_set failed: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.4.0 -- agent profiles. Answers go to the requesting client only; a refusal
                // is an `error` frame naming the problem, and nothing is stored.
                Envelope.AGENT_SAVE -> try {
                    val decoded = AgentProfile.decode(envelope.data)
                    // CP-3: a NEW agent is bound to the project it names; one that names none (a CLI call outside
                    // any project) is bound to the DEFAULT project, never left global. A known agent keeps its own.
                    val isNew = decoded.id.isNullOrBlank() || agents.get(decoded.id.trim()) == null
                    val bound = if (isNew && decoded.projectId.isNullOrBlank()) decoded.copy(projectId = projects.defaultProjectId()) else decoded
                    val saved = agents.save(bound)
                    Envelope(Envelope.AGENT, json.encodeToString(AgentProfile.serializer(), saved))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "agent_save refused: ${e.message ?: "invalid profile"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "agent_save failed: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.47.0 (role-first S3): one agent onto another executor. Answered by the `agent_swapped` push to
                // EVERY client (this one included), the same fan-out as `role_binding_set`; a refusal is an `error`, nothing written.
                Envelope.AGENT_SWAP -> try {
                    val swapped = swapAgent(manager, agents, AgentSwap.decode(envelope.data), repo, handover)
                    val push = Envelope(Envelope.AGENT_SWAPPED, json.encodeToString(AgentSwapped.serializer(), swapped))
                    for (client in clients) client.trySend(push)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "agent_swap refused: ${e.message ?: "invalid swap"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "agent_swap failed: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.115.0 (ASSISTANT-1): the installation-wide Assistant. Answers the sender with its `session` frame,
                // creating the session on the first request; the creation itself reaches every client as a `session` push.
                Envelope.ASSISTANT -> try {
                    val assistant = AssistantService.ensure(manager, agents)
                    Envelope(Envelope.SESSION, json.encodeToString(SessionRecordSerializer, assistant), assistant.id)
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "assistant refused: ${e.message ?: "cannot start the assistant"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "assistant failed: ${e.message ?: e::class.simpleName}")
                }

                // UX-HARNESS-2 (contract 1.83.0): one session with no agent onto another harness, in place. Answered by the
                // `session_swapped` push to EVERY client (the session's own `session` frame follows from the manager).
                Envelope.SESSION_SWAP -> try {
                    val swapped = swapSession(manager, SessionSwap.decode(envelope.data), agents::roleKnown, handover)
                    val push = Envelope(Envelope.SESSION_SWAPPED, json.encodeToString(SessionSwapped.serializer(), swapped), swapped.sessionId)
                    for (client in clients) client.trySend(push)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "session_swap refused: ${e.message ?: "invalid switch"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "session_swap failed: ${e.message ?: e::class.simpleName}")
                }

                // CP-3: `agent_list` data {"projectId": ...} answers only that project's agents.
                Envelope.AGENT_LIST -> try {
                    val scope = AgentListRequest.decode(envelope.data).projectId?.trim().orEmpty()
                    if (scope.isEmpty()) agentsEnvelope(agents)
                    else Envelope(Envelope.AGENTS, json.encodeToString(ListSerializer(AgentProfile.serializer()), agents.list(scope)))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "agent_list refused: ${e.message ?: "invalid request"}")
                }

                // IN-APP BUILD T2 (contract 1.33.0): id, name, cwd; argv + graceMillis since 1.108.0 (RUN-CMD-1).
                Envelope.COMMAND_LIST -> Envelope(Envelope.COMMANDS, commands.registry.specsJson())

                // RUN-CMD-1 (contract 1.108.0): write commands.json, then push the new list to EVERY client (the
                // requester included). Never while that command's run is in flight; a refusal names the id only.
                Envelope.COMMAND_SAVE, Envelope.COMMAND_DELETE -> {
                    val refusal = if (envelope.type == Envelope.COMMAND_SAVE) commands.save(envelope.data, runManager::unlessRunning)
                    else commands.delete(envelope.data, runManager::unlessRunning)
                    if (refusal != null) Envelope(Envelope.ERROR, "${envelope.type} refused: $refusal")
                    else {
                        val list = Envelope(Envelope.COMMANDS, commands.registry.specsJson())
                        for (client in clients) client.trySend(list)
                        null
                    }
                }

                // IN-APP BUILD T3: the `run` answer goes out first, then the child starts and streams.
                Envelope.RUN_START -> {
                    val started = runManager.start(envelope.data)
                    outgoing.trySend(started.reply)
                    started.launch()
                    null
                }

                // IN-APP BUILD T4: the answer to a stop is the run's run_finished; only a bad request answers here.
                Envelope.RUN_STOP -> runManager.stop(envelope.data)

                // RUN-CONSOLE (contract 1.134.0): the pills' list, a console's replay, its size and (interactive runs) keys.
                Envelope.RUN_LIST -> runManager.list()
                Envelope.RUN_ATTACH -> runManager.attach(envelope.data)
                Envelope.RUN_RESIZE -> runManager.resize(envelope.data)
                Envelope.RUN_INPUT -> runManager.input(envelope.data)

                Envelope.AGENT_DELETE -> try {
                    agents.delete(envelope.data)
                    agentsEnvelope(agents)
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "agent_delete refused: ${e.message ?: "invalid id"}")
                }

                // AGENT-ROSTER: the live, project-scoped "who's here" list. `agent_roster` carries
                // {"projectId": ...} and is answered by `roster`, the JSON list of that project's
                // RosterRow entries (one per agent, never another project's). A blank projectId is
                // refused as an error frame; the answer goes to the requesting client only.
                Envelope.AGENT_ROSTER -> try {
                    val request = RosterRequest.decode(envelope.data)
                    val projectId = request.projectId?.trim().orEmpty()
                    if (projectId.isEmpty()) {
                        Envelope(Envelope.ERROR, "agent_roster refused: projectId is blank")
                    } else {
                        val rows = roster(
                            projectId,
                            projectAgents(projectId, manager.list(), agents::get),
                            manager.list(),
                            mailbox,
                            ticketTitles(board),
                        )
                        Envelope(Envelope.ROSTER, json.encodeToString(ListSerializer(RosterRow.serializer()), rows))
                    }
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "agent_roster refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "agent_roster failed: ${e.message ?: e::class.simpleName}")
                }

                // STATS-1 (contract 1.92.0): one session's statistics, read on demand from its harness's own local
                // record (read only, nothing leaves the machine). Answered to the requester only; the readers never
                // throw, so a missing or locked harness record is a `session_stats` with null fields, not an error.
                Envelope.SESSION_STATS_GET -> try {
                    val id = SessionStatsRequest.decode(envelope.data).sessionId?.trim().orEmpty()
                    val record = if (id.isEmpty()) null else manager.get(id)
                    when {
                        id.isEmpty() -> Envelope(Envelope.ERROR, "session_stats_get refused: sessionId is blank")
                        record == null -> Envelope(Envelope.ERROR, "session_stats_get refused: unknown session '$id'")
                        else -> {
                            // A large session record takes a moment to stream: answered off the request loop, like LIMITS.
                            session.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val stats = sessionStats.read(record.id, record.provider, record.providerSessionId, record.state.name.lowercase())
                                outgoing.trySend(Envelope(Envelope.SESSION_STATS, json.encodeToString(SessionStats.serializer(), stats)))
                            }
                            null
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "session_stats_get refused: ${e.message ?: "invalid request"}")
                }

                // Contract 1.5.0 -- the backlog. A change is pushed to EVERY client by the board's sink (see
                // broadcastBoard), and for the requester that push is the answer; only a request that changed
                // nothing is answered here. A refusal is an `error` frame naming the problem, and nothing is stored.
                Envelope.TICKET_SAVE -> boardRequest(envelope.type) { board.save(TicketSave.decode(envelope.data)).reply(direct) }
                Envelope.TICKET_GET -> boardRequest(envelope.type) { ticketEnvelope(board.get(envelope.data)) }
                Envelope.TICKET_LIST -> boardRequest(envelope.type) {
                    // UI-BOARD-PROJ: a payload naming a project lists only its tickets; empty data keeps
                    // the whole-board answer a legacy client expects. E1-b (1.66.0): a JSON object
                    // `{projectId?, epicId?}` also filters by epic; an unknown epic answers an empty board.
                    val query = TicketListQuery.decode(envelope.data)
                    ticketsEnvelope(board.list(query.projectId, query.epicId))
                }
                Envelope.TICKET_MOVE -> boardRequest(envelope.type) {
                    // BUG-MAIL-2: a move run from inside a session (`agenthq ticket move`, which names its session on
                    // the frame) records that session as the ticket's starter. A UI move names none: nothing recorded.
                    val starter = envelope.sessionId?.takeIf { manager.get(it) != null }
                    board.move(TicketMove.decode(envelope.data), startedBy = starter).reply(direct)
                }
                Envelope.TICKET_LINK -> boardRequest(envelope.type) {
                    board.link(TicketLinkRequest.decode(envelope.data, envelope.type)).reply(direct)
                }
                Envelope.TICKET_UNLINK -> boardRequest(envelope.type) {
                    board.unlink(TicketLinkRequest.decode(envelope.data, envelope.type)).reply(direct)
                }
                Envelope.TICKET_REORDER -> boardRequest(envelope.type) {
                    board.reorder(TicketReorder.decode(envelope.data)).reply(direct)
                }
                Envelope.TICKET_DELETE -> boardRequest(envelope.type) { board.delete(envelope.data).reply(direct) }
                Envelope.TICKET_START_NEXT -> boardRequest(envelope.type) { board.startNext().reply(direct) }

                // HARNESS-INSTALL-1 (1.100.0): detect / install the harness CLIs. Both run npm or `--version` children, so they are
                // answered off the request loop, and only to the client that asked.
                Envelope.HARNESS_INSTALL_STATUS, Envelope.HARNESS_INSTALL -> {
                    session.launch(kotlinx.coroutines.Dispatchers.IO) {
                        outgoing.trySend(
                            try {
                                val service = harnessInstaller ?: throw IllegalArgumentException("this daemon has no harness installer")
                                if (envelope.type == Envelope.HARNESS_INSTALL_STATUS) {
                                    val request = com.superiorsoftllc.agenthq.protocol.HarnessInstallStatusRequest.decode(envelope.data)
                                    Envelope(
                                        Envelope.HARNESS_INSTALL_STATUS,
                                        json.encodeToString(com.superiorsoftllc.agenthq.protocol.HarnessInstallReport.serializer(), service.status(request.checkLatest)),
                                    )
                                } else {
                                    val request = com.superiorsoftllc.agenthq.protocol.HarnessInstallRequest.decode(envelope.data)
                                    Envelope(
                                        Envelope.HARNESS_INSTALL_RESULT,
                                        json.encodeToString(com.superiorsoftllc.agenthq.protocol.HarnessInstallResult.serializer(), service.install(request.ids, request.update)),
                                    )
                                }
                            } catch (e: IllegalArgumentException) {
                                Envelope(Envelope.ERROR, "${envelope.type} refused: ${e.message ?: "invalid request"}")
                            } catch (e: Exception) {
                                Envelope(Envelope.ERROR, "${envelope.type} failed: ${e.message ?: e::class.simpleName}")
                            },
                        )
                    }
                    null
                }

                // CLIPATH-1 (1.73.0): where each CLI is launched from, and the user's override of it.
                Envelope.CLI_LOCATION_SET -> try {
                    val service = cliLocations ?: throw IllegalArgumentException("this daemon has no CLI locations")
                    val request = com.superiorsoftllc.agenthq.protocol.CliLocationSet.decode(envelope.data)
                    cliLocationsEnvelope(service.set(request.id, request.path))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "cli_location_set refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "cli_location_set failed: ${e.message ?: e::class.simpleName}")
                }

                // BUG-PERM-1 (1.76.0): the global, harness-neutral Tool approval.
                Envelope.TOOL_APPROVAL_SET -> try {
                    val service = toolApproval ?: throw IllegalArgumentException("this daemon has no Tool approval setting")
                    val request = com.superiorsoftllc.agenthq.protocol.ToolApprovalSet.decode(envelope.data)
                    toolApprovalEnvelope(service.set(request.mode))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "tool_approval_set refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "tool_approval_set failed: ${e.message ?: e::class.simpleName}")
                }

                // PROMPT-1 (1.77.0): answer an open prompt; the `prompt_resolved` push to every client is the reply.
                Envelope.PROMPT_RESPONSE -> try {
                    val service = pendingPrompts ?: throw IllegalArgumentException("this daemon has no prompts")
                    service.respond(com.superiorsoftllc.agenthq.protocol.PromptResponse.decode(envelope.data), promptClientLabel)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "prompt_response refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "prompt_response failed: ${e.message ?: e::class.simpleName}")
                }

                // PROMPT-6 (1.78.0): list the "Always allow" rules (one project, or all), or delete one.
                Envelope.APPROVAL_RULES -> try {
                    val service = pendingPrompts ?: throw IllegalArgumentException("this daemon has no prompts")
                    val project = com.superiorsoftllc.agenthq.protocol.ApprovalRulesQuery.decode(envelope.data).projectId?.trim()?.takeIf { it.isNotEmpty() }
                    approvalRulesEnvelope(project, service.rules.list(project))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "approval_rules refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "approval_rules failed: ${e.message ?: e::class.simpleName}")
                }

                Envelope.APPROVAL_RULE_DELETE -> try {
                    val service = pendingPrompts ?: throw IllegalArgumentException("this daemon has no prompts")
                    val deleted = service.rules.delete(com.superiorsoftllc.agenthq.protocol.ApprovalRuleDelete.decode(envelope.data).ruleId)
                    approvalRulesEnvelope(deleted.projectId, service.rules.list(deleted.projectId))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "approval_rule_delete refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "approval_rule_delete failed: ${e.message ?: e::class.simpleName}")
                }
                // PROMPT-5 (1.79.0): `agenthq ask`. A question for the envelope's session; this connection alone gets
                // the `prompt_answer` once the owner answers, the prompt times out or the session ends.
                Envelope.PROMPT_ASK -> try {
                    val service = pendingPrompts ?: throw IllegalArgumentException("this daemon has no prompts")
                    val asker = envelope.sessionId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("no session: the envelope's sessionId names the asking session")
                    require(manager.get(asker) != null) { "unknown session '${asker.take(80)}'" }
                    val spec = com.superiorsoftllc.agenthq.prompts.QuestionSpec.of(
                        com.superiorsoftllc.agenthq.protocol.PromptAsk.decode(envelope.data),
                    )
                    session.launch(askJobs) {
                        val answer = service.askQuestion(asker, spec)
                        val payload = com.superiorsoftllc.agenthq.protocol.PromptAnswer(answer.answered, answer.answers, answer.otherText)
                        outgoing.trySend(
                            Envelope(
                                Envelope.PROMPT_ANSWER,
                                json.encodeToString(com.superiorsoftllc.agenthq.protocol.PromptAnswer.serializer(), payload),
                                asker,
                            ),
                        )
                    }
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "prompt_ask refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "prompt_ask failed: ${e.message ?: e::class.simpleName}")
                }

                // ASK-1 (1.97.0): a NON-BLOCKING owner question. Answered at once with its id; the pick arrives as mail.
                Envelope.QUESTION_ASK -> try {
                    val service = ownerQuestions ?: throw IllegalArgumentException("this daemon has no owner questions")
                    val askerId = envelope.sessionId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("no session: the envelope's sessionId names the asking session")
                    val record = manager.get(askerId) ?: throw IllegalArgumentException("unknown session '${askerId.take(80)}'")
                    val asker = com.superiorsoftllc.agenthq.questions.Asker(askerId, record.agentId, record.role, record.projectId)
                    val created = service.ask(asker, com.superiorsoftllc.agenthq.protocol.QuestionAsk.decode(envelope.data))
                    Envelope(
                        Envelope.QUESTION_ASKED,
                        json.encodeToString(
                            com.superiorsoftllc.agenthq.protocol.QuestionAsked.serializer(),
                            com.superiorsoftllc.agenthq.protocol.QuestionAsked(created.id),
                        ),
                        askerId,
                    )
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "question_ask refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "question_ask failed: ${e.message ?: e::class.simpleName}")
                }

                Envelope.QUESTION_LIST -> try {
                    val service = ownerQuestions ?: throw IllegalArgumentException("this daemon has no owner questions")
                    questionsEnvelope(service.list())
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "question_list refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "question_list failed: ${e.message ?: e::class.simpleName}")
                }

                // The owner's pick (or an override of an auto-pick). Only the owner answers: a frame naming a session
                // (an agent) is refused. The change reaches every client, this one included, as a `question` push.
                Envelope.QUESTION_ANSWER -> try {
                    val service = ownerQuestions ?: throw IllegalArgumentException("this daemon has no owner questions")
                    require(envelope.sessionId.isNullOrBlank()) { "only the owner answers a question; an agent cannot" }
                    service.answer(com.superiorsoftllc.agenthq.protocol.QuestionAnswer.decode(envelope.data))
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "question_answer refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "question_answer failed: ${e.message ?: e::class.simpleName}")
                }

                // The owner (no sessionId) or the asking session itself withdraws an open question.
                Envelope.QUESTION_CANCEL -> try {
                    val service = ownerQuestions ?: throw IllegalArgumentException("this daemon has no owner questions")
                    val request = com.superiorsoftllc.agenthq.protocol.QuestionCancel.decode(envelope.data)
                    service.cancel(request.questionId, envelope.sessionId?.trim()?.takeIf { it.isNotEmpty() })
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "question_cancel refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "question_cancel failed: ${e.message ?: e::class.simpleName}")
                }

                Envelope.SETTINGS_SET -> try {
                    val request = SettingsSet.decode(envelope.data)
                    val updated = settings.set(request.key, request.value)
                    Envelope(Envelope.SETTINGS, json.encodeToString(JsonObject.serializer(), updated))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "settings_set refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "settings_set failed: ${e.message ?: e::class.simpleName}")
                }

                Envelope.RESUME -> {
                    val (id, prompt) = splitResume(envelope.data)
                    if (id.isNullOrBlank() || prompt.isNullOrBlank()) {
                        Envelope(Envelope.ERROR, "resume expects '<sessionId>\\n<prompt>'")
                    } else {
                        try {
                            active = manager.resume(id, prompt).id
                            null
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "resume failed")
                        }
                    }
                }

                Envelope.INTERRUPT -> {
                    // Stop the session's current turn and leave the session at rest (see SessionManager.interrupt).
                    val id = envelope.data.trim()
                    if (id.isEmpty()) {
                        Envelope(Envelope.ERROR, "interrupt expects '<sessionId>'")
                    } else {
                        try {
                            manager.interrupt(id)
                            null
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "interrupt failed")
                        }
                    }
                }

                Envelope.KILL -> {
                    val parts = envelope.data.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val id = parts.firstOrNull()
                    if (id == null) {
                        Envelope(Envelope.ERROR, "kill expects '<sessionId> [force]'")
                    } else {
                        try {
                            manager.kill(id, force = parts.drop(1).any { it.equals("force", ignoreCase = true) })
                            null
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "kill failed")
                        }
                    }
                }

                Envelope.SESSIONS -> {
                    val sessionsElements = manager.list().map { json.encodeToJsonElement(SessionRecordSerializer, it) }
                    val sessionsJson = JsonArray(sessionsElements)
                    Envelope(Envelope.SESSIONS, json.encodeToString(sessionsJson))
                }

                // BUG-M2-15b (contract 1.61.0): the record change is pushed as a `session` frame to every client by the
                // manager's event sink, so there is no direct answer; a refusal is an `error`, nothing written.
                Envelope.SESSION_PROJECT -> try {
                    val body = json.parseToJsonElement(envelope.data.ifBlank { "{}" }).jsonObject
                    val sessionId = body["sessionId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val projectId = body["projectId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(sessionId.isNotEmpty()) { "expects a sessionId" }
                    require(projects.exists(projectId)) { "no project '${projectId.take(80)}'" }
                    manager.setProject(sessionId, projectId)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "session_project refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "session_project refused: ${e.message ?: e::class.simpleName}")
                }

                // ROGUE-1 (contract 1.120.0): list / reap the processes this daemon's sessions started.
                Envelope.PROCESSES -> try {
                    val watch = processWatch ?: throw IllegalArgumentException("this daemon has no process watch")
                    val req = if (envelope.data.isBlank()) com.superiorsoftllc.agenthq.session.ProcessesRequest()
                    else json.decodeFromString(com.superiorsoftllc.agenthq.session.ProcessesRequest.serializer(), envelope.data)
                    val answer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.superiorsoftllc.agenthq.session.ProcessFrames.answer(watch, req)
                    }
                    Envelope(Envelope.PROCESSES, json.encodeToString(com.superiorsoftllc.agenthq.session.ProcessesAnswer.serializer(), answer))
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "processes refused: ${e.message ?: e::class.simpleName}")
                }

                // `agenthq bg run`: beat (register/heartbeat), done, list. The answer is always the session's job list
                // (every session's when the frame names none); the record change reaches clients as a `session` push.
                Envelope.BG_JOB -> try {
                    val req = json.decodeFromString(com.superiorsoftllc.agenthq.session.BgJobRequest.serializer(), envelope.data)
                    val sid = envelope.sessionId?.trim()?.takeIf { it.isNotEmpty() }
                    when (req.op) {
                        "beat" -> {
                            val job = req.job ?: throw IllegalArgumentException("beat expects a job")
                            require(sid != null) { "beat expects the frame's sessionId (AGENTHQ_SESSION_ID)" }
                            require(manager.bgJobBeat(sid, job)) { "no session '${sid.take(80)}'" }
                        }
                        "done" -> {
                            require(sid != null && req.jobId != null) { "done expects a sessionId and a jobId" }
                            manager.bgJobDone(sid, req.jobId)
                        }
                        "list" -> Unit
                        else -> throw IllegalArgumentException("unknown op '${req.op.take(40)}'")
                    }
                    Envelope(
                        Envelope.BG_JOB,
                        json.encodeToString(ListSerializer(com.superiorsoftllc.agenthq.session.BgJob.serializer()), manager.bgJobs(sid)),
                        sid,
                    )
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "bg_job refused: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.117.0: an agent's progress report on its own session. Validated by the reducer; the record
                // change reaches every client as a `session` push, and the answer is the session's current progress.
                Envelope.PROGRESS -> try {
                    val req = json.decodeFromString(com.superiorsoftllc.agenthq.session.ProgressReport.serializer(), envelope.data)
                    val sid = envelope.sessionId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("expects the frame's sessionId (AGENTHQ_SESSION_ID)")
                    val record = manager.reportProgress(sid, req) ?: throw IllegalArgumentException("no session '${sid.take(80)}'")
                    Envelope(
                        Envelope.PROGRESS,
                        record.progress?.let { json.encodeToString(com.superiorsoftllc.agenthq.session.SessionProgress.serializer(), it) } ?: "null",
                        sid,
                    )
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "progress refused: ${e.message ?: e::class.simpleName}")
                }

                Envelope.SESSION_RESTART -> try {
                    val body = json.parseToJsonElement(envelope.data.ifBlank { "{}" }).jsonObject
                    val sessionId = body["sessionId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(sessionId.isNotEmpty()) { "expects a sessionId" }
                    val force = body["force"]?.jsonPrimitive?.contentOrNull == "true"
                    manager.restart(sessionId, force)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "session_restart refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "session_restart failed: ${e.message ?: e::class.simpleName}")
                }

                Envelope.SESSION_DELETE -> {
                    val id = envelope.data.trim()
                    if (id.isEmpty()) {
                        Envelope(Envelope.ERROR, "session_delete expects '<sessionId>'")
                    } else {
                        try {
                            manager.delete(id)
                            // Contract 1.26.0: the delete itself gets no direct reply -- every client
                            // (not just the one that asked) hears about it through a fresh `sessions`
                            // push, exactly like any other session-list mutation.
                            val sessionsElements = manager.list().map { json.encodeToJsonElement(SessionRecordSerializer, it) }
                            val pushed = Envelope(Envelope.SESSIONS, json.encodeToString(JsonArray(sessionsElements)))
                            for (client in clients) client.trySend(pushed)
                            null
                        } catch (e: IllegalArgumentException) {
                            Envelope(Envelope.ERROR, "session_delete refused: ${e.message}")
                        }
                    }
                }

                Envelope.REPO -> {
                    val resolved = envelope.data.trim().takeIf { it.isNotEmpty() }?.let { File(it) }
                    repo = resolved
                    Envelope(Envelope.REPO, resolved?.absolutePath ?: "")
                }

                Envelope.WORKTREES -> {
                    // A bare `worktrees` answers every worktree (one git read each). A session id in `data`
                    // (the git view polls ONE agent) answers just that session's status as a `worktree`
                    // frame: same payload, no new frame, and an older client that sends no data is unchanged.
                    val sessionId = envelope.data.trim().takeIf { it.isNotEmpty() }
                    if (sessionId == null) {
                        Envelope(
                            Envelope.WORKTREES,
                            json.encodeToString(ListSerializer(WorktreeStatus.serializer()), worktrees.statuses()),
                        )
                    } else {
                        worktrees.status(sessionId)?.let(::worktreeEnvelope)
                            ?: Envelope(Envelope.ERROR, "no worktree known for session $sessionId")
                    }
                }

                Envelope.WORKTREE_REMOVE -> {
                    val parts = envelope.data.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val id = parts.firstOrNull()
                    if (id == null) {
                        Envelope(Envelope.ERROR, "worktree_remove expects '<sessionId> [force]'")
                    } else {
                        try {
                            val result = worktrees.remove(
                                id,
                                force = parts.drop(1).any { it.equals("force", ignoreCase = true) },
                            )
                            Envelope(Envelope.WORKTREE_REMOVE, json.encodeToString(CleanupResult.serializer(), result), id)
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "worktree_remove failed")
                        }
                    }
                }

                Envelope.MAIL_SEND -> {
                    val decoded = runCatching {
                        json.decodeFromString(MailSendRequest.serializer(), envelope.data)
                    }.getOrNull()
                    // A blank `from` means "the session this frame names, else this connection's active one";
                    // the client does not track session ids, the daemon does.
                    val actor = envelope.sessionId ?: active
                    val request = decoded?.let {
                        if (it.from.isBlank() && actor != null) it.copy(from = actor) else it
                    }
                    if (request == null) {
                        Envelope(
                            Envelope.MAIL_REFUSED,
                            json.encodeToString(
                                MailRefused.serializer(),
                                MailRefused("", "", null, "malformed mail_send payload"),
                            ),
                        )
                    } else {
                        when (val result = mail.send(request)) {
                            is MailResult.Sent -> {
                                // Report the CURRENT state, not the QUEUED snapshot: the pump already
                                // ran, so a sender learns whether it landed or failed in this reply.
                                val current = mailbox.message(result.message.id) ?: result.message
                                Envelope(Envelope.MAIL, json.encodeToString(Message.serializer(), current), current.to)
                            }
                            is MailResult.Refused -> Envelope(
                                Envelope.MAIL_REFUSED,
                                json.encodeToString(
                                    MailRefused.serializer(),
                                    MailRefused(request.from, request.to, request.threadId, result.reason),
                                ),
                            )
                        }
                    }
                }

                Envelope.MAIL_READ -> {
                    val id = envelope.data.trim()
                    val updated = mailbox.markRead(id)
                    if (updated == null) {
                        Envelope(Envelope.ERROR, "unknown or unreadable message '$id'")
                    } else {
                        Envelope(Envelope.MAIL, json.encodeToString(Message.serializer(), updated), updated.to)
                    }
                }

                Envelope.MAIL_INBOX -> {
                    val who = envelope.data.trim().ifEmpty { envelope.sessionId ?: active ?: "" }
                    Envelope(Envelope.MAILS, json.encodeToString(ListSerializer(Message.serializer()), mailbox.inbox(who)))
                }

                Envelope.MAIL_OUTBOX -> {
                    val who = envelope.data.trim().ifEmpty { envelope.sessionId ?: active ?: "" }
                    Envelope(Envelope.MAILS, json.encodeToString(ListSerializer(Message.serializer()), mailbox.outbox(who)))
                }

                Envelope.MAIL_THREAD -> Envelope(
                    Envelope.MAILS,
                    json.encodeToString(ListSerializer(Message.serializer()), mailbox.thread(envelope.data.trim())),
                )

                Envelope.MAIL_THREADS -> Envelope(
                    Envelope.MAIL_THREADS,
                    json.encodeToString(ListSerializer(ThreadSummary.serializer()), mailbox.threads()),
                )

                Envelope.MAIL_WAITING -> Envelope(
                    Envelope.MAIL_WAITING,
                    json.encodeToString(ListSerializer(WaitingOn.serializer()), mail.waiting()),
                )

                // Contract 1.8.0: replay history explicitly; acknowledgements are pushed back to all
                // clients by AttentionStore.eventSink, including the requesting connection.
                Envelope.ATTENTION_LIST -> Envelope(
                    Envelope.ATTENTION_ITEMS,
                    json.encodeToString(ListSerializer(AttentionItem.serializer()), attention.list()),
                )

                Envelope.ATTENTION_ACK -> {
                    val id = envelope.data.trim()
                    when {
                        id.isEmpty() -> Envelope(Envelope.ERROR, "attention_ack expects an attention id")
                        attention.acknowledge(id) == null -> Envelope(Envelope.ERROR, "unknown attention item '$id'")
                        else -> null
                    }
                }

                // --- Phase 5b: the pod command surface (contract 1.1.0) -----------------------
                // Every failure below answers with the FROZEN `error` frame (a plain string),
                // exactly as `kill` / `resume` / `worktree_remove` already do. A refusal is never a
                // silent no-op: an invalid pod, an unknown id or a blank payload all say why.
                Envelope.POD_SUBMIT -> {
                    val request = runCatching {
                        json.decodeFromString(PodSubmitRequest.serializer(), envelope.data)
                    }.getOrNull()
                    if (request == null) {
                        Envelope(Envelope.ERROR, "malformed pod_submit payload")
                    } else {
                        try {
                            // BUG-G2d (1.58.0): a pod submitted FROM a session (the envelope names it) is charged to
                            // that session's pod, one session per node, and refused past the cap or the depth limit
                            // before anything is written. A pod that names no session (user, UI) is not charged.
                            val origin = envelope.sessionId
                            val pod = spawnGuard.admit(origin, requested = request.nodes.size) { placement ->
                                // The daemon supplies WHERE the pod runs; the client supplied only WHAT.
                                // BUG-G2b: the request's project, else the daemon's default project (never dropped).
                                val spec = request.toSpec(
                                    repo = repo, defaultProjectId = projects.defaultProjectId(),
                                    parentSessionId = origin?.takeIf { placement != null },
                                )
                                orchestrator.submit(spec).also { if (placement != null) spawnGuard.recordPod(it.id, placement.root) }
                            }
                            podAnswer(orchestrator, pod.id)
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "pod_submit failed")
                        }
                    }
                }

                Envelope.POD_QUERY -> {
                    val id = envelope.data.trim()
                    if (id.isEmpty()) {
                        Envelope(Envelope.ERROR, "pod_query expects '<podId>'")
                    } else {
                        try {
                            podAnswer(orchestrator, id)
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "pod_query failed")
                        }
                    }
                }

                Envelope.POD_CANCEL -> {
                    val parts = envelope.data.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val id = parts.firstOrNull()
                    if (id == null) {
                        Envelope(Envelope.ERROR, "pod_cancel expects '<podId> [force]'")
                    } else {
                        try {
                            orchestrator.cancelPod(id, force = parts.drop(1).any {
                                it.equals("force", ignoreCase = true)
                            })
                            podAnswer(orchestrator, id)
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "pod_cancel failed")
                        }
                    }
                }

                Envelope.PROJECT_SAVE, Envelope.PROJECT_GET, Envelope.PROJECT_LIST,
                // BASE-1a (contract 1.81.0): read, set or clear a project's base branch.
                Envelope.PROJECT_BASE,
                Envelope.PROJECT_CONFIG -> projects.answer(envelope)

                // IMG-1 (contract 1.83.0): store an image under the project's `.agenthq/uploads/`, answer its path.
                Envelope.IMAGE_UPLOAD -> imageUploads?.answer(envelope)
                    ?: Envelope(Envelope.ERROR, "image_upload refused: this daemon stores no uploads")

                // CP-2 (contract 1.36.0): the git working tree, one file's diff and the history of a
                // project's own directory.
                Envelope.PROJECT_GIT_STATUS, Envelope.PROJECT_GIT_DIFF, Envelope.PROJECT_GIT_LOG,
                // BRANCH-VIEW-1 (contract 1.65.0): local branches, a branch's changes and one file's branch diff.
                Envelope.PROJECT_GIT_BRANCHES, Envelope.PROJECT_GIT_BRANCH_CHANGES, Envelope.PROJECT_GIT_BRANCH_DIFF,
                // BRANCH-SWITCH-1 (contract 1.74.0): switch the checked-out branch; a switch pushes fresh status to all.
                Envelope.PROJECT_GIT_CHECKOUT,
                // UX-CHANGES-1 (contract 1.88.0): the repository's worktrees and one worktree's state, read-only.
                // Answered by [projectGitWorker], off this loop (BUG-CHANGES-BRANCH-2).
                Envelope.PROJECT_GIT_WORKTREES, Envelope.PROJECT_GIT_WORKTREE_STATUS -> {
                    projectGitRequests.trySend(envelope)
                    null
                }

                Envelope.WORKSPACE_SAVE, Envelope.WORKSPACE_LIST -> workspaceFrames.answer(envelope)

                // EPIC-4: a project's epics, over the daemon's own epic store.
                // BASE-1c (contract 1.82.0): `epic_base` reads, sets or clears an epic's base branch.
                Envelope.EPIC_SAVE, Envelope.EPIC_LIST, Envelope.EPIC_BASE -> epicFrames.answer(envelope)

                // MEM-5 / MEM-8 (contract 1.21.0): the project's memory notes -- search, save, list.
                Envelope.MEMORY_SEARCH, Envelope.MEMORY_SAVE, Envelope.MEMORY_LIST, Envelope.MEMORY_GET,
                Envelope.MEMORY_DELETE, Envelope.MEMORY_PURGE_ROLE -> memoryFrames.answer(envelope)

                // P10-K4 (contract 1.53.0): the decisions log. An add is pushed to every client (no direct reply).
                Envelope.DECISION_ADD, Envelope.DECISION_LIST, Envelope.DECISION_GET -> decisionFrames.answer(envelope)

                // PIPE-1 (additive; contract version not bumped): the optional project pipeline.
                Envelope.PIPELINE_CONFIG, Envelope.PIPELINE_SHOW, Envelope.PIPELINE_RECORD,
                Envelope.PIPELINE_CHECK -> pipelineFrames.answer(envelope)
                Envelope.NOTIFY -> notifyFrames.answer(envelope)

                // SECRETS-1 (contract 1.100.0): the keystore. Answered by `secret_state`; a value never travels back.
                Envelope.SECRET_SET, Envelope.SECRET_CLEAR, Envelope.SECRET_IMPORT,
                Envelope.SECRET_STATUS -> keystoreFrames.answer(envelope)

                // P10-K6 (contract 1.54.0): the issues log. An open / attempt / fix is pushed to every client (no direct reply).
                Envelope.ISSUE_OPEN, Envelope.ISSUE_ATTEMPT, Envelope.ISSUE_FIX,
                Envelope.ISSUE_LIST, Envelope.ISSUE_GET, Envelope.ISSUE_PRECHECK -> issueFrames.answer(envelope)

                // MVP Sprint-Zero, I0: slice B's story board, over the daemon's own story store.
                Envelope.STORY_SAVE, Envelope.STORY_GET, Envelope.STORY_MOVE -> stories.answer(envelope)

                Envelope.STORY_LIST -> storyBoard.answer(envelope)

                else -> Envelope(Envelope.ERROR, "unknown frame type '${envelope.type}'")
            }
            // BUG-MCP-RACE-1: the direct answer echoes the request's id; a push (sent elsewhere) never carries one.
            reply?.let { outgoing.trySend(if (direct && it.requestId == null) it.copy(requestId = envelope.requestId) else it) }
        }
    } catch (e: Throwable) {
        System.err.println("[agentHq] ws handler error: $e")
    } finally {
        println("[agentHq] ws closed")
        clients.remove(outgoing)
        askJobs.cancel()
        modelsRequests.close()
        modelsWorker.cancel()
        projectGitRequests.close()
        projectGitWorker.cancel()
        readyBurstRequests.close()
        readyBurstWorker.cancel()
        outgoing.close()
        sender.cancel()
    }
}

private fun splitResume(data: String): Pair<String?, String?> {
    val newline = data.indexOf('\n')
    if (newline < 0) return null to null
    return data.substring(0, newline).trim() to data.substring(newline + 1)
}

/** Contract 1.4.0: the `agents` frame -- every profile, oldest first, every field present. */
private fun agentsEnvelope(agents: AgentProfiles): Envelope =
    Envelope(Envelope.AGENTS, json.encodeToString(ListSerializer(AgentProfile.serializer()), agents.list()))

/**
 * AGENT-ROSTER: session id -> the title of the work it is on (its ticket's title), for the rows.
 */
private fun ticketTitles(board: TicketBoard): Map<String, String> =
    board.list().mapNotNull { ticket -> ticket.sessionId?.let { it to ticket.title } }.toMap()

/** Contract 1.5.0: one ticket as a `ticket` frame, every field present. */
private fun ticketEnvelope(ticket: Ticket): Envelope =
    Envelope(Envelope.TICKET, json.encodeToString(Ticket.serializer(), ticket))

/** Contract 1.5.0: the whole board as a `tickets` frame, in board order. */
private fun ticketsEnvelope(tickets: List<Ticket>): Envelope =
    Envelope(Envelope.TICKETS, json.encodeToString(ListSerializer(Ticket.serializer()), tickets))

/** The requester's own answer, or null when the push to every client already carried it. */
/** [direct] (BUG-MCP-RACE-1): the request carried a `requestId`, so it is answered here even after the push. */
private fun TicketAnswer.reply(direct: Boolean = false): Envelope? = if (pushed && !direct) null else ticketEnvelope(ticket)

private fun BoardAnswer.reply(direct: Boolean = false): Envelope? = if (pushed && !direct) null else ticketsEnvelope(tickets)

/**
 * Run one backlog request for frame [frame]: a refusal ([IllegalArgumentException], a malformed payload
 * included) answers `<frame> refused: <why>`; anything else unexpected answers `<frame> failed: <what>`.
 */
private inline fun boardRequest(frame: String, block: () -> Envelope?): Envelope? = try {
    block()
} catch (e: IllegalArgumentException) {
    Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
} catch (e: Exception) {
    Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
}

/**
 * The `providers` answer (contract 1.3.0): every provider a spawn may name, the default first, each
 * with the model its runs use when a spawn names none, as resolved right now.
 */
internal fun providerInfos(manager: SessionManager, harnesses: HarnessRegistry? = null): List<ProviderInfo> {
    val configured = manager.providers.map { p -> providerInfo(p, p.id == manager.providerId) }
    // WIRE-1: a harness a plugin registered is listed beside the providers (a configured provider of
    // the same id wins, so `claude` appears once).
    val known = configured.map { it.id }.toSet()
    val fromHarnesses = harnesses?.harnessIds().orEmpty()
        .filter { it !in known }
        .map { ProviderInfo(id = it, default = false, defaultModel = null) }
    // CLIPATH-1 (1.73.0): a provider/harness whose CLI the resolver cannot find says so, with the paths it tried,
    // instead of looking usable and failing at spawn. A provider with no CLI (the stub) is always available.
    val tools = cliTools(harnesses).associateBy { it.id }
    return (configured + fromHarnesses).map { info ->
        val tool = tools[info.id] ?: return@map info
        val resolution = com.superiorsoftllc.agenthq.executables.CliExecutables.resolve(tool)
        if (resolution.found) info else info.copy(available = false, unavailableReason = resolution.describeFailure())
    }
}

/**
 * CLIPATH-1: every CLI this daemon can launch: the built-in table (claude, opencode, pi, git, node), then each registered
 * descriptor harness's own command. A third-party code plugin's executable is its own business and is not listed.
 */
internal fun cliTools(harnesses: HarnessRegistry?): List<com.superiorsoftllc.agenthq.executables.CliTool> {
    val builtIn = com.superiorsoftllc.agenthq.executables.CliTools.KNOWN
    val ids = builtIn.map { it.id }.toSet()
    val descriptors = harnesses?.harnessIds().orEmpty()
        .filter { it !in ids }
        .mapNotNull { (harnesses?.resolve(it) as? com.superiorsoftllc.agenthq.harness.descriptor.DescriptorHarness)?.cliTool }
    return builtIn + descriptors
}

private fun toolApprovalEnvelope(info: com.superiorsoftllc.agenthq.protocol.ToolApprovalInfo): Envelope = Envelope(
    Envelope.TOOL_APPROVAL,
    json.encodeToString(com.superiorsoftllc.agenthq.protocol.ToolApprovalInfo.serializer(), info),
)

/** PROMPT-6 (1.78.0): the `approval_rules` answer, [projectId] null = every project. */
private fun approvalRulesEnvelope(projectId: String?, rules: List<com.superiorsoftllc.agenthq.protocol.ApprovalRule>): Envelope = Envelope(
    Envelope.APPROVAL_RULES,
    json.encodeToString(
        com.superiorsoftllc.agenthq.protocol.ApprovalRulesList.serializer(),
        com.superiorsoftllc.agenthq.protocol.ApprovalRulesList(projectId, rules),
    ),
)

/** ASK-1: `questions`, every owner question oldest first (open ones and the history). */
private fun questionsEnvelope(questions: List<com.superiorsoftllc.agenthq.protocol.OwnerQuestion>): Envelope = Envelope(
    Envelope.QUESTIONS,
    json.encodeToString(ListSerializer(com.superiorsoftllc.agenthq.protocol.OwnerQuestion.serializer()), questions),
)

/**
 * BUG-PERM-1: the chat notice for a harness denial, as an additive `stderr` line (UI-BOOT-1's `[agenthq]` convention, no
 * new frame), naming the Tool approval the session runs under.
 */
internal fun denialEnvelope(
    sessionId: String,
    denial: EventNormalizer.Line.Denial,
    mode: com.superiorsoftllc.agenthq.harness.ToolApproval,
): Envelope = Envelope(
    Envelope.STDERR,
    "[agenthq] " + com.superiorsoftllc.agenthq.session.ToolApprovalNotice.text(denial.harness, denial.tool, mode, denial.prompted),
    sessionId,
)

private fun cliLocationsEnvelope(tools: List<com.superiorsoftllc.agenthq.protocol.CliLocation>): Envelope = Envelope(
    Envelope.CLI_LOCATIONS,
    json.encodeToString(com.superiorsoftllc.agenthq.protocol.CliLocations.serializer(), com.superiorsoftllc.agenthq.protocol.CliLocations(tools)),
)

/** The directory the daemon keeps its own configuration in (`AGENTHQ_HOME`, else the state dir): `commands.json`, `harnesses/`. */
internal fun daemonConfigDir(): File =
    agentHqSetting("AGENTHQ_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: stateDir()

/**
 * WIRE-1: discover every harness plugin on the classpath (ServiceLoader). One bad plugin never hides the others.
 *
 * HARNESS-DESC-1: then register the harnesses [descriptorDir] declares as JSON, beside them. The code plugins are
 * registered FIRST, so on an id clash the code wins; every descriptor that was skipped is reported to [onNote]
 * (a fixed reason, never file content). A null [descriptorDir] reads no descriptors.
 */
internal fun loadHarnesses(
    source: HarnessPluginSource = ServiceLoaderPluginSource(),
    descriptorDir: File? = File(daemonConfigDir(), "harnesses"),
    onNote: (DescriptorNote) -> Unit = { System.err.println("[harness] descriptor ${it.source}: ${it.reason}${if (it.detail.isEmpty()) "" else " (${it.detail})"}") },
): HarnessRegistry =
    HarnessRegistry().also { registry ->
        registry.discover(source)
        descriptorDir?.let { HarnessDescriptorLoader(it).loadInto(registry).notes.forEach(onNote) }
    }

/**
 * AGENT-UX-10: one provider as the `providers` frame reports it. The model list comes from the
 * provider itself -- AGENT-UX-7's canonical `knownModels()` seam, never a list we maintain -- so a
 * client's model picker is driven by what the CLI actually offers.
 *
 * `modelsStale` stays at its default false: the canonical seam exposes no staleness signal yet, so
 * the field is carried for when it does rather than guessed at here.
 */
internal fun providerInfo(provider: AgentProvider, isDefault: Boolean): ProviderInfo =
    ProviderInfo(
        id = provider.id,
        default = isDefault,
        defaultModel = provider.defaultModel(),
        models = provider.knownModels() ?: offeredModels(provider),
    )

/**
 * CP-1: a provider that does not VALIDATE models (`knownModels()` null: Claude accepts any id the CLI
 * does) still OFFERS the ones its configuration names, so the profile picker shows them. Same ids the
 * `models` frame lists; null when the listing is empty or fails, which keeps the picker's free-text field.
 */
private fun offeredModels(provider: AgentProvider): List<String>? = try {
    provider.models().flatMap { it.models }.takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}

/**
 * The `models` answer: every configured provider's models, then (ROLE-FIRST S4) the rows [catalogs] yields for
 * registered harnesses no configured provider covers (Claude Code, Pi; S5: a descriptor's `models` section). A failing
 * listing becomes a noted empty entry.
 */
private fun modelCatalog(
    manager: SessionManager,
    harnesses: HarnessRegistry? = null,
    catalogs: com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs = com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs.EMPTY,
): List<ProviderModels> {
    val fromProviders = manager.providers.flatMap { p ->
        try {
            p.models()
        } catch (e: Exception) {
            listOf(ProviderModels(p.id, "", p.id, emptyList(), "could not list models: ${e.message ?: e::class.simpleName}"))
        }
    }
    val covered = manager.providers.map { it.id }.toSet()
    // ROLE-FIRST S5: a descriptor harness's `models` section is its catalog, so it needs no Kotlin source.
    val rows = fromProviders + catalogs.rows(harnesses?.harnessIds().orEmpty(), covered) { id ->
        (harnesses?.resolve(id) as? com.superiorsoftllc.agenthq.harness.descriptor.DescriptorHarness)?.modelCatalog
    }
    // CLIPATH-1: a row whose CLI cannot be found says so (appended, so an existing note is kept), resolved once per id.
    val tools = cliTools(harnesses).associateBy { it.id }
    val missing = rows.map { it.provider }.distinct().mapNotNull { id ->
        tools[id]?.let { com.superiorsoftllc.agenthq.executables.CliExecutables.resolve(it) }?.takeIf { !it.found }?.let { id to it.reason }
    }.toMap()
    return rows.map { row ->
        val reason = missing[row.provider] ?: return@map row
        if (row.note?.contains(reason) == true) row else row.copy(note = listOfNotNull(row.note, reason).joinToString("; "))
    }
}

/** One worktree's live status as a `worktree` frame, tagged with its session. */
private fun worktreeEnvelope(status: WorktreeStatus): Envelope =
    Envelope(Envelope.WORKTREE, json.encodeToString(WorktreeStatus.serializer(), status), status.sessionId)

/**
 * The `pod` answer frame for [podId] -- what `pod_submit`, `pod_query` and `pod_cancel` all answer
 * with, because all three are answered by the same question: what is this pod's state NOW.
 *
 * An unknown id answers the frozen `error` frame rather than an empty `pod`, so a client that
 * mistyped an id can tell that apart from a pod that genuinely has no nodes yet.
 */
private fun podAnswer(orchestrator: Orchestrator, podId: String): Envelope {
    val status = orchestrator.status(podId)
        ?: return Envelope(Envelope.ERROR, "unknown pod '$podId'")
    return Envelope(Envelope.POD, json.encodeToString(PodStatus.serializer(), status))
}

/** ASK-1: how often the daemon sweeps owner questions for a `defaultAfterMin` auto-pick. */
private const val QUESTION_SWEEP_MS = 15_000L
