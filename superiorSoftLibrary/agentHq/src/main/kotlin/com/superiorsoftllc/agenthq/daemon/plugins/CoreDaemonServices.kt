package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.daemon.DaemonConfig
import com.superiorsoftllc.agenthq.daemon.api.ActionInfo
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.Policy
import com.superiorsoftllc.agenthq.daemon.api.ProjectInfo
import com.superiorsoftllc.agenthq.daemon.api.Projects
import com.superiorsoftllc.agenthq.daemon.api.SessionInfo
import com.superiorsoftllc.agenthq.daemon.api.Sessions
import com.superiorsoftllc.agenthq.daemon.bindSessionOnSpawn
import com.superiorsoftllc.agenthq.keystore.Keystore
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import com.superiorsoftllc.agenthq.policy.PolicyRuntime
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord

/**
 * DAEMON-PLUGINS-1: the real daemon's [DaemonServices], over its own managers. A spawn goes through the same binding as a
 * `new` frame ([bindSessionOnSpawn]), so a plugin's session is a role session like any other (and the `spawnGate` hooks
 * apply to it too); a resume of a never-run session is composed by [firstTurn], exactly as a client's first message is.
 */
class CoreDaemonServices(
    private val manager: SessionManager,
    private val agents: AgentProfiles,
    private val projectStore: ProjectStore,
    private val daemonConfig: DaemonConfig,
    private val keystore: Keystore,
    private val policies: PolicyRuntime,
    private val getenv: (String) -> String?,
    /** A never-run session's first turn: persona, identity and notes around [prompt]. */
    private val firstTurn: (SessionRecord, String) -> String,
    private val limits: (com.superiorsoftllc.agenthq.settings.LimitDef) -> Long = { it.default },
    /** DP-4a: the local binding of tickets / epics / board; null = a daemon without a board (every call Unavailable). */
    private val boardServices: LocalBoardServices? = null,
    /** DP-4b: the ticket event stream; null = none. */
    private val ticketEventStream: com.superiorsoftllc.agenthq.daemon.api.board.TicketEvents? = null,
    /** AGENT-REGISTRY-1: the agent registry; null = none. */
    override val agentRegistry: com.superiorsoftllc.agenthq.agents.registry.AgentRegistry? = null,
    /** RUNTIME-LOAD-5: the sessions read model; null = none. */
    private val sessionReadModel: com.superiorsoftllc.agenthq.daemon.api.sessions.SessionReads? = null,
    /** RUNTIME-LOAD-6: the host spawn service; null = none. */
    private val spawnService: com.superiorsoftllc.agenthq.daemon.api.spawns.Spawns? = null,
    /** DP-5h-3a: the board runtime a board plugin builds from; null = none. */
    private val boardRuntimeService: com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime? = null,
    /** STACK-NEUTRAL-2: the host process service, the per-launch env seam and the plugin settings reader; null = none. */
    override val processHost: com.superiorsoftllc.agenthq.session.LocalProcesses? = null,
    override val launchEnvHost: com.superiorsoftllc.agenthq.session.LaunchEnvHost? = null,
    override val pluginSettings: com.superiorsoftllc.agenthq.daemon.api.processes.PluginSettings = com.superiorsoftllc.agenthq.daemon.api.processes.NoPluginSettings,
    /** PACE-TREND-1: the plan meters' current readings; null = none. */
    override val meters: com.superiorsoftllc.agenthq.daemon.api.usage.Meters = com.superiorsoftllc.agenthq.daemon.api.usage.NoMeters,
    /** DP-7e: the mail port; null = delivers nothing. */
    private val notifier: com.superiorsoftllc.agenthq.daemon.api.sessions.SessionNotifier? = null,
    /** DAEMON-STORAGE-1: the database handle behind `api.storage`; null = no plugin storage. */
    override val storageHandle: com.superiorsoftllc.agenthq.db.AgentHQDatabaseHandle? = null,
    /** ATTN-2a: sends one frame to every connected client; null = none. */
    private val clientBroadcast: ((type: String, data: String) -> Unit)? = null,
) : DaemonServices {
    override fun broadcast(type: String, data: String) { clientBroadcast?.invoke(type, data) }
    override val sessionNotifier get() = notifier ?: com.superiorsoftllc.agenthq.daemon.api.sessions.NoSessionNotifier
    override val boardRuntime get() = boardRuntimeService ?: com.superiorsoftllc.agenthq.daemon.api.board.NoBoardRuntime
    override val spawns get() = spawnService ?: com.superiorsoftllc.agenthq.daemon.api.spawns.NoSpawns
    override val sessionReads get() = sessionReadModel ?: com.superiorsoftllc.agenthq.daemon.api.sessions.NoSessionReads
    override fun onSessionRemoved(listener: (String) -> Unit): AutoCloseable = manager.addSessionEndedListener(listener)
    override val ticketEvents: com.superiorsoftllc.agenthq.daemon.api.board.TicketEvents get() = ticketEventStream ?: com.superiorsoftllc.agenthq.daemon.api.board.NoTicketEvents
    override fun limit(def: com.superiorsoftllc.agenthq.settings.LimitDef): Long = limits(def)
    override val tickets get() = boardServices?.tickets ?: com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.tickets
    override val epics get() = boardServices?.epics ?: com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.epics
    override val board get() = boardServices?.boardView ?: com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.board
    override fun projectData(projectId: String): Map<String, String> = runCatching { projectStore.get(projectId).data }.getOrDefault(emptyMap())
    override fun config(module: String, key: String): String? = daemonConfig.value(module, key)
    override fun env(name: String): String? = getenv(name)
    override fun secret(name: String): String? = keystore.get(name)

    override val projects: Projects = object : Projects {
        override fun list(): List<ProjectInfo> = projectStore.list().map { ProjectInfo(it.id, it.name, it.directory) }
        override fun create(name: String, goal: String, directory: String): ProjectInfo {
            java.io.File(directory).mkdirs()
            val p = projectStore.list().firstOrNull { it.directory == directory }
                ?: projectStore.save(ProjectSave(name = name, goal = goal, directory = directory))
            return ProjectInfo(p.id, p.name, p.directory)
        }
    }

    override val sessions: Sessions = object : Sessions {
        override fun list(): List<SessionInfo> = manager.list().map(::info)
        override fun get(id: String): SessionInfo? = manager.get(id)?.let(::info)
        override fun spawn(provider: String, model: String, role: String, projectId: String): SessionInfo =
            bindSessionOnSpawn(manager, agents, SpawnOptions(provider = provider, model = model, role = role, projectId = projectId), null)
                ?.let(::info) ?: error("could not create the $role session")
        override fun resume(id: String, prompt: String): SessionInfo {
            val record = manager.get(id) ?: error("no session '$id'")
            val turn = if (record.neverRun) firstTurn(record, prompt) else prompt
            return info(manager.resume(id, turn))
        }
        override fun restart(id: String) { manager.restart(id) }
    }

    override fun policy(): Policy = object : Policy {
        override fun active(): PolicyPreset = policies.current
        override fun onChange(listener: (PolicyPreset) -> Unit): AutoCloseable = policies.onChange { listener(it.current) }
        override fun actionOf(frame: Frame): ActionInfo? =
            policies.actionOf(frame.envelope())?.let { ActionInfo(it.id, it.kind, it.scope.name.lowercase()) }
        override fun refusal(frame: Frame): String? = policies.current.takeIf { !it.isEmpty }?.let { PolicyRuntime.actionRefusal(it, frame.envelope(), policies.actionOf(frame.envelope())) }
        override fun refusalText(type: String): String =
            PolicyRuntime.refusalText(policies.current, type, policies.actionOf(Envelope(type, ""))?.id)
        override fun reportSeating(error: String?) = policies.seatingFailed(error)
        override fun claimSeats() {}
    }

    companion object {
        fun info(r: SessionRecord) = SessionInfo(
            id = r.id, projectId = r.projectId, role = r.role, agentId = r.agentId, global = r.global, spawnedAt = r.spawnedAt,
            state = r.state.name, neverRun = r.neverRun, providerSessionId = r.providerSessionId, runCount = r.runCount,
        )

        private fun Frame.envelope() = Envelope(type, data, sessionId = sessionId, requestId = requestId)
    }
}
