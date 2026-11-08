// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.daemon.DaemonConfig
import com.superiorsoftllc.infantry.daemon.api.ActionInfo
import com.superiorsoftllc.infantry.daemon.api.AutomationMark
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.Policy
import com.superiorsoftllc.infantry.daemon.api.ProjectInfo
import com.superiorsoftllc.infantry.daemon.api.Projects
import com.superiorsoftllc.infantry.daemon.api.QueuedMessage
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.Sessions
import com.superiorsoftllc.infantry.daemon.bindSessionOnSpawn
import com.superiorsoftllc.infantry.keystore.Keystore
import com.superiorsoftllc.infantry.mvp.ProjectStore
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.policy.PolicyRuntime
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProjectSave
import com.superiorsoftllc.infantry.protocol.SpawnOptions
import com.superiorsoftllc.infantry.session.SessionAutomation
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionRecord

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
    private val limits: (com.superiorsoftllc.infantry.settings.LimitDef) -> Long = { it.default },
    /** SCRIPT-SESSION-1: marks a built-in notice (name, session or null, millis) as suppressed. */
    private val noticeSuppressor: (String, String?, Long) -> Unit = { _, _, _ -> },
    /** DP-4a: the local binding of tickets / epics / board; null = a daemon without a board (every call Unavailable). */
    private val boardServices: LocalBoardServices? = null,
    /** DP-4b: the ticket event stream; null = none. */
    private val ticketEventStream: com.superiorsoftllc.infantry.daemon.api.board.TicketEvents? = null,
    /** AGENT-REGISTRY-1: the agent registry; null = none. */
    override val agentRegistry: com.superiorsoftllc.infantry.agents.registry.AgentRegistry? = null,
    /** RUNTIME-LOAD-5: the sessions read model; null = none. */
    private val sessionReadModel: com.superiorsoftllc.infantry.daemon.api.sessions.SessionReads? = null,
    /** RUNTIME-LOAD-6: the host spawn service; null = none. */
    private val spawnService: com.superiorsoftllc.infantry.daemon.api.spawns.Spawns? = null,
    /** DP-5h-3a: the board runtime a board plugin builds from; null = none. */
    private val boardRuntimeService: com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime? = null,
    /** STACK-NEUTRAL-2: the host process service, the per-launch env seam and the plugin settings reader; null = none. */
    override val processHost: com.superiorsoftllc.infantry.session.LocalProcesses? = null,
    override val launchEnvHost: com.superiorsoftllc.infantry.session.LaunchEnvHost? = null,
    override val pluginSettings: com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings = com.superiorsoftllc.infantry.daemon.api.processes.NoPluginSettings,
    /** PACE-TREND-1: the plan meters' current readings; null = none. */
    override val meters: com.superiorsoftllc.infantry.daemon.api.usage.Meters = com.superiorsoftllc.infantry.daemon.api.usage.NoMeters,
    /** DP-7e: the mail port; null = delivers nothing. */
    private val notifier: com.superiorsoftllc.infantry.daemon.api.sessions.SessionNotifier? = null,
    /** DAEMON-STORAGE-1: the database handle behind `api.storage`; null = no plugin storage. */
    override val storageHandle: com.superiorsoftllc.infantry.db.InfantryDatabaseHandle? = null,
    /** ATTN-2a: sends one frame to every connected client; null = none. */
    private val clientBroadcast: ((type: String, data: String) -> Unit)? = null,
    /** CMD-1: the run surface; null = none. */
    private val runService: com.superiorsoftllc.infantry.daemon.api.runs.Runs? = null,
    /** PLUGIN-MCP-1: the plugin manager view; null = none. */
    private val pluginCatalog: com.superiorsoftllc.infantry.daemon.api.plugins.PluginCatalogReader? = null,
    /** CORE-PROGRESS-DOWNLOAD-1: reads a plain-string core setting; "" = unset. */
    private val coreStringSetting: (String) -> String = { "" },
) : DaemonServices {
    override fun coreSetting(key: String) = coreStringSetting(key)
    override val plugins get() = pluginCatalog ?: com.superiorsoftllc.infantry.daemon.api.plugins.NoPluginCatalog
    override val runs get() = runService ?: com.superiorsoftllc.infantry.daemon.api.runs.NoRuns
    override fun broadcast(type: String, data: String) { clientBroadcast?.invoke(type, data) }
    override val sessionNotifier get() = notifier ?: com.superiorsoftllc.infantry.daemon.api.sessions.NoSessionNotifier
    override val boardRuntime get() = boardRuntimeService ?: com.superiorsoftllc.infantry.daemon.api.board.NoBoardRuntime
    override val spawns get() = spawnService ?: com.superiorsoftllc.infantry.daemon.api.spawns.NoSpawns
    override val sessionReads get() = sessionReadModel ?: com.superiorsoftllc.infantry.daemon.api.sessions.NoSessionReads
    override fun onSessionRemoved(listener: (String) -> Unit): AutoCloseable = manager.addSessionEndedListener(listener)
    override val ticketEvents: com.superiorsoftllc.infantry.daemon.api.board.TicketEvents get() = ticketEventStream ?: com.superiorsoftllc.infantry.daemon.api.board.NoTicketEvents
    override fun limit(def: com.superiorsoftllc.infantry.settings.LimitDef): Long = limits(def)
    override val tickets get() = boardServices?.tickets ?: com.superiorsoftllc.infantry.daemon.api.board.NoBoard.tickets
    override val epics get() = boardServices?.epics ?: com.superiorsoftllc.infantry.daemon.api.board.NoBoard.epics
    override val board get() = boardServices?.boardView ?: com.superiorsoftllc.infantry.daemon.api.board.NoBoard.board
    override fun projectData(projectId: String): Map<String, String> = runCatching { projectStore.get(projectId).data }.getOrDefault(emptyMap())
    override fun config(module: String, key: String): String? = daemonConfig.value(module, key)
    override fun env(name: String): String? = getenv(name)
    override fun secret(name: String): String? = keystore.get(name)

    override val projects: Projects = object : Projects {
        override fun list(): List<ProjectInfo> = projectStore.list().map { ProjectInfo(it.id, it.name, it.directory, it.data["project.description"].orEmpty()) }
        override fun create(name: String, goal: String, directory: String): ProjectInfo {
            java.io.File(directory).mkdirs()
            val p = projectStore.list().firstOrNull { it.directory == directory }
                ?: projectStore.save(ProjectSave(name = name, goal = goal, directory = directory))
            return ProjectInfo(p.id, p.name, p.directory, p.data["project.description"].orEmpty())
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
        override fun status(id: String): com.superiorsoftllc.infantry.daemon.api.SessionStatus? = manager.get(id)?.let { com.superiorsoftllc.infantry.session.SessionActivityLog.overlay(it) }?.let {
            com.superiorsoftllc.infantry.daemon.api.SessionStatus(it.id, it.state.name, it.lastAction, it.lastOutputAt, it.lastEventAt, it.lastTurnAt)
        }
        override fun recentLines(id: String, max: Int): List<String> = com.superiorsoftllc.infantry.session.SessionFeed.recent(id, max)
        override fun interrupt(id: String): Boolean = manager.get(id)?.let { runCatching { manager.interrupt(id) }.isSuccess } ?: false
        override fun suppressNotice(name: String, sessionId: String?, ms: Long) = noticeSuppressor(name, sessionId, ms)
        override fun setAutomation(id: String, mark: AutomationMark?) { manager.setAutomation(id, mark?.let { SessionAutomation(it.script, it.hookId, it.since) }) }
        override fun reconnectMcp(id: String, force: Boolean): String = manager.reconnectMcp(id, force)
        override fun queued(id: String): List<QueuedMessage> = manager.queued(id).map { QueuedMessage(it.id, it.text, it.at) }
        override fun queueAdd(id: String, text: String): QueuedMessage? =
            manager.queueAdd(id, text, limits(com.superiorsoftllc.infantry.settings.LimitRegistry.CHAT_QUEUE_MAX).toInt())
                ?.let { QueuedMessage(it.id, it.text, it.at) }
        override fun queueRemove(id: String, itemId: String): QueuedMessage = manager.queueRemove(id, itemId).let { QueuedMessage(it.id, it.text, it.at) }
        // JOBFEED-1: the jobs a session watches. A job that ended is remembered (bounded) so its last lines and exit code stay readable.
        private val endedJobs = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, com.superiorsoftllc.infantry.session.BgJob>>()
        private fun logFileOf(job: com.superiorsoftllc.infantry.session.BgJob): java.io.File? = job.logPath?.let { java.io.File(it) }
        override fun jobs(id: String): List<com.superiorsoftllc.infantry.daemon.api.JobInfo> {
            if (manager.get(id) == null) return emptyList()
            val live = manager.bgJobs(id)
            val memory = endedJobs.getOrPut(id) { java.util.Collections.synchronizedMap(LinkedHashMap()) }
            synchronized(memory) {
                live.forEach { memory[it.id] = it }
                while (memory.size > limits(com.superiorsoftllc.infantry.settings.LimitRegistry.JOBS_REMEMBERED).toInt()) memory.remove(memory.keys.first())
            }
            val liveIds = live.map { it.id }.toSet()
            val all = synchronized(memory) { memory.values.toList() }
            return all.sortedByDescending { it.startedAt }.map { j ->
                val running = j.id in liveIds
                com.superiorsoftllc.infantry.daemon.api.JobInfo(j.id, j.label, j.startedAt, running,
                    if (running) null else logFileOf(j)?.let { com.superiorsoftllc.infantry.runner.JobLogTail.exitCode(it) })
            }
        }
        override fun jobLog(id: String, jobId: String, fromOffset: Long, maxBytes: Int): com.superiorsoftllc.infantry.daemon.api.JobLogChunk? {
            if (manager.get(id) == null) return null
            val info = jobs(id).firstOrNull { it.id == jobId } ?: return null
            val job = manager.bgJobs(id).firstOrNull { it.id == jobId } ?: endedJobs[id]?.get(jobId)
            val file = job?.let(::logFileOf) ?: return null
            val c = com.superiorsoftllc.infantry.runner.JobLogTail.read(file, fromOffset, maxBytes.coerceAtMost(limits(com.superiorsoftllc.infantry.settings.LimitRegistry.JOB_LOG_MAX_READ_BYTES).toInt()))
            return com.superiorsoftllc.infantry.daemon.api.JobLogChunk(c.text, c.nextOffset, c.size, c.reset, info)
        }
        override fun queueForce(id: String, itemId: String): String = when (manager.queueForce(id, itemId)) {
            SessionManager.ForceOutcome.INJECTED -> "injected"
            SessionManager.ForceOutcome.SENT_AS_TURN -> "sent as turn"
        }
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
            mcp = r.mcp, mcpChangedAt = r.mcpChangedAt,
        )

        private fun Frame.envelope() = Envelope(type, data, sessionId = sessionId, requestId = requestId)
    }
}
