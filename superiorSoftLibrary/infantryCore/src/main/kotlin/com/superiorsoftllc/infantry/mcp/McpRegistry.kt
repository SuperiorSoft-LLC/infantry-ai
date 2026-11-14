// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.HarnessMcp
import com.superiorsoftllc.infantry.harness.api.INFANTRY_MCP_SERVER
import com.superiorsoftllc.infantry.harness.api.McpListing
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import com.superiorsoftllc.infantry.protocol.MCP_TARGET_INFANTRY
import com.superiorsoftllc.infantry.protocol.McpServerView
import com.superiorsoftllc.infantry.protocol.McpTargetView
import com.superiorsoftllc.infantry.provider.CachedListing
import com.superiorsoftllc.infantry.provider.ListingCache
import kotlinx.coroutines.runBlocking

/**
 * MCP-REGISTRY-1: one server of the registry. [target]: [MCP_TARGET_INFANTRY] or the harness id whose CLI lists it. [spec]: what a
 * session would be handed (secrets resolved), null when it cannot be built ([problem] says why). [shadowedBy]: the source whose server
 * of the same name wins; a shadowed entry is listed, never delivered.
 */
data class McpEntry(
    val name: String,
    val source: McpSource,
    val target: String,
    val spec: McpServerSpec?,
    val status: McpServerStatus,
    val enabled: Boolean = true,
    val shadowedBy: McpSource? = null,
    val projectId: String? = null,
    val problem: String? = null,
    /** For display only: the transport with its secret values dropped (an Infantry-owned server whose secrets are not set still shows). */
    val shown: McpTransport? = spec?.transport,
    val scope: String? = spec?.scope,
    val provides: Set<String> = spec?.provides.orEmpty(),
    val localOnly: Boolean = spec?.localOnly ?: false,
) {
    /** The wire view: env and header KEYS only, never a value. */
    fun view(): McpServerView {
        val t = shown
        return McpServerView(
            name = name, source = source.name.lowercase(), target = target, scope = scope,
            transport = when (t) { is McpTransport.Http -> "http"; else -> "stdio" },
            command = (t as? McpTransport.Stdio)?.command.orEmpty(),
            url = (t as? McpTransport.Http)?.url,
            envKeys = (t as? McpTransport.Stdio)?.env?.keys?.sorted().orEmpty(),
            headerKeys = (t as? McpTransport.Http)?.headers?.keys?.sorted().orEmpty(),
            provides = provides.sorted(), localOnly = localOnly, enabled = enabled,
            state = status.state.name.lowercase(), detail = status.detail, toolCount = status.toolCount,
            shadowedBy = shadowedBy?.name?.lowercase(), projectId = projectId,
        )
    }
}

/** MCP-REGISTRY-1: what [McpRegistry.catalog] answers: every entry and what each target can do. */
data class McpCatalog(val entries: List<McpEntry>, val targets: List<McpTargetView>)

/**
 * MCP-REGISTRY-1 (design docs/design/MCP-TOOLS.md section 4.2): every MCP server from every source, merged.
 *  - the daemon's own server ([infantry]), always first;
 *  - Infantry-owned servers ([owned]; secret refs resolved through [secret], never stored);
 *  - plugin-contributed specs ([pluginSpecs]);
 *  - each harness CLI's own servers, read ONLY through its [HarnessMcp] (official commands), cached per harness and project folder in
 *    [cache] (a [ListingCache]: the last good list at once, a refresh behind it, bounded by `limits.mcp.*`).
 * A name clash is settled in that order (infantry, Infantry-owned, plugin, CLI); the loser stays listed as shadowed. Mechanism only:
 * no harness, CLI or server is named here.
 */
