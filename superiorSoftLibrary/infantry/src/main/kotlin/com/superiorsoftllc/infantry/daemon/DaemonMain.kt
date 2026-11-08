// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.prompts.promptTimeoutOverride
import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.session.isBusy
import com.superiorsoftllc.infantry.session.InfantryEnv

import com.superiorsoftllc.infantry.daemon.api.attention.AttentionKinds
import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.agents.KnowledgeCounter
import com.superiorsoftllc.infantry.agents.KnowledgeLines
import com.superiorsoftllc.infantry.memory.MemoryIndexEntry
import com.superiorsoftllc.infantry.agents.KnowledgeCounts
import com.superiorsoftllc.infantry.agents.firstTurn
import com.superiorsoftllc.infantry.agents.section
import com.superiorsoftllc.infantry.harness.CommandLines
import com.superiorsoftllc.infantry.harness.HarnessPluginSource
import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.ServiceLoaderPluginSource
import com.superiorsoftllc.infantry.harness.descriptor.DescriptorNote
import com.superiorsoftllc.infantry.harness.descriptor.HarnessDescriptorLoader
import com.superiorsoftllc.infantry.epics.Epic


import com.superiorsoftllc.infantry.epics.SqlEpicPersistence
import com.superiorsoftllc.infantry.protocol.RUN_IN_FLIGHT
import com.superiorsoftllc.infantry.protocol.AgentListRequest
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.AgentSwap
import com.superiorsoftllc.infantry.protocol.AgentSwapped
import com.superiorsoftllc.infantry.protocol.SessionSwap
import com.superiorsoftllc.infantry.protocol.SessionSwapped
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.EventNormalizer
import com.superiorsoftllc.infantry.protocol.EventVocabulary
import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.protocol.ProviderInfo
import com.superiorsoftllc.infantry.protocol.ProviderModels
import com.superiorsoftllc.infantry.protocol.RosterRequest
import com.superiorsoftllc.infantry.protocol.SpawnOptions
import com.superiorsoftllc.infantry.protocol.StatusPayload
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketListQuery
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketReorder
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.SettingsSet
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.ProjectSettings
import com.superiorsoftllc.infantry.settings.SettingsService
import com.superiorsoftllc.infantry.mailbox.MailStore
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.mailbox.MailEvent
import com.superiorsoftllc.infantry.mailbox.MailRefused
import com.superiorsoftllc.infantry.mailbox.MailResult
import com.superiorsoftllc.infantry.mailbox.MailRouter
import com.superiorsoftllc.infantry.mvp.BoardHandler
import com.superiorsoftllc.infantry.mvp.ProjectHandler
import com.superiorsoftllc.infantry.mvp.ProjectStore
import com.superiorsoftllc.infantry.mvp.SqlProjectPersistence
import com.superiorsoftllc.infantry.mvp.SqlWorkspacePersistence
import com.superiorsoftllc.infantry.mvp.WorkspaceHandler
import com.superiorsoftllc.infantry.mvp.WorkspaceStore
import com.superiorsoftllc.infantry.mvp.StoryHandler
import com.superiorsoftllc.infantry.mvp.StoryHandler.Companion.asStory
import com.superiorsoftllc.infantry.mailbox.MailSendRequest
import com.superiorsoftllc.infantry.session.isInSession
import com.superiorsoftllc.infantry.daemon.ComponentBindings
import com.superiorsoftllc.infantry.daemon.UnknownComponentProviderException
import com.superiorsoftllc.infantry.mailbox.Mailbox
import com.superiorsoftllc.infantry.mailbox.MailLimits
import com.superiorsoftllc.infantry.mailbox.Message
import com.superiorsoftllc.infantry.mailbox.MessageState
import com.superiorsoftllc.infantry.mailbox.ThreadSummary
import com.superiorsoftllc.infantry.mailbox.WaitingOn
import com.superiorsoftllc.infantry.orchestration.Orchestrator
import com.superiorsoftllc.infantry.daemon.api.orchestrator.refusalOrNull
import com.superiorsoftllc.infantry.daemon.api.orchestrator.valueOr
import com.superiorsoftllc.infantry.orchestration.PodFirstTurn
import com.superiorsoftllc.infantry.orchestration.PodStatus
import com.superiorsoftllc.infantry.orchestration.PodSubmitRequest
import com.superiorsoftllc.infantry.session.RoleInstance
import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.persistence.traitReferences
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.provider.ProviderFactory
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.RunnerFactory
import com.superiorsoftllc.infantry.session.SessionStore
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionRecordSerializer
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.RunInFlightException
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.stats.SessionStats
import com.superiorsoftllc.infantry.stats.SessionStatsRequest
import com.superiorsoftllc.infantry.stats.SessionStatsService
import com.superiorsoftllc.infantry.session.KillOutcome
import com.superiorsoftllc.infantry.keystore.Keystore
import com.superiorsoftllc.infantry.keystore.KeystoreHandler
import com.superiorsoftllc.infantry.session.stateDir
import com.superiorsoftllc.infantry.session.infantrySetting
import com.superiorsoftllc.infantry.session.toWorktreeRef
import com.superiorsoftllc.infantry.stories.StoryMigration
import com.superiorsoftllc.infantry.projectgit.ProjectGitHandler
import com.superiorsoftllc.infantry.projectgit.ProjectGitService
import com.superiorsoftllc.infantry.worktree.CleanupResult
import com.superiorsoftllc.infantry.worktree.GitWorktreeManager
import com.superiorsoftllc.infantry.worktree.SystemGitCli
import com.superiorsoftllc.infantry.worktree.TicketBaseRef
import com.superiorsoftllc.infantry.worktree.TicketBranches
import com.superiorsoftllc.infantry.worktree.WorktreeStatus
import kotlinx.serialization.json.JsonArray
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import io.ktor.server.request.receiveText
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import com.superiorsoftllc.infantry.protocol.RoleSave
import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerSave
import com.superiorsoftllc.infantry.protocol.TriggerView
import com.superiorsoftllc.infantry.triggers.TriggerNudges
import com.superiorsoftllc.infantry.triggers.TriggerService
import com.superiorsoftllc.infantry.triggers.WebhookIngress
import com.superiorsoftllc.infantry.usage.GateDecision
import com.superiorsoftllc.infantry.usage.LaunchGate
import com.superiorsoftllc.infantry.usage.LaunchOrigin
import com.superiorsoftllc.infantry.usage.OperatorBudget
import com.superiorsoftllc.infantry.usage.ProviderState
import com.superiorsoftllc.infantry.usage.ProviderStateTracker
import com.superiorsoftllc.infantry.usage.RunUsage
import com.superiorsoftllc.infantry.usage.UsageRecorder
import com.superiorsoftllc.infantry.usage.LimitService
import com.superiorsoftllc.infantry.usage.ProviderLimits
import com.superiorsoftllc.infantry.daemon.api.usage.UsageMeters
import com.superiorsoftllc.infantry.usage.UsagePacing
import com.superiorsoftllc.infantry.daemon.api.usage.UsageReport
import com.superiorsoftllc.infantry.protocol.RoleIds
import com.superiorsoftllc.infantry.protocol.RoleBinding
import com.superiorsoftllc.infantry.protocol.PromptSave
import com.superiorsoftllc.infantry.protocol.PromptView
import com.superiorsoftllc.infantry.protocol.SkillSave
import com.superiorsoftllc.infantry.protocol.SkillView
import com.superiorsoftllc.infantry.protocol.TraitSave
import com.superiorsoftllc.infantry.protocol.TraitView
import com.superiorsoftllc.infantry.protocol.RoleTraits
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess
import com.superiorsoftllc.infantry.backlog.TicketEventLog
import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import com.superiorsoftllc.infantry.ticketfiles.RelatedDescription
import com.superiorsoftllc.infantry.ticketfiles.RelatedWorkSource
import com.superiorsoftllc.infantry.ticketfiles.TicketFiles
import com.superiorsoftllc.infantry.ticketfiles.TicketFilesText
import java.util.concurrent.atomic.AtomicReference

private const val DEFAULT_PORT = DaemonPort.DEFAULT
private const val DEFAULT_DB_NAME = "infantry.db"

private const val SESSION_REPLAY_REQUEST_TYPE = "replay_request"
private const val SESSION_REPLAY_GAP_TYPE = "replay_gap"

/** POLICY-1 (docs/POLICY.md): daemon -> client, the active policy (JSON PolicyWire); answers `policy_get` / `policy_set` too. */
internal const val POLICY_FRAME = "policy"
private const val SESSION_REPLAY_BUFFER_CAPACITY = 256
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** STATS-1: one per daemon process, so the per-session resolved-path cache outlives a single connection. */
private val sessionStats = SessionStatsService()

/** USAGE-1: the usage meter (package `usagemeter`, own file). The daemon touches it at three points: this val, the event sink arm and the `usage_meter_get` frame. A failure to open it never stops the daemon. */
private val usageMeter: com.superiorsoftllc.infantry.usagemeter.UsageMeter? by lazy {
    runCatching {
        com.superiorsoftllc.infantry.usagemeter.UsageMeter.open(File(stateDir(), "usage-meter.db")).also { m ->
            m.start()
            val prices = com.superiorsoftllc.infantry.usagemeter.UsagePrices(File(stateDir(), "usage-prices.json"))
            m.prices = prices::current
        }
    }
        .onFailure { System.err.println("[infantry] usage meter disabled: ${it.message}") }.getOrNull()
}

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
    /**
     * Ticket 55d60a5e: `ok`, `stuck` (no answer within `limits.health.dbTimeoutSeconds`) or `error`. Anything but `ok`
     * comes with HTTP 503 and [status] `unhealthy`; the counts above are then the last ones the database gave.
     */
    val database: String = "ok",
    /** Ticket 55d60a5e: how long the database has not answered (`stuck`), or its error message (`error`). */
    val databaseDetail: String? = null,
    /** DaemonApi 1.29.0: fields plugins add through `hooks.status`. */
    val pluginStatus: Map<String, String> = emptyMap(),
)

/** Ticket 55d60a5e: the database-backed part of `/health`, read through the [HealthGate]. */
private data class HealthCounts(
    val sessions: Int,
    val worktrees: Int,
    val pods: Int,
    val runsInFlight: Int,
    val attentionOpen: Int,
    val triggersTripped: Int,
)

/** Ticket 55d60a5e: the health limits, resolved once at start-up (see [LimitRegistry.HEALTH_DB_TIMEOUT_SECONDS]). */
data class HealthSettings(
    val dbTimeoutMs: Long,
    val watchdogFailures: Int,
    val watchdogIntervalMs: Long,
    val watchdogTimeoutMs: Long,
)

/** States from which a session can no longer take a plain prompt: a new/last-known session is used. */
private val TERMINAL = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)

/**
 * Infantry daemon.
 *
 * Phase 1 proved a live session over the WebSocket; Phase 2 put two providers behind one seam;
 * **Phase 3 turns the spawned process into a managed session**. The daemon now owns a
 * [SessionManager]: sessions are persisted (JSON on disk), survive a daemon restart, can be killed
 * and resumed, and every state change is broadcast to connected clients as a `session` frame.
 *
 * The provider seam is untouched: the daemon never names a CLI, it asks [SessionManager] to manage
 * whatever [com.superiorsoftllc.infantry.harness.AgentProvider] it was configured with.
 *
 * Phase 9 freezes the boundary in [com.superiorsoftllc.infantry.protocol.EventVocabulary]: the raw
 * provider stream keeps flowing on `stdout`/`stderr`/`exit`, and a normalised
 * `text_delta`/`status`/`error`/`finished` stream is published beside it. `INFANTRY_PROVIDER=stub`
 * runs the whole daemon with no provider CLI installed.
 *
 * Phase 5b makes the orchestrator REACHABLE. The daemon now owns the three things Phase 5 needed and
 * nothing outside could reach: a [SqlInfantryStore] on the configured database, an [Orchestrator] fed
 * by the session event sink, and the `pod_submit` / `pod_query` / `pod_cancel` frames (contract
 * 1.1.0) that submit a pod and read its state back.
 */
