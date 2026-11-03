// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.ActionInfo
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.Policy
import com.superiorsoftllc.infantry.daemon.api.ProjectInfo
import com.superiorsoftllc.infantry.daemon.api.Projects
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.Sessions
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.settings.LimitDef

/**
 * DAEMON-PLUGINS-1: the daemon services a [DaemonPluginHost] hands its plugins, as the API's own types. The daemon builds
 * one from its managers ([com.superiorsoftllc.infantry.daemon.InfantryDaemon]); tests build one from fakes. The host scopes
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
    val tickets: com.superiorsoftllc.infantry.daemon.api.board.Tickets get() = com.superiorsoftllc.infantry.daemon.api.board.NoBoard.tickets
    val epics: com.superiorsoftllc.infantry.daemon.api.board.Epics get() = com.superiorsoftllc.infantry.daemon.api.board.NoBoard.epics
    val board: com.superiorsoftllc.infantry.daemon.api.board.Board get() = com.superiorsoftllc.infantry.daemon.api.board.NoBoard.board

    /** DP-4b: the typed ticket event stream (ordered, at-least-once, cursors kept across restarts). Default: none. */
    /** DP-7d (1.18.0): told once per session that is killed or removed (the session-scoped data of a plugin dies with it). */
    fun onSessionRemoved(listener: (String) -> Unit): AutoCloseable = AutoCloseable {}
    /** ATTN-2a (1.24.0): push one frame to every connected client (behind `api.events.broadcast`); default: no clients. */
    fun broadcast(type: String, data: String) {}
    val ticketEvents: com.superiorsoftllc.infantry.daemon.api.board.TicketEvents get() = com.superiorsoftllc.infantry.daemon.api.board.NoTicketEvents

    /** AGENT-REGISTRY-1: the daemon's agent registry; null = none (plugins see [com.superiorsoftllc.infantry.daemon.api.agents.NoAgents]). */
    val agentRegistry: com.superiorsoftllc.infantry.agents.registry.AgentRegistry? get() = null

    /** RUNTIME-LOAD-5: the sessions read model, host-scoped ([LocalSessionReads]). Default: none (every call Unavailable). */
    val sessionReads: com.superiorsoftllc.infantry.daemon.api.sessions.SessionReads get() = com.superiorsoftllc.infantry.daemon.api.sessions.NoSessionReads

    /** RUNTIME-LOAD-6: the host spawn service ([LocalSpawns]). Default: none (every call Unavailable). */
    /** DP-5h-3a: what a board plugin builds its board from; the no-board runtime when none. */
    val boardRuntime: com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime get() = com.superiorsoftllc.infantry.daemon.api.board.NoBoardRuntime
    val spawns: com.superiorsoftllc.infantry.daemon.api.spawns.Spawns get() = com.superiorsoftllc.infantry.daemon.api.spawns.NoSpawns

    /** STACK-NEUTRAL-2: the host process service ([com.superiorsoftllc.infantry.session.LocalProcesses]); null = none. */
    val processHost: com.superiorsoftllc.infantry.session.LocalProcesses? get() = null

    /** STACK-NEUTRAL-2: the per-launch env seam; null = none. */
    val launchEnvHost: com.superiorsoftllc.infantry.session.LaunchEnvHost? get() = null

    /** STACK-NEUTRAL-2: read access to `limits.*` / `watch.*` / `processes.*` settings. */
    val pluginSettings: com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings get() = com.superiorsoftllc.infantry.daemon.api.processes.NoPluginSettings

    /** CMD-1: the cwd-bound run surface ([LocalRuns]); default: none (every call refuses). */
    val runs: com.superiorsoftllc.infantry.daemon.api.runs.Runs get() = com.superiorsoftllc.infantry.daemon.api.runs.NoRuns

    /** PLUGIN-MCP-1: the read-only plugin manager view; default: none. */
    val plugins: com.superiorsoftllc.infantry.daemon.api.plugins.PluginCatalogReader get() = com.superiorsoftllc.infantry.daemon.api.plugins.NoPluginCatalog

    /** PACE-TREND-1: the plan meters (current readings); default: none. */
    val meters: com.superiorsoftllc.infantry.daemon.api.usage.Meters get() = com.superiorsoftllc.infantry.daemon.api.usage.NoMeters

    /** DP-7e: the mail port behind `api.sessionNotifier` (ungated; the plugin host adds the grant); default: delivers nothing. */
    val sessionNotifier: com.superiorsoftllc.infantry.daemon.api.sessions.SessionNotifier get() = com.superiorsoftllc.infantry.daemon.api.sessions.NoSessionNotifier

    /** DAEMON-STORAGE-1: the database handle behind `api.storage`; null = none (every call refuses). */
    val storageHandle: com.superiorsoftllc.infantry.db.InfantryDatabaseHandle? get() = null

    /** FULLSUITE-2: a project's data entries (`tests.*` live here); empty when unknown. */
    fun projectData(projectId: String): Map<String, String> = emptyMap()

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
