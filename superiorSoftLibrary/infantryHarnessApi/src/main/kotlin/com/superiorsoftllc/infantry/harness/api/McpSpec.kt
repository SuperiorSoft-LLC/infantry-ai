// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.api

/*
 * 2.19.0 (MCP-SPEC-1, additive; design docs/design/MCP-TOOLS.md section 4.1): harness-neutral MCP server types. A harness
 * reaches its CLI's own servers ONLY through that CLI's official commands or API ([HarnessMcp]); the host hands a session
 * its plan ([McpPlan], [HarnessHost.mcpServers]); a live session reports per-server state ([SessionOutput.mcpServers]).
 * Nothing here starts, adds or denies a server yet: every new member has a default that keeps today's behaviour.
 */

/** 2.19.0: the name of the daemon's own MCP server (`infantry mcp`), the infantry entry of every [McpPlan]. */
const val INFANTRY_MCP_SERVER: String = "infantry"

/**
 * 2.19.0: one MCP server, harness-neutral. Secrets live in [McpTransport.Stdio.env] / [McpTransport.Http.headers] and are never
 * logged, shown or put on the wire: [toString] prints the env and header KEYS only.
 */
data class McpServerSpec(
    /** The key the agent sees: `mcp__<name>__*` / `<name>_*`. */
    val name: String,
    val transport: McpTransport,
    /** Where it came from (shown to the owner). */
    val source: McpSource,
    /** The CLI's own scope label, as its CLI prints it ("user", "local", "project"); null = none. */
    val scope: String? = null,
    /** Capability tags, e.g. "browser"; free strings, plugins add their own. */
    val provides: Set<String> = emptySet(),
    /** The command or paths exist only on this machine: never sent to a remote runner. */
    val localOnly: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "an MCP server needs a name" }
    }
}

/** 2.19.0: how an MCP server is reached. */
sealed interface McpTransport {
    /** A child process speaking MCP on stdin/stdout. [env] values are secret. */
    data class Stdio(
        val command: List<String>,
        val env: Map<String, String> = emptyMap(),
        val workingDir: String? = null,
    ) : McpTransport {
        init {
            require(command.isNotEmpty()) { "a stdio MCP server needs a command" }
        }

        override fun toString(): String = "Stdio(command=$command, env=${env.keys}, workingDir=$workingDir)"
    }

    /** A remote server over streamable HTTP (or SSE). [headers] values are secret. */
    data class Http(val url: String, val headers: Map<String, String> = emptyMap()) : McpTransport {
        init {
            require(url.isNotBlank()) { "an HTTP MCP server needs a url" }
        }

        override fun toString(): String = "Http(url=$url, headers=${headers.keys})"
    }
}

/** 2.19.0: where an [McpServerSpec] came from. */
enum class McpSource {
    /** The daemon's own server ([INFANTRY_MCP_SERVER]). */
    INFANTRY,

    /** Added by the user in Infantry's own MCP Tools screen. */
    INFANTRY_USER,

    /** The CLI's own configuration, read through its official commands. */
    HARNESS,

    /** Declared by a plugin. */
    PLUGIN,
}

/** 2.19.0: one server's state, as the CLI or the live session reports it. */
enum class McpServerState { CONFIGURED, CONNECTED, FAILED, NEEDS_AUTH, PENDING_APPROVAL, DISABLED, UNKNOWN }

/** 2.19.0: [name]'s [state]; [detail] = why, short (never a secret); [toolCount] = tools it offers, null = unknown. */
data class McpServerStatus(
    val name: String,
    val state: McpServerState,
    val detail: String? = null,
    val toolCount: Int? = null,
)

/** 2.19.0: one server the CLI lists, with its state at listing time. */
data class McpListing(val spec: McpServerSpec, val status: McpServerStatus)

/**
 * 2.19.0: a CLI's own MCP servers, reached ONLY through that CLI's official commands or API (never by reading its private
 * files). Run every command through [HarnessHost]; mask any secret value the CLI prints before it leaves the plugin.
 * Every member has a default: a harness implements only what its CLI can do, and the screen greys out the rest.
 */
interface HarnessMcp {
    val canAdd: Boolean get() = false
    val canRemove: Boolean get() = false
    val canEdit: Boolean get() = false

    /** The CLI's scope labels (Claude: local, user, project); empty = the CLI has no scopes. */
    val scopes: List<String> get() = emptyList()

    /** The servers the CLI would load for [project] (a project folder). Default: none visible. */
    suspend fun list(project: String): List<McpListing> = emptyList()

    /** Add [spec] at [scope]. Default: refused (the CLI cannot add). */
    suspend fun add(project: String, spec: McpServerSpec, scope: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("this CLI cannot add MCP servers"))

    /** Remove [name] ([scope] null = wherever the CLI finds it). Default: refused. */
    suspend fun remove(project: String, name: String, scope: String?): Result<Unit> =
        Result.failure(UnsupportedOperationException("this CLI cannot remove MCP servers"))

    /** Check [name] now. Default: [McpServerState.UNKNOWN]. */
    suspend fun test(project: String, name: String): McpServerStatus =
        McpServerStatus(name, McpServerState.UNKNOWN, "this CLI cannot test MCP servers")
}

/**
 * 2.19.0: the MCP servers one session gets: [add] = the servers the host delivers (infantry, Infantry-owned and plugin specs),
 * [deny] = server names the selection turns off (a CLI that loads the user's own servers itself, [Harness.loadsUserMcp],
 * is told to skip them). A name is never in both: [deny] wins.
 */
data class McpPlan(val add: List<McpServerSpec> = emptyList(), val deny: Set<String> = emptySet()) {
    /** [add] without the denied names. */
    val delivered: List<McpServerSpec> get() = add.filter { it.name !in deny }

    companion object {
        val EMPTY: McpPlan = McpPlan()

        /** The infantry entry of a plan, from the command [HarnessHost.mcpLaunch] answers; null when there is none. */
        fun infantry(launch: HarnessProcess?): McpServerSpec? = launch?.let {
            McpServerSpec(
                name = INFANTRY_MCP_SERVER,
                transport = McpTransport.Stdio(it.command, it.env, it.workingDir?.path),
                source = McpSource.INFANTRY,
            )
        }
    }
}