fun main(args: Array<String> = emptyArray()) {
    // RUNNER-POOL-10: `--mode both|runner|orchestrator` (the launcher passes it on); INFANTRY_MODE and daemon.mode are the other sources.
    DeploymentMode.fromArgs(args)?.let { System.setProperty(DeploymentMode.PROPERTY, it) }
    com.superiorsoftllc.infantry.session.InfantryEnv.installProcessMirror()
    // DAEMON-PLUGINS-1: an install (installDist, docker, headless) ships its daemon plugins in `<install>/modules`; it is the
    // bundled root unless INFANTRY_BUNDLED_MODULES or the property already names one (bundled = trusted, owner decision).
    installModules()?.let { if (System.getProperty(com.superiorsoftllc.infantry.plugin.host.PluginFolders.BUNDLED_PROPERTY) == null) System.setProperty(com.superiorsoftllc.infantry.plugin.host.PluginFolders.BUNDLED_PROPERTY, it.absolutePath) }
    com.superiorsoftllc.infantry.policy.LegacyLockEnvCheck.verdict({ System.getenv(it) })?.let {
        System.err.println("[infantry] WARNING: ${it.message}")
        if (it.refuse) exitProcess(2)
    }
    // DAEMON-LOG-1: rolling log file in the state dir (no-op when the desktop already installed its own).
    DaemonLog.install(stateDir())?.also { System.err.println("[infantry] log file: ${DaemonLog.path(stateDir())}") }
    // BUG-PORT-1-STATEDIR: one daemon per state dir, decided before anything opens the database.
    val stateLock = try {
        StateDirLock.acquire(stateDir())
    } catch (refused: StateDirInUseException) {
        System.err.println("[infantry] refusing to start: ${refused.message}")
        exitProcess(2)
    }
    val daemon = try {
        InfantryDaemon.fromEnv()
    } catch (refused: AccessConfigurationException) {
        // Refused before anything was opened: the message names the setting to change.
        System.err.println("[infantry] refusing to start: ${refused.message}")
        exitProcess(2)
    } catch (refused: IllegalArgumentException) {
        // RUNNER-POOL-10: an unknown --mode / INFANTRY_MODE / daemon.mode.
        System.err.println("[infantry] refusing to start: ${refused.message}")
        exitProcess(2)
    } catch (refused: UnknownComponentProviderException) {
        System.err.println("[infantry] refusing to start: ${refused.message}")
        exitProcess(2)
    } catch (refused: PortInUseException) {
        // PORT-1: INFANTRY_PORT_STRICT=true on a taken port. One readable line, no stack trace.
        System.err.println("[infantry] refusing to start: ${refused.message}")
        exitProcess(2)
    }
    println("[infantry] access: ${daemon.policy.describe()}")
    println("[infantry] mode: ${daemon.deploymentMode.id}")
    println("[infantry] web: ${daemon.webBundle.describe()}")
    println(
        InfantryBin.resolve()?.let { "[infantry] sessions get the infantry CLI on PATH from $it" }
            ?: "[infantry] WARNING: infantry CLI not found (run installDist or set INFANTRY_BIN); sessions cannot run `infantry knowledge`",
    )
    if (!daemon.policy.loopbackBind) {
        System.err.println(
            "[infantry] WARNING: bound to ${daemon.host}, which other machines can reach. The bearer token " +
                "and every frame cross the network in cleartext unless a TLS proxy or tunnel is in front " +
                "of this daemon (see README, \"Headless server\")."
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
        System.err.println("[infantry] refusing to start: port ${daemon.port} could not be bound (${taken.message})")
        exitProcess(2)
    }
    stateLock.recordPort(bound)
    Runtime.getRuntime().addShutdownHook(Thread { daemon.releasePortFile(); stateLock.release() })
    println("[infantry] listening on ws://${daemon.host}:$bound/ws")
    // Ticket 55d60a5e: the headless daemon only (the desktop app embeds the daemon and never runs main).
    val health = daemon.healthSettings
    if (health.watchdogFailures > 0) {
        val url = HealthWatchdog.selfUrl(daemon.host, bound)
        HealthWatchdog(url, health.watchdogIntervalMs, health.watchdogTimeoutMs, health.watchdogFailures).start()
        println(
            "[infantry] health watchdog: $url every ${health.watchdogIntervalMs / 1000} s, halts after " +
                "${health.watchdogFailures} failed checks (limits.health.watchdog*)",
        )
    } else {
        println("[infantry] health watchdog: off (limits.health.watchdogFailures = 0)")
    }
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
class InfantryDaemon(
    val host: String,
    val port: Int,
    val providerId: String,
    val runnerId: String,
    val manager: SessionManager,
    val orchestrator: Orchestrator,
    val store: InfantryStore,
    internal val clients: MutableList<Channel<Envelope>>,
    val server: EmbeddedServer<*, *>,
    /** Who may connect, decided at startup. Read by [main] for the banner; see [AccessPolicy]. */
    val policy: AccessPolicy,
    /** WEB-1 (contract 1.122.0): the browser client bundle served at `/`; read by [main] for the banner. */
    val webBundle: WebBundle,
    /** Contract 1.4.0: the agent profiles a spawn can name, stored in [store]. */
    val agents: AgentProfiles,
    /** DP-5h-3e-b: the port to the board plugin (null-safe: no board attached = no board). */
    val boardPorts: BoardPorts,
    /** ATTN-2b: the attention service as consumers hold it (the provider is Infantry_Attention's plugin). */
    val attention: com.superiorsoftllc.infantry.daemon.api.attention.Attention,
    /** Story 9.7b: the operator budget the launch gate reads. */
    val budget: OperatorBudget,
    /** Story 9.7b: observed limit state per provider. */
    val providerStates: ProviderStateTracker,
    /** Story 9.5: the daemon's triggers. */
    val triggers: TriggerService,
    /** PW-3: the workspaces, stored in [store] and checked against the project store. */
    val workspaces: WorkspaceStore,
    /** PORT-1: where [start] records the bound port (`<stateDir>/daemon.port`); null = recorded nowhere. */
    val portFile: File? = null,
    /** CLIPATH-1: puts back the executable resolver that was installed before this daemon's (see `CliExecutables.install`). */
    private val restoreExecutables: () -> Unit = {},
    /** PROMPT-1 (contract 1.77.0): the open tool-approval / question prompts; harness adapters ask through it. */
    val pendingPrompts: com.superiorsoftllc.infantry.prompts.PendingPrompts = com.superiorsoftllc.infantry.prompts.PendingPrompts(),
    /** BUG-FLAKE-1: the port [build] chose, held bound until [server] starts (BUG-PORT-0b); null = nothing held. */
    private val portReservation: DaemonPort.Reservation? = null,
    /** TICKET-HANDOVER: switches ticket workers across harnesses with a handoff turn; stopped first by [close]. */
    val handover: SessionHandover? = null,
    /** Ticket b785f383: [close] kills what ended or removed sessions left running (after the manager's own shutdown). */
    private val processHost: com.superiorsoftllc.infantry.session.LocalProcesses? = null,
    /**
     * Ticket 3b30398d: the schedulers [build] started (usage refresh, agent watchdog), stopped early by [close]; and the
     * process-watch scheduler, stopped by [close] after its last reap. A daemon that is closed leaves no thread running.
     */
    private val background: List<AutoCloseable> = emptyList(),
    /**
     * CI-FIX-PLUGINLOAD: closed by [close] AFTER the session manager's shutdown, so the exits that shutdown emits still reach the plugins
     * that hold them (the board plugin writes the killed run's `ticket_runs` row); an early close would unload the board first.
     */
    private val afterSessions: List<AutoCloseable> = emptyList(),
    /** Ticket 55d60a5e: the health limits [build] resolved at start-up; [main] starts the watchdog from them. */
    /** RUNNER-POOL-10: what this daemon is; a runner refuses the board frames. */
    val deploymentMode: DeploymentMode = DeploymentMode.BOTH,
    val healthSettings: HealthSettings = HealthSettings(
        dbTimeoutMs = LimitRegistry.HEALTH_DB_TIMEOUT_SECONDS.default * 1000L,
        watchdogFailures = LimitRegistry.HEALTH_WATCHDOG_FAILURES.default.toInt(),
        watchdogIntervalMs = LimitRegistry.HEALTH_WATCHDOG_INTERVAL_SECONDS.default * 1000L,
        watchdogTimeoutMs = LimitRegistry.HEALTH_WATCHDOG_TIMEOUT_SECONDS.default * 1000L,
    ),
    /** PLUGIN-HOTSWAP-1 (tests): the running plugin host, so a test can swap one daemon half while sessions run. */
    internal val pluginHost: () -> com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost = { com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.empty() },
) {
    @Volatile private var recordedPort: Int? = null
    /** ASK-1: the `defaultAfterMin` sweep, started by [start], stopped by [close]. */

    /**
     * PORT-1: start the server WITHOUT blocking, wait until it has bound, record the bound port in [portFile],
     * and return that port. A client follows this port (or the file), never the one that was asked for.
     *
     * BUG-FLAKE-1 / BUG-PORT-0b: the port [build] chose stays held until the server's ApplicationStarted event lets
     * go of it, right before the engine binds; that holds for a direct `server.start` too (see [build]).
     */
    fun start(): Int {
        server.start(wait = false)
        val bound = boundPort()
        portFile?.let { DaemonPort.write(it, bound) }
        recordedPort = bound
        // DAEMON-DROP-1: the embedded (desktop) daemon had no watchdog; this one only logs, with the busy threads.
        stallProbe?.close()
        stallProbe = StallProbe(HealthWatchdog.selfUrl(host, bound)).start()
        return bound
    }

    @Volatile private var stallProbe: StallProbe? = null

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
    /** PLUGIN-HOTSWAP-2: the hot hook over this daemon's plugin host, for the desktop Settings > Plugins manager. */
    fun pluginHotApply(): com.superiorsoftllc.infantry.daemon.plugins.manager.PluginHotApply =
        com.superiorsoftllc.infantry.daemon.plugins.manager.HostPluginHotApply(pluginHost)

    /** CORE-PROGRESS-DOWNLOAD-1: this daemon's downloader and progress system, for the desktop Settings > Plugins manager. */
    fun pluginDownloads(): com.superiorsoftllc.infantry.daemon.api.downloads.Downloads =
        com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.lateDownloads(pluginHost, "plugins")
    fun pluginProgress(): com.superiorsoftllc.infantry.plugin.api.Progress =
        com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.lateProgress(pluginHost, "plugins")

    fun close() {
        // Triggers stop observing FIRST: the kills below must not read as trigger-run failures; a trigger
        // still running is tripped at the next boot instead ("daemon restarted mid-run").
        runCatching { triggers.close() }
        background.forEach { runCatching { it.close() } }
        // PROMPT-1: nobody can answer after this; every suspended asker gets deny / no answer.
        runCatching { pendingPrompts.closeAll() }
        releasePortFile()
        // BUG-FLAKE-1: a daemon built but never started still holds its port.
        runCatching { portReservation?.close() }
        boardPorts.stopQueueDrain()
        runCatching { handover?.close() }
        runCatching { orchestrator.close() }
        runCatching { manager.shutdown() }
        // Ticket b785f383: a clean stop leaves nothing of an ended or removed session behind; live sessions' bg jobs stay.
        // STACK-NEUTRAL-2: the process-watch plugin (if loaded) sweeps them from its own shutdown listener.
        runCatching { processHost?.fireShutdown() }
        // CI-FIX-PLUGINLOAD: the plugins stop only now (the board applies the kills' exits first, then drains and closes).
        afterSessions.forEach { runCatching { it.close() } }
        // Everything the kills above queued is applied before the store is released; see the KDoc.
        runCatching { boardPorts.close() }
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
             *   into the operator's real `~/.infantry` state. (E1-c: the story board is the ticket board now.)
         * @param mailBaseUrl override for the URL rendered into delivered prompts (tests use their
         *   own ephemeral port rather than the default `127.0.0.1:<port>`).
         */
        fun build(
            port: Int = configuredPort(),
            host: String = RemoteAccess.resolveHost(stateDir()),
            provider: com.superiorsoftllc.infantry.harness.AgentProvider =
                com.superiorsoftllc.infantry.provider.ProviderFactory.fromEnv(),
            runner: ProcessRunner = provider.transport()
                ?: com.superiorsoftllc.infantry.runner.RunnerFactory.fromFlag(),
            repo: File? = InfantryEnv.get("INFANTRY_REPO")?.takeIf { it.isNotBlank() }?.let { File(it) },
             sessionStore: SessionStore = JsonSessionStore.default(),
             mailStore: MailStore = JsonMailStore.default(),
             store: InfantryStore = SqlInfantryStore.open(defaultDbPath()),
             /** E1-c (contract 1.62.0): IGNORED -- the story frames are shims over the ticket board. Kept so callers still compile. */
             @Suppress("UNUSED_PARAMETER") storyStore: com.superiorsoftllc.infantry.stories.StoryStore? = null,
            /** Null (no `INFANTRY_MAIL_BASE`) = `http://<agent-reachable host>:<the port actually chosen>` (PORT-1). */
            mailBaseUrl: String? = InfantryEnv.get("INFANTRY_MAIL_BASE")?.takeIf { it.isNotBlank() },
            /**
             * The bearer token every route except `/health` requires. Defaults to the environment
             * (`INFANTRY_TOKEN_FILE`, else `INFANTRY_TOKEN`); null is loopback-only with no credential.
             * Validated before anything else in the body runs: a non-loopback bind without a token throws
             * [AccessConfigurationException] here.
             */
            token: String? = RemoteAccess.resolveToken(stateDir()),
            /**
             * SEC-FIX-1 (1.136.0): the OWNER credential (`<stateDir>/owner.token`, generated once), apart from [token]. Never
             * handed to a spawned agent; only a connection presenting it may send [FrameGate.OWNER_ONLY] frames.
             */
            ownerToken: String = OwnerCredential.ensure(stateDir()),
            /**
             * DAEMON-PLUGINS-1: the plugins root (`<root>/core|official|community/<Module>`); null = the environment's
             * (`INFANTRY_PLUGINS_ROOT`, else `<stateDir>/plugins`, plus the bundled root). A test passes a temp folder.
             */
            pluginsRoot: File? = null,
            /** DAEMON-PLUGINS-1: which official daemon jars load unsigned; null = the bundled root and `infantry.plugins.devTrust`. */
            pluginTrust: com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.Trust? = null,
            /** DAEMON-PLUGINS-1: the environment the plugins read (`DaemonApi.env`, only declared names); a test passes its own. */
            pluginEnv: (String) -> String? = { com.superiorsoftllc.infantry.session.InfantryEnv.get(it) },
            /** ORCH-1: orchestrator bindings offered besides the plugins' (selected by `INFANTRY_ORCHESTRATOR_PROVIDER` through [pluginEnv]); a test passes its fake remote. */
            orchestratorBindings: List<Pair<String, com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorBinding>> = emptyList(),
            /** RUNNER-POOL-10: what this daemon is (null = `--mode` / `INFANTRY_MODE`, else the setting `daemon.mode`, else both). */
            mode: DeploymentMode? = null,
            /**
             * Contract 1.3.0: the providers a spawn may name besides [provider], from `INFANTRY_PROVIDERS`.
             * Empty (the default when the variable is unset) = this daemon serves [provider] only.
             */
            additionalProviders: List<AgentProvider> = ProviderFactory.additionalFromEnv(provider),
            fixedFirstTurn: (() -> Pair<String, List<Pair<String, Int>>>)? = null,
            /** Story 9.7b: the operator budget (`INFANTRY_BUDGET_RUNS_PER_DAY` / `_TOKENS_PER_DAY`; unset = none). */
            budget: OperatorBudget = OperatorBudget.fromEnv(),
            /**
             * BRANCH-PER-TASK 1/3 + BUG-REGRESS-1: the ONE resolver of the ref a ticket start cuts its own branch
             * from. Defaults to `INFANTRY_BASE_BRANCH`, else NONE (BUG-BASE-087: a start with no base configured is
             * refused, never cut from the main checkout's HEAD); BASE-1a plugs the project's own setting in there.
             */
            baseRef: TicketBaseRef = TicketBaseRef.fromEnv(orElse = TicketBaseRef.NONE),
            /** IN-APP BUILD T2: saved commands file, `<INFANTRY_HOME>/commands.json` (home defaults to the state dir). */
            commandsFile: File = File(daemonConfigDir(), "commands.json"),
            /** WIRE-1: the harness plugins this daemon discovered (ServiceLoader by default); listed by `providers`. */
            harnesses: HarnessRegistry = loadHarnesses(),
            /** ROLE-FIRST S4: model listings for registered harnesses that have no provider of their own (Pi). */
            modelCatalogs: com.superiorsoftllc.infantry.provider.HarnessModelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.builtIn(),
            /** BUG-CHANGES-BRANCH-2: the git the `project_git_*` frames read through; a test hands in a slow fake. */
            projectGitCli: com.superiorsoftllc.infantry.worktree.GitCli = SystemGitCli(),
            /** PERF-3: the machine the executable resolver reads (PATH, file stats); a test hands in a slow fake. */
            executableEnvironment: com.superiorsoftllc.infantry.executables.ExecutableEnvironment =
                com.superiorsoftllc.infantry.executables.SystemExecutableEnvironment,
            /** BUG-G2: the `infantry` CLI's bin dir, prepended to every spawned session's PATH; null = PATH untouched. */
            infantryBin: File? = InfantryBin.resolve(),
            /**
             * BUG-G2d: how many live sessions a session may grow its pod by, and
             * how deep spawns from inside sessions may nest. null (the real daemon) = the live settings `limits.concurrency.maxPerPod` / `maxDepth`; a test passes a fixed value. See [SpawnGuard].
             */
            podSpawnCap: Int? = null,
            podSpawnDepth: Int? = null,
            /** U1 (contract 1.59.0): the plan-limit meters; `INFANTRY_LIMITS_LIVE=false` turns the live HTTP reads off. */
            limits: LimitService = LimitService.fromEnv(extraSources = { harnesses.limitSources() }),
            /**
             * E1-a: the `stories.json` folded into the ticket board at startup ([StoryMigration]); null = no migration.
             * Only [fromEnv] (the real daemon) passes the state dir's file, so a test never migrates the operator's state.
             */
            storyMigrationFile: File? = null,
            /** DP-5h-3d-1: false = a daemon with no board (project/workspace frames work; ticket frames answer `<type> refused: this daemon has no board`). */
            withBoard: Boolean = true,
            /** STALL-1: where the watch shims are written; null (tests) = no shims. Only [fromEnv] passes the state dir's. */
            shimDir: File? = null,
            /**
             * PORT-1: `INFANTRY_PORT_STRICT=true` refuses to start on a taken [port] ([PortInUseException]) instead of
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
             * INSTALL-CWD-1: `INFANTRY_PROJECT_DIRS=true` (pinned by the installed app's InstallProfile) = with no [repo],
             * sessions and ticket starts run in their project's own directory and cut from its checkout, and a project
             * with no directory is refused; [workingDir] is then never used as a session's folder. Off = unchanged.
             */
            projectDirs: Boolean = com.superiorsoftllc.infantry.session.ProjectFolders.enabledFromSettings(),
            /** IMG-5: "now" for upload mtimes and the chat image retention sweep; a test passes a fake clock. */
            clock: () -> Long = System::currentTimeMillis,
            /**
             * INSTALL-REFOCUS-1: routes the embedding app adds to this server, behind the same [AccessPolicy] as every
             * other route. The daemon knows nothing about them (the installed desktop app adds its window activation).
             */
            extraRoutes: io.ktor.server.routing.Route.() -> Unit = {},
            /** RUNNER-POOL-11: this daemon's state dir (the process one by default), so two daemons in one JVM keep apart. */
            daemonStateDir: File = stateDir(),
            /** Contract 1.115.0 (ASSISTANT-1): where the global Assistant runs: a folder of its own under the app's data dir. */
            assistantDir: File = File(daemonStateDir, "assistant"),
            /** TICKET-HANDOVER: how long a switched ticket worker's old agent gets to write its handoff note. */
            handoffTimeoutMs: Long = SessionHandover.DEFAULT_TIMEOUT_MS,
            /** WEB-1 (contract 1.122.0): the browser client bundle served at `/` (none when it is not installed). */
            webBundle: WebBundle = WebBundle.load(WebBundle.locate()),
            /** TAB-SHOTS-DIFF: new session ids; the tab-shots renderer pins them so two renders are pixel-identical. */
            sessionIds: () -> String = { UUID.randomUUID().toString() },
            /** ROGUE-1: free RAM for the process watch; the tab-shots renderer pins it so the host's RAM never adds a low-memory notice. */
            memory: com.superiorsoftllc.infantry.session.MemorySource = com.superiorsoftllc.infantry.session.OsMemorySource,
            /**
             * POLICY-1 (docs/POLICY.md): the ONE switch: which policy preset is active (env-pinned, a preset's `selectWhenEnv`,
             * or the owner's Settings pick). A seated preset's lockdown is a daemon plugin's (`seats` hook). A test passes its own.
             */
            policies: com.superiorsoftllc.infantry.policy.PolicyRuntime = com.superiorsoftllc.infantry.policy.PolicyRuntime.fromEnvironment(daemonStateDir),
        ): InfantryDaemon {
            // SANDBOX-1: the scoped token sandboxed agents get instead of the owner token (in memory only, new every start).
            val agentToken = if (token != null) AgentFrames.newToken() else null
            // DAEMON-PLUGINS-1: the daemon plugins, loaded once the managers they reach exist (below); every seam reads this
            // holder, so until then (and with no daemon plugin) each answers neutrally.
            val pluginHost = java.util.concurrent.atomic.AtomicReference(com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.empty())
            // AR-4b: core reads roles through the Roles port (no provider = no persona), looked up at call time.
            val rolesLookup = com.superiorsoftllc.infantry.daemon.RolesLookup { pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.roles.Roles::class) }
            // DAEMON-PLUGINS-1 (`admission`): the guest tokens an official plugin admits NOW (read per request).
            val policy = AccessPolicy.of(host, token, agentToken = agentToken).withGuestTokens { pluginHost.get().guestTokens() }
            // SEC-FIX-1: built once; refused when it equals the access token every agent is handed.
            val ownerCredential = OwnerCredential.forDaemon(ownerToken, token)
            // PORT-1: FIRST, because the mail base and every session's INFANTRY_URL below carry the port.
            // BUG-FLAKE-1: and HELD from here until start(), so no other socket on the host can take it meanwhile.
            val portReservation = DaemonPort.reserve(port, host, strictPort)
            val portChoice = portReservation.choice
            portChoice.note?.let { println("[infantry] $it") }
            val boundPort = portChoice.port
            val mailBase = mailBaseUrl ?: "http://${AccessPolicy.agentReachableHost(host)}:$boundPort"
            val loopbackHost = AccessPolicy.agentReachableHost(host)
            val worktrees = GitWorktreeManager()
            // Every agent this daemon spawns receives the token as INFANTRY_TOKEN, so the curl a delivered
            // prompt carries can authenticate, AND the daemon's agent-facing mail base as INFANTRY_MAIL_BASE,
            // so a running agent can INITIATE a handoff to a colleague (its own session id is added per run
            // by SessionManager). Absent a token, only the mail base is added.
            // STALL-1: the watch shims, rewritten at every start (the CLI launcher may have moved). Null = could not write.
            val watchShimDir: File? = shimDir?.let { dir ->
                runCatching { com.superiorsoftllc.infantry.runner.WatchShims.install(dir, com.superiorsoftllc.infantry.runner.WatchShims.launcher(infantryBin)) }
            }?.onFailure { System.err.println("[infantry] watch shims not written: ${it.message}") }?.getOrNull()
            val childEnv = buildMap {
                // BUG-G2: `infantry knowledge` (P10-K9) must resolve inside every session, so the CLI's bin
                // dir goes first on the child's PATH (and is exported as INFANTRY_BIN).
                // CLI-PATH-1: no launcher found = sessions get no `infantry` on PATH; say so loudly instead of omitting it.
                if (infantryBin == null) System.err.println("[infantry] WARNING: no infantry CLI launcher found (set ${InfantryBin.BIN_ENV}); spawned sessions will NOT have `infantry` on PATH")
                putAll(InfantryBin.childEnv(infantryBin))
                // BUG-G2c: and the CLI must reach THIS daemon, not the default 8787 (INFANTRY_URL; an inherited one wins).
                putAll(InfantryBin.urlEnv(host, boundPort))
                // SEC 6d635c2f: an exported app's daemon blanks the owner credential its agents would inherit.
                if (RemoteAccess.credentialsIsolated()) putAll(RemoteAccess.inheritedCredentialScrub())
                // RUN-CMD-REPO-1: the updater's token (a PAT) and any other inherited PAT never reach an agent session.
                putAll(RemoteAccess.inheritedSecretScrub())
                if (token != null) putAll(RemoteAccess.daemonTokenEnv(token))
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
            // RUNNER-POOL-10: a value that is no mode refuses the start, here, before anything else is wired.
            val deploymentMode = mode ?: DeploymentMode.resolve(infantrySetting(DeploymentMode.ENV) ?: System.getProperty(DeploymentMode.PROPERTY), null, settings.daemonMode())
            // STALL-3: the hooks and the process watch read the tool profiles in force from here on (refreshed per launch).
            com.superiorsoftllc.infantry.runner.ToolProfiles.active = settings.toolProfiles()
            // STACK-NEUTRAL-1: the watch tooling is opt-in; an install that existed before keeps it on (migrated once).
            settings.migrateWatchEnabled {
                sessionStore.load().isNotEmpty() ||
                    File(daemonStateDir, "process-ledger.json").isFile
            }
                ?.let { println("[infantry] limits.watch.enabled set to $it (${if (it == 1L) "existing install: watch tooling kept on" else "fresh install: watch tooling off until enabled"})") }
            // STACK-NEUTRAL-6: this repo's test-fork properties live in the install's tool-profile override, not the bundled profile.
            if (settings.migrateRepoProfileAdditions { sessionStore.load().isNotEmpty() || File(daemonStateDir, "process-ledger.json").isFile }) {
                com.superiorsoftllc.infantry.runner.ToolProfiles.active = settings.toolProfiles()
            }
            // Ticket 55d60a5e: resolved now, while the database answers; /health and its watchdog never read it later.
            val healthSettings = HealthSettings(
                dbTimeoutMs = settings.limit(LimitRegistry.HEALTH_DB_TIMEOUT_SECONDS) * 1000L,
                watchdogFailures = settings.limit(LimitRegistry.HEALTH_WATCHDOG_FAILURES).toInt(),
                watchdogIntervalMs = settings.limit(LimitRegistry.HEALTH_WATCHDOG_INTERVAL_SECONDS) * 1000L,
                watchdogTimeoutMs = settings.limit(LimitRegistry.HEALTH_WATCHDOG_TIMEOUT_SECONDS) * 1000L,
            )
            val healthGate = HealthGate(healthSettings.dbTimeoutMs)
            // The last counts the database gave, shown beside a 503 while it is stuck.
            var lastHealthCounts: HealthCounts? = null
            // CLIPATH-1: every launch (harness, session adapter, model listing, git) resolves its executable through
            // this one resolver, which reads the user's Settings overrides from the store.
            val executableOverrides = com.superiorsoftllc.infantry.executables.StoredExecutableOverrides(store)
            val harnessToolsDir = File(daemonConfigDir(), "harness-tools")
            val executableResolver = com.superiorsoftllc.infantry.executables.ExecutableResolver(executableEnvironment, executableOverrides, harnessToolsDir.path)
            val restoreExecutables = com.superiorsoftllc.infantry.executables.CliExecutables.install(executableResolver)
            // HARNESS-PLUGIN-3 H4: the harness modules, set once the first resolution and the daemon halves are loaded (below).
            val harnessModulesRef = java.util.concurrent.atomic.AtomicReference<com.superiorsoftllc.infantry.daemon.plugins.HarnessModules?>(null)
            // HARNESS-INSTALL-1: npm installs of the harness CLIs go to <INFANTRY_HOME>/harness-tools; launches resolve them from there.
            // HARNESS-PLUGIN-4 H5a: plus each loaded harness module's `harness.cli.install` (read per call).
            val harnessInstaller = com.superiorsoftllc.infantry.harness.install.HarnessInstaller(
                harnessToolsDir, executableResolver, executableEnvironment, executableOverrides,
                fromModules = { harnessModulesRef.get()?.installables().orEmpty() },
            )
            val cliLocations = com.superiorsoftllc.infantry.executables.CliLocationService(
                executableResolver, executableOverrides, tools = { cliTools(harnesses, harnessModulesRef.get()?.cliTools().orEmpty()) },
            )
            // BUG-PERM-1: every run resolves its Tool approval through this store-backed global value.
            val toolApproval = com.superiorsoftllc.infantry.session.ToolApprovalService(store)
            val restoreToolApproval = com.superiorsoftllc.infantry.session.ToolApprovals.install(toolApproval::stored)
            // BUG-REAP-OWNER: no id (unwritable dir) => nothing is ever reaped, which is the safe side.
            val instanceId = runCatching { InstanceId.loadOrCreate(daemonStateDir) }.getOrNull()
            // SANDBOX-1: every CLI launch goes through the sandbox seam; with limits.sandbox.mode = 0 it is a pass-through.
            val sandboxPlan = com.superiorsoftllc.infantry.runner.SandboxPlan(daemonStateDir)
            val sandboxConfig = {
                com.superiorsoftllc.infantry.runner.SandboxConfig(settings.limit(LimitRegistry.SANDBOX_MODE) == 1L, settings.sandboxAllowedDirs(), agentToken)
            }
            fun sandboxed(r: ProcessRunner): ProcessRunner = com.superiorsoftllc.infantry.runner.SandboxRunner(r, sandboxPlan, sandboxConfig)
            settings.sandboxStatus = {
                com.superiorsoftllc.infantry.runner.SandboxSupport.status(sandboxConfig().enabled) +
                    (if (sandboxConfig().enabled && token == null) "; WARNING: this daemon has no token, so agents reach it unauthenticated over loopback (set INFANTRY_TOKEN_FILE)" else "")
            }
            // STACK-NEUTRAL-2: the per-launch env seam (`api.launchEnv`). Core's base: the watch OFF unless a plugin turns it on, the
            // tool profiles and the tree-wait cap the runner a session starts reads, and the runner's space limits.
            val launchEnvHost = com.superiorsoftllc.infantry.session.LaunchEnvHost(base = { _ ->
                com.superiorsoftllc.infantry.runner.WatchShims.sessionEnv(
                    null, null, forced = false,
                    treeWaitMinutes = settings.limit(LimitRegistry.WATCH_TREE_WAIT_MINUTES),
                    profiles = settings.toolProfiles(),
                ) +
                    // STALL-4: the runner's space limits and the follow-redirects switch reach every run a session starts.
                    com.superiorsoftllc.infantry.cli.BgCli.watchLimitEnv(
                        logMaxMb = settings.limit(LimitRegistry.WATCH_LOG_MAX_MB),
                        logTotalMb = settings.limit(LimitRegistry.WATCH_LOG_TOTAL_MB),
                        keepJobs = settings.limit(LimitRegistry.WATCH_LOG_KEEP_JOBS),
                        keepDays = settings.limit(LimitRegistry.WATCH_LOG_KEEP_DAYS),
                        minFreeMb = settings.limit(LimitRegistry.WATCH_MIN_FREE_MB),
                        fileIdleSeconds = settings.limit(LimitRegistry.WATCH_FILE_IDLE_SECONDS),
                        followRedirects = settings.watchFollowRedirects(),
                        spaceCheckSeconds = settings.limit(LimitRegistry.WATCH_SPACE_CHECK_SECONDS),
                        tailReadKb = settings.limit(LimitRegistry.WATCH_TAIL_READ_KB),
                    )
            })
            // PROC-LEAK-1: one OS container per session (a Windows Job Object), shared by the manager (assigns) and api.processes (reads).
            val sessionContainers = com.superiorsoftllc.infantry.runner.SessionContainers.forHost(instanceId)
            val manager = SessionManager(
                provider,
                sandboxed(runner),
                sessionStore,
                worktrees = worktrees,
                childEnv = childEnv,
                bindingPolicy = com.superiorsoftllc.infantry.daemon.plugins.TicketBindingPolicy(),
                additionalProviders = additionalProviders,
                cliRunner = sandboxed(cliRunner),
                lineRunner = sandboxed(com.superiorsoftllc.infantry.runner.PipedProcessRunner()),
                harnesses = harnesses,
                instances = store.sessionInstances,
                lifecyclePolicy = { com.superiorsoftllc.infantry.session.host.LifecyclePolicy(settings.keepAliveMinutes()) },
                defaultWorkingDir = workingDir,
                instanceId = instanceId,
                // Ticket 66153313: every worker run gets the build limits (priority, Gradle workers, forks) of the settings.
                // STALL-1 (ticket 802c64d5): and the watch shims first on PATH (gradle/mvn/npm/yarn/pnpm run watched), unless
                // the owner turned the forced watch off (`limits.watch.forced` = 0).
                buildEnv = { info ->
                    // STALL-3: the tool profiles are read at every launch; a changed list rewrites the shims.
                    val profiles = settings.toolProfiles()
                    if (profiles != com.superiorsoftllc.infantry.runner.ToolProfiles.active) {
                        com.superiorsoftllc.infantry.runner.ToolProfiles.active = profiles
                        watchShimDir?.let { dir ->
                            runCatching { com.superiorsoftllc.infantry.runner.WatchShims.install(dir, com.superiorsoftllc.infantry.runner.WatchShims.launcher(infantryBin)) }
                        }
                    }
                    // STACK-NEUTRAL-2: core's base env (watch off, the profiles, the runner's space limits), then every plugin's
                    // contributions (the process-watch plugin adds the build limits, the shims and `INFANTRY_WATCH=on`).
                    // TOKEN-1: the role's response-size fields reach the tool layer as the settings' env names; absent = the global limits.
                    val role = info.role?.let { rolesLookup.role(it) }
                    val detail = when (role?.responseDetail) { "ack" -> 1L; "summary" -> 2L; "full" -> 3L; else -> null }
                    launchEnvHost.collect(info) + listOfNotNull(
                        detail?.let { LimitRegistry.MCP_DEFAULT_DETAIL.env to it.toString() },
                        role?.responseMaxChars?.let { LimitRegistry.MCP_MAX_RESULT_CHARS.env to it.toString() },
                        role?.responseBodyChars?.let { LimitRegistry.MCP_BODY_CHARS.env to it.toString() },
                    ).toMap()
                },
                // No-hard-coded-limits S2: the watchdog and silent sweeper read limits.watchdog.* live.
                limitSource = settings::limit,
                idFactory = sessionIds,
                containers = sessionContainers,
            )
            // BUG-M2-11f: a new keep-alive value applies to the processes already kept, not only to the next launch.
            settings.onKeepAliveChanged = manager::lifecyclePolicyChanged
            // LIMITS: the model allowlist and the daemon-wide working-session cap, read live.
            // POLICY-1: the active preset's lists apply while the settings (and their env) are empty.
            manager.allowedModels = { settings.allowedModels().takeIf { it.restricts } ?: com.superiorsoftllc.infantry.settings.ModelAllowlist.parse(policies.current.allowedModels.joinToString(",")) }
            manager.allowedRoles = { settings.allowedRoles().takeIf { it.restricts } ?: com.superiorsoftllc.infantry.settings.ModelAllowlist.roles(policies.current.allowedRoles.joinToString(",")) }
            if (settings.allowedRoles().restricts) println("[infantry] role allowlist in force: ${settings.allowedRoles().patterns.joinToString()}")
            manager.maxSessions = { settings.limitCap(LimitRegistry.MAX_SESSIONS) }
            if (settings.allowedModels().restricts) println("[infantry] model allowlist in force: ${settings.allowedModels().patterns.joinToString()}")
            val agentsWired = com.superiorsoftllc.infantry.daemon.wiring.AgentsWiring.build(store, manager, rolesLookup, com.superiorsoftllc.infantry.daemon.RoleLists({ pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.roles.RoleBindings::class) }, { pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.roles.RoleTraitLists::class) }))
            val agentRegistry = agentsWired.registry
            val agentModules = agentsWired.modules
            val agents = agentsWired.profiles
            // TRAITS-T4: the chokepoint's fallback, so a spawn path that resolves no instance itself still freezes one.
            manager.instanceFor = agents::instanceFor
            manager.assistantDir = assistantDir
            // PROGRESS-S5: the opt-in estimated mode's per-project label durations outlive a restart.
            manager.progressEstimates = com.superiorsoftllc.infantry.session.FileProgressEstimates(
                File(daemonStateDir, com.superiorsoftllc.infantry.session.FileProgressEstimates.FILE_NAME),
            )
            // PROGRESS-ALL: every working ticket session shows a daemon-computed estimate (no model call, zero tokens).
            com.superiorsoftllc.infantry.session.AutoProgressHistory(store).install(settings)
            val clients = CopyOnWriteArrayList<Channel<Envelope>>()
            // STACK-NEUTRAL-2: the host side of `api.processes` (the OS table, the start-instant-checked kill, the session roots, the
            // close event, the gate lock). The detector, the ledger and the reaping are Infantry_Processes' daemon half.
            val alertSink = java.util.concurrent.atomic.AtomicReference<(String, String) -> Unit> { _, _ -> }
            val localProcesses = com.superiorsoftllc.infantry.session.LocalProcesses(
                source = com.superiorsoftllc.infantry.session.OsProcessSource,
                table = com.superiorsoftllc.infantry.session.host.OsProcessTable,
                memory = memory,
                sessionList = manager::list,
                profiles = { com.superiorsoftllc.infantry.runner.ToolProfiles.active },
                shimDirectory = { watchShimDir },
                inherited = { name -> childEnv[name] ?: System.getenv(name) },
                pressure = { reportJson -> for (c in clients.toList()) c.trySend(Envelope(Envelope.PROCESS_PRESSURE, reportJson)) },
                alert = { outcome, detail -> alertSink.get().invoke(outcome, detail) },
                instanceId = instanceId,
                containers = sessionContainers,
            )
            // Ticket b785f383: a closing session's background processes die with it (the plugin's listener does it, after a grace).
            manager.onSessionClosed = { c -> localProcesses.fireSessionClosed(c) }
            val pendingPrompts = com.superiorsoftllc.infantry.daemon.wiring.PromptsWiring.build(object : com.superiorsoftllc.infantry.daemon.api.ApprovalRulesHook { override fun match(projectId: String?, tool: String, input: String) = pluginHost.get().approvalRules.match(projectId, tool, input); override fun remember(projectId: String?, tool: String, input: String) = pluginHost.get().approvalRules.remember(projectId, tool, input) }, settings, manager, clients)
            // IN-APP BUILD T3: one saved command at a time; every frame it produces goes to every client.
            // SECRETS-1: the daemon-owned keystore, in the state dir.
            val keystore = Keystore(daemonStateDir)
            // HARNESS-GAP G18: every known secret value is masked in all session output (chat rows, transcript, raw stream, a terminal's screen).
            manager.outputMask = com.superiorsoftllc.infantry.keystore.SecretMask(keystore::allValues)
            // POLICY-1: "restricted" follows the ACTIVE preset (a Settings switch included), read per call. A seated preset is
            // always restricted (secrets locked), as the seated lockdown was before it became a plugin.
            val locked: () -> Boolean = { policies.current.let { it.restricted == true || it.seats != null } }
            val foundAttention = com.superiorsoftllc.infantry.daemon.FoundAttention { pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.attention.Attention::class) }
            val attentionSvc: com.superiorsoftllc.infantry.daemon.api.attention.Attention = foundAttention
            // Ticket 66153313 (via `api.processes.raiseAlert`): the process-watch plugin reports agent CPU held above the limit.
            alertSink.set { outcome, detail ->
                println("[infantry] $outcome: $detail")
                runCatching { attentionSvc.raise(AttentionKinds.CEILING, outcome = outcome, detail = detail) }
                    .onFailure { System.err.println("[infantry] $outcome attention not raised: ${it.message}") }
            }
            // Story 9.7b: observed limit state per provider -- persisted, pushed, and put back after a restart.
            val providerStates = ProviderStateTracker(
                quotaHitHoldMs = com.superiorsoftllc.infantry.settings.LimitRegistry.fromEnv(
                    com.superiorsoftllc.infantry.settings.LimitRegistry.USAGE_QUOTA_HIT_HOLD_MINUTES, System::getenv,
                ) * 60_000L,
                sink = { state ->
                    runCatching { store.providerStates.upsert(state) }
                        .onFailure { System.err.println("[infantry] provider state not stored: ${it.message}") }
                    broadcastProviderState(clients, state)
                },
            )
            providerStates.restore(store.providerStates.all())
            // Story 9.7b: today's budget is recounted from the runs this daemon stored, so a restart does not reset it.
            val dayStart = System.currentTimeMillis() / 86_400_000L * 86_400_000L
            store.usage.since(dayStart).groupBy { it.provider }.forEach { (providerId, runs) ->
                budget.restore(providerId, runs.size, runs.sumOf { r -> r.usage?.let { it.input + it.output + it.reasoning } ?: 0L })
            }
            val launchGate = LaunchGate(providerStates, budget, attentionSvc)
            // ORCH-1: the runner reaches the orchestration side only through this hub's link (docs/DAEMON-CORE.md 6.5). The local
            // binding answers from the functions below; the board and the ticket files exist later, so they are filled in then.
            val boardPorts = BoardPortRef()
            // RUNNER-POOL-2 (docs/RUNNER-POOL.md 2.1-2.2): the orchestrator side reaches sessions through the runner pool. It holds one
            // runner, `local`, whose link wraps the session manager unchanged; spawns and mail resumes below go through it.
            val runnerProjects = java.util.concurrent.atomic.AtomicReference<() -> List<String>> { emptyList() }
            // RUNNER-POOL-6: the runner side of starts an orchestrator sends with a git remote (clone into runners.cloneRoot, cut,
            // push on each turn end), and the orchestrator side's paired runners (set once the runner link service exists).
            // RUNNER-E2E-2: the runner side of an on-demand transcript read (set once the stream sequencer exists).
            val runnerTranscript = java.util.concurrent.atomic.AtomicReference<(String, Int, Int) -> List<String>> { _, _, _ -> emptyList() }
            val remoteStarts = com.superiorsoftllc.infantry.runners.RemoteStarts(
                cloneRoot = { settings.runnerLink().cloneRoot.takeIf { it.isNotBlank() }?.let(::File) ?: File(daemonStateDir, com.superiorsoftllc.infantry.runners.RemoteStarts.DEFAULT_FOLDER) },
                outboxMax = { settings.limit(LimitRegistry.RUNNERS_OUTBOX_MAX).toInt() },
                persistTo = File(daemonStateDir, "runner-outbox.json"),
            )
            val runnerLinksRef = java.util.concurrent.atomic.AtomicReference<com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinks>(com.superiorsoftllc.infantry.daemon.api.runners.NoRunnerLinks)
            val localRunnerLink = com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink(
                LocalRunner.services(
                    manager, agents,
                    info = {
                        com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo(
                            name = com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL,
                            contract = com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink.CONTRACT_VERSION,
                            projects = runnerProjects.get()(),
                            // Installed = registered here; signed in = not refused by its provider (no quota or stall seen).
                            harnesses = manager.providers.map { p ->
                                com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHarness(
                                    p.id, p.knownModels().orEmpty(),
                                    loggedIn = providerStates.state(p.id).status == com.superiorsoftllc.infantry.usage.ProviderStatus.UNKNOWN,
                                )
                            },
                            capacity = settings.limitCap(LimitRegistry.MAX_SESSIONS),
                            os = System.getProperty("os.name").orEmpty(),
                        )
                    },
                    capacity = { settings.limitCap(LimitRegistry.MAX_SESSIONS) },
                    events = { since -> remoteStarts.events(since) },
                    eventsAcked = { acked -> remoteStarts.events(acked) },
                    transcript = { id, from, max -> runnerTranscript.get()(id, from, max) },
                ),
            )
            val runnerPool = com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub(localRunnerLink, remotes = { runnerLinksRef.get().paired() })
            val remoteMirror = com.superiorsoftllc.infantry.runners.RemoteSessionMirror(sessionIds = { runnerPool.runners().filter { it.name != com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL }.flatMap { runnerPool.sessionsOn(it.name) } })
            val forwardGateRef = java.util.concurrent.atomic.AtomicReference<com.superiorsoftllc.infantry.runners.ForwardedFrameGate?>(null)
            SessionRecordSerializer.runnerOf = runnerPool::runnerOf
            val orchestratorHub = com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorHub(
                local = com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalOrchestratorLink(
                    runner = localRunnerLink,
                    services = com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalOrchestratorServices(
                        // RUNNER-POOL-7: a frame a remote runner forwards for one of its agents, re-gated (set below, with the pump).
                        forwardFrame = { agent, frame -> forwardGateRef.get()?.answer(agent, frame) },
                        reviewReport = { ticketId, _, body ->
                            ticketId?.let { tid ->
                                val row = store.tickets.get(tid)
                                val line = com.superiorsoftllc.infantry.ticketfiles.FilesReadCheck.extract(body)
                                val refusal = com.superiorsoftllc.infantry.ticketfiles.FilesReadCheck.refusal(body, row?.status == com.superiorsoftllc.infantry.protocol.TicketStatus.DONE)
                                if (refusal == null && line != null) runCatching { boardPorts.saveFilesRead(tid, line) }
                                refusal
                            }
                        },
                        ticketOf = { ticketId -> boardPorts.ticket(ticketId)?.let { com.superiorsoftllc.infantry.daemon.api.orchestrator.TicketRef(it.sessionId, it.projectId) } },
                        starterOf = { workerSessionId -> boardPorts.starterOf(workerSessionId) },
                        launchDecision = { providerId, unattended, ticketId ->
                            when (val d = launchGate.check(providerId, if (unattended) LaunchOrigin.UNATTENDED else LaunchOrigin.HUMAN, ticketId = ticketId)) {
                                GateDecision.Allow -> com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict.Allow
                                is GateDecision.Warn -> com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict(com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict.WARN, d.reason)
                                is GateDecision.Refused -> com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict(com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict.REFUSED, d.reason, d.disposition)
                            }
                        },
                    ),
                ),
                runner = localRunnerLink,
            )
            val orchestratorLink: com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorLink = orchestratorHub.link
            manager.launchGate = { providerId, origin, binding ->
                when (val v = orchestratorLink.launchDecision(providerId, origin == LaunchOrigin.UNATTENDED, binding?.id).valueOr(com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict.Allow)) {
                    com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict.Allow -> GateDecision.Allow
                    else -> when (v.kind) {
                        com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict.WARN -> GateDecision.Warn(v.reason)
                        com.superiorsoftllc.infantry.daemon.api.orchestrator.LaunchVerdict.REFUSED -> GateDecision.Refused(v.reason, v.disposition)
                        else -> GateDecision.Allow
                    }
                }
            }
            manager.silentSink = { session, minutes ->
                attentionSvc.raise(
                    kind = AttentionKinds.STALLED,
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
                if (expiry == com.superiorsoftllc.infantry.session.WatchdogExpiry.STALLED) providerStates.markStalled(session.provider)
                val podId = store.runs.bySession(session.id).firstOrNull()?.podId
                val kind = when (expiry) {
                    com.superiorsoftllc.infantry.session.WatchdogExpiry.DEADLINE -> AttentionKinds.DEADLINE
                    com.superiorsoftllc.infantry.session.WatchdogExpiry.STALLED -> AttentionKinds.STALLED
                }
                val label = expiry.name.lowercase()
                attentionSvc.raise(
                    kind = kind,
                    ticketId = session.ticketId,
                    podId = podId,
                    sessionId = session.id,
                    outcome = label,
                    detail = "Unattended run $label after ${if (expiry == com.superiorsoftllc.infantry.session.WatchdogExpiry.DEADLINE) "deadline" else "no output"}; stopped without retry.",
                )
            }
            // MVP Sprint-Zero, I0: the two slices meet HERE and only here. Slice A owns the project,
            // slice B owns the story, and a story names its project by id -- that join alone, with
            // no ordered list kept on the project side any more (RETIRED, contract 1.29.0: a story's
            // order used to also append onto `Project.storyIds` here; it now lives entirely on
            // `Epic.storyIds`, appended below when the create named an epic).
            val projectStore = ProjectStore(persistence = SqlProjectPersistence((store as SqlInfantryStore).database))
            runnerProjects.set { projectStore.list().map { it.id } }
            projectStore.laneDefault = { settings.limitCap(LimitRegistry.LANE_DEFAULT) }
            // SPAWN-HARNESS-1: a spawn naming no provider is routed like a ticket start: the project's routing rule for the role first.
            agents.spawnRoute = { pid, role ->
                pid?.let { id -> runCatching { com.superiorsoftllc.infantry.settings.RoutingRules.match(projectStore.routingRulesOf(id), null, null, role) }.getOrNull() }
                    ?.takeIf { !it.provider.isNullOrBlank() || !it.model.isNullOrBlank() }
                    ?.let { com.superiorsoftllc.infantry.protocol.RoleBinding(role ?: "", it.provider, it.model, it.effort) }
            }
            // EXPERIMENTS-1: the pi tool bridge (off unless the project setting or the env override says on). Removing it = this line.
            com.superiorsoftllc.infantry.harness.HarnessConfigMerge.pluginMerge = harnesses::mergeConfig
            sessionStats.pluginStats = harnesses::statsSource // HARNESS H5d: plugin harness rows, no harness branch in the service
            com.superiorsoftllc.infantry.experiments.pitoolbridge.PiToolBridgeRegistration.register({ sandboxConfig().enabled || projectStore.experimentOn(it, "piToolBridge") }, sessionStats)
            // TODO-PROGRESS-1: every pi session loads the daemon's `todowrite` extension (pi has no to-do tool of its own).
            // STALL-2: and the watch hook extension (`tool_call` -> the watched runner) when the session's watch is on.
            CoreHarnessContributions.register()
            // BASE-1a: `project_base` checks a branch in the repo ticket starts cut in -- the board plugin's, asked through the board port at
            // answer time -- with the same resolver a cut uses.
            val baseCheck = com.superiorsoftllc.infantry.mvp.ProjectBaseCheck(
                com.superiorsoftllc.infantry.worktree.TicketBranches(),
                cutRepo = { project -> boardPorts.cutRepo(project.id)?.let(::File) },
            )
            val projects = ProjectHandler(
                projectStore,
                baseCheck = baseCheck,
                onConfigChanged = { project ->
                    val frame = Envelope(Envelope.PROJECT, json.encodeToString(com.superiorsoftllc.infantry.protocol.Project.serializer(), project))
                    for (client in clients) client.trySend(frame)
                    boardPorts.laneCapsChanged(project.id)
                },
                // ROUTING-1 (contract 1.128.0): a rule list is checked with the one executor check a profile save uses
                // (provider resolves, model listed by its harness, effort known) and the role catalog.
                routingValidator = { rules ->
                    com.superiorsoftllc.infantry.settings.RoutingRules.validate(
                        rules,
                        checkExecutor = { provider, model, effort -> agents.validateExecutor(provider, model, effort) },
                        roleKnown = { rolesLookup.role(it) != null },
                    )
                },
            )
            // INSTALL-CWD-1: the installed app runs each project's work in that project's own directory.
            if (projectDirs && repo == null) {
                manager.projectFolders = com.superiorsoftllc.infantry.session.ProjectFolders(
                    directoryOf = { id -> runCatching { projectStore.get(id).directory }.getOrNull() },
                    defaultProjectId = { projectStore.list().firstOrNull()?.id },
                )
            }
            // SESSION-CWD-1: the dev daemon too runs a session that names a project in that project's directory.
            if (manager.projectFolders == null) {
                manager.namedProjectFolders = com.superiorsoftllc.infantry.session.ProjectFolders(
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
            // PW-3: workspaces live inside a project and are checked against the same project store, so a
            // workspace written before a restart is listed after one, under the project it was saved in.
            val workspaces = WorkspaceStore(
                projects = projectStore,
                persistence = SqlWorkspacePersistence((store as SqlInfantryStore).database),
            )
            // MEM-2: project-memory notes, over the same database file as everything else, so a note
            // saved before a restart is read back (index and body) after one. Wired the way projects are:
            // constructed over the daemon's own handle, here and only here.
            // DP-7c: the decisions and issues logs (stores, frames) belong to the Infantry_Notes plugin.
            // VOICE-1: spoken attention alerts, pushed to every client; nothing is stored.
            val notifyFrames = com.superiorsoftllc.infantry.notify.NotifyHandler(
                projectName = { id -> projectStore.get(id).name },
                push = { envelope -> for (client in clients) client.trySend(envelope) },
            )
            // SECRETS-1 / SECRETS-2: the keystore's frames; a restricted policy refuses every change.
            val keystoreFrames = KeystoreHandler(
                keystore = keystore,
                restricted = locked,
                push = { envelope -> for (client in clients) client.trySend(envelope) },
            )
            // P10-K9: the knowledge section's counts, read for a project whenever a first turn renders.
            // BUG-G2e: and the digest itself (what `infantry knowledge` prints), built in-process from the same stores.
            // DP-7b: every first-turn path reads the knowledge through the registry's Knowledge port (registered below, with the plugin host).
            val knowledge: KnowledgeCounter = com.superiorsoftllc.infantry.agents.ServiceKnowledgeCounter {
                pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.knowledge.Knowledge::class)
            }
            // Contract 1.5.0: the backlog. It belongs to the daemon, not to a connection: a change one client
            // makes is pushed to every client, the one that asked included.
            // P10-K15: the skills section's library and project switch, read whenever a first turn renders.
            val skillsSource = com.superiorsoftllc.infantry.agents.SkillsSource(
                library = { com.superiorsoftllc.infantry.persistence.SkillStore(store.skills).list(null) },
                includeSkillsContext = { settings.includeSkillsContext() },
            )
            val relatedRef = java.util.concurrent.atomic.AtomicReference(com.superiorsoftllc.infantry.ticketfiles.RelatedWorkSource.NONE)
            val relatedDescriptionRef = java.util.concurrent.atomic.AtomicReference(com.superiorsoftllc.infantry.ticketfiles.RelatedDescription.NONE)
            // P10-K13: the effective skill ids each bound spawn freezes on its instance (K-D9 / K-D11), read once, at spawn.
            manager.skillsFor = { agentId, binding ->
                com.superiorsoftllc.infantry.agents.SkillAttachment.effective(
                    agent = agentId?.let(agents::get),
                    project = com.superiorsoftllc.infantry.agents.SkillAttachment.Project(settings.skillIds(), settings.includeSkillsContext()),
                    ticket = binding?.id?.let(store.tickets::get),
                )
            }
            // USAGE-PACE (1.119.0 read, 1.121.0 manual / override / rule): plan meters -> allowance of ticket workers.
            val usagePacing = UsagePacing(
                limits = limits::cachedOrRefresh,
                pacingLimits = limits::cachedOrRefreshForPacing,
                rule = settings::usagePacing,
                manual = settings::usageManual,
                overrides = settings::usageOverrides,
                // RUNNER-POOL-10: the allowance is per provider ACCOUNT, so a worker on a remote runner counts too (it has no
                // session record here: its meter comes from the ticket's pinned provider / model, else the first provider).
                workerMeters = {
                    val inProgress = store.tickets.all()
                        .filter { it.status == com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS }
                    val local = inProgress
                        .mapNotNull { row -> row.sessionId?.takeIf { it.isNotBlank() }?.let(manager::get) }
                        .filter { it.state !in DEAD_SESSION_STATES }
                        .map { r -> UsageMeters.meterFor(r.provider, r.model ?: manager.providerFor(r.provider)?.defaultModel()) }
                    val remote = inProgress
                        .filter { row -> row.sessionId?.takeIf { it.isNotBlank() }?.let { runnerPool.runnerOf(it) != com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL } == true }
                        .map { row ->
                            val pid = row.provider ?: manager.providers.firstOrNull()?.id ?: UsageMeters.CLAUDE
                            UsageMeters.meterFor(pid, row.model ?: manager.providerFor(pid)?.defaultModel())
                        }
                    local + remote
                },
            )
            // USAGE-CARD-1: the stale limit, backoff and manual floor of the plan meters, read from the settings at every read.
            limits.tuning = {
                com.superiorsoftllc.infantry.usage.UsageReadTuning(
                    staleMaxMs = settings.limit(LimitRegistry.USAGE_STALE_MAX_HOURS) * 3_600_000L,
                    backoffBaseMs = settings.usageRefreshMinutes().takeIf { it > 0 }?.let { it * 60_000L } ?: LimitService.DEFAULT_MIN_INTERVAL_MS,
                    backoffMaxMs = settings.limit(LimitRegistry.USAGE_BACKOFF_MAX_MINUTES) * 60_000L,
                    manualFloorMs = settings.limit(LimitRegistry.USAGE_MANUAL_REFRESH_FLOOR_SECONDS) * 1000L,
                )
            }
            // USAGE-AUTO (1.124.0): every usage.refreshMinutes (default 5, 0 = off) a live read, pushed to every client.
            val usageRefresher = com.superiorsoftllc.infantry.usage.UsageRefresher(
                read = { limits.snapshot(force = true) },
                minutes = settings::usageRefreshMinutes,
                onRefreshed = {
                    val frame = Envelope(Envelope.USAGE_REPORT, json.encodeToString(UsageReport.serializer(), usagePacing.report()))
                    for (c in clients.toList()) c.trySend(frame)
                },
            ).also { it.start() }
            // DP-5h-3e-b: the board is the plugin's; every consumer below reaches it through [boardPorts], resolved at call time.
            val handover = SessionHandover(manager, { boardPorts.brief(it.id) }, handoffTimeoutMs)
            manager.addFirstTurnProvider { sessionId, _ -> boardPorts.brief(sessionId) }
            val workerSweepCloser = WorkerSweep(boardPorts, { LimitRegistry.minutesMs(settings.limit(LimitRegistry.UNBRIEFED_GRACE_MINUTES), LimitRegistry.UNBRIEFED_GRACE_MINUTES) }) { ticketId, sessionId, detail ->
                println("[infantry] ticket worker sweep: $ticketId: $detail")
                attentionSvc.raise(kind = AttentionKinds.STALLED, ticketId = ticketId, podId = null, sessionId = sessionId, outcome = "unbriefed", detail = detail)
            }
            // Phase 6: the mailbox. Messages are persisted BEFORE any delivery attempt, so a message
            // to an offline agent is never lost, and the router hands queued messages to a session
            // whenever that session comes to rest (see MailRouter's delivery policy). Built before the
            // triggers (TRIGGER-NUDGE): a schedule's catch-up fire on boot mails through it.
            // DAEMON-CORE-2: an unknown core.<component>.provider id refuses the start (no silent fallback); only `local` ships.
            ComponentBindings({ k -> store.settings.get(k)?.value?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } }, { null }).verifyAll()
            // MAIL-LIMITS-1: the sender rate limit and the thread ceiling are settings (limits.mail.*), read on every send.
            val mailbox = Mailbox(mailStore, { MailLimits.of(settings::limit) })
            // BUG-S3: mail to an agent swapped onto another harness opens a fresh session there (4.2, lazily).
            // RUNNER-AGENT-1: the placement view a non-ticket start uses (paused runners, per-runner caps), same as the board's.
            val spawnRunnerEntries = { LocalRunner.placementEntries(runnerPool, settings) }
            val bindFreshSession = { agentId: String ->
                LocalRunner.start(runnerPool, manager, SpawnOptions(agentId = agentId, projectId = agents.get(agentId)?.projectId), repo, agentRunner = agents.get(agentId)?.runner, defaultRunner = settings.runnersDefault(), entries = spawnRunnerEntries)?.let { s -> when (s) { is StartedSession.Local -> com.superiorsoftllc.infantry.mailbox.FreshSession.Local(s.record); is StartedSession.Remote -> com.superiorsoftllc.infantry.mailbox.FreshSession.Remote(s.id) } }
            }
            val mail = MailRouter(
                manager, mailbox, agents, mailBase,
                agentsSendToken = token != null, bindFreshSession = bindFreshSession,
                automationFooter = settings::automationFooter,
                coalesceMax = { settings.limit(LimitRegistry.MAIL_COALESCE_MAX).toInt() },
                wakeMode = { role, sid -> settings.wakeMode(role, sid) },
                deliveryMode = { role, sid -> settings.deliveryMode(role, sid) },
                injectMaxChars = { settings.limit(LimitRegistry.MAIL_INJECT_MAX_CHARS).toInt() },
                injectTotalChars = { settings.limit(LimitRegistry.MAIL_INJECT_TOTAL_CHARS).toInt() },
                wakeDebounceMs = { settings.limit(LimitRegistry.MAIL_WAKE_DEBOUNCE_SECONDS) * 1000 },
                wakeMaxMs = { settings.limit(LimitRegistry.MAIL_WAKE_MAX_SECONDS) * 1000 },
                contextCap = { role, sid -> settings.contextCap(role, sid) },
                contextTokens = { r -> sessionStats.read(r.id, r.provider, r.providerSessionId, r.state.name.lowercase()).lastContextTokens },
                knowledge = knowledge, skills = skillsSource, starterOf = { sid -> orchestratorLink.starterOf(sid).valueOr(null) },
                projectRefs = { projectStore.list().map { MailRouter.ProjectRef(it.id, it.name) } },
                resumeTurn = LocalRunner.deliverVia(runnerPool),
                // RUNNER-POOL-8: a session placed on a remote runner takes its mail through that runner (kept queued while it is offline).
                remoteSession = { sid -> runnerPool.runnerOf(sid) != com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL },
            )
            // Story 9.5: triggers -- stored, coalesced, tripped on any non-success, their starts unattended.
            // TRIGGER-NUDGE (contract 1.105.0): a `prompt` action is mail to the target in the trigger's project.
            // DP-5b: triggers hand out through the Tickets API, bound once the board services exist (below).
            var triggerTickets: com.superiorsoftllc.infantry.daemon.api.board.Tickets? = null
            val triggerCaller = com.superiorsoftllc.infantry.daemon.api.Caller(owner = true)
            val triggerApi = com.superiorsoftllc.infantry.daemon.wiring.LocalTriggerApi(
                store = store.triggers,
                getSession = manager::get,
                listSessions = manager::list,
                send = mail::send,
                launch = { agentId, prompt, projectId -> triggerPrompt(triggerTickets, agentId, prompt, projectId, triggerCaller) },
                pending = { mailId -> mailbox.message(mailId)?.state == MessageState.QUEUED },
            )
            val triggerNudges = TriggerNudges(
                sessions = triggerApi::sessions,
                send = triggerApi::sendMail,
                startSession = triggerApi::startPrompt,
            )
            val triggers = com.superiorsoftllc.infantry.daemon.wiring.TriggersWiring.build(
                repo = store.triggers,
                attention = attentionSvc,
                startNext = { filter -> (triggerTickets?.startNext(filter, triggerCaller) as? com.superiorsoftllc.infantry.daemon.api.board.BoardResult.Ok)?.value },
                startPrompt = { agentId, prompt -> triggerPrompt(triggerTickets, agentId, prompt, null, triggerCaller) },
                sessions = triggerApi::session,
                deliverPrompt = triggerNudges::deliver,
                mailPending = triggerApi::mailPending,
            )
            triggerApi.onRunEvent(triggers::onRunEvent)
            triggers.eventSink = { view -> broadcastTrigger(clients, view) }
            // H12b: a committed move into done reaches the ship triggers, after the board commit has released.
            // BASICS-KILL-1 (contract 1.118.0): a project with autoRemoveDoneWorkers ends and removes a done ticket's worker.
            val doneWorkerReaper = DoneWorkerReaper(
                manager = manager,
                enabled = projectStore::autoRemovesDoneWorkers,
                ticketOf = { ticketId -> orchestratorLink.ticketOf(ticketId).valueOr(null)?.let { it.sessionId to it.projectId } },
                executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "infantry-done-worker-reaper").also { it.isDaemon = true } },
                onRemoved = {
                    val pushed = Envelope(Envelope.SESSIONS, json.encodeToString(JsonArray(manager.list().map { json.encodeToJsonElement(SessionRecordSerializer, it) })))
                    for (client in clients) client.trySend(pushed)
                },
            )
            // TICKET-FILES, the typed ticket events and their three core consumers, the story migration, the queue-mark clear.
            // DP-5h-3e-b: the board is the PLUGIN's alone. Core owns only what the board runs on and what consumes its events: the typed durable
            // ticket-event log, the core-built [LocalBoardHost] over it (the plugin builds its TicketBoard on this host through the board runtime), the
            // ticket files and the three core event consumers. The repo a ticket was cut in and an epic's base branch are asked of the board port.
            val ticketCore = com.superiorsoftllc.infantry.daemon.wiring.BoardWiring.buildTicketServices(
                withBoard, store, settings, triggers, doneWorkerReaper, relatedRef, relatedDescriptionRef, boardPorts,
            )
            val ticketEvents: com.superiorsoftllc.infantry.backlog.TicketEventLog? = ticketCore?.events
            val ticketServices = ticketCore?.services
            val ticketFilesText = ticketServices?.filesText
            // ARCHIVE-1: archive / restore service, and the optional auto policy (board.archive.doneAfterDays, off by default), hourly.
            val archiveService = com.superiorsoftllc.infantry.archive.ArchiveService(
                store,
                setting = { key -> (store.settings.get(key)?.value as? kotlinx.serialization.json.JsonPrimitive)?.content },
            )
            scheduledTicker(3_600_000L) { runCatching { archiveService.autoArchive() } }
            val coreHost = com.superiorsoftllc.infantry.daemon.wiring.BoardWiring.buildHost(
                events = ticketEvents, store = store, manager = manager,
                // BASE-1c: the ticket's epic's base branch, then its project's (BASE-1a), then the daemon's.
                baseRef = com.superiorsoftllc.infantry.worktree.TicketBaseRef.epicFirst(
                    boardPorts::epicBaseBranchOf, com.superiorsoftllc.infantry.worktree.TicketBaseRef.projectFirst(projectStore::baseBranchOf, baseRef),
                ),
                // NOGIT-1: a project's own directory decides where its start runs / cuts.
                projectDirectory = { id -> id?.let { runCatching { projectStore.get(it).directory }.getOrNull() } },
                repo = repo, agents = agents, knowledge = knowledge, skills = skillsSource, runners = runnerPool,
                pushOnCommit = { settings.runnerLink().pushOnCommit },
                // IMG-3: a ticket's images live under its project's directory (.infantry/uploads/ticket/).
                ticketImages = com.superiorsoftllc.infantry.backlog.TicketImages { projectId ->
                    runCatching { projectStore.get(projectId) }.getOrNull()?.directory?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
                },
            )
            mail.reportCheck = { from, body -> orchestratorLink.reviewReport(from, body).refusalOrNull() }

            // CLI-PTY-5: each session's chat, kept across a restart so a resumed session shows its history at once.
            val chatHistory = SessionHistoryStore(File(daemonStateDir, "chat-history"), { settings.limit(LimitRegistry.CHAT_HISTORY_FRAMES).toInt() })
            val streamSequencer = SessionFrameSequencer(chatHistory)
            runnerTranscript.set { id, from, max ->
                val out = com.superiorsoftllc.infantry.runners.RunnerTranscript.lines(streamSequencer.replayAfter(id, 0)?.frames.orEmpty()).drop(from.coerceAtLeast(0))
                if (max > 0) out.take(max) else out
            }
            // Contract 1.95.0 (1c24fdc4): each session's harness-neutral run activity, carried on `status` frames.
            val runActivity = com.superiorsoftllc.infantry.protocol.RunActivityTracker()
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
                attentionSvc.raise(
                    kind = AttentionKinds.STALLED,
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
                // RUNTIME-LOAD-6: the persisted spawner chain, and a finished ticket's idle worker no longer holds a slot.
                all = { manager.list() },
                released = { r ->
                    !r.live && r.ticketId != null && boardPorts.ticket(r.ticketId!!)
                        .let { t -> t == null || t.status != com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS || t.sessionId != r.id }
                },
            )
            // CHAT-SENDING-1: one store for every connection, so a resend after a reconnect is recognised.
            val promptSendIds = PromptSendIds { settings.limit(LimitRegistry.CHAT_SENT_IDS_KEPT).toInt() }
            val ticketStartCharge = TicketStartCharge(spawnGuard, { boardPorts.startMoves() }, { id -> manager.get(id) ?: remoteMirror.get(id) }, manager::setSpawner)
            orchestrator.sessionStartedSink = { _, sessionId, parent -> spawnGuard.record(sessionId, parent); manager.setSpawner(sessionId, parent) }
            settings.limitEnvProblems().forEach { println("[infantry] limit ignored: $it") }
            println("[infantry] pod spawn cap=${podSpawnCap ?: settings.limit(LimitRegistry.MAX_PER_POD)} depth=${podSpawnDepth ?: settings.limit(LimitRegistry.MAX_DEPTH)} (cap 0 = unlimited; settings limits.concurrency.*; session-originated spawns only)")

            mailbox.load()
            mailbox.eventSink = { event -> broadcastMail(clients, event, attentionSvc) }
            // TRAITS-T6: REPORT BACK -- an unattended clone's run that ends by itself without its report mail is re-prompted
            // once by `system`, then raises one `unreported` attention item.
            val reportBack = com.superiorsoftllc.infantry.mailbox.ReportBackMonitor(
                sessions = manager, mailbox = mailbox, send = mail::send, attention = attentionSvc,
                agentRole = { id -> agents.get(id)?.role },
                ticketOpen = { id -> store.transaction { store.tickets.get(id) }?.let { it.status != com.superiorsoftllc.infantry.protocol.TicketStatus.DONE } == true },
            )
            // Tells a ticket's starter when its worker's run ends, so a silent or crashed worker never stalls the pipeline.
            // Ticket ef3356ae: a clean end of turn is waiting, not failing; one calm note after limits.watchdog.idleNoticeMinutes.
            // NUDGE-SCRIPT-1: built-in nudges emit a named event; those listed in `nudges.deferToHooks` are left to script hooks.
            val nudgeGate = com.superiorsoftllc.infantry.mailbox.NudgeGate(
                emit = { name, payload -> pluginHost.get().namedEvents.publish(name, kotlinx.serialization.json.JsonObject(payload.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString(), "daemon") },
                defers = { name -> name in settings.nudgeDefer() },
            )
            val finishNotice = com.superiorsoftllc.infantry.mailbox.FinishNoticeMonitor(
                sessions = manager, mailbox = mailbox, send = mail::send,
                ticketOf = { id -> store.transaction { store.tickets.get(id) } },
                idleNoticeMs = { LimitRegistry.minutesMs(settings.limit(LimitRegistry.IDLE_NOTICE_MINUTES), LimitRegistry.IDLE_NOTICE_MINUTES) },
                idleNudgeMs = { LimitRegistry.minutesMs(settings.limit(LimitRegistry.IDLE_NUDGE_MINUTES), LimitRegistry.IDLE_NUDGE_MINUTES) },
                exemptWhenDone = { settings.limit(LimitRegistry.WATCH_EXEMPT_WHEN_TICKET_DONE) != 0L },
                reports = { pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.tickets.TicketReports::class) },
                idleComment = { settings.limit(LimitRegistry.IDLE_COMMENT) != 0L },
                gate = nudgeGate,
            ).also { it.start() }
            // STALL-4: the watch's recipient rule (`watch.escalateTo`): `starter` = the ticket's starter session, else the
            // user through the UI; `user` = always the user; `none` = nobody. No role is assumed to exist.
            com.superiorsoftllc.infantry.runner.WatchNotices.overrides = { settings.watchNotices() }
            fun watchRecipientOf(s: com.superiorsoftllc.infantry.session.SessionRecord?): String? {
                if (s == null) return null
                return when (settings.watchEscalateTo()) {
                    "none" -> null
                    "user" -> com.superiorsoftllc.infantry.runner.WatchNotices.USER
                    else -> s.ticketId?.let { id -> runCatching { store.transaction { store.tickets.get(id) } }.getOrNull() }
                        ?.startedBy?.takeIf { it.isNotBlank() && it != s.id } ?: com.superiorsoftllc.infantry.runner.WatchNotices.USER
                }
            }
            // Mail to [WatchNotices.USER] is an attention item (the UI), never mail to a role that may not exist.
            val watchSend: (com.superiorsoftllc.infantry.mailbox.MailSendRequest) -> com.superiorsoftllc.infantry.mailbox.MailResult = { req ->
                if (req.to == com.superiorsoftllc.infantry.runner.WatchNotices.USER) {
                    val sid = req.threadId?.split(':')?.getOrNull(1)?.takeIf { manager.get(it) != null }
                    runCatching {
                        attentionSvc.raise(
                            kind = AttentionKinds.STALLED,
                            sessionId = sid, ticketId = sid?.let { manager.get(it)?.ticketId },
                            outcome = "watch_notice", detail = (req.subject.removePrefix("[AUTOMATIC] ") + "\n" + req.body).trim().take(2000),
                        )
                    }
                    com.superiorsoftllc.infantry.mailbox.MailResult.Refused("raised for the user as an attention item")
                } else mail.send(req)
            }
            // BG-NOTIFY (ticket 245a7d31): `bg run --notify` jobs mail their session on exit / pattern / timeout, waking it.
            val bgNotifier = com.superiorsoftllc.infantry.mailbox.BgJobNotifier(
                jobs = { manager.list().flatMap { r -> r.bgJobs.map { r.id to it } } },
                gate = nudgeGate,
                send = watchSend,
                defaultTailLines = { settings.limit(LimitRegistry.BG_NOTIFY_TAIL_LINES).toInt() },
                defaultTimeoutMs = { LimitRegistry.minutesMs(settings.limit(LimitRegistry.BG_NOTIFY_TIMEOUT_MINUTES), LimitRegistry.BG_NOTIFY_TIMEOUT_MINUTES) },
                patternGraceMs = { settings.limit(LimitRegistry.BG_NOTIFY_PATTERN_GRACE_SECONDS) * 1000L },
                quietMs = { LimitRegistry.minutesMs(settings.limit(LimitRegistry.WATCH_QUIET_MINUTES), LimitRegistry.WATCH_QUIET_MINUTES) },
                heartbeatCapMs = { settings.limit(LimitRegistry.WATCH_HEARTBEAT_CAP_MINUTES) * 60_000L },
                statusNotices = { settings.limit(LimitRegistry.NOTICE_MAIL_BG_STATUS) != 0L },
                inTurn = { sid -> manager.get(sid)?.isBusy() == true },
                recipientOf = { sid -> watchRecipientOf(manager.get(sid)) },
                lineChars = { settings.limit(LimitRegistry.WATCH_NOTICE_LINE_CHARS).toInt() },
                readMaxBytes = { settings.limit(LimitRegistry.WATCH_READ_MAX_KB) * 1024L },
                tailReadBytes = { settings.limit(LimitRegistry.WATCH_TAIL_READ_KB) * 1024L },
                drop = { thread, to, reason, subject -> mailbox.dropQueued(thread, to, reason) { m -> subject == null || m.subject == subject } },
                dedupe = { settings.limit(LimitRegistry.WATCH_NOTICE_DEDUPE) != 0L },
                keepMs = { settings.limit(LimitRegistry.WATCH_HANDLED_KEEP_MINUTES) * 60_000L },
                hangGraceMs = { settings.limit(LimitRegistry.WATCH_HANG_GRACE_SECONDS) * 1000L },
                foldIntoTurn = { settings.limit(LimitRegistry.BG_NOTICE_FOLD_INTO_TURN) != 0L },
                skipIfResultRead = { settings.limit(LimitRegistry.BG_NOTICE_SKIP_IF_RESULT_READ) != 0L },
                endTree = { _, job ->
                    ProcessHandle.of(job.cmdPid ?: job.pid).ifPresent { h ->
                        h.descendants().forEach { runCatching { it.destroyForcibly() } }
                        runCatching { h.destroyForcibly() }
                    }
                },
            )
            com.superiorsoftllc.infantry.daemon.WatchHooks.nativeBackground = { settings.limit(LimitRegistry.WATCH_BG_NATIVE) != 0L }
            manager.bgJobListener = bgNotifier
            // STALL-1 item 5: idle or BUSY-but-silent ticket workers are nudged and their PM told; no model call, no kill.
            val stallDetector = com.superiorsoftllc.infantry.mailbox.StallDetector(
                sessions = manager, send = watchSend,
                gate = nudgeGate,
                ticketInProgress = { id -> store.transaction { store.tickets.get(id) }?.status == com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS },
                deliberateWait = { s ->
                    mailbox.waiting().any { it.session == s.id } ||
                        // ATTN-2c: the plugin that owns the questions (Infantry_Attention) answers; none = nobody has one.
                        (runCatching { pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.attention.QuestionsOpen::class)?.hasOpen(s.id) }.getOrNull() ?: false)
                },
                lastJobEndedAt = bgNotifier::lastEndedAt,
                quietSeconds = bgNotifier::quietSeconds,
                stallMs = { if (settings.limit(LimitRegistry.WATCH_STALL_NUDGES) == 0L) Long.MAX_VALUE else LimitRegistry.minutesMs(settings.limit(LimitRegistry.WATCH_STALL_MINUTES), LimitRegistry.WATCH_STALL_MINUTES) },
                recipientOf = ::watchRecipientOf,
                foregroundStallMs = { if (settings.limit(LimitRegistry.WATCH_STALL_NUDGES) == 0L) Long.MAX_VALUE else LimitRegistry.minutesMs(settings.limit(LimitRegistry.WATCH_FOREGROUND_STALL_MINUTES), LimitRegistry.WATCH_FOREGROUND_STALL_MINUTES) },
                exemptWhenDone = { settings.limit(LimitRegistry.WATCH_EXEMPT_WHEN_TICKET_DONE) != 0L },
                ticketDone = { id -> store.transaction { store.tickets.get(id) }?.status == com.superiorsoftllc.infantry.protocol.TicketStatus.DONE },
                reportDelivered = { s ->
                    val starter = s.ticketId?.let { id -> store.transaction { store.tickets.get(id) } }?.startedBy
                    com.superiorsoftllc.infantry.mailbox.FinishNoticeMonitor.reportDelivered(mailbox, s, starter)
                },
            ).also { it.start() }
            // PIPELINE-WATCHDOG: derives per-agent pending/stalled/quiet state, nudges, then tells the PM and the owner.
            val agentWatchdog = com.superiorsoftllc.infantry.mailbox.AgentWatchdog(
                sessions = manager, mailbox = mailbox, send = mail::send, attention = attentionSvc,
                config = com.superiorsoftllc.infantry.session.WatchdogConfig.fromEnv(),
                isEnabledFor = { projectStore.experimentOn(it, "agentWatchdog") },
            ).also { it.start() }
            // STATS-ACC-1: every recorded turn pushes a fresh `usage_meter` frame to all clients (debounced to 4/s), so the pane never polls.
            usageMeter?.let { m ->
                val push = com.superiorsoftllc.infantry.usagemeter.PushDebounce { val f = Envelope(Envelope.USAGE_METER, m.reportJson()); for (c in clients) c.trySend(f) }
                m.onRecorded = push::poke
                // H5b: a plugin harness's Usage events (its stdout is in no format the meter reads) are charged too.
                m.eventTranslatorFor = { rec -> if (rec.provider in CoreHarnessContributions.meterReadsOwnStore) null else (harnesses.resolve(rec.provider) as? com.superiorsoftllc.infantry.harness.CliHarness)?.newTranslator() }
            }
            // DP-5h-3b/3c: session events reach the board through the BoardSeam (a SwapFeed): events during a plugin attach /
            // reload are queued (drop-oldest, bounded), not lost. The board attaches to it below, once, before the host loads.
            val boardSeam = com.superiorsoftllc.infantry.daemon.BoardSeam<com.superiorsoftllc.infantry.daemon.plugins.LocalBoardHost, com.superiorsoftllc.infantry.session.SessionEvent>({ h, e -> h.publish(e) })
            // First attach: no re-adopt (the board plugin clears the marks at attach; the sweep waits for session restore). A plugin RELOAD attaches with
            // [reAdopt] (clearQueueMarks + sweepWorkers) after detaching.
            coreHost?.let { host -> boardSeam.attach(host) }
            val reAdopt: () -> Unit = { boardPorts.clearQueueMarks(); boardPorts.sweepWorkers(LimitRegistry.minutesMs(settings.limit(LimitRegistry.UNBRIEFED_GRACE_MINUTES), LimitRegistry.UNBRIEFED_GRACE_MINUTES)) }
            manager.eventSink = { event ->
                broadcast(clients, event, streamSequencer, runActivity, { id, seconds ->
                    // A `sleep N` in a tool call becomes a "waiting" countdown; the call returning (or the agent moving on) ends it.
                    if (seconds != null) manager.setWait(id, com.superiorsoftllc.infantry.session.WaitHint(seconds, System.currentTimeMillis())) else manager.clearWait(id)
                }, { id, items -> manager.setTodoProgress(id, items) }) { id -> manager.toolApprovalFor(id).mode }
                // PROMPT-1: a turn's end or a terminal session resolves its open prompts (deny / no answer).
                pendingPrompts.onSessionEvent(event)
                // RUNNER-POOL-6: a session its orchestrator started here pushes its ticket branch after each turn.
                when (event) {
                    is com.superiorsoftllc.infantry.session.SessionEvent.Status -> remoteStarts.onStatus(event.session.id, event.session.state.name)
                    is com.superiorsoftllc.infantry.session.SessionEvent.Exit -> if (remoteStarts.isRemote(event.sessionId)) Thread({ remoteStarts.onTurnEnd(event.sessionId, event.code) }, "runner-push").start()
                    else -> Unit
                }
                mail.onSessionEvent(event)
                // THIRD ARM, and its position is load-bearing. The orchestrator schedules against the
                // session state it believes, so it must observe the same events that were broadcast
                // and mailed -- after broadcast, never before, or it would schedule on a snapshot the
                // clients have not been told about yet.
                orchestrator.onSessionEvent(event)
                // Story 9.7b: usage BEFORE the backlog, so a run that spends the budget is counted before its
                // exit drains a queue -- the drained (unattended) start is judged against the spent budget.
                usageRecorder.onSessionEvent(event)
                // USAGE-1: the usage meter (own package, own file); the seam is these three lines plus its frame below.
                usageMeter?.onSessionEvent(event, manager::get)
                // FOURTH ARM: ticket outcomes are durable, then a freed profile lane is drained.
                boardSeam.emit(event)
                // Story 9.5: a trigger's run ends with its session's run.
                triggerApi.dispatch(event)
                // TRAITS-T6: LAST, so the board and the triggers have recorded the run's end before a re-prompt mail
                // can start the session's next run.
                reportBack.onSessionEvent(event)
                finishNotice.onSessionEvent(event)
                // BASICS-KILL-1: after everything else has seen the turn's end; the reap itself runs on its own thread.
                doneWorkerReaper.onSessionEvent(event)
            }

            println("[infantry] provider=${provider.id} runner=${runner.id} stateDir=${daemonStateDir}")
            println("[infantry] sandbox: ${runCatching { settings.sandboxStatus?.invoke() }.getOrNull() ?: "off"} (setting limits.sandbox.mode)")
            println(
                if (additionalProviders.isEmpty()) {
                    "[infantry] providers: ${provider.id} only (set ${ProviderFactory.PROVIDERS_ENV} to let a spawn choose another)"
                } else {
                    "[infantry] providers: ${manager.providers.joinToString { it.id }} (default ${provider.id})"
                },
            )
            println("[infantry] harnesses: ${harnesses.harnessIds().ifEmpty { listOf("<none>") }.joinToString()}")
            println("[infantry] contract=infantry/${EventVocabulary.VERSION} (see CONTRACT.md)")
            // DP-5h-3c: the plugin host loads here, BEFORE session restore (and before the first frame); the board services take lazy refs.
            // COPY-1: the session cap's refusal text is the active preset's (read per refusal, so a switch applies at once).
            manager.sessionCapText = { policies.current.sessionCapText }
            // DAEMON-PLUGINS-1: a plugin's first turn into a never-run session is composed like a client's first message.
            val pluginFirstTurn = { s: com.superiorsoftllc.infantry.session.SessionRecord, prompt: String ->
                val instance = manager.instance(s.id)
                firstTurn(instance, s.role, s.agentId, prompt, knowledge.section(s.projectId, s.role), skillsSource.section(instance))
            }

            // PLUGINS-1: the daemon reads the same plugins root and rules as the desktop (no UI here); GET /plugins lists them.
            // CONFIG-8: the daemon's own config manifest, compiled from the loaded modules' `daemon.config`.
            val daemonConfig = DaemonConfig(settings, store)
            daemonConfig.onChanged = { changed ->
                val frame = Envelope(Envelope.CONFIG_CHANGED, json.encodeToString(com.superiorsoftllc.infantry.plugin.api.ConfigChangedFrame.serializer(), changed))
                for (client in clients) client.trySend(frame)
            }
            val harnessModulesRoot = java.util.concurrent.atomic.AtomicReference<java.io.File?>(null)
            fun harnessModulesConfig() = com.superiorsoftllc.infantry.plugin.host.PluginFolders.config(harnessModulesRoot.get()!!)
            // POLICY-1: resolved under the ACTIVE preset's restriction, and again on every switch (GET /plugins reads the latest).
            val firstResolution = java.util.concurrent.atomic.AtomicReference<com.superiorsoftllc.infantry.plugin.host.Resolution?>(null)
            // PLUGIN-HOTLOAD-1: [onResolved] defaults to the daemon's usual side effects; a live load passes its own (no re-sync).
            fun resolvePlugins(onResolved: (com.superiorsoftllc.infantry.plugin.host.Resolution) -> Unit = { r ->
                    firstResolution.compareAndSet(null, r); daemonConfig.load(r).forEach { line -> System.err.println(line) }
                    // HARNESS-PLUGIN-3 H4: a re-resolution (a policy switch) loads / unloads harness modules to match.
                    harnessModulesRef.get()?.let { hm -> runCatching { hm.sync(r, harnessModulesConfig()) }.onFailure { System.err.println("[harness] module sync failed: ${it.message}") } }
                    // AGENT-REGISTRY-1: the same for modules' `agents` blocks (the first resolution is synced below, once the root is known).
                    harnessModulesRoot.get()?.let { runCatching { agentModules.sync(r, harnessModulesConfig()) }.onFailure { System.err.println("[agents] module sync failed: ${it.message}") } }
                }) = DaemonPlugins.resolve(
                daemonStateDir,
                env = { name -> if (pluginsRoot != null && name == com.superiorsoftllc.infantry.plugin.host.PluginFolders.ROOT_ENV) pluginsRoot.absolutePath else if (pluginsRoot != null && name == com.superiorsoftllc.infantry.plugin.host.PluginFolders.BUNDLED_ENV) null else System.getenv(name) },
                property = { name -> if (pluginsRoot != null && name == com.superiorsoftllc.infantry.plugin.host.PluginFolders.BUNDLED_PROPERTY) null else System.getProperty(name) },
                locked = locked(),
                onResolved = onResolved,
            )
            val pluginsNow = java.util.concurrent.atomic.AtomicReference(resolvePlugins())
            // DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the daemon half of every module the rules let through, loaded ONCE,
            // before anything is delivered or served (a plugin seats its sessions in its start). A module that fails its
            // rules, trust or start is logged and left out; the daemon starts either way.
            firstResolution.get()?.let { resolution ->
                val root = java.io.File(pluginsNow.get().root)
                // AGENT-REGISTRY-1: modules' `agents` blocks first, so a daemon half's agents.contribute finds its declared ids.
                agentModules.sync(resolution, com.superiorsoftllc.infantry.plugin.host.PluginFolders.config(root))
                val bundled = if (pluginsRoot != null) null else com.superiorsoftllc.infantry.plugin.host.PluginFolders.bundledRoot()
                val trustNow = pluginTrust ?: com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.Trust(
                    com.superiorsoftllc.infantry.plugin.host.OfficialTrust.infantryKeys(root, bundled),
                    com.superiorsoftllc.infantry.plugin.host.PluginFolders.devTrust(),
                    bundled,
                )
                // RUNTIME-LOAD-6: the spawn service starts tickets through the board services built just below (one scope, one charge).
                var pluginBoardServices: com.superiorsoftllc.infantry.daemon.plugins.LocalBoardServices? = null
                val host = com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.load(
                    resolution,
                    com.superiorsoftllc.infantry.plugin.host.PluginFolders.config(root),
                    trustNow,
                    com.superiorsoftllc.infantry.daemon.plugins.CoreDaemonServices(
                        manager, agents, projectStore, daemonConfig, keystore, policies,
                        getenv = pluginEnv, firstTurn = pluginFirstTurn, limits = settings::limit,
                        // ATTN-2a: api.events.broadcast reaches every connected client.
                        clientBroadcast = { type, data -> val frame = Envelope(type, data); for (c in clients.toList()) c.trySend(frame) },
                        // STACK-NEUTRAL-2: api.processes / api.launchEnv / api.settings.
                        processHost = localProcesses, launchEnvHost = launchEnvHost,
                        pluginSettings = settings.forPlugins(),
                        // CORE-PROGRESS-DOWNLOAD-1: the core string settings the host reads (progress.presenter, downloads.*).
                        coreStringSetting = { settings.plainString(it) },
                        notificationSettingsSource = { settings.notificationSettings() },
                        noticeSuppressor = { name, session, ms -> nudgeGate.suppress(name, session, ms) },
                        // CMD-1: api.runs, a cwd-bound child process over the runner (pty and pipes).
                        runService = com.superiorsoftllc.infantry.daemon.plugins.LocalRuns(
                            workRoot = repo, configDir = commandsFile.absoluteFile.parentFile ?: File("."),
                            projectDirectory = { id -> runCatching { projectStore.get(id).directory }.getOrNull() },
                            pipes = com.superiorsoftllc.infantry.runner.PipedProcessRunner(),
                            pty = com.superiorsoftllc.infantry.runner.Pty4jTerminalRunner(),
                            // SEC-FIX-1 H1(b): user secrets only; a built-in (the update token) never reaches a run.
                            secrets = KeystoreHandler.runSecrets({ keystore.fields(it) }),
                            // SECRETS-2b: a project's run resolves a name in that project first, then the app scope.
                            projectSecrets = KeystoreHandler.projectRunSecrets(keystore),
                            secretNames = { project -> buildMap {
                                keystore.names().filter { n -> KeystoreHandler.DEFAULT.none { it.name == n } }.forEach { put(it, Keystore.SCOPE_APP) }
                                if (!project.isNullOrBlank()) runCatching { keystore.names(project) }.getOrDefault(emptyList()).forEach { put(it, Keystore.SCOPE_PROJECT) }
                            } },
                        ),
                        // PLUGIN-MCP-1: api.plugins, the plugin manager on this host's plugins root (reads; install only on the owner's yes).
                        pluginCatalog = com.superiorsoftllc.infantry.daemon.plugins.LocalPluginCatalog(
                            com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager(
                                com.superiorsoftllc.infantry.plugin.host.PluginFolders.root(daemonStateDir, { System.getenv(it) }),
                                daemonStateDir, com.superiorsoftllc.infantry.plugin.host.PluginFolders.bundledRoot({ System.getenv(it) }),
                                // PLUGIN-HOTLOAD-1: an install / enable / update / remove reaches the running host (no daemon restart).
                                hot = com.superiorsoftllc.infantry.daemon.plugins.manager.HostPluginHotApply { pluginHost.get() },
                                catalogStaleMs = { settings.limit(LimitRegistry.PLUGIN_CATALOG_STALE_MS) },
                                // CORE-PROGRESS-DOWNLOAD-1: catalog and jar downloads go through the core downloader; each change is a progress task.
                                coreDownloads = com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.lateDownloads({ pluginHost.get() }, "plugins"),
                                progressSystem = com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.lateProgress({ pluginHost.get() }, "plugins"),
                            ),
                            asks = { pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.attention.OwnerAsks::class) },
                            log = { System.err.println("[infantry] $it") },
                        ),
                        // PACE-TREND-1: api.meters, the current reading of every provider window (the cached live read).
                        meters = com.superiorsoftllc.infantry.usage.PacingMeters(usagePacing, requestSource = { since -> usageMeter?.requestsSince(since).orEmpty() }),
                        // DP-7e: api.sessionNotifier, over the mail router.
                        notifier = com.superiorsoftllc.infantry.daemon.plugins.LocalSessionNotifier({ manager.list() }, { to, subject, body -> mail.send(MailSendRequest(from = "owner", to = to, subject = subject, body = body)) }, { to, subject, body, thread -> mail.send(MailSendRequest(from = "owner", to = to, subject = subject, body = body, threadId = thread)) }),
                        // DAEMON-STORAGE-1: api.storage, over the store's own handle (null for a non-SQL store).
                        storageHandle = (store as? SqlInfantryStore)?.handle,
                        // DP-4a: tickets / epics / board for plugins, over the board built above (host-applied policy and scope).
                        boardServices = com.superiorsoftllc.infantry.daemon.plugins.LocalBoardServices(
                            ops = { boardPorts.ops() }, sessions = { manager.list() }, link = { orchestratorHub.link },
                            gate = { type, data ->
                                val envelope = Envelope(type, data)
                                policies.current.takeIf { !it.isEmpty }?.let { com.superiorsoftllc.infantry.policy.PolicyRuntime.actionRefusal(it, envelope, policies.actionOf(envelope)) }
                            },
                            chargedMove = { m, by -> boardPorts.startMoves().let { moves -> moves.ticketOf(ticketStartCharge.move(m, by)) } },
                        ).also { pluginBoardServices = it; triggerTickets = it.tickets },
                        ticketEventStream = ticketEvents,
                        // DP-5h-3a: the board runtime (the plugin builds the board).
                        boardRuntimeService = boardSeam.host?.let { _ ->
                            com.superiorsoftllc.infantry.daemon.plugins.LocalBoardRuntime(
                                hostSource = boardSeam::require, store = store, settings = settings, projectStore = projectStore, roles = rolesLookup,
                                pacingCheck = { providerId, model ->
                                    val pid = providerId ?: manager.providers.firstOrNull()?.id ?: com.superiorsoftllc.infantry.daemon.api.usage.UsageMeters.CLAUDE
                                    usagePacing.check(com.superiorsoftllc.infantry.daemon.api.usage.UsageMeters.meterFor(pid, model ?: manager.providerFor(pid)?.defaultModel()))
                                },
                                attention = attentionSvc,
                                coreBuiltBoard = false,
                                onAttachCore = { access -> boardPorts.attach(com.superiorsoftllc.infantry.daemon.PluginBoardPorts(access) { ticketServices?.files }) },
                                onDetachCore = { boardPorts.detach() },
                                roleNotesOf = { project, role -> pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.knowledge.Knowledge::class)?.roleNotes(project, role).orEmpty() },
                                relatedBlockOf = { id, desc -> relatedRef.get().block(id, desc) },
                                fixedFirstTurnOf = { fixedFirstTurn?.invoke() },
                                runnersOf = {
                                    runnerPool.runners().map { e ->
                                        com.superiorsoftllc.infantry.daemon.api.board.BoardRunner(
                                            // RUNNER-POOL-10: a paused runner takes no new starts (placement refuses it as "paused").
                                            e.name, if (e.name == com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL && !deploymentMode.runsSessions) "orchestrator-only" else if (settings.runnerPaused(e.name)) "paused" else e.state,
                                            // RUNNER-POOL-9: the runner's own free slots, further limited by runners.<name>.maxWorkers.
                                            freeSlots = minOf(
                                                e.health?.freeSlots?.let { if (it < 0) Int.MAX_VALUE else it } ?: 1,
                                                (if (e.name == com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL) settings.runnerMaxWorkersExplicit(e.name) ?: Int.MAX_VALUE else settings.runnerMaxWorkers(e.name))
                                                    .let { cap -> if (cap == Int.MAX_VALUE) cap else (cap - (e.health?.running ?: 0)).coerceAtLeast(0) },
                                            ),
                                            projects = e.info?.projects.orEmpty(),
                                            loggedInHarnesses = e.info?.harnesses.orEmpty().filter { it.loggedIn }.map { it.id },
                                        )
                                    }
                                },
                                // DP-5h-3a2 (1.13.0): the typed events, story / epic inputs, RELATED block and skill check.
                                ticketEvents = ticketEvents ?: com.superiorsoftllc.infantry.daemon.api.board.NoTicketEvents,
                                stories = object : com.superiorsoftllc.infantry.daemon.api.board.BoardStoryInputs {
                                    override fun ticketFor(storyId: String) = (store as SqlInfantryStore).database.story_migrationQueries.ticketFor(storyId).executeAsOneOrNull()
                                    override fun epicChanged(epicId: String) { boardPorts.epicJson(epicId)?.let { broadcastEpic(clients, json.decodeFromString(Epic.serializer(), it)) } }
                                    override fun migrationFile(): String? = storyMigrationFile?.path
                                    override fun recordMapping(storyId: String, ticketId: String, at: Long) { (store as SqlInfantryStore).database.story_migrationQueries.insertMapping(storyId, ticketId, at) }
                                },
                                epics = object : com.superiorsoftllc.infantry.daemon.api.board.BoardEpicInputs {
                                    private val persistence = SqlEpicPersistence((store as SqlInfantryStore).database)
                                    override fun loadAll() = persistence.loadAll().map { Json.encodeToString(Epic.serializer(), it) }
                                    override fun upsert(epicJson: String) = persistence.upsert(Json.decodeFromString(Epic.serializer(), epicJson))
                                    override fun projectKnown(projectId: String) = runCatching { projectStore.get(projectId) }.isSuccess
                                    override fun checkBase(projectId: String, branch: String) { baseCheck.check(projectStore.get(projectId), branch) }
                                    override fun branchName(name: String) = com.superiorsoftllc.infantry.mvp.ProjectBaseCheck.checkName(name)
                                },
                                related = { d ->
                                    relatedDescriptionRef.get().describe(
                                        com.superiorsoftllc.infantry.ticketfiles.RelatedDraft(
                                            d.ticketId, d.title, d.description, d.projectId,
                                            d.rows.map { com.superiorsoftllc.infantry.ticketfiles.TicketInfo(it.id, it.title, it.description, it.status, it.projectId, it.branch, it.base) } +
                                                // ARCHIVE-1: archived tickets stay in the related set (series, named ids)
                                                store.ticketArchive.infos(d.projectId).map { it.toTicketInfo() },
                                            d.links.map { com.superiorsoftllc.infantry.ticketfiles.TicketLinkInfo(it.ticketId, it.relatedId) },
                                        ),
                                    )
                                },
                                skillKnown = { id -> store.transaction { store.skills.get(id) != null } },
                                pushToClients = { frames -> for (f in frames) { val e = Envelope(f.type, f.data, f.sessionId, requestId = f.requestId); for (c in clients.toList()) c.trySend(e) } },
                            )
                        },
                        agentRegistry = agentRegistry,
                        // RUNTIME-LOAD-5: the sessions read model (host-scoped), with the replay buffer for transcripts.
                        sessionReadModel = com.superiorsoftllc.infantry.daemon.plugins.LocalSessionReads(
                            sessions = { manager.list() + remoteMirror.list() },
                            replay = { id ->
                                streamSequencer.replayAfter(id, 0)?.let { batch ->
                                    com.superiorsoftllc.infantry.daemon.plugins.LocalSessionReads.Replay(
                                        batch.gap?.let { json.encodeToString(SessionReplayGapPayload.serializer(), it) }, batch.frames,
                                    )
                                }
                            },
                            gate = { type ->
                                val envelope = Envelope(type, "")
                                policies.current.takeIf { !it.isEmpty }?.let { com.superiorsoftllc.infantry.policy.PolicyRuntime.actionRefusal(it, envelope, policies.actionOf(envelope)) }
                            },
                        ),
                        // RUNTIME-LOAD-6: the host spawn service: depth, concurrency and kill scope keyed by the calling session.
                        spawnService = com.superiorsoftllc.infantry.daemon.plugins.LocalSpawns(
                            sessions = { manager.list() + remoteMirror.list() },
                            guard = spawnGuard,
                            limit = { def -> if (def == LimitRegistry.MAX_DEPTH && podSpawnDepth != null) podSpawnDepth else settings.limitCap(def) }, // a test pins the depth through podSpawnDepth
                            tickets = { pluginBoardServices!!.tickets },
                            create = { request ->
                                LocalRunner.start(
                                    runnerPool, manager,
                                    SpawnOptions(agentId = request.agentId, role = request.role, projectId = request.projectId, contextLimit = request.contextLimit, effort = request.effort, runner = request.runner),
                                    repo, agentRunner = request.agentId?.let { agents.get(it)?.runner }, defaultRunner = settings.runnersDefault(), entries = spawnRunnerEntries,
                                ) ?: throw IllegalArgumentException("the daemon could not bind a session for this spawn")
                            },
                            firstTask = { started, task ->
                                when (started) {
                                    is StartedSession.Local -> {
                                        val record = started.record
                                        val turn = if (record.neverRun) pluginFirstTurn(record, task) else task
                                        manager.resume(record.id, turn)
                                    }
                                    // RUNNER-AGENT-1: the first task of a remote spawn is mail to its runner.
                                    is StartedSession.Remote -> {
                                        val r = LocalRunner.deliverVia(runnerPool)(started.id, task, com.superiorsoftllc.infantry.protocol.MessageOrigin.Owner, "spawn", emptyList())
                                        if (r.kind == com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult.FAILED) throw IllegalStateException(r.reason.ifBlank { null } ?: "the runner refused the first task")
                                    }
                                }
                            },
                            setSpawner = manager::setSpawner,
                            gate = { type ->
                                val envelope = Envelope(type, "")
                                policies.current.takeIf { !it.isEmpty }?.let { com.superiorsoftllc.infantry.policy.PolicyRuntime.actionRefusal(it, envelope, policies.actionOf(envelope)) }
                            },
                        ),
                    ),
                    daemonStateDir,
                )
                pluginHost.set(host)
                // PLUGIN-HOTLOAD-1: what a live load reads: the plugins root as it is now, its grants, and the boot trust. No side effects (no config or harness re-sync).
                host.hotSource = {
                    var fresh: com.superiorsoftllc.infantry.plugin.host.Resolution? = null
                    resolvePlugins { fresh = it }
                    com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.HotSource(
                        fresh ?: error("the plugins root could not be resolved"),
                        com.superiorsoftllc.infantry.plugin.host.PluginFolders.config(root), trustNow,
                    )
                }
                // SCRIPT-HOOK-3: a script's tool calls reach the core frames through the daemon's own /ws (see LoopbackFrames).
                // AR-4b-ii-b: the Infantry_Roles plugin provides Roles / RoleBindings / RoleTraitLists; core keeps the environment port.
                host.serviceRegistry.provide("core", com.superiorsoftllc.infantry.daemon.api.roles.RoleEnvironment::class, RolePorts.environment(store, manager, harnesses))
                testRoles?.let { host.serviceRegistry.provide("core", com.superiorsoftllc.infantry.daemon.api.roles.Roles::class, it) }
                host.loopback = com.superiorsoftllc.infantry.daemon.plugins.LoopbackFrames(loopbackHost, boundPort, token, ownerToken)
                foundAttention.flush()
                testKnowledge?.let { host.serviceRegistry.provide("core", com.superiorsoftllc.infantry.daemon.api.knowledge.Knowledge::class, it) }
                // HARNESS-PLUGIN-3 H4 (docs/HARNESS-PLUGINS.md 3.2): each loaded module's `harness` block, into the ONE registry,
                // AFTER module resolution and with the daemon halves' trust. The built-ins (class path, 1.x) are already in it.
                val harnessModules = com.superiorsoftllc.infantry.daemon.wiring.HarnessModulesWiring.build(
                    harnesses, trustNow, daemonConfig, harnessModulesRef, executableResolver, infantryBin, pendingPrompts, daemonStateDir,
                    reserved = { manager.providers.map { it.id }.toSet() },
                )
                harnessModulesRoot.set(root)
                harnessModules.sync(resolution, com.superiorsoftllc.infantry.plugin.host.PluginFolders.config(root))
                manager.harnessAliases = harnessModules::alias
                harnessModulesRef.set(harnessModules)
            }
            val plugins = pluginHost.get()
            // DEMO-USAGE-HIDE-1: plugin-offered plan-limit readings replace the provider sources (`hooks.usageReading`).
            limits.replacement = {
                val at = System.currentTimeMillis()
                val seen = HashSet<String>()
                plugins.usageReadings().flatMap { offer -> runCatching { offer.read(at) }.getOrNull().orEmpty().entries }
                    .filter { (provider, windows) -> windows.isNotEmpty() && seen.add(provider) }
                    .map { (provider, windows) ->
                        object : com.superiorsoftllc.infantry.usage.LimitSource {
                            override val provider = provider
                            override fun read(now: Long) = com.superiorsoftllc.infantry.usage.ProviderLimits(provider, "ok", windows, null, now)
                        }
                    }
            }
            // USAGE-MOCK-1: pacing judges the real readings unless an offering plugin says its readings count (false = a faked screenshot buys no worker).
            limits.replacementAffectsPacing = { plugins.usageReadings().any { it.affectsPacing() } }
            // SCRIPT-3: the daemon's own happenings as named events (api.events.subscribe / infantry_event_emit share this bus).
            val eventPublisher = com.superiorsoftllc.infantry.daemon.plugins.DaemonEventPublisher { name, payload -> runCatching { plugins.publishEvent(name, payload) } }
            mail.onSent = eventPublisher::mail
            manager.wakeSink = { id, reason, ref -> eventPublisher.woken(id, reason, ref, manager.get(id)?.projectId) }
            mail.onIncoming = { i ->
                val m = i.message
                plugins.incomingMail(com.superiorsoftllc.infantry.daemon.api.IncomingMail(m.id, m.from, m.to, i.recipientSessionId, i.recipientRole, m.subject, m.body, m.threadId, i.projectId, i.fromProjectId, i.fromProjectName))
                    ?.let { v -> MailRouter.MailVerdictInfo(v.handled, v.text) }
            }
            manager.addSessionEndedListener(eventPublisher::sessionEnded)
            // SCRIPT-SESSION-1: tool calls, output and errors of every session as named events (rate limited), and a readable chat ring.
            com.superiorsoftllc.infantry.session.SessionFeed.sink = { name, payload -> runCatching { plugins.publishEvent(name, payload) } }
            manager.addSessionEndedListener(com.superiorsoftllc.infantry.session.SessionFeed::forget)
            ticketEvents?.subscribe("core:named-events", eventPublisher::ticket)
            val reconcileReport = manager.loadAndReconcile()
            println("[infantry] reconcile: $reconcileReport")
            // BRIDGE-EVENTS-1: who waits on a session the restart touched (its ticket's starter, else its spawner); named in `daemon.started`.
            val restartStarter: (com.superiorsoftllc.infantry.session.SessionRecord) -> String? = { s ->
                s.ticketId?.let { id -> runCatching { store.transaction { store.tickets.get(id) } }.getOrNull() }?.startedBy?.takeIf { it.isNotBlank() && it != s.id }
                    ?: s.spawnerSessionId?.takeIf { it != s.id && manager.get(it) != null }
            }
            // CLI-PTY-5: the kept chat of every session goes back on its replay stream, as history: no process, no turn.
            run {
                val started = System.nanoTime()
                val loaded = runCatching { chatHistory.loadAll(manager.list().map { it.id }.toSet()) }
                    .onFailure { System.err.println("[infantry] chat history load failed: ${it.message}") }.getOrDefault(emptyMap())
                for ((id, history) in loaded) streamSequencer.seed(id, history)
                println("[infantry] chat history: ${loaded.size} sessions, ${loaded.values.sumOf { it.frames.size }} items in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
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
                "[infantry] recovery: runs=${recoveredRuns.size} pods=${recoveredPods.size} " +
                    "(runs closed ORPHANED, open pods INTERRUPTED)",
            )

            // ORCH-1: `INFANTRY_ORCHESTRATOR_PROVIDER` (default local) picks the binding callers' link answers from; a plugin offers
            // bindings through hooks.orchestratorBinding. An unknown or failing provider keeps the local binding and is logged.
            (orchestratorBindings + plugins.orchestratorBindings()).forEach { (id, binding) -> runCatching { orchestratorHub.register(id, binding) } }
            // RUNNER-POOL-2: runner bindings plugins offer (hooks.runnerBinding) are kept on the pool; none is placed on yet.
            plugins.runnerBindings().forEach { (id, binding) -> runCatching { runnerPool.register(id, binding) }.onFailure { println("[infantry] runner binding '$id' refused: ${it.message}") } }
            // RUNNER-POOL-5 (docs/RUNNER-POOL.md 7): once `infantry runner pair` paired this machine, it serves the runner link (wss,
            // pinned self-signed certificate) to its one orchestrator; on the orchestrator side it rekeys paired runners when due.
            // A daemon never paired opens nothing. RUNNER-POOL-6a: the transport is a plugin's (hooks.runnerLinkTransport); with
            // none loaded nothing is served and the `runner_admin` ops that need one say so.
            // RUNNER-POOL-7 (docs/RUNNER-POOL.md 2.3 b): the runner side holds an orchestrator-side frame of a session its remote
            // orchestrator started until the orchestrator pulls it; the orchestrator side re-gates each pulled frame as the named
            // agent and runs it on its own /ws as a non-owner (so every gate runs as for a local agent). A daemon that is its own
            // orchestrator forwards nothing: [frameForwarder] owns only link-started sessions.
            val frameForwarder = com.superiorsoftllc.infantry.runners.FrameForwarder(
                owns = remoteStarts::isLinked,
                timeoutMs = { settings.limit(LimitRegistry.RUNNERS_FORWARD_TIMEOUT_SEC) * 1000L },
                pendingMax = { settings.limit(LimitRegistry.RUNNERS_FORWARD_PENDING_MAX).toInt() },
            )
            val forwardSides = FrameTable().also { com.superiorsoftllc.infantry.backlog.registerCoreProjectFrames(it, projects, WorkspaceHandler(workspaces)) }
            val loopbackUrl = "ws://$loopbackHost:$boundPort/ws"
            val loopbackFrames = LoopbackFrames(
                loopbackUrl,
                token = { if (sandboxConfig().enabled) agentToken else token },
                timeoutMs = { settings.limit(LimitRegistry.RUNNERS_FORWARD_TIMEOUT_SEC) * 1000L },
            )
            val forwardGate = com.superiorsoftllc.infantry.runners.ForwardedFrameGate(
                runnerOf = runnerPool::runnerOf,
                sideOf = { f -> frameSide(forwardSides, plugins, Envelope(f.type, f.data, f.sessionId)) },
                pluginOwnerOnly = plugins::ownerOnly,
                dispatch = { f ->
                    loopbackFrames.call(Envelope(f.type, f.data, f.sessionId, requestId = f.requestId))
                        ?.let { com.superiorsoftllc.infantry.daemon.api.orchestrator.ForwardedFrame(it.type, it.data, it.sessionId, it.requestId) }
                },
            )
            forwardGateRef.set(forwardGate)
            val forwardPump = com.superiorsoftllc.infantry.runners.ForwardPump(
                links = { runnerLinksRef.get() },
                active = runnerPool::hasSessionsOn,
                execute = forwardGate::answer,
                waitMs = { settings.limit(LimitRegistry.RUNNERS_FORWARD_WAIT_SEC) * 1000L },
                idleMs = { settings.limit(LimitRegistry.RUNNERS_FORWARD_WAIT_SEC) * 1000L },
            )
            val runnerLinkService = com.superiorsoftllc.infantry.runners.RunnerLinkService(
                daemonStateDir, settings::limit, settings::runnerLink, localRunnerLink, transport = { plugins.runnerLinkTransport() },
                starts = remoteStarts,
                forwarder = frameForwarder,
                pump = forwardPump,
            )
            runnerLinksRef.set(runnerLinkService.links)
            plugins.runnerLinks = runnerLinkService.links
            // RUNNER-POOL-10: the rows of the Runners card (`runner_admin` op `list`): health, workers / cap, paused.
            runnerLinkService.enabledNow = settings::runnersEnabled
            runnerLinkService.setPaused = { name, paused -> settings.set("runners.$name.paused", kotlinx.serialization.json.JsonPrimitive(paused)) }
            runnerLinkService.grant = { key, ids -> settings.set(key, kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
            runnerLinkService.rowStatus = { name ->
                val e = runnerPool.runners().firstOrNull { it.name == name }
                val running = e?.health?.running ?: 0
                mapOf(
                    "health" to (e?.state ?: "down"), "workers" to running.toString(), "cap" to settings.runnerMaxWorkers(name).toString(),
                    "paused" to settings.runnerPaused(name).toString(),
                )
            }
            manager.addSessionEndedListener(remoteStarts::forget)
            // RUNNER-POOL-6: a heartbeat round over the paired runners every limits.runners.heartbeatSec sets up / degraded / down.
            // RUNNER-POOL-8 (docs/RUNNER-POOL.md 3.1): after each round the watch replays the runners' outboxes (deduped by session +
            // seq), pumps the mail of sessions that came to rest or whose runner is back, and handles a runner lost past lostAfterMin.
            val runnerWatch = com.superiorsoftllc.infantry.runners.RunnerWatch(
                pool = runnerPool,
                lostAfterMs = { settings.limit(LimitRegistry.RUNNERS_LOST_AFTER_MIN) * 60_000L },
                restartElsewhere = { settings.runnerLink().restartElsewhere },
                apply = { _, e ->
                    if (e.kind == com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent.STATUS) remoteMirror.status(e.sessionId, e.payload)
                    if (e.kind == com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent.FINISHED) remoteMirror.finished(e.sessionId)
                    val rest = e.kind == com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent.FINISHED ||
                        (e.kind == com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent.STATUS && e.payload.equals(SessionState.IDLE.name, ignoreCase = true))
                    if (rest) mail.pump(e.sessionId)
                },
                reconnected = { _, sessions -> sessions.forEach { mail.pump(it) } },
                attention = { runner, sessions, detail ->
                    attentionSvc.raise(
                        com.superiorsoftllc.infantry.daemon.api.attention.AttentionKinds.RUNNER_LOST,
                        ticketId = sessions.firstNotNullOfOrNull { boardPorts.ticketOfWorker(it) }, sessionId = sessions.firstOrNull(),
                        outcome = "runner $runner lost", detail = detail,
                    )
                },
                ticketOf = { sid -> boardPorts.ticketOfWorker(sid) },
                restart = { t, r, p -> boardPorts.restartElsewhere(t, r, p) },
            )
            scheduledTicker(settings.limit(LimitRegistry.RUNNERS_HEARTBEAT_SEC) * 1000L) {
                runCatching { runnerPool.heartbeat(settings.limit(LimitRegistry.RUNNERS_LOST_AFTER_SEC) * 1000L).forEach { println("[infantry] $it") } }
                runCatching { runnerWatch.tick() }.onFailure { System.err.println("[infantry] runner watch failed: ${it.message}") }
            }
            // RUNNER-POOL-8: runner side of runners.pushOnCommit: push a ticket branch once its worker committed.
            scheduledTicker(settings.limit(LimitRegistry.RUNNERS_COMMIT_CHECK_SEC) * 1000L) { runCatching { remoteStarts.checkCommits() } }
            runCatching { runnerLinkService.tick() }
            scheduledTicker(settings.limit(LimitRegistry.RUNNERS_LINK_CHECK_SECONDS) * 1000L) { runCatching { runnerLinkService.tick() } }
            orchestratorHub.select(pluginEnv("INFANTRY_ORCHESTRATOR_PROVIDER")?.trim().orEmpty())?.let { System.err.println(it) }
            // A guest token present at start is checked now, so a bad one still stops the daemon starting.
            plugins.guestTokens().forEach { policy.guestDigestOf(it) }
            // AGENT-REGISTRY-1: a disabled agent starts no new session (no override = nothing refused), then the plugins' gates.
            manager.spawnGate = { role, projectId, _ ->
                // RUNNER-POOL-10: an orchestrator-only daemon runs no session of its own.
                (if (deploymentMode.runsSessions) null else "this daemon runs in orchestrator mode (--mode orchestrator): local sessions are refused; place the work on a runner")
                    ?: agentRegistry.spawnRefusal(role) ?: plugins.spawnRefusal(role, projectId)
            }
            policies.attach(object : com.superiorsoftllc.infantry.policy.PolicyRuntime.Extensions {
                override fun presets() = plugins.presets()
                override fun seatsClaimed() = plugins.seatsClaimed
                override fun notice() = plugins.notice()
                override fun action(envelope: Envelope) = plugins.actionFor(envelope)
                override fun frameActions() = plugins.frameActions()
            })
            policies.onChange { runtime -> println("[infantry] policy switched to '${runtime.current.id}'") }
            policies.onChange { pluginsNow.set(resolvePlugins()) }
            // DAEMON-PLUGINS-1 (`events`): every frame the daemon pushes to all its clients, to the plugins' listeners.
            if (plugins.hasPlugins) {
                val pluginEvents = Channel<Envelope>(Channel.UNLIMITED)
                clients.add(pluginEvents)
                Thread({
                    kotlinx.coroutines.runBlocking {
                        for (e in pluginEvents) {
                            plugins.broadcast(e)
                            // `sessions` hook: a session's status push, as the session it is now.
                            if (e.type == Envelope.SESSION) e.sessionId?.let(manager::get)?.let {
                                plugins.sessionChanged("status", com.superiorsoftllc.infantry.daemon.plugins.CoreDaemonServices.info(it))
                                eventPublisher.sessionStatus(it.id, it.state.name, it.role, it.projectId, it.ticketId, it.agentId)
                                eventPublisher.sessionMcp(it.id, it.mcp, it.mcpChangedAt, it.role, it.projectId)
                            }
                        }
                    }
                }, "daemon-plugin-events").apply { isDaemon = true }.start()
            }
            // Ticket 91495a9f (DaemonApi 1.39.0): every daemon half and harness module is in and recovery is done: the plugins' `whenStarted` work runs now.
            plugins.markStarted()
            // Deliver anything that was queued before the last shutdown to a session already at rest.
            mail.onStartup()
            // TRIGGER-NUDGE: only now may a schedule fire (a slot missed while down fires on this first pump).
            triggers.start()
            // Contract 1.11.0: this process's identity, taken AFTER reconciliation and recovery so a session
            // they touched (and that streamed nothing here) reads as older than the replay history.
            // CLI-PTY-5: with kept chat history every session may have history here, whatever its last heartbeat.
            val identity = DaemonIdentity(
                UUID.randomUUID().toString(),
                if (settings.limit(LimitRegistry.CHAT_HISTORY_FRAMES) > 0) 0L else System.currentTimeMillis(),
            )
            println(
                if (projectDirs && repo == null) {
                    "[infantry] worktree repo=<per project> (sessions and ticket starts run in their project's directory; " +
                        "a project with no directory is refused)"
                } else {
                    worktreeBootLine(repo, workingDir, baseRef.refFor(com.superiorsoftllc.infantry.worktree.BaseScope()))
                },
            )

            val server = embeddedServer(CIO, port = boundPort, host = host) {
                install(WebSockets)
                install(ContentNegotiation) { json() }
                // Before routing, so a refused request never reaches a handler (the mailbox included).
                installAccessPolicy(policy, webBundle::isAsset, httpGate = plugins::httpRefusal, publicRoute = plugins::isPublicRoute)
                routing {
                    // DAEMON-PLUGINS-1: each plugin's declared top-level routes, and every plugin's own `/plugins/<id>/...`.
                    for ((method, path) in plugins.topLevelRoutes()) {
                        route(path, io.ktor.http.HttpMethod.parse(method)) { handle { answerPluginRoute(call, plugins) } }
                    }
                    route("${com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.PLUGIN_PREFIX}{module}/{path...}") {
                        handle { answerPluginRoute(call, plugins) }
                    }
                    get("/health") {
                        // Ticket 55d60a5e: the database part runs on the gate's own thread, bounded; a stuck database
                        // is answered 503 at once instead of holding this request thread (and every later one).
                        val outcome = healthGate.read {
                            HealthCounts(
                                sessions = manager.list().size,
                                worktrees = worktrees.list().count { !it.removed },
                                pods = store.pods.open().size,
                                runsInFlight = store.runs.unfinished().size,
                                attentionOpen = attentionSvc.list().count { it.ackedAt == null },
                                triggersTripped = triggers.trippedCount(),
                            )
                        }
                        val counts = (outcome as? HealthGate.Outcome.Ok)?.value?.also { lastHealthCounts = it } ?: lastHealthCounts
                        val (database, detail) = when (outcome) {
                            is HealthGate.Outcome.Ok -> "ok" to null
                            is HealthGate.Outcome.Stuck -> "stuck" to "no answer for ${outcome.forMs} ms"
                            is HealthGate.Outcome.Failed -> "error" to outcome.message
                        }
                        call.respond(
                            if (database == "ok") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                            Health(
                                status = if (database == "ok") "ok" else "unhealthy",
                                provider = provider.id,
                                runner = runner.id,
                                sessions = counts?.sessions ?: 0,
                                worktrees = counts?.worktrees ?: 0,
                                pods = counts?.pods ?: 0,
                                runsInFlight = counts?.runsInFlight ?: 0,
                                providers = manager.providers.map { it.id },
                                attentionOpen = counts?.attentionOpen ?: 0,
                                providerStatus = manager.providers.associate { it.id to providerStates.state(it.id).status.wireName },
                                triggersTripped = counts?.triggersTripped ?: 0,
                                database = database,
                                databaseDetail = detail,
                                pluginStatus = plugins.statusFields(),
                            ),
                        )
                    }
                    get("/plugins") { call.respond(pluginsNow.get().withDaemon(plugins).withHarness(harnessModulesRef.get())) }
                    // P1 step 2 (6s B): the per-node UI configuration the window seeds and edits.
                    val nodeConfigStore = NodeConfigStore((store as SqlInfantryStore).database)
                    nodeConfigRoutes(nodeConfigStore)
                    // CONFIG-3: the compiled unified manifest, one row per host; a swap migrates node_config's seed rows.
                    configManifestRoutes(ConfigManifestStore(store.database, nodeConfigStore))
                    // CONFIG-8: `GET /config/schema` (+ `/config/values`), the HTTP twin of the config_schema / config_values frames.
                    daemonConfigRoutes(daemonConfig)
                    // Phase 6 agent-facing mail API: how a spawned CLI agent answers a delivered message.
                    mailRoutes(mail, mailbox) { request -> plugins.mailRefusal(request.from, request.to, request.allowCrossProject) }
                    // UPDATER-1: `/update/*` (status / check / apply / client) is served by the Infantry_Updater plugin's daemon half.
                    // P9.6: signature-admitted webhook ingress (exempt from the bearer in AccessPolicy, signed per hook).
                    hookRoutes(WebhookIngress(triggers))
                    // STALL-2: the harness watch hooks (claude PreToolUse http, opencode plugin, pi extension) ask here.
                    watchHookRoutes(
                        approvalOf = { id -> manager.get(id)?.let { manager.toolApprovalFor(id).mode } },
                        launcher = { WatchHooks.launcher(infantryBin) },
                    )
                    extraRoutes()
                    // WEB-1: the browser client, one exact route per bundle file (no path resolution, nothing to traverse).
                    webBundleRoutes(webBundle)
                    webSocket("/ws") {
                        // Ticket 91495a9f: the address, never remoteHost. remoteHost is a reverse DNS lookup whose name the JVM then
                        // caches on the socket address, so the Caller's remoteAddress (hostString) became "localhost" and a
                        // loopback reverse proxy was no longer recognised: every visitor shared one per-IP turn budget.
                        val wsPeer = call.request.local.remoteAddress
                        System.err.println("[infantry] ws connect from $wsPeer")
                        try { handleConnection(
                            this,
                            manager,
                            worktrees,
                            mail,
                            mailbox,
                            repo,
                            clients,
                            streamSequencer,
                            orchestrator,
                            agents,
                            projects,
                            projectGit,
                            WorkspaceHandler(workspaces),
                            notifyFrames,
                            keystoreFrames,
                            settings,
                            identity,
                            providerStates,
                            triggers,
                            harnesses,
                            com.superiorsoftllc.infantry.persistence.PromptStore(store.prompts),
                            com.superiorsoftllc.infantry.persistence.SkillStore(store.skills),
                            com.superiorsoftllc.infantry.persistence.TraitStore(store.traits, { rolesLookup.role(it) != null }, boundBy = { id -> store.traitReferences(id, runCatching { pluginHost.get().serviceRegistry.find(com.superiorsoftllc.infantry.daemon.api.roles.RoleTraitLists::class)?.all() }.getOrNull().orEmpty()) }),
                            knowledge,
                            modelCatalogs,
                            skillsSource,
                            com.superiorsoftllc.infantry.agents.LayeredSkillStore(store),
                            spawnGuard,
                            runnerPool = runnerPool,
                            remoteSessions = remoteMirror::list,
                            limits = limits,
                            usagePacing = usagePacing,
                            ticketFiles = ticketFilesText?.let { text ->
                                { query: com.superiorsoftllc.infantry.ticketfiles.TicketFilesQuery ->
                                    text.answer(
                                        query,
                                        settings.limit(LimitRegistry.TICKET_FILES_MAX_LINES).toInt(),
                                        settings.limit(LimitRegistry.TICKET_FILES_RELATED_TOP).toInt(),
                                    )
                                }
                            },
                            archive = archiveService,
                            cliLocations = cliLocations,
                            harnessInstaller = harnessInstaller,
                            toolApproval = toolApproval,
                            pendingPrompts = pendingPrompts,
                            handover = handover,
                            boardPorts = boardPorts,
                            processHost = localProcesses,
                            ownerCredential = ownerCredential,
                            plugins = plugins,
                            runnerLink = runnerLinkService,
                            forwarder = frameForwarder,
                            deploymentMode = deploymentMode,
                            daemonConfig = daemonConfig,
                            tier = call.attributes.getOrNull(ACCESS_TIER) ?: AccessTier.OWNER,
                            policies = policies,
                            promptSendIds = promptSendIds,
                        ) } finally {
                            System.err.println("[infantry] ws close from $wsPeer: ${runCatching { closeReason.await() }.getOrNull() ?: "no reason"}")
                        }
                    }
                }
            }
            // BUG-PORT-0b: give the held port back on EVERY start path, InfantryDaemon.start() and a direct
            // server.start(...) alike. ApplicationStarted is the last event EmbeddedServer.start raises before
            // engine.start binds (after module setup), so the release-to-bind gap stays as small as it can be.
            // runCatching: it is raised with monitor.raise, so a throwing handler would abort the start.
            server.monitor.subscribe(ApplicationStarted) { runCatching { portReservation.close() } }
            // BRIDGE-EVENTS-1: once the port is bound (a resumed CLI's MCP connects back), once: `daemon.started` with the turns the stop
            // cut and the sessions still running. Core only reports; the process (resume, notify) is a script (Infantry_Scripting's
            // shipped `resume-after-restart`, or the developer's own hook on `daemon.started`).
            val daemonStartedSent = java.util.concurrent.atomic.AtomicBoolean(false)
            server.monitor.subscribe(io.ktor.server.application.ServerReady) {
                if (daemonStartedSent.compareAndSet(false, true)) {
                    runCatching {
                        val payload = com.superiorsoftllc.infantry.session.DaemonStartedEvent.payload(reconcileReport, manager::get, restartStarter)
                        eventPublisher.daemonStarted(payload)
                        println("[infantry] daemon.started: cutTurns=${reconcileReport.cutTurns.size} orphaned=${reconcileReport.orphanedIds.size}")
                    }.onFailure { System.err.println("[infantry] daemon.started failed: ${it.message}") }
                }
            }

            return InfantryDaemon(
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
                boardPorts = boardPorts,
                attention = attentionSvc,
                budget = budget,
                providerStates = providerStates,
                triggers = triggers,
                workspaces = workspaces,
                portFile = portFile,
                // BUG-PERM-1: the Tool approval reader is put back with the executable resolver (both read this store).
                restoreExecutables = { restoreToolApproval(); restoreExecutables() },
                pendingPrompts = pendingPrompts,
                portReservation = portReservation,
                handover = handover,
                processHost = localProcesses,
                background = listOf(AutoCloseable { usageRefresher.stop() }, runnerLinkService, workerSweepCloser, AutoCloseable { agentWatchdog.close() }, AutoCloseable { finishNotice.close() }, AutoCloseable { stallDetector.close() }, healthGate, AutoCloseable { harnessModulesRef.get()?.close() }),
                afterSessions = listOf(plugins),
                healthSettings = healthSettings,
                deploymentMode = deploymentMode,
                pluginHost = { pluginHost.get() },
            )
        }

        /**
         * The daemon's database file: `INFANTRY_DB` when set, else `infantry.db` inside [stateDir].
         *
         * Same convention as `sessions.json` and `mailbox.json` -- inside the state dir, so the file
         * is stable across restarts and survives a `git clean` because it lives outside the repo.
         */
        fun defaultDbPath(): String =
            infantrySetting("INFANTRY_DB")?.takeIf { it.isNotBlank() }
                ?: File(stateDir(), DEFAULT_DB_NAME).absolutePath

        /**
         * Build from the environment, exactly as [main] does, AFTER validating the access configuration.
         *
         * The validation runs before [build] because build's default `store` opens the SQLite file before its
         * body runs: a daemon that is about to refuse to start must not touch its state first.
         */
        fun fromEnv(extraRoutes: io.ktor.server.routing.Route.() -> Unit = {}): InfantryDaemon {
            val host = RemoteAccess.resolveHost(stateDir())
            AccessPolicy.of(host, RemoteAccess.resolveToken(stateDir()))
            // PORT-1: a strict daemon on a taken port refuses here, before build() opens the database.
            if (DaemonPort.strictFromEnv()) DaemonPort.choose(configuredPort(), host, strict = true)
            return build(
                storyMigrationFile = File(stateDir(), "stories.json"),
                shimDir = File(stateDir(), "shims"),
                portFile = File(stateDir(), DaemonPort.FILE_NAME),
                extraRoutes = extraRoutes,
            )
        }

        /** `INFANTRY_PORT` (system property first, PKG-1), else [DaemonPort.DEFAULT]. */
        fun configuredPort(): Int =
            infantrySetting(DaemonPort.PORT_ENV)?.toIntOrNull() ?: RemoteAccess.filePort(stateDir()) ?: DEFAULT_PORT
    }
}

