package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.daemon.api.ActionInfo
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.Policy
import com.superiorsoftllc.agenthq.daemon.api.ProjectInfo
import com.superiorsoftllc.agenthq.daemon.api.Projects
import com.superiorsoftllc.agenthq.daemon.api.SessionInfo
import com.superiorsoftllc.agenthq.daemon.api.Sessions
import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import com.superiorsoftllc.agenthq.settings.LimitDef

/**
 * DAEMON-PLUGINS-1: the daemon services a [DaemonPluginHost] hands its plugins, as the API's own types. The daemon builds
 * one from its managers ([com.superiorsoftllc.agenthq.daemon.AgentHqDaemon]); tests build one from fakes. The host scopes
 * every call to the calling plugin's declarations ([config] by module id, [env] and [secret] only when declared).
 */
interface DaemonServices {
    fun config(module: String, key: String): String?
    fun env(name: String): String?
    fun secret(name: String): String?
    /** A limit's effective value (the stored setting, the env, the default: SettingsService.limit). */
    fun limit(def: LimitDef): Long = def.default
    val projects: Projects
    val sessions: Sessions
    /** The policy, read at call time (the runtime is built after the host). */
    fun policy(): Policy

    /** DP-4a: the board, over the daemon's own TicketBoard / EpicStore (docs/DAEMON-BOARD-API.md). Default: no board. */
    val tickets: com.superiorsoftllc.agenthq.daemon.api.board.Tickets get() = com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.tickets
    val epics: com.superiorsoftllc.agenthq.daemon.api.board.Epics get() = com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.epics
    val board: com.superiorsoftllc.agenthq.daemon.api.board.Board get() = com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.board

    /** DP-4b: the typed ticket event stream (ordered, at-least-once, cursors kept across restarts). Default: none. */
    val ticketEvents: com.superiorsoftllc.agenthq.daemon.api.board.TicketEvents get() = com.superiorsoftllc.agenthq.daemon.api.board.NoTicketEvents

    /** AGENT-REGISTRY-1: the daemon's agent registry; null = none (plugins see [com.superiorsoftllc.agenthq.daemon.api.agents.NoAgents]). */
    val agentRegistry: com.superiorsoftllc.agenthq.agents.registry.AgentRegistry? get() = null

    /** RUNTIME-LOAD-5: the sessions read model, host-scoped ([LocalSessionReads]). Default: none (every call Unavailable). */
    val sessionReads: com.superiorsoftllc.agenthq.daemon.api.sessions.SessionReads get() = com.superiorsoftllc.agenthq.daemon.api.sessions.NoSessionReads

    /** RUNTIME-LOAD-6: the host spawn service ([LocalSpawns]). Default: none (every call Unavailable). */
    val spawns: com.superiorsoftllc.agenthq.daemon.api.spawns.Spawns get() = com.superiorsoftllc.agenthq.daemon.api.spawns.NoSpawns

    /** STACK-NEUTRAL-2: the host process service ([com.superiorsoftllc.agenthq.session.LocalProcesses]); null = none. */
    val processHost: com.superiorsoftllc.agenthq.session.LocalProcesses? get() = null

    /** STACK-NEUTRAL-2: the per-launch env seam; null = none. */
    val launchEnvHost: com.superiorsoftllc.agenthq.session.LaunchEnvHost? get() = null

    /** STACK-NEUTRAL-2: read access to `limits.*` / `watch.*` / `processes.*` settings. */
    val pluginSettings: com.superiorsoftllc.agenthq.daemon.api.processes.PluginSettings get() = com.superiorsoftllc.agenthq.daemon.api.processes.NoPluginSettings

    /** PACE-TREND-1: the plan meters (current readings); default: none. */
    val meters: com.superiorsoftllc.agenthq.daemon.api.usage.Meters get() = com.superiorsoftllc.agenthq.daemon.api.usage.NoMeters

    companion object {
        /** No daemon behind it: empty lists, no config, the open policy. */
        val NONE: DaemonServices = object : DaemonServices {
            override fun config(module: String, key: String): String? = null
            override fun env(name: String): String? = null
            override fun secret(name: String): String? = null
            override val projects = object : Projects {
                override fun list() = emptyList<ProjectInfo>()
                override fun create(name: String, goal: String, directory: String): ProjectInfo = error("no daemon")
            }
            override val sessions = object : Sessions {
                override fun list() = emptyList<SessionInfo>()
                override fun get(id: String): SessionInfo? = null
                override fun spawn(provider: String, model: String, role: String, projectId: String): SessionInfo = error("no daemon")
                override fun resume(id: String, prompt: String): SessionInfo = error("no daemon")
                override fun restart(id: String) = error("no daemon")
            }
            override fun policy(): Policy = OPEN_POLICY
        }

        val OPEN_POLICY: Policy = object : Policy {
            override fun active(): PolicyPreset = PolicyPreset(id = "production")
            override fun onChange(listener: (PolicyPreset) -> Unit): AutoCloseable = AutoCloseable {}
            override fun actionOf(frame: Frame): ActionInfo? = null
            override fun refusal(frame: Frame): String? = null
            override fun refusalText(type: String): String = "refused: $type"
            override fun reportSeating(error: String?) {}
            override fun claimSeats() {}
        }
    }
}