class McpRegistry(
    private val owned: McpOwnedServers,
    private val harnessIds: () -> List<String>,
    private val harnessMcp: (String) -> HarnessMcp?,
    /** The folder a project id means (null = no project: the daemon passes the user's home), handed to [HarnessMcp.list]. */
    private val projectDir: (String?) -> String,
    /** A Secrets entry's value: (secret name, project id) -> value, or null when it is not set. */
    private val secret: (String, String?) -> String?,
    private val cache: ListingCache<McpListing>,
    private val infantry: () -> McpServerSpec? = { null },
    private val pluginSpecs: () -> List<McpServerSpec> = { emptyList() },
) {
    /** Every server [projectId] sees; [harnessId] null = every harness's own, else only that one's. */
    fun catalog(projectId: String?, harnessId: String? = null): McpCatalog {
        val base = mutableListOf<McpEntry>()
        val inf = infantry()
        base += McpEntry(INFANTRY_MCP_SERVER, McpSource.INFANTRY, MCP_TARGET_INFANTRY, inf, McpServerStatus(INFANTRY_MCP_SERVER, McpServerState.CONFIGURED))
        owned.visible(projectId).forEach { base += ownedEntry(it) }
        pluginSpecs().filter { it.name != INFANTRY_MCP_SERVER }.forEach {
            base += McpEntry(it.name, McpSource.PLUGIN, MCP_TARGET_INFANTRY, it.copy(source = McpSource.PLUGIN), McpServerStatus(it.name, McpServerState.CONFIGURED))
        }
        val winners = LinkedHashMap<String, McpSource>()
        val merged = base.map { e ->
            val winner = winners[e.name]
            if (winner == null) { winners[e.name] = e.source; e } else e.copy(shadowedBy = winner)
        }.toMutableList()
        val targets = mutableListOf(McpTargetView(MCP_TARGET_INFANTRY, canAdd = true, canRemove = true, canEdit = true, scopes = listOf(SCOPE_APP, SCOPE_PROJECT)))
        val ids = harnessIds().filter { harnessId == null || it == harnessId }
        for (id in ids) {
            val mcp = harnessMcp(id) ?: continue
            val listed = listing(id, mcp, projectId)
            targets += McpTargetView(
                id, mcp.canAdd, mcp.canRemove, mcp.canEdit, mcp.scopes, listing = listed.listing, note = noteFor(listed),
            )
            listed.rows.orEmpty().forEach { l ->
                val spec = l.spec.copy(source = McpSource.HARNESS)
                merged += McpEntry(spec.name, McpSource.HARNESS, id, spec, l.status, shadowedBy = winners[spec.name])
            }
        }
        return McpCatalog(merged, targets)
    }

    /** One server by [name] ([target] null = the first that is not shadowed, else that target's). */
    fun find(name: String, projectId: String?, target: String? = null): McpEntry? {
        val harness = target?.takeIf { it != MCP_TARGET_INFANTRY }
        val entries = catalog(projectId, harness).entries.filter { it.name == name }
        return if (target == null) entries.firstOrNull { it.shadowedBy == null } ?: entries.firstOrNull()
        else entries.firstOrNull { it.target == target }
    }

    /** Forget the cached CLI lists (a change was made through a CLI, so its list is stale). */
    fun invalidate() = cache.clear()

    /** The folder a project means, for a call into a [HarnessMcp]. */
    fun dirOf(projectId: String?): String = projectDir(projectId)

    private fun listing(id: String, mcp: HarnessMcp, projectId: String?): CachedListing<McpListing> {
        val dir = projectDir(projectId)
        return cache.read("$id|$dir") { runBlocking { mcp.list(dir) } }
    }

    private fun noteFor(c: CachedListing<McpListing>): String? = when {
        c.rows == null && c.error != null -> "could not list MCP servers: ${c.error}"
        c.rows == null -> "still listing its MCP servers (limit 'limits.mcp.listWaitMs')"
        c.listing && c.ageMs != null -> "showing the list from ${cache.ageText(c.ageMs)} ago; refreshing now"
        c.error != null && c.ageMs != null -> "showing the list from ${cache.ageText(c.ageMs)} ago: the last refresh failed: ${c.error}"
        else -> null
    }

    private fun ownedEntry(o: OwnedServer): McpEntry {
        val s = o.server
        val resolved = McpOwnedServers.toSpec(o, secret)
        val spec = resolved.getOrNull()
        val problem = resolved.exceptionOrNull()?.message
        val state = when {
            !s.enabled -> McpServerState.DISABLED
            problem != null -> McpServerState.FAILED
            else -> McpServerState.CONFIGURED
        }
        // Shown without secret values: the keys of plain and secret env/headers alike.
        val shown = if (s.command.isNotEmpty()) McpTransport.Stdio(s.command, (s.env.keys + s.envSecrets.keys).associateWith { "" }, s.workingDir)
        else McpTransport.Http(s.url!!, (s.headers.keys + s.headerSecrets.keys).associateWith { "" })
        return McpEntry(
            s.name, McpSource.INFANTRY_USER, MCP_TARGET_INFANTRY, spec, McpServerStatus(s.name, state, problem),
            enabled = s.enabled, projectId = o.projectId, problem = problem, shown = shown,
            scope = if (o.projectId == null) SCOPE_APP else SCOPE_PROJECT, provides = s.provides.toSet(), localOnly = s.localOnly,
        )
    }

    companion object {
        const val SCOPE_APP = "app"
        const val SCOPE_PROJECT = "project"
    }
}