/** Holds a streamed (pi) reasoning block until it ends, so the wire and the replay carry it as ONE `reasoning` frame. */
private val streamedReasoning = com.superiorsoftllc.infantry.protocol.ReasoningCoalescer()
private val claudeTasks = com.superiorsoftllc.infantry.protocol.todo.ClaudeTaskTools()

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
    runActivity: com.superiorsoftllc.infantry.protocol.RunActivityTracker,
    /** A sleep wait seen in a tool call (seconds), or null when it ended; see SleepHint. */
    onWait: (sessionId: String, seconds: Long?) -> Unit = { _, _ -> },
    /** TODO-PROGRESS-1: a to-do tool call carried the agent's full list (text to status); it drives the progress bar. */
    onTodos: (sessionId: String, items: List<Pair<String, String>>) -> Unit = { _, _ -> },
    /** BUG-PERM-1: the Tool approval a session runs under, named by its denial notice. */
    toolApprovalFor: (sessionId: String) -> com.superiorsoftllc.infantry.harness.ToolApproval = { com.superiorsoftllc.infantry.harness.ToolApproval.DENY },
) {
    val frames: List<Envelope> = when (event) {
        is SessionEvent.Status -> {
            println("[infantry] session ${event.session.id.take(8)} -> ${event.session.state}")
            // FIRSTRUN-FAIL-1: a failed session's last action names why it failed, so the board's "doing" is not just "state failed".
            val stateAction = event.session.lastError?.takeIf { event.session.state == SessionState.FAILED && it.isNotBlank() }
                ?.let { "failed: $it" } ?: "state ${event.session.state.name.lowercase()}"
            // DENIED-LABEL-1: a turn that ended on a refused tool keeps its "blocked:" label when the session goes idle.
            val keepBlocked = event.session.state == SessionState.IDLE &&
                com.superiorsoftllc.infantry.session.SessionActivityLog.lastAction(event.session.id)?.startsWith("blocked:") == true
            if (!keepBlocked) com.superiorsoftllc.infantry.session.SessionActivityLog.event(event.session.id, stateAction)
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
            // Kept from Phase 1: the child's lines are visible in the daemon terminal.
            println("[${event.sessionId.take(8)}] ${event.line}")
            val raw = Envelope(Envelope.STDOUT, event.line, event.sessionId)
            val taskTodos = claudeTasks.feed(event.sessionId, event.line)
            val normalised = EventNormalizer.normalise(event.line, afterInterrupt = event.afterInterrupt).map { l ->
                if (taskTodos != null && l is EventNormalizer.Line.Tool && l.todos == null && l.name.startsWith("Task")) l.copy(todos = taskTodos) else l
            }
            val neutral = streamedReasoning.process(event.sessionId, normalised).flatMap { line ->
                // 1.95.0: the line moves the run activity first; a tool call carries the change on its own status
                // frame, any other line on a bare `status` frame after its own.
                val moved = runActivity.line(event.sessionId, line)
                when (line) {
                    is EventNormalizer.Line.Tool -> {
                        com.superiorsoftllc.infantry.session.SessionFeed.tool(event.sessionId, (if (line.activity.isNullOrBlank()) line.name else "${line.name}: ${line.activity}"))
                        onWait(event.sessionId, line.waitSeconds)
                        line.todos?.let { t -> onTodos(event.sessionId, t.map { it.text to it.status }) }
                    }
                    EventNormalizer.Line.ToolEnd, is EventNormalizer.Line.Text, is EventNormalizer.Line.Reasoning -> {
                        if (line is EventNormalizer.Line.Text) com.superiorsoftllc.infantry.session.SessionFeed.text(event.sessionId, line.delta)
                        onWait(event.sessionId, null)
                    }
                    is EventNormalizer.Line.Failure -> com.superiorsoftllc.infantry.session.SessionFeed.error(event.sessionId, line.message)
                    else -> Unit
                }
                com.superiorsoftllc.infantry.session.SessionActivityLog.output(
                    event.sessionId,
                    when (line) {
                        is EventNormalizer.Line.Tool -> (if (line.activity.isNullOrBlank()) line.name else "${line.name}: ${line.activity}").take(120)
                        is EventNormalizer.Line.Text -> "replying"
                        is EventNormalizer.Line.Reasoning -> "thinking"
                        else -> null
                    },
                    tool = line is EventNormalizer.Line.Tool,
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
                    is EventNormalizer.Line.Denial -> {
                        val mode = toolApprovalFor(event.sessionId)
                        // FIRSTRUN-PERM-1: a blocked turn shows on the card/board as its last action, not only as "succeeded".
                        com.superiorsoftllc.infantry.session.SessionActivityLog.event(event.sessionId, deniedAction(line, mode))
                        denialEnvelope(event.sessionId, line, mode)
                    }
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
            // LAST-TURN-1: every harness's turn ends here (`finished`), so `lastTurnAt` is stamped once, harness-neutral.
            com.superiorsoftllc.infantry.session.SessionActivityLog.turnEnded(event.sessionId)
            streamedReasoning.flush(event.sessionId)?.let { add(Envelope(Envelope.REASONING, it.text, event.sessionId)) }
            // Contract 1.64.0 (BUG-M2-11b-1): `exit` reports a hosted process that ended unasked; a turn's end is `finished`.
            if (event.processExited) event.code?.let { add(Envelope(Envelope.EXIT, it.toString(), event.sessionId)) }
            add(finishedEnvelope(event.sessionId, event.code, event.killOutcome, event.reason))
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
            // CHAT-QUEUE-1: a message forced into the running turn is not a turn start.
            if (event.midTurn) null else runActivityEnvelope(event.sessionId, runActivity.turnStarted(event.sessionId)),
        ).filterNotNull()

        // M2-11: run plumbing (process started, pid, resumed provider session) goes to the Activity box, on the
        // existing status frame's tool/activity fields, so the chat shows at most one boot line.
        is SessionEvent.Activity -> {
            System.err.println("[${event.sessionId.take(8)}] [infantry] ${event.detail}")
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
private fun runActivityEnvelope(sessionId: String, activity: com.superiorsoftllc.infantry.protocol.RunActivity): Envelope = Envelope(
    Envelope.STATUS,
    json.encodeToString(StatusPayload.serializer(), StatusPayload(activity.state.sessionState(), runActivity = activity)),
    sessionId,
)

/** The session state a run-activity frame stands for, so its `status.state` never claims BUSY after the turn ended. */
private fun com.superiorsoftllc.infantry.protocol.RunPhase.sessionState(): SessionState = when (this) {
    com.superiorsoftllc.infantry.protocol.RunPhase.THINKING, com.superiorsoftllc.infantry.protocol.RunPhase.TOOL_RUNNING, com.superiorsoftllc.infantry.protocol.RunPhase.COMPACTING -> SessionState.BUSY
    com.superiorsoftllc.infantry.protocol.RunPhase.WAITING_FOR_INPUT -> SessionState.WAITING_INPUT
    else -> SessionState.IDLE
}

/** Assign each live session-stream frame exactly one increasing sequence before publishing it. */
private class SessionFrameSequencer(private val history: SessionHistoryStore? = null) {
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
                        history?.record(sequenced)
                    }
                } else {
                    frame
                }
                for (client in clients) client.trySend(published)
            }
        }
    }

    /**
     * CLI-PTY-5: put [loaded] (a session's kept history) on [sessionId]'s stream before anything live. The frames are numbered
     * after the dropped ones, so a replay from 0 also answers a gap that says how many older items were dropped.
     */
    fun seed(sessionId: String, loaded: SessionHistoryStore.Loaded) {
        val state = sessions.computeIfAbsent(sessionId) { State() }
        synchronized(state) {
            if (state.latestSequence != 0L) return
            val kept = loaded.frames.takeLast(SESSION_REPLAY_BUFFER_CAPACITY)
            state.evictedCount = loaded.dropped + (loaded.frames.size - kept.size)
            state.latestSequence = state.evictedCount
            for (frame in kept) state.frames.addLast(frame.copy(sequence = ++state.latestSequence))
        }
    }

    /** CLI-PTY-5: the session was deleted: its stream and its kept history go. */
    fun forget(sessionId: String) {
        sessions.remove(sessionId)
        history?.forget(sessionId)
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
private fun finishedEnvelope(sessionId: String, exitCode: Int?, kill: KillOutcome? = null, reason: String? = null): Envelope {
    val outcome = when {
        kill == KillOutcome.GRACEFUL -> FinishedPayload.Outcome.KILLED_GRACEFUL
        kill == KillOutcome.FORCED -> FinishedPayload.Outcome.KILLED_FORCED
        exitCode == 0 -> FinishedPayload.Outcome.SUCCEEDED
        else -> FinishedPayload.Outcome.FAILED
    }
    // FIRSTRUN-FAIL-1: a FAILED outcome always carries its reason (the harness's, or the exit code).
    val detail = if (outcome != FinishedPayload.Outcome.FAILED) "" else reason?.takeIf { it.isNotBlank() }
        ?: exitCode?.let { "the process exited with code $it" } ?: "the turn failed with no exit code and no reason"
    val payload = FinishedPayload(exitCode = exitCode, outcome = outcome, detail = detail)
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
private fun broadcastMail(clients: List<Channel<Envelope>>, event: MailEvent, attention: com.superiorsoftllc.infantry.daemon.api.attention.Attention) {
    val envelope = when (event) {
        is MailEvent.Sent ->
            Envelope(Envelope.MAIL, json.encodeToString(Message.serializer(), event.message), event.message.to)

        is MailEvent.Changed -> {
            val m = event.message
            if (m.failed) System.err.println("[infantry] mail ${m.id.take(8)} UNDELIVERABLE: ${m.failureReason}")
            Envelope(Envelope.MAIL, json.encodeToString(Message.serializer(), m), m.to)
        }

        is MailEvent.Refused -> {
            System.err.println("[infantry] mail refused ${event.from} -> ${event.to}: ${event.reason}")
            Envelope(
                Envelope.MAIL_REFUSED,
                json.encodeToString(MailRefused.serializer(), MailRefused(event.from, event.to, null, event.reason)),
            )
        }

        is MailEvent.CeilingReached -> {
            // A conversation ceiling needs a durable operator signal, not only a stdout line.
            println("[infantry] conversation ceiling reached on thread ${event.threadId} (limit ${event.limit})")
            attention.raise(
                kind = AttentionKinds.CEILING,
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
    com.superiorsoftllc.infantry.session.SessionState.EXITED,
    com.superiorsoftllc.infantry.session.SessionState.FAILED,
    com.superiorsoftllc.infantry.session.SessionState.ORPHANED,
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
 * BUG-REGRESS-1: the boot line naming where work runs. With no `INFANTRY_REPO`, a ticket start still cuts its own
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
    if (repo != null) return "[infantry] worktree repo=${repo.absolutePath} (new sessions get their own worktree)"
    val checkout = generateSequence(workingDir.absoluteFile) { it.parentFile }.firstOrNull { File(it, ".git").exists() }
        ?: return "[infantry] worktree repo=<unset> (sessions and ticket starts run in ${workingDir.absolutePath}, " +
            "which is not a git checkout; set INFANTRY_REPO to give each its own worktree)"
    val main = mainRootOf(checkout)
    // BUG-BASE-087: no daemon-wide base; each start takes its epic's or project's, and is refused with neither.
    val from = baseRef.ifBlank { "their epic's or project's base branch (refused when neither is set)" }
    return "[infantry] worktree repo=<unset> (ticket starts cut their own ticket/ branch from $from of the main " +
        "checkout ${main.absolutePath}, in its .worktrees/; other sessions run in ${workingDir.absolutePath}; set " +
        "INFANTRY_REPO to give them their own worktree too)"
}

/** Contract 1.12.0: push a saved role to every connected client; for the one that saved it, this IS the answer. */
private fun configSchemaEnvelope(config: DaemonConfig) =
    Envelope(Envelope.CONFIG_SCHEMA, json.encodeToString(com.superiorsoftllc.infantry.plugin.api.ConfigSchemaFrame.serializer(), config.schema()))

private fun configValuesEnvelope(values: com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame) =
    Envelope(Envelope.CONFIG_VALUES, json.encodeToString(com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame.serializer(), values))


/**
 * Contract 1.22.0: push an epic whose ordered `storyIds` just changed to every connected client,
 * the one whose `story_save` caused it included -- so a UI's epics list refreshes on the same `epic`
 * frame an `epic_save` answers with.
 */
private fun broadcastEpic(clients: List<Channel<Envelope>>, epic: Epic) {
    val envelope = Envelope(Envelope.EPIC, json.encodeToString(Epic.serializer(), epic))
    for (client in clients) client.trySend(envelope)
}

/**
 * RUNNER-POOL-7: the side attribute of [envelope]'s frame: this connection's frame table entry, else the plugin frame or tool's
 * declared side, else the core inline frames' ([CoreFrameSides]). Null = not known here.
 */
internal fun frameSide(frames: FrameTable, plugins: com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost, envelope: Envelope): String? =
    frames.side(envelope.type) ?: plugins.sideOf(envelope) ?: CoreFrameSides.side(envelope.type)

private suspend fun handleConnection(
    session: DefaultWebSocketServerSession,
    manager: SessionManager,
    worktrees: GitWorktreeManager,
    mail: MailRouter,
    mailbox: Mailbox,
    defaultRepo: File?,
    clients: MutableList<Channel<Envelope>>,
    streamSequencer: SessionFrameSequencer,
    orchestrator: Orchestrator,
    agents: AgentProfiles,
    projects: ProjectHandler,
    projectGit: ProjectGitHandler,
    workspaceFrames: WorkspaceHandler,
    notifyFrames: com.superiorsoftllc.infantry.notify.NotifyHandler,
    keystoreFrames: KeystoreHandler,
    settings: ProjectSettings,
    identity: DaemonIdentity,
    providerStates: ProviderStateTracker,
    triggers: TriggerService,
    harnesses: HarnessRegistry,
    prompts: com.superiorsoftllc.infantry.persistence.PromptStore,
    skills: com.superiorsoftllc.infantry.persistence.SkillStore,
    traits: com.superiorsoftllc.infantry.persistence.TraitStore,
    knowledge: KnowledgeCounter,
    modelCatalogs: com.superiorsoftllc.infantry.provider.HarnessModelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
    /** P10-K15: the `skills` section every first turn renders after `knowledge`. */
    skillsSource: com.superiorsoftllc.infantry.agents.SkillsSource = com.superiorsoftllc.infantry.agents.SkillsSource.NONE,
    /** SKILLS-GLOBAL-1b: the layered skill store behind `layer_skill_*` (null = those frames are refused). */
    layerSkills: com.superiorsoftllc.infantry.agents.LayeredSkillStore? = null,
    /** BUG-G2d: bounds the spawns a frame naming its session (`pod_submit` / `new`) may make. */
    spawnGuard: SpawnGuard = SpawnGuard(manager::get),
    /** RUNNER-POOL-2: the runner pool a `new` console start goes through (default: the local runner over [manager]). */
    runnerPool: com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool = LocalRunner.pool(manager, agents),
    /** U1: the plan-limit meters a `limits` request reads. */
    limits: LimitService = LimitService.NONE,
    /** USAGE-PACE (1.119.0): the meters and allowances a `usage_get` request reads. */
    usagePacing: UsagePacing = UsagePacing({ emptyList() }, { com.superiorsoftllc.infantry.daemon.api.usage.PacingRule.DEFAULT }, { emptyMap() }, { emptyMap() }, { emptyList() }),
    /** TICKET-FILES (contract 1.137.0): answers `ticket_files` (query -> capped text); null = this daemon serves none. */
    ticketFiles: ((com.superiorsoftllc.infantry.ticketfiles.TicketFilesQuery) -> String)? = null,
    /** ARCHIVE-1 (contract 1.149.0): answers `archive_query` / `archive_apply`; null = this daemon serves none. */
    archive: com.superiorsoftllc.infantry.archive.ArchiveService? = null,
    /** CLIPATH-1 (contract 1.73.0): `cli_locations` / `cli_location_set`; null = this daemon serves neither. */
    cliLocations: com.superiorsoftllc.infantry.executables.CliLocationService? = null,
    /** HARNESS-INSTALL-1 (contract 1.100.0): `harness_install_status` / `harness_install`; null = this daemon serves neither. */
    harnessInstaller: com.superiorsoftllc.infantry.harness.install.HarnessInstaller? = null,
    /** BUG-PERM-1 (contract 1.76.0): `tool_approval` / `tool_approval_set`; null = served by neither. */
    toolApproval: com.superiorsoftllc.infantry.session.ToolApprovalService? = null,
    /** PROMPT-1 (contract 1.77.0): open prompts replayed on connect, answered by `prompt_response`; null = none. */
    pendingPrompts: com.superiorsoftllc.infantry.prompts.PendingPrompts? = null,
    /** TICKET-HANDOVER: a ticket worker's harness switch goes through a handoff turn; null = switched at once. */
    handover: SessionHandover? = null,
    /** DP-5h-3e-a: the roster titles come from the board through this port; no board = no titles. */
    boardPorts: BoardPorts = BoardPortRef(),
    /** ROGUE-1 (contract 1.120.0): `processes`; null = this daemon serves none. */
    processHost: com.superiorsoftllc.infantry.session.LocalProcesses? = null,
    /** SEC-FIX-1 (1.136.0): who is the owner ([FrameGate.OWNER_ONLY]); null = no connection is. */
    ownerCredential: OwnerCredential? = null,
    /** DAEMON-PLUGINS-1: the daemon plugins: their gates, frames and tools (docs/DAEMON-PLUGINS.md); none by default. */
    plugins: com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost = com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.empty(),
    /** RUNNER-POOL-6a: answers the owner-only `runner_admin` frame (`infantry runner`); null = this daemon serves none. */
    runnerLink: com.superiorsoftllc.infantry.runners.RunnerLinkService? = null,
    /** RUNNER-POOL-7: holds the orchestrator-side frames of sessions a remote orchestrator started here; null = forwards nothing. */
    forwarder: com.superiorsoftllc.infantry.runners.FrameForwarder? = null,
    /** RUNNER-POOL-10: a runner-mode daemon refuses the board frames of a client. */
    deploymentMode: DeploymentMode = DeploymentMode.BOTH,
    /** CONFIG-8 (1.142.0): the daemon's declared config (`config_*` frames); the default declares nothing. */
    daemonConfig: DaemonConfig,
    /** SANDBOX-1: what this connection was admitted as; an AGENT (the scoped token) may send only [AgentFrames.ALLOWED]. */
    tier: AccessTier = AccessTier.OWNER,
    /**
     * POLICY-1: the active policy preset gates every frame by its named action (its seated lockdown, for every
     * tier); this connection is sent the `policy` frame after `ready` and again on every switch.
     */
    policies: com.superiorsoftllc.infantry.policy.PolicyRuntime = com.superiorsoftllc.infantry.policy.PolicyRuntime(emptyList(), { null }, null, java.io.File(".")),
    /** CHAT-SENDING-1: the client message ids of recent prompts, daemon-wide (a resend arrives on a new connection). */
    promptSendIds: PromptSendIds = PromptSendIds { LimitRegistry.CHAT_SENT_IDS_KEPT.default.toInt() },
    /** RUNNER-E2E-2: the sessions started on remote runners (read-only mirror); the `sessions` answer lists them with the local ones. */
    remoteSessions: () -> List<SessionRecord> = { emptyList() },
) {
    println("[infantry] ws connected (provider=${manager.providerId})")
    // DP-5d: the board-family frames, registered by their owner; the loop below only looks them up.
    val frames = FrameTable().also { com.superiorsoftllc.infantry.backlog.registerCoreProjectFrames(it, projects, workspaceFrames) }
    // SEC-FIX-1: decided once, at the handshake, from what this connection presented.
    val owner = ownerCredential?.presentedBy(
        session.call.request.headers[OwnerCredential.HEADER] ?: session.call.request.headers[OwnerCredential.LEGACY_HEADER],
        session.call.request.headers.getAll(io.ktor.http.HttpHeaders.SecWebSocketProtocol)?.joinToString(","),
    ) == true
    val outgoing = Channel<Envelope>(Channel.UNLIMITED)
    clients.add(outgoing)
    // RUNNER-POOL-6a: `infantry runner pair|add|rotate|detach <name>` (FrameGate.OWNER_ONLY); the link calls block, so off the loop.
    runnerLink?.let { link ->
        frames.register(com.superiorsoftllc.infantry.runners.RunnerLinkService.ADMIN) { env, _ ->
            val request = runCatching { json.parseToJsonElement(env.data) as kotlinx.serialization.json.JsonObject }.getOrNull()
                ?: return@register Envelope(Envelope.ERROR, "runner_admin: the data must be a JSON object", requestId = env.requestId)
            session.launch(kotlinx.coroutines.Dispatchers.IO) {
                val answer = link.admin(request)
                outgoing.trySend(Envelope(com.superiorsoftllc.infantry.runners.RunnerLinkService.ADMIN, answer.toString(), requestId = env.requestId))
            }
            null
        }
    }
    // PLUGIN-BRIDGES-1 (docs/wiki/Bridges.md): this connection may host client-side capabilities (the UI halves it loaded). The
    // registry keys the host by this connection's outgoing channel; a call reaches it as a `capability_call` frame.
    // CORE-PROGRESS-DOWNLOAD-1: a client's Cancel / Retry of a progress task (owner connections only).
    for (type in listOf(com.superiorsoftllc.infantry.plugin.api.ProgressWire.CANCEL, com.superiorsoftllc.infantry.plugin.api.ProgressWire.RETRY)) frames.register(type) { env, _ ->
        if (!owner) return@register Envelope(Envelope.ERROR, "$type: only an owner connection may change a task", requestId = env.requestId)
        plugins.progressFrame(type, env.data)
        null
    }
    val capabilityWire = com.superiorsoftllc.infantry.plugin.api.CapabilityWire
    frames.register(capabilityWire.HOST) { env, _ ->
        if (!owner) return@register Envelope(Envelope.ERROR, "${capabilityWire.HOST}: only an owner connection hosts capabilities", requestId = env.requestId)
        val hosting = capabilityWire.hosting(env.data)
            ?: return@register Envelope(Envelope.ERROR, "${capabilityWire.HOST}: the data must be a capability hosting object", requestId = env.requestId)
        val id = plugins.capabilities.host(outgoing, hosting) { type, data -> outgoing.trySend(Envelope(type, data)).isSuccess }
        Envelope(capabilityWire.HOST, kotlinx.serialization.json.buildJsonObject { put("hostId", kotlinx.serialization.json.JsonPrimitive(id)) }.toString(), requestId = env.requestId)
    }
    frames.register(capabilityWire.RESULT) { env, _ ->
        capabilityWire.result(env.data)?.let { plugins.capabilities.result(outgoing, it) }
        null
    }
    frames.register(capabilityWire.EVENT) { env, _ ->
        val e = capabilityWire.event(env.data)
            ?: return@register Envelope(Envelope.ERROR, "${capabilityWire.EVENT}: the data must be a capability event object", requestId = env.requestId)
        plugins.capabilities.clientEvent(outgoing, e).fold(
            onSuccess = { (module, name) ->
                runCatching { plugins.publishEvent(name, e.payload.toString(), source = "plugin:$module") }
                    .fold({ null }, { Envelope(Envelope.ERROR, "${capabilityWire.EVENT}: ${it.message}", requestId = env.requestId) })
            },
            onFailure = { Envelope(Envelope.ERROR, "${capabilityWire.EVENT}: ${it.message}", requestId = env.requestId) },
        )
    }
    // NOTIFY-CORE-1 (docs/wiki/Notifications.md): a client pressed a notification's action, or dismissed one the daemon pushed.
    val notificationWire = com.superiorsoftllc.infantry.plugin.api.NotificationWire
    frames.register(notificationWire.ACTION) { env, _ ->
        val e = notificationWire.action(env.data)
            ?: return@register Envelope(Envelope.ERROR, "${notificationWire.ACTION}: the data must be {notificationId, actionId}", requestId = env.requestId)
        plugins.notifications.action(e)
        null
    }
    frames.register(notificationWire.DISMISS) { env, _ ->
        notificationWire.dismissId(env.data)?.let { plugins.notifications.dismissed(it) }
        null
    }
    // DAEMON-PLUGINS-1: a plugin tool's own requests on this connection (requestId `plugin:<uuid>`, fed to [inbound]) are
    // answered to the waiting tool, never sent to the client.
    val pluginWaiters = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Envelope>>()
    val sender = session.launch {
        for (envelope in outgoing) {
            envelope.requestId?.let { pluginWaiters.remove(it) }?.let { waiter -> waiter.complete(envelope); continue }
            // DISCONNECT-1: a frame that cannot be encoded is dropped and logged; it must not end the sender and leave an open
            // connection that never delivers anything again.
            val text = try {
                json.encodeToString(Envelope.serializer(), envelope)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                System.err.println("[infantry] ws frame '${envelope.type}' not encodable, dropped: $e")
                continue
            }
            session.send(Frame.Text(text))
        }
    }
    // DAEMON-PLUGINS-1: every frame the loop below reads: the client's, then a plugin tool's requests on its behalf.
    val inbound = Channel<String>(Channel.UNLIMITED)
    val inboundPump = session.launch {
        try {
            for (frame in session.incoming) (frame as? Frame.Text)?.readText()?.let { inbound.send(it) }
        } finally {
            inbound.close()
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
                    json.encodeToString(
                        ListSerializer(ProviderModels.serializer()),
                        allowedCatalog(modelCatalog(manager, harnesses, modelCatalogs), manager.allowedModels()),
                    ),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Envelope(Envelope.ERROR, "models failed: ${e.message ?: e::class.simpleName}")
            }
            outgoing.trySend(answer)
        }
    }

    // BUG-SpawnBoxCatalog (ticket 3615b753): `processes` (in every client's connect burst) enumerates every host process,
    // ~0.5 s idle and seconds on a loaded host. Awaited inline it held the request loop, so a request sent right after
    // connecting (the New agent box's `command_catalog`) queued behind it past the client's wait. One worker per
    // connection answers it off the loop, on IO, in arrival order (a `reap` and the `list` after it stay ordered);
    // other replies may overtake a `processes` reply. The direct answer echoes the request's id, as the inline path did.
    val processesRequests = Channel<Envelope>(Channel.UNLIMITED)
    val processesWorker = session.launch(kotlinx.coroutines.Dispatchers.IO) {
        for (request in processesRequests) {
            val answer = try {
                val watch = processHost?.frameHandler() ?: throw IllegalArgumentException("this daemon has no process watch")
                val req = if (request.data.isBlank()) com.superiorsoftllc.infantry.daemon.api.processes.ProcessesRequest()
                else json.decodeFromString(com.superiorsoftllc.infantry.daemon.api.processes.ProcessesRequest.serializer(), request.data)
                // POLICY-1 (1.143.0): `processes_reap` only kills (its policy action is destructive); listing is `processes`.
                require(request.type != Envelope.PROCESSES_REAP || req.op != "list") { "processes_reap only reaps: list with `processes`" }
                Envelope(
                    Envelope.PROCESSES,
                    json.encodeToString(
                        com.superiorsoftllc.infantry.daemon.api.processes.ProcessesAnswer.serializer(),
                        watch.answer(req),
                    ),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Envelope(Envelope.ERROR, "processes refused: ${e.message ?: e::class.simpleName}")
            }
            outgoing.trySend(if (request.requestId != null && answer.requestId == null) answer.copy(requestId = request.requestId) else answer)
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
            json.encodeToString(ListSerializer(ProviderInfo.serializer()), allowedProviders(providerInfos(manager, harnesses), manager.allowedModels())),
        )
        Envelope.SETTINGS_GET -> try {
            val all = settings.all()
            val key = envelope.data.trim()
            val selected: JsonObject = if (key.startsWith("{")) {
                // SETTINGS-SCOPES-1: {"key","project"?,"role"?,"session"?,"explain"?}: the effective value, its scope and every layer.
                val req = json.parseToJsonElement(key).jsonObject
                fun str(k: String) = (req[k] as? JsonPrimitive)?.contentOrNull
                val scoped = (settings as? SettingsService ?: throw IllegalArgumentException("this daemon has no scoped settings")).scoped
                val ctx = com.superiorsoftllc.infantry.settings.SettingContext(str("project"), str("role"), str("session"))
                val k = str("key")?.trim().orEmpty().ifEmpty { throw IllegalArgumentException("settings_get needs a key") }
                val resolved = scoped.resolve(k, ctx).toJson()
                if (str("explain") == "true") JsonObject(resolved + ("explain" to JsonPrimitive(scoped.explain(k, ctx)))) else resolved
            } else if (key.isEmpty()) {
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
                "build=${com.superiorsoftllc.infantry.update.BuildInfo.current}" +
                // POLICY-1: additive keys of the active preset, none in production (the UI dims what the daemon refuses).
                policies.readyFields() +
                // DAEMON-PLUGINS-1: the plugins' `ready` hooks (fail open); a key core already sent is not repeated.
                plugins.readyFields().entries.filter { (k, _) -> !Regex("(^| )${Regex.escape(k)}=").containsMatchIn(policies.readyFields()) && k !in CORE_READY_KEYS }
                    .joinToString("") { (k, v) -> " $k=$v" },
        ),
    )
    // POLICY-1: the active policy right after `ready`, and again on every switch (each connection its own canSwitch).
    fun policyEnvelope() = Envelope(POLICY_FRAME, json.encodeToString(com.superiorsoftllc.infantry.plugin.api.PolicyWire.serializer(), policies.wire(owner)))
    outgoing.trySend(policyEnvelope())
    val policySubscription = policies.onChange { outgoing.trySend(policyEnvelope()) }
    // Manually serialize sessions with SessionRecordSerializer to include computed `live` field
    val sessionsElements = (manager.list() + remoteSessions()).map { json.encodeToJsonElement(SessionRecordSerializer, it) }
    val sessionsJson = JsonArray(sessionsElements)
    outgoing.trySend(Envelope(Envelope.SESSIONS, json.encodeToString(sessionsJson)))
    outgoing.trySend(
        Envelope(
            Envelope.WORKTREES,
            json.encodeToString(ListSerializer(WorktreeStatus.serializer()), worktrees.statuses()),
        ),
    )

    // CONFIG-8: a daemon with declared config tells a connecting client its schema and values (none declared = no frames, as before).
    if (daemonConfig.schema().declarations.isNotEmpty()) {
        outgoing.trySend(configSchemaEnvelope(daemonConfig))
        outgoing.trySend(configValuesEnvelope(daemonConfig.values()))
    }
    // PROMPT-1: every prompt still open, so a client that (re)connects shows its card.
    pendingPrompts?.snapshot()?.forEach { request ->
        outgoing.trySend(
            Envelope(
                Envelope.PROMPT_REQUEST,
                json.encodeToString(com.superiorsoftllc.infantry.protocol.PromptRequest.serializer(), request),
                request.sessionId,
            ),
        )
    }
    // ATTN-2a: frames the plugins replay to a newly connected client (fail open).
    runCatching { plugins.connectedFrames(null) }
        .onSuccess { frames -> for (f in frames) outgoing.trySend(Envelope(f.type, f.data, f.sessionId)) }
        .onFailure { System.err.println("[infantry] connect replay failed: ${it.message}") }
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
        for (text in inbound) {
            // DISCONNECT-1: one frame that throws answers that frame with an `error` and leaves the connection (and every
            // other session of this client) alone; it used to reach the outer catch and close the websocket.
            try {
            val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull()
                ?: Envelope(Envelope.PROMPT, text)
            // PLUGIN-BRIDGES-1: any frame is activity (the default capability target is the most recently active desktop).
            plugins.capabilities.touch(outgoing)
            // DAEMON-PLUGINS-1: who sent this frame, as the plugins' gates and tools see it.
            val caller = com.superiorsoftllc.infantry.daemon.api.Caller(
                owner = owner, agent = tier == AccessTier.AGENT,
                remoteAddress = session.call.request.local.remoteAddress,
                forwardedFor = session.call.request.headers["X-Forwarded-For"], activeSession = active,
            )
            // POLICY-1: the active preset, by the frame's named action. First, so a lock's neutral refusal wins over the owner-gate text.
            policies.frameRefusal(envelope, active, owner)?.let { reason ->
                outgoing.trySend(Envelope(Envelope.ERROR, reason).copy(requestId = envelope.requestId))
                continue
            }
            // SEC-FIX-1: the owner gate, before every branch below (the bare-text prompt and the replay included).
            FrameGate.refusal(envelope.type, owner, plugins.ownerOnly(envelope.type))?.let { why ->
                outgoing.trySend(Envelope(Envelope.ERROR, why, requestId = envelope.requestId))
                continue
            }
            // DAEMON-PLUGINS-1 (`frameGate`, fail closed): the plugins' own gates (a seated lockdown scopes frames here).
            plugins.frameRefusal(envelope, caller)?.let { reason ->
                outgoing.trySend(Envelope(Envelope.ERROR, reason).copy(requestId = envelope.requestId))
                continue
            }
            // RUNNER-POOL-7 (docs/RUNNER-POOL.md 2.3 b): a session a REMOTE orchestrator started here asks for orchestrator state (the
            // board, knowledge, mail, scripts): the frame goes over the link and the orchestrator's answer comes back to this caller.
            // Asked first whether the session is link-started, so a daemon that is its own orchestrator never reads the side tag.
            val forwardedFor = envelope.sessionId?.takeIf { it.isNotBlank() } ?: active
            if (forwarder != null && forwardedFor != null && forwarder.forwards(forwardedFor) &&
                frameSide(frames, plugins, envelope) == com.superiorsoftllc.infantry.daemon.api.orchestrator.FrameSide.ORCHESTRATOR
            ) {
                session.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val reply = forwarder.forwardFrame(
                        forwardedFor,
                        com.superiorsoftllc.infantry.daemon.api.orchestrator.ForwardedFrame(envelope.type, envelope.data, envelope.sessionId, envelope.requestId),
                    )
                    when (reply) {
                        is com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply.Ok -> reply.value?.let { a ->
                            outgoing.trySend(Envelope(a.type, a.data, a.sessionId, requestId = envelope.requestId))
                        }
                        is com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply.Unavailable ->
                            outgoing.trySend(Envelope(Envelope.ERROR, "${envelope.type} unavailable: ${reply.reason}", requestId = envelope.requestId))
                    }
                }
                continue
            }
            // RUNNER-POOL-10: a runner-mode daemon has no board; what a link-started session asks was forwarded above.
            deploymentMode.refusal(envelope.type, frameSide(frames, plugins, envelope))?.let { why ->
                outgoing.trySend(Envelope(Envelope.ERROR, why, requestId = envelope.requestId))
                continue
            }
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
                            // CLI-PTY-5: a known session with nothing kept simply has no history yet; only an unknown one is an error.
                            val runnerName = runnerPool.runnerOf(sessionId)
                            if (runnerName != com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL) {
                                // RUNNER-E2E-2: a remote session's output is read from its runner on demand (runners.transcripts = on-demand).
                                val link = runnerPool.link(runnerName)
                                when (val lines = link?.readTranscript(sessionId, request.lastSequence.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), 0)) {
                                    is com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply.Ok ->
                                        for (line in lines.value) outgoing.trySend(Envelope(Envelope.STDOUT, line, sessionId))
                                    is com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply.Unavailable ->
                                        outgoing.trySend(Envelope(Envelope.ERROR, "runner '$runnerName' is offline: ${lines.reason}", sessionId))
                                    null -> outgoing.trySend(Envelope(Envelope.ERROR, "runner '$runnerName' is not registered", sessionId))
                                }
                            } else if (manager.get(sessionId) == null) outgoing.trySend(Envelope(Envelope.ERROR, "no replay history for session '$sessionId'"))
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
            // SANDBOX-1: a sandboxed agent's connection sends only what its scoped token allows.
            if (tier == AccessTier.AGENT && !AgentFrames.allows(envelope.type)) {
                outgoing.trySend(Envelope(Envelope.ERROR, AgentFrames.refusal(envelope.type)).copy(requestId = envelope.requestId))
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
                        // CHAT-SENDING-1 (1.151.0): a client message id is claimed before the turn runs; a resend of one
                        // already taken is acknowledged and not run again. A refused prompt releases its id, so a retry runs.
                        val sendKey = promptSendIds.keyOf(envelope.sessionId, envelope.requestId)
                        if (sendKey != null && !promptSendIds.claim(sendKey)) promptAck(envelope, duplicate = true) else
                        try {
                            val named = manager.get(envelope.sessionId!!)
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
                            // RUNNER-REMOTE-IO-1: a session on a remote runner takes the prompt through that runner, like mail does.
                            val remoteId = envelope.sessionId!!
                            if (manager.get(remoteId) == null && runnerPool.runnerOf(remoteId) != com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL) {
                                val sent = LocalRunner.deliverVia(runnerPool)(remoteId, turn, MessageOrigin.Owner, "", emptyList())
                                if (sent.kind == com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult.FAILED) throw IllegalStateException("runner refused the prompt: ${sent.reason}")
                                if (sent.kind == com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult.QUEUED) throw IllegalStateException("runner '${runnerPool.runnerOf(remoteId)}' is busy or unreachable (${sent.reason.ifBlank { "queued" }}); send again")
                                active = remoteId
                            } else
                            active = manager.resume(envelope.sessionId!!, turn).id
                            plugins.turnAccepted(envelope, caller)
                            pluginSessionsAck(envelope, manager) ?: sendKey?.let { promptAck(envelope, duplicate = false) }
                        } catch (e: RunInFlightException) {
                            sendKey?.let(promptSendIds::release)
                            // 1.96.0: typed, so the client that sent it (its requestId is echoed below) can queue it again.
                            Envelope(Envelope.ERROR, "$RUN_IN_FLIGHT ${e.sessionId}: ${e.message}")
                        } catch (e: Exception) {
                            sendKey?.let(promptSendIds::release)
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
                                val timerOverride = spawn?.promptTimeoutOverride()
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
                                        toolApproval = com.superiorsoftllc.infantry.harness.ToolApproval.parseStrict(spawn?.toolApproval),
                                        contextLimit = spawn?.contextLimit, // CONTEXT-LIMIT-1
                                    ).also {
                                        if (placement != null) spawnGuard.record(it.id, origin)
                                        manager.setSpawner(it.id, origin)
                                        // ASK-TIMER (1.139.0): the spawn's own prompt timer (validated before the start below).
                                        manager.setPromptTimeoutOverride(it.id, timerOverride)
                                    }
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
                            // LIMITS: a role the allowlist leaves out is refused now, with nothing bound.
                            manager.requireAllowedRole(agents.resolveSpawn(options).role)
                            // BUG-G2d (1.58.0): a `new` that names the session it comes from (`infantry_spawn`) is
                            // charged to that session's pod: checked NOW, so a refusal binds nothing and leaves no
                            // spawn options behind, and again when the session is actually created.
                            val origin = envelope.sessionId
                            spawnGuard.check(origin)
                            spawn = options
                            spawnRefused = null
                            spawnOrigin = origin
                            // BIND AT CREATION: a console opened as a profile is addressable at once.
                            spawnGuard.admit(origin) { placement ->
                                LocalRunner.start(runnerPool, manager, options, repo, agentRunner = options.agentId?.let { agents.get(it)?.runner }, defaultRunner = (settings as? SettingsService)?.runnersDefault() ?: "local", entries = (settings as? SettingsService)?.let { ss -> { LocalRunner.placementEntries(runnerPool, ss) } })
                                    ?.also { if (placement != null) spawnGuard.record(it.id, origin); (it as? StartedSession.Local)?.let { l -> manager.setSpawner(l.id, origin) } }
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
                // SKILLS-GLOBAL-1b: the layered (global/project/role) skills.
                Envelope.LAYER_SKILL_SAVE, Envelope.LAYER_SKILL_LIST, Envelope.LAYER_SKILL_DELETE ->
                    layerSkills?.let { ls -> com.superiorsoftllc.infantry.agents.LayerSkillWire(ls) { push -> for (client in clients) client.trySend(push) }.handle(envelope) }
                        ?: Envelope(Envelope.ERROR, "${envelope.type} refused: layered skills are not available")
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

                // TICKET-FILES (1.137.0): indexed SQL reads, or a backfill that runs git: both off the request loop.
                Envelope.TICKET_FILES -> {
                    session.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val answer = try {
                            val serve = ticketFiles ?: throw IllegalArgumentException("this daemon has no ticket files")
                            val text = serve(com.superiorsoftllc.infantry.ticketfiles.TicketFilesQuery.decode(envelope.data))
                            Envelope(Envelope.TICKET_FILES_REPORT, com.superiorsoftllc.infantry.ticketfiles.TicketFilesReport(text).encode())
                        } catch (e: IllegalArgumentException) {
                            Envelope(Envelope.ERROR, "ticket_files refused: ${e.message ?: "invalid request"}")
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, "ticket_files failed: ${e.message ?: e::class.simpleName}")
                        }
                        // DAEMON-PLUGINS-1: echoes the request's id, so a correlated caller (a plugin tool) gets its own answer.
                        outgoing.trySend(answer.copy(requestId = envelope.requestId))
                    }
                    null
                }

                // ARCHIVE-1 (1.149.0): reads and moves are SQL, answered off the request loop.
                Envelope.ARCHIVE_QUERY, Envelope.ARCHIVE_APPLY -> {
                    session.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val answer = try {
                            val svc = archive ?: throw IllegalArgumentException("this daemon has no archive")
                            val req = com.superiorsoftllc.infantry.archive.ArchiveRequest.decode(envelope.data)
                            val report = if (envelope.type == Envelope.ARCHIVE_QUERY) svc.query(req) else svc.apply(req)
                            Envelope(Envelope.ARCHIVE_REPORT, report.encode())
                        } catch (e: IllegalArgumentException) {
                            Envelope(Envelope.ERROR, "archive refused: ${e.message ?: "invalid request"}")
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, "archive failed: ${e.message ?: e::class.simpleName}")
                        }
                        outgoing.trySend(answer.copy(requestId = envelope.requestId))
                    }
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
                        // DAEMON-PLUGINS-3: echoes the request's id (null for a CLI), so a plugin tool gets its own answer.
                        outgoing.trySend(answer.copy(requestId = envelope.requestId))
                    }
                    null
                }

                // USAGE-PACE (1.121.0): the owner's manual percent / override / rule; answered by the fresh report.
                Envelope.USAGE_SET -> try {
                    val service = settings as? com.superiorsoftllc.infantry.settings.SettingsService
                        ?: throw IllegalArgumentException("this daemon has no usage settings")
                    service.applyUsage(com.superiorsoftllc.infantry.daemon.api.usage.UsageSet.decode(envelope.data))
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
                    val isNew = decoded.id.isNullOrBlank() || agents.get(decoded.id!!.trim()) == null
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
                            boardPorts.titlesBySession(),
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
                Envelope.USAGE_METER_GET -> try {
                    Envelope(Envelope.USAGE_METER, usageMeter?.reportJson() ?: com.superiorsoftllc.infantry.usagemeter.UsageMeter.Report(emptyList(), 0, 0, watch = com.superiorsoftllc.infantry.mailbox.WatchMeter.counts())
                        .let { Json.encodeToString(com.superiorsoftllc.infantry.usagemeter.UsageMeter.Report.serializer(), it) })
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "usage_meter_get failed: ${e.message ?: e::class.simpleName}")
                }

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

                // HARNESS-INSTALL-1 (1.100.0): detect / install the harness CLIs. Both run npm or `--version` children, so they are
                // answered off the request loop, and only to the client that asked.
                Envelope.HARNESS_INSTALL_STATUS, Envelope.HARNESS_INSTALL -> {
                    session.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val answer = (
                            try {
                                val service = harnessInstaller ?: throw IllegalArgumentException("this daemon has no harness installer")
                                if (envelope.type == Envelope.HARNESS_INSTALL_STATUS) {
                                    val request = com.superiorsoftllc.infantry.protocol.HarnessInstallStatusRequest.decode(envelope.data)
                                    Envelope(
                                        Envelope.HARNESS_INSTALL_STATUS,
                                        json.encodeToString(com.superiorsoftllc.infantry.protocol.HarnessInstallReport.serializer(), service.status(request.checkLatest)),
                                    )
                                } else {
                                    val request = com.superiorsoftllc.infantry.protocol.HarnessInstallRequest.decode(envelope.data)
                                    Envelope(
                                        Envelope.HARNESS_INSTALL_RESULT,
                                        json.encodeToString(com.superiorsoftllc.infantry.protocol.HarnessInstallResult.serializer(), service.install(request.ids, request.update)),
                                    )
                                }
                            } catch (e: IllegalArgumentException) {
                                Envelope(Envelope.ERROR, "${envelope.type} refused: ${e.message ?: "invalid request"}")
                            } catch (e: Exception) {
                                Envelope(Envelope.ERROR, "${envelope.type} failed: ${e.message ?: e::class.simpleName}")
                            }
                        )
                        // RUNTIME-LOAD-1: a daemon plugin tool (infantry_harness_status) waits for its answer by request id.
                        outgoing.trySend(if (envelope.requestId != null && answer.requestId == null) answer.copy(requestId = envelope.requestId) else answer)
                    }
                    null
                }

                // CLIPATH-1 (1.73.0): where each CLI is launched from, and the user's override of it.
                Envelope.CLI_LOCATION_SET -> try {
                    val service = cliLocations ?: throw IllegalArgumentException("this daemon has no CLI locations")
                    val request = com.superiorsoftllc.infantry.protocol.CliLocationSet.decode(envelope.data)
                    cliLocationsEnvelope(service.set(request.id, request.path))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "cli_location_set refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "cli_location_set failed: ${e.message ?: e::class.simpleName}")
                }

                // BUG-PERM-1 (1.76.0): the global, harness-neutral Tool approval.
                Envelope.TOOL_APPROVAL_SET -> try {
                    val service = toolApproval ?: throw IllegalArgumentException("this daemon has no Tool approval setting")
                    val request = com.superiorsoftllc.infantry.protocol.ToolApprovalSet.decode(envelope.data)
                    toolApprovalEnvelope(service.set(request.mode))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "tool_approval_set refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "tool_approval_set failed: ${e.message ?: e::class.simpleName}")
                }

                // PROMPT-1 (1.77.0): answer an open prompt; the `prompt_resolved` push to every client is the reply.
                Envelope.PROMPT_RESPONSE -> try {
                    val service = pendingPrompts ?: throw IllegalArgumentException("this daemon has no prompts")
                    service.respond(com.superiorsoftllc.infantry.protocol.PromptResponse.decode(envelope.data), promptClientLabel)
                    null
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "prompt_response refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "prompt_response failed: ${e.message ?: e::class.simpleName}")
                }

                // the `prompt_answer` once the owner answers, the prompt times out or the session ends.
                Envelope.PROMPT_ASK -> try {
                    val service = pendingPrompts ?: throw IllegalArgumentException("this daemon has no prompts")
                    val asker = envelope.sessionId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("no session: the envelope's sessionId names the asking session")
                    require(manager.get(asker) != null) { "unknown session '${asker.take(80)}'" }
                    val spec = com.superiorsoftllc.infantry.prompts.QuestionSpec.of(
                        com.superiorsoftllc.infantry.protocol.PromptAsk.decode(envelope.data),
                    )
                    session.launch(askJobs) {
                        val answer = service.askQuestion(asker, spec)
                        val payload = com.superiorsoftllc.infantry.protocol.PromptAnswer(answer.answered, answer.answers, answer.otherText)
                        outgoing.trySend(
                            Envelope(
                                Envelope.PROMPT_ANSWER,
                                json.encodeToString(com.superiorsoftllc.infantry.protocol.PromptAnswer.serializer(), payload),
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

                // ATTN-2c: question_ask / question_list / question_answer / question_cancel are answered by Infantry_Attention (api.frames).

                Envelope.SETTINGS_SET -> try {
                    val request = SettingsSet.decode(envelope.data)
                    val scope = com.superiorsoftllc.infantry.settings.SettingScope.parse(request.scope ?: "app")
                    if (scope != com.superiorsoftllc.infantry.settings.SettingScope.APP) {
                        // SETTINGS-SCOPES-1: a scoped write answers with the resolved value, scope and layers of the key.
                        val ctx = com.superiorsoftllc.infantry.settings.SettingContext(request.project, request.role, request.session)
                        val value = request.value.takeIf { it !is kotlinx.serialization.json.JsonNull }
                        val resolved = (settings as? SettingsService ?: throw IllegalArgumentException("this daemon has no scoped settings")).scoped.set(request.key, value, scope, ctx)
                        Envelope(Envelope.SETTINGS, json.encodeToString(JsonObject.serializer(), resolved.toJson()))
                    } else {
                        val updated = settings.set(request.key, request.value)
                        Envelope(Envelope.SETTINGS, json.encodeToString(JsonObject.serializer(), updated))
                    }
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "settings_set refused: ${e.message ?: "invalid request"}")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "settings_set failed: ${e.message ?: e::class.simpleName}")
                }

                // CONFIG-8 (contract 1.142.0): the daemon's declared config. Answered by `config_schema` + `config_values`.
                Envelope.CONFIG_GET -> {
                    outgoing.trySend(configSchemaEnvelope(daemonConfig))
                    configValuesEnvelope(daemonConfig.values())
                }

                Envelope.CONFIG_SET -> try {
                    val request = json.decodeFromString(com.superiorsoftllc.infantry.plugin.api.ConfigSetFrame.serializer(), envelope.data)
                    configValuesEnvelope(daemonConfig.set(request, by = "client"))
                } catch (e: IllegalArgumentException) {
                    Envelope(Envelope.ERROR, "config_set refused: ${e.message ?: "invalid request"}")
                } catch (e: kotlinx.serialization.SerializationException) {
                    Envelope(Envelope.ERROR, "config_set refused: malformed request")
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "config_set failed: ${e.message ?: e::class.simpleName}")
                }

                Envelope.RESUME -> {
                    val (id, prompt) = splitResume(envelope.data)
                    if (!id.isNullOrBlank() && prompt.isNullOrBlank()) {
                        // CLI-PTY-5: `resume` with no prompt = the owner's "Resume session": attach the CLI, send no turn.
                        try {
                            manager.attach(id)
                            active = id
                            null
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, "resume failed: ${e.message ?: e::class.simpleName}", id)
                        }
                    } else if (id.isNullOrBlank() || prompt.isNullOrBlank()) {
                        Envelope(Envelope.ERROR, "resume expects '<sessionId>\\n<prompt>'")
                    } else {
                        try {
                            active = manager.resume(id, prompt).id
                            plugins.turnAccepted(envelope, caller)
                            null
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "resume failed")
                        }
                    }
                }

                // CHAT-QUEUE-1 (1.150.0): the held messages live on the session record; every change is pushed as its `session` frame.
                Envelope.QUEUE_ADD, Envelope.QUEUE_REMOVE, Envelope.QUEUE_FORCE -> {
                    val id = envelope.sessionId?.trim().orEmpty()
                    val arg = envelope.data
                    if (id.isEmpty() || arg.isBlank()) {
                        Envelope(Envelope.ERROR, "${envelope.type} expects the frame's sessionId and a non-empty data")
                    } else {
                        try {
                            when (envelope.type) {
                                Envelope.QUEUE_ADD -> {
                                    if (manager.queueAdd(id, arg, ((settings as? SettingsService)?.limit(LimitRegistry.CHAT_QUEUE_MAX) ?: LimitRegistry.CHAT_QUEUE_MAX.default).toInt()) == null) active = id
                                }
                                Envelope.QUEUE_REMOVE -> manager.queueRemove(id, arg.trim())
                                else -> manager.queueForce(id, arg.trim())
                            }
                            null
                        } catch (e: RunInFlightException) {
                            Envelope(Envelope.ERROR, "$RUN_IN_FLIGHT ${e.sessionId}: ${e.message}")
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, "${envelope.type} refused: ${e.message ?: e.javaClass.simpleName}")
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
                            pluginSessionsAck(envelope, manager)
                        } catch (e: Exception) {
                            Envelope(Envelope.ERROR, e.message ?: "kill failed")
                        }
                    }
                }

                Envelope.SESSIONS -> {
                    val sessionsElements = (manager.list() + remoteSessions()).map { json.encodeToJsonElement(SessionRecordSerializer, it) }
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
                // Answered by [processesWorker], off this loop (BUG-SpawnBoxCatalog, ticket 3615b753).
                Envelope.PROCESSES, Envelope.PROCESSES_REAP -> {
                    processesRequests.trySend(envelope)
                    null
                }

                // `infantry bg run`: beat (register/heartbeat), done, list. The answer is always the session's job list
                // (every session's when the frame names none); the record change reaches clients as a `session` push.
                Envelope.BG_JOB -> try {
                    val req = json.decodeFromString(com.superiorsoftllc.infantry.session.BgJobRequest.serializer(), envelope.data)
                    val sid = envelope.sessionId?.trim()?.takeIf { it.isNotEmpty() }
                    when (req.op) {
                        "beat" -> {
                            val job = req.job ?: throw IllegalArgumentException("beat expects a job")
                            require(sid != null) { "beat expects the frame's sessionId (INFANTRY_SESSION_ID)" }
                            require(manager.bgJobBeat(sid, job)) { "no session '${sid.take(80)}'" }
                        }
                        "done" -> {
                            require(sid != null && req.jobId != null) { "done expects a sessionId and a jobId" }
                            manager.bgJobDone(sid, req.jobId!!)?.let { job ->
                                (manager.bgJobListener as? com.superiorsoftllc.infantry.mailbox.BgJobNotifier)?.let { n ->
                                    runCatching { n.done(sid, job, req.exitCode, req.tail, req.bytes, req.eof, req.survivors, req.marker, req.pipeNoFail) }
                                        .onFailure { System.err.println("[infantry] bg notify failed for ${job.id}: ${it.message}") }
                                }
                            }
                        }
                        "eof" -> {
                            require(sid != null && req.jobId != null) { "eof expects a sessionId and a jobId" }
                            manager.bgJobs(sid).firstOrNull { it.id == req.jobId }?.let { job ->
                                (manager.bgJobListener as? com.superiorsoftllc.infantry.mailbox.BgJobNotifier)?.let { n ->
                                    runCatching { n.eof(sid, job, req.bytes) }
                                        .onFailure { System.err.println("[infantry] bg eof notice failed for ${job.id}: ${it.message}") }
                                }
                            }
                        }
                        "ack" -> {
                            require(sid != null && req.jobId != null) { "ack expects a sessionId and a jobId" }
                            (manager.bgJobListener as? com.superiorsoftllc.infantry.mailbox.BgJobNotifier)?.ack(sid, req.jobId!!)
                        }
                        "stop" -> {
                            require(req.jobId != null) { "stop expects a jobId" }
                            require(manager.bgJobStop(req.jobId!!) != null) { "no running bg job '${req.jobId!!.take(80)}'" }
                        }
                        "list" -> Unit
                        else -> throw IllegalArgumentException("unknown op '${req.op.take(40)}'")
                    }
                    Envelope(
                        Envelope.BG_JOB,
                        json.encodeToString(ListSerializer(com.superiorsoftllc.infantry.session.BgJob.serializer()), manager.bgJobs(sid)),
                        sid,
                    )
                } catch (e: Exception) {
                    Envelope(Envelope.ERROR, "bg_job refused: ${e.message ?: e::class.simpleName}")
                }

                // Contract 1.117.0: an agent's progress report on its own session. Validated by the reducer; the record
                // change reaches every client as a `session` push, and the answer is the session's current progress.
                Envelope.PROGRESS -> try {
                    val req = json.decodeFromString(com.superiorsoftllc.infantry.session.ProgressReport.serializer(), envelope.data)
                    val sid = envelope.sessionId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("expects the frame's sessionId (INFANTRY_SESSION_ID)")
                    val record = manager.reportProgress(sid, req) ?: throw IllegalArgumentException("no session '${sid.take(80)}'")
                    Envelope(
                        Envelope.PROGRESS,
                        record.progress?.let { json.encodeToString(com.superiorsoftllc.infantry.session.SessionProgress.serializer(), it) } ?: "null",
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
                    pluginSessionsAck(envelope, manager)
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
                            // SESSION-GHOST-1: an id this daemon does not hold is already gone: a clean no-op, but the
                            // fresh `sessions` push below still goes out so a client holding a ghost row reconciles.
                            if (manager.get(id) != null) manager.delete(id)
                            streamSequencer.forget(id)
                            // Contract 1.26.0: the delete itself gets no direct reply -- every client
                            // (not just the one that asked) hears about it through a fresh `sessions`
                            // push, exactly like any other session-list mutation.
                            val sessionsElements = (manager.list() + remoteSessions()).map { json.encodeToJsonElement(SessionRecordSerializer, it) }
                            val pushed = Envelope(Envelope.SESSIONS, json.encodeToString(JsonArray(sessionsElements)))
                            for (client in clients) client.trySend(pushed)
                            if (wantsDirectAck(envelope)) pushed else null
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
                        // MAIL-LIMITS-1: mail a script hook sends (the daemon's own loopback, with the owner credential) is automation:
                        // exempt from the sender rate limit, so a burst of hook mail under one identity is never dropped.
                        when (val result = mail.send(request, automation = isAutomationFrame(envelope, owner))) {
                            is MailResult.Sent -> {
                                plugins.turnAccepted(envelope, caller)
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

                // ATTN-2b: attention_list / attention_ack are answered by Infantry_Attention (api.frames).
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

                Envelope.NOTIFY -> notifyFrames.answer(envelope)

                // SECRETS-1 (contract 1.100.0): the keystore. Answered by `secret_state`; a value never travels back.
                // SECRETS-2 (1.135.0): and the user's key/value secrets.
                Envelope.SECRET_SET, Envelope.SECRET_CLEAR, Envelope.SECRET_IMPORT, Envelope.SECRET_STATUS,
                Envelope.SECRET_FIELD_SET, Envelope.SECRET_FIELD_DELETE, Envelope.SECRET_DELETE, Envelope.SECRET_MOVE,
                Envelope.PLUGIN_SECRET_GET -> keystoreFrames.answer(envelope)

                // POLICY-1 (docs/POLICY.md): read the active policy; the owner switches it (refused while an env pins it).
                // A switch tells every connection (each its own `policy` frame), this one included, so no direct answer.
                com.superiorsoftllc.infantry.policy.ActionCatalog.POLICY_GET -> policyEnvelope()
                com.superiorsoftllc.infantry.policy.ActionCatalog.POLICY_SET -> policies.select(envelope.data)?.let { Envelope(Envelope.ERROR, "policy_set refused: $it") }

                // DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md section 3): the plugins' tools, as MCP `tools/list` entries.
                com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.PLUGIN_TOOLS -> Envelope(
                    com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.PLUGIN_TOOLS,
                    json.encodeToString(JsonArray.serializer(), plugins.toolDescriptors(policies::allowsAction)),
                )
                // DAEMON-PLUGINS-1: `{name, arguments}` -> `plugin_tool_result {name, text, isError}`. Off the loop: a tool may
                // send frames on this connection itself (each through every gate above, as this caller) and wait for them.
                com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.PLUGIN_TOOL_CALL -> {
                    val call = runCatching { json.parseToJsonElement(envelope.data) as? JsonObject }.getOrNull()
                    val name = (call?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    if (call == null || name == null) {
                        Envelope(Envelope.ERROR, "plugin_tool_call expects {\"name\": \"<tool>\", \"arguments\": {...}}")
                    } else {
                        val arguments = call["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                        val requestId = envelope.requestId
                        session.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val result = plugins.callToolSuspending(name, arguments, caller, call["client"] as? JsonObject ?: JsonObject(emptyMap())) { request, timeoutMs ->
                                val id = "plugin:" + UUID.randomUUID()
                                val waiter = kotlinx.coroutines.CompletableDeferred<Envelope>()
                                pluginWaiters[id] = waiter
                                inbound.trySend(json.encodeToString(Envelope.serializer(), request.copy(requestId = id)))
                                kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { waiter.await() } }
                                    ?: run { pluginWaiters.remove(id); Envelope(Envelope.ERROR, "${request.type} got no answer in $timeoutMs ms") }
                            }
                            val payload = JsonObject(mapOf(
                                "name" to kotlinx.serialization.json.JsonPrimitive(name),
                                "text" to kotlinx.serialization.json.JsonPrimitive(result.text),
                                "isError" to kotlinx.serialization.json.JsonPrimitive(result.isError),
                            ))
                            outgoing.trySend(Envelope(com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost.PLUGIN_TOOL_RESULT, json.encodeToString(JsonObject.serializer(), payload), requestId = requestId))
                        }
                        null
                    }
                }

                // DAEMON-PLUGINS-1: a frame a plugin declared and answers; anything else is unknown.
                // DP-5d: a board-family frame is answered by the table its owner registered into; with no handler the
                // daemon has no board.
                else -> if (frames.handles(envelope.type)) frames.answer(envelope, direct)
                else if (plugins.handlesFrame(envelope.type)) plugins.answerFrame(envelope, caller)
                else if (envelope.type in BoardFrames.TYPES) BoardFrames.noBoard(envelope.type)
                // DP-6: the pipeline frames belong to the Infantry_Board plugin; without it the daemon says so.
                else if (envelope.type in BoardFrames.PIPELINE_TYPES) BoardFrames.noPipeline(envelope.type)
                // DP-7c: the decisions and issues frames belong to the Infantry_Notes plugin; without it the daemon says so.
                else if (envelope.type in BoardFrames.KNOWLEDGE_RECORD_TYPES) BoardFrames.noKnowledgeRecords(envelope.type)
                // DP-7f: the memory frames belong to the Infantry_Notes plugin too.
                else if (envelope.type in BoardFrames.MEMORY_TYPES) BoardFrames.noMemory(envelope.type)
                // AR-4b-ii-b: the role frames belong to the Infantry_Roles plugin; without it the daemon says so.
                else if (envelope.type in BoardFrames.ROLE_TYPES) BoardFrames.noRoles(envelope.type)
                // DP-10a: image_upload belongs to the Infantry_Chat plugin (project-scoped files); without it the daemon says so.
                else if (envelope.type == Envelope.IMAGE_UPLOAD) Envelope(Envelope.ERROR, "image_upload refused: this daemon stores no uploads")
                else Envelope(Envelope.ERROR, "unknown frame type '${envelope.type}'")
            }
            // BUG-MCP-RACE-1: the direct answer echoes the request's id; a push (sent elsewhere) never carries one.
            reply?.let { outgoing.trySend(if (direct && it.requestId == null) it.copy(requestId = envelope.requestId) else it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                System.err.println("[infantry] ws frame failed, connection kept: $e")
                val requestId = runCatching { json.decodeFromString(Envelope.serializer(), text).requestId }.getOrNull()
                outgoing.trySend(Envelope(Envelope.ERROR, "the daemon failed handling that frame: ${e.message ?: e::class.simpleName}", requestId = requestId))
            }
        }
    } catch (e: Throwable) {
        System.err.println("[infantry] ws handler error: $e")
    } finally {
        println("[infantry] ws closed")
        clients.remove(outgoing)
        plugins.capabilities.disconnected(outgoing)
        policySubscription.close()
        inboundPump.cancel()
        pluginWaiters.values.forEach { it.cancel() }
        askJobs.cancel()
        modelsRequests.close()
        modelsWorker.cancel()
        processesRequests.close()
        processesWorker.cancel()
        projectGitRequests.close()
        projectGitWorker.cancel()
        readyBurstRequests.close()
        readyBurstWorker.cancel()
        outgoing.close()
        sender.cancel()
    }
}

/**
 * RUNTIME-LOAD-7: `kill`, a named `prompt`, `session_restart` and `session_delete` answer nothing on success (a refusal is an `error`).
 * A plugin tool waits for its own request id, so for those requests (id `plugin:<uuid>`) success is the `sessions` list after the
 * write: the connection handles frames in order, so it is the fence the CLI's MCP server drew with a trailing `sessions` request.
 */
private fun pluginSessionsAck(envelope: Envelope, manager: SessionManager): Envelope? =
    if (wantsDirectAck(envelope)) {
        Envelope(Envelope.SESSIONS, json.encodeToString(JsonArray(manager.list().map { json.encodeToJsonElement(SessionRecordSerializer, it) })))
    } else null

private fun splitResume(data: String): Pair<String?, String?> {
    val newline = data.indexOf('\n')
    if (newline < 0) return data.trim().takeIf { it.isNotEmpty() } to null
    return data.substring(0, newline).trim() to data.substring(newline + 1)
}

/** Contract 1.4.0: the `agents` frame -- every profile, oldest first, every field present. */
private fun agentsEnvelope(agents: AgentProfiles): Envelope =
    Envelope(Envelope.AGENTS, json.encodeToString(ListSerializer(AgentProfile.serializer()), agents.list()))

/**
 * The `providers` answer (contract 1.3.0): every provider a spawn may name, the default first, each
 * with the model its runs use when a spawn names none, as resolved right now.
 */
internal fun providerInfos(manager: SessionManager, harnesses: HarnessRegistry? = null): List<ProviderInfo> {
    // H2: the placeholder default of a zero-harness daemon is not a harness and is never listed.
    val configured = manager.providers.filter { it !is com.superiorsoftllc.infantry.harness.NoHarnessProvider }
        .map { p -> providerInfo(p, p.id == manager.providerId) }
    // WIRE-1: a harness a plugin registered is listed beside the providers (a configured provider of
    // the same id wins, so `claude` appears once).
    val known = configured.map { it.id }.toSet()
    val fromHarnesses = harnesses?.harnessIds().orEmpty()
        .filter { it !in known }
        .map { id ->
            val limit = harnesses?.resolve(id)?.contextLimit
            val effort = harnesses?.resolve(id)?.effort // EFFORT-1
            ProviderInfo(id = id, default = false, defaultModel = null, contextLimit = limit?.support?.wire, contextLimitHow = limit?.how, effort = effort?.support?.wire, effortHow = effort?.how,
                midTurnInput = harnesses?.resolve(id)?.capabilities?.contains(com.superiorsoftllc.infantry.harness.HarnessCapability.MID_TURN_INPUT)?.takeIf { it })
        }
    // CLIPATH-1 (1.73.0): a provider/harness whose CLI the resolver cannot find says so, with the paths it tried,
    // instead of looking usable and failing at spawn. A provider with no CLI (the stub) is always available.
    val tools = cliTools(harnesses).associateBy { it.id }
    return (configured + fromHarnesses).map { info ->
        val tool = tools[info.id] ?: return@map info
        val resolution = com.superiorsoftllc.infantry.executables.CliExecutables.resolve(tool)
        if (resolution.found) info else info.copy(available = false, unavailableReason = resolution.describeFailure())
    }
}

/**
 * CLIPATH-1: every CLI this daemon can launch: the built-in table (claude, opencode, pi, git, node), then each registered
 * descriptor harness's own command. A third-party code plugin's executable is its own business and is not listed.
 */
internal fun cliTools(
    harnesses: HarnessRegistry?,
    /** HARNESS-PLUGIN-4 H5a: the loaded harness modules' `harness.cli` tools. */
    modules: List<com.superiorsoftllc.infantry.executables.CliTool> = emptyList(),
): List<com.superiorsoftllc.infantry.executables.CliTool> {
    val builtIn = com.superiorsoftllc.infantry.executables.CliTools.KNOWN
    val ids = builtIn.map { it.id }.toMutableSet()
    val descriptors = harnesses?.harnessIds().orEmpty()
        .filter { it !in ids }
        .mapNotNull { (harnesses?.resolve(it) as? com.superiorsoftllc.infantry.harness.descriptor.DescriptorHarness)?.cliTool }
    ids += descriptors.map { it.id }
    return builtIn + descriptors + modules.filter { ids.add(it.id) }
}

private fun toolApprovalEnvelope(info: com.superiorsoftllc.infantry.protocol.ToolApprovalInfo): Envelope = Envelope(
    Envelope.TOOL_APPROVAL,
    json.encodeToString(com.superiorsoftllc.infantry.protocol.ToolApprovalInfo.serializer(), info),
)

/** ASK-1: `questions`, every owner question oldest first (open ones and the history). */

/**
 * BUG-PERM-1: the chat notice for a harness denial, as an additive `stderr` line (UI-BOOT-1's `[infantry]` convention, no
 * new frame), naming the Tool approval the session runs under.
 */
internal fun denialEnvelope(
    sessionId: String,
    denial: EventNormalizer.Line.Denial,
    mode: com.superiorsoftllc.infantry.harness.ToolApproval,
): Envelope = Envelope(
    Envelope.STDERR,
    "[infantry] " + com.superiorsoftllc.infantry.session.ToolApprovalNotice.text(denial.harness, denial.tool, mode, denial.prompted),
    sessionId,
)

/** FIRSTRUN-PERM-1: the card's last action for a denied tool, e.g. "blocked: Write denied (Tool approval = deny)". */
internal fun deniedAction(denial: EventNormalizer.Line.Denial, mode: com.superiorsoftllc.infantry.harness.ToolApproval): String =
    com.superiorsoftllc.infantry.session.ToolApprovalNotice.blockedAction(denial.tool, mode, denial.prompted)

private fun cliLocationsEnvelope(tools: List<com.superiorsoftllc.infantry.protocol.CliLocation>): Envelope = Envelope(
    Envelope.CLI_LOCATIONS,
    json.encodeToString(com.superiorsoftllc.infantry.protocol.CliLocations.serializer(), com.superiorsoftllc.infantry.protocol.CliLocations(tools)),
)

/** The directory the daemon keeps its own configuration in (`INFANTRY_HOME`, else the state dir): `commands.json`, `harnesses/`. */
internal fun daemonConfigDir(): File =
    infantrySetting("INFANTRY_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: stateDir()

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
        contextLimit = provider.contextLimit().support.wire, // CONTEXT-LIMIT-1
        contextLimitHow = provider.contextLimit().how,
        effort = provider.effort().support.wire, // EFFORT-1
        effortHow = provider.effort().how,
        midTurnInput = provider.midTurnInput().takeIf { it }, // CHAT-QUEUE-1
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
    catalogs: com.superiorsoftllc.infantry.provider.HarnessModelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
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
        harnesses?.resolve(id)?.catalog()
    }
    // CLIPATH-1: a row whose CLI cannot be found says so (appended, so an existing note is kept), resolved once per id.
    val tools = cliTools(harnesses).associateBy { it.id }
    val missing = rows.map { it.provider }.distinct().mapNotNull { id ->
        tools[id]?.let { com.superiorsoftllc.infantry.executables.CliExecutables.resolve(it) }?.takeIf { !it.found }?.let { id to it.reason }
    }.toMap()
    return rows.map { row ->
        val reason = missing[row.provider] ?: return@map row
        if (row.note?.contains(reason) == true) row else row.copy(note = listOfNotNull(row.note, reason).joinToString("; "))
    }
}

/**
 * LIMITS: the `models` answer narrowed to [allowlist]: every row keeps only its allowed models,
 * a row left empty is dropped, and a harness left with none at all becomes one `unavailable` row saying why, so pickers
 * show it dimmed. No restriction = [rows] unchanged.
 */
internal fun allowedCatalog(rows: List<ProviderModels>, allowlist: com.superiorsoftllc.infantry.settings.ModelAllowlist): List<ProviderModels> {
    if (!allowlist.restricts) return rows
    return rows.map { it.provider }.distinct().flatMap { harness ->
        val own = rows.filter { it.provider == harness }
        val kept = own.filter { it.models.isNotEmpty() }.map { it.copy(models = allowlist.filter(it.models)) }.filter { it.models.isNotEmpty() }
        kept.ifEmpty {
            // A harness that listed nothing keeps its own reason; one that listed models says none is allowed.
            val own0 = own.none { it.models.isNotEmpty() }
            val why = if (own0) own.mapNotNull { it.note?.trim()?.trimEnd('.') }.joinToString("; ").ifEmpty { "$harness lists no models" }
            else "no model of $harness is allowed on this server"
            listOf(ProviderModels(harness, "", harness, emptyList(), "$why. ${allowlist.describe()}", unavailable = true))
        }
    }
}

/**
 * LIMITS: the `providers` answer under [allowlist]: each harness's listed models narrowed to the allowed ones, and
 * one whose list names no allowed model marked unavailable with the reason. No restriction = [infos] unchanged.
 */
internal fun allowedProviders(infos: List<ProviderInfo>, allowlist: com.superiorsoftllc.infantry.settings.ModelAllowlist): List<ProviderInfo> {
    if (!allowlist.restricts) return infos
    return infos.map { info ->
        val listed = info.models ?: return@map info
        val allowed = allowlist.filter(listed)
        if (allowed.isNotEmpty() || !info.available) {
            info.copy(models = allowed.ifEmpty { null }, defaultModel = info.defaultModel?.takeIf { allowlist.allows(it) })
        } else {
            info.copy(models = null, available = false, unavailableReason = "no model of ${info.id} is allowed on this server. ${allowlist.describe()}")
        }
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

/** A daemon thread running [task] every [intervalMillis] (first run one interval in) until the handle is closed (the owner-question sweeper). */
private fun scheduledTicker(intervalMillis: Long, task: () -> Unit): AutoCloseable {
    val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "infantry-question-sweep").apply { isDaemon = true } }
    executor.scheduleWithFixedDelay(task, intervalMillis, intervalMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    return AutoCloseable { executor.shutdownNow() }
}

/** DAEMON-PLUGINS-1: one HTTP request to a plugin route, answered by its plugin ([DaemonPluginHost.answerRoute]); 404 when none. */
private suspend fun answerPluginRoute(call: io.ktor.server.application.ApplicationCall, plugins: com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost) {
    val request = call.request
    val body = if (request.httpMethod == io.ktor.http.HttpMethod.Get) "" else runCatching { call.receiveText() }.getOrDefault("")
    val reply = plugins.answerRoute(
        com.superiorsoftllc.infantry.daemon.api.HttpCall(
            method = request.httpMethod.value,
            path = request.path(),
            query = request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() },
            headers = request.headers.entries().filter { !it.key.equals(io.ktor.http.HttpHeaders.Authorization, ignoreCase = true) }
                .associate { it.key to it.value.firstOrNull().orEmpty() },
            body = body,
            remoteAddress = request.local.remoteAddress,
        ),
    ) ?: com.superiorsoftllc.infantry.daemon.api.HttpReply(404, "no plugin answers ${request.httpMethod.value} ${request.path()}")
    reply.headers.forEach { (k, v) -> call.response.header(k, v) }
    call.respondText(reply.body, io.ktor.http.ContentType.parse(reply.contentType), io.ktor.http.HttpStatusCode.fromValue(reply.status))
}

/** The `ready` keys core itself sends; a plugin's `ready` hook may not repeat them. */
private val CORE_READY_KEYS = setOf("provider", "contract", "instance", "since", "build", "notice")

/**
 * The install's bundled modules root, one place per layout (docs/DAEMON-PLUGINS.md section 9), or null (a dev run from classes):
 * installDist / headless / docker: `<install>/modules` next to the `lib/` folder holding this daemon's jar; the desktop app
 * image (jpackage, jars in `app/`, no `lib/`): `<compose.application.resources.dir>/modules`, the folder its launcher also
 * names in `infantry.plugins.bundled` (that property wins when set).
 */
internal fun installModules(
    code: File? = runCatching { File(InfantryDaemon::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull(),
    property: (String) -> String? = System::getProperty,
): File? {
    val lib = code?.takeIf { it.isFile && it.parentFile?.name == "lib" }?.parentFile?.parentFile?.let { File(it, "modules") }
    val appImage = property("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { File(it, "modules") }
    return listOfNotNull(lib, appImage).firstOrNull { it.isDirectory }
}

/** DP-5b: a trigger's `prompt` hand-out through the Tickets API; no board = the refusal the board itself gave for a missing manager. */
private fun triggerPrompt(
    tickets: com.superiorsoftllc.infantry.daemon.api.board.Tickets?, agentId: String, prompt: String, projectId: String?,
    by: com.superiorsoftllc.infantry.daemon.api.Caller,
): com.superiorsoftllc.infantry.daemon.api.board.PromptStart =
    (tickets?.startPrompt(agentId, prompt, projectId, by) as? com.superiorsoftllc.infantry.daemon.api.board.BoardResult.Ok)?.value
        ?: com.superiorsoftllc.infantry.daemon.api.board.PromptStart.Refused("ticket session manager is unavailable")

/**
 * DP-5h-3e-b: the core's own ticket services, built without a board class: the ticket files (the repository a ticket was cut in is asked of
 * [repoFor], the board port), the typed durable ticket events and their three core consumers. The board itself is the board plugin's (DP-5h-4)
 * uses the same construction.
 */
class TicketServices private constructor(val files: TicketFiles, val filesText: TicketFilesText, val events: TicketEventLog) {
    companion object {
        /** The event log alone: the board host appends to it, so it exists before any consumer and before the board. */
        /** BOARD-REENTRY-1: delivery runs on its own daemon thread, so a move answers before its hooks run (and they may call the board). */
        fun eventLog(store: InfantryStore): TicketEventLog = TicketEventLog(
            (store as SqlInfantryStore).database,
            executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ticket-events").apply { isDaemon = true } },
        )

        fun create(
            store: InfantryStore,
            events: TicketEventLog,
            settings: SettingsService,
            triggers: TriggerService,
            doneWorkerReaper: DoneWorkerReaper,
            relatedRef: AtomicReference<RelatedWorkSource>,
            relatedDescriptionRef: AtomicReference<RelatedDescription>,
            repoFor: (String?) -> File?,
        ): TicketServices {
            val database = (store as SqlInfantryStore).database
            // TICKET-FILES (55476f3c): a ticket that goes done records the files it changed (one git diff, off the board's lock, on its own
            // thread) and pins its tip under refs/agenthq/tickets/<id>.
            val files = TicketFiles(db = database, tickets = { store.tickets.all().map { it.toTicketInfo() } + store.ticketArchive.infos(null).map { it.toTicketInfo() } }, repoFor = repoFor)
            relatedRef.set(
                com.superiorsoftllc.infantry.ticketfiles.LiveRelatedWork(
                    files, ticket = { id -> store.tickets.get(id)?.toTicketInfo() ?: store.ticketArchive.info(id)?.toTicketInfo() },
                    rows = { store.tickets.all().map { it.toTicketInfo() } + store.ticketArchive.infos(null).map { it.toTicketInfo() } }, links = { store.tickets.links().map { it.toTicketInfo() } }, config = settings::relatedConfig,
                ),
            )
            // KNOWLEDGE-PTR-1 (C): the stored RELATED (auto) block a ticket save writes at the top of its description.
            relatedDescriptionRef.set(com.superiorsoftllc.infantry.ticketfiles.RelatedDescriptionWriter(files, settings::relatedConfig))
            val filesText = TicketFilesText(files, { settings.relatedConfig().filesFormat }) { id -> store.tickets.get(id)?.title ?: store.ticketArchive.info(id)?.title }
            val ticketFilesExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "infantry-ticket-files").also { it.isDaemon = true }
            }
            // BOARD-API-2 (DP-4b): the three core consumers subscribe like a plugin would; a cursor survives a restart, so a transition not
            // handled when the daemon stopped is replayed on the next start.
            events.subscribe("core.triggers") { e -> if (e.isTransition) triggers.onTicketTransition(e.ticketId, e.to!!) }
            events.subscribe("core.done-worker-reaper") { e -> if (e.isTransition) doneWorkerReaper.onTicketTransition(e.ticketId, e.to!!) }
            events.subscribe("core.ticket-files") { e ->
                if (e.kind == TicketEvent.DONE) ticketFilesExec.execute {
                    runCatching { store.tickets.get(e.ticketId)?.let { files.record(it.toTicketInfo()) } }
                        .onFailure { System.err.println("[infantry] ticket files of ${e.ticketId} not recorded: ${it.message}") }
                }
            }
            return TicketServices(files, filesText, events)
        }
    }
}

private fun com.superiorsoftllc.infantry.persistence.TicketRow.toTicketInfo() =
    com.superiorsoftllc.infantry.ticketfiles.TicketInfo(id, title, description, status, projectId, branch, base)
private fun com.superiorsoftllc.infantry.persistence.ArchivedInfo.toTicketInfo() =
    com.superiorsoftllc.infantry.ticketfiles.TicketInfo(id, title, description, status, projectId, branch, base)
private fun com.superiorsoftllc.infantry.persistence.TicketLinkRow.toTicketInfo() =
    com.superiorsoftllc.infantry.ticketfiles.TicketLinkInfo(ticketId, relatedId)

/** DP-7f: test seam only. A fake Knowledge provider core tests install in place of the Infantry_Notes plugin. */
@Volatile
internal var testKnowledge: com.superiorsoftllc.infantry.daemon.api.knowledge.Knowledge? = null

/**
 * KILL-TIMEOUT-1: frames whose normal answer is a pushed event (no direct reply) still answer a caller that waits on its requestId:
 * a plugin call ("plugin:") or the daemon's own script loopback ("loopback:", [com.superiorsoftllc.infantry.daemon.plugins.LoopbackFrames]).
 * Before, only "plugin:" got the ack, so a hook's session_remove/kill waited its whole timeout ("kill got no answer in 30000 ms").
 */
internal fun wantsDirectAck(envelope: Envelope): Boolean =
    envelope.requestId?.let { it.startsWith("plugin:") || it.startsWith("loopback:") } == true

/**
 * MAIL-LIMITS-1: a frame the daemon sent itself for a script/hook action (its [com.superiorsoftllc.infantry.daemon.plugins.LoopbackFrames]
 * request id) on an OWNER connection. Both are needed: the request id alone is client-chosen, the owner credential is not, so an agent
 * cannot claim to be automation. Such mail is exempt from the sender rate limit.
 */
internal fun isAutomationFrame(envelope: Envelope, owner: Boolean): Boolean =
    owner && envelope.requestId?.startsWith("loopback:") == true

/** AR-4b-ii-b: test seam only. A Roles provider core tests install in place of the Infantry_Roles plugin (no frames; the port only). */
@Volatile
internal var testRoles: com.superiorsoftllc.infantry.daemon.api.roles.Roles? = null
