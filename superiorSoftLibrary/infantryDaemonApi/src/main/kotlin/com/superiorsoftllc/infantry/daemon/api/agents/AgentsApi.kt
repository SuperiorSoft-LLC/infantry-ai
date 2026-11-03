// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.agents

import com.superiorsoftllc.infantry.plugin.api.AgentDefinition
import kotlinx.serialization.Serializable

/**
 * AGENT-REGISTRY-1 (DaemonApi 1.5.0, additive; docs/AGENT-REGISTRY.md): the daemon's agent registry as a plugin sees it. Every
 * agent the daemon knows, whoever contributed it (a module's `agents` block, a plugin through [contribute], the daemon's own
 * role catalog), with the user's enable / disable override applied. This package names no board, ticket, worktree or
 * orchestrator type, and never may (AgentRegistryLayerGuardTest): agents sit below orchestration, not inside it.
 */
interface Agents {
    /** Every agent, enabled or not, in a stable order (the role catalog's first, then by id). */
    fun list(): List<AgentEntry>

    /** One agent, or null. */
    fun get(id: String): AgentEntry?

    /**
     * Contributes (or replaces) [definition] for as long as the handle stays open; the plugin's own agents only: its id must
     * be one the module's module.json `agents` block declares. Throws [IllegalStateException] otherwise.
     */
    fun contribute(definition: AgentDefinition): AutoCloseable

    /**
     * The user's override: [enabled] = false refuses new sessions of [id] (running ones carry on). Core / official plugins
     * only (a community plugin throws). Returns the entry as it is now, or null when no agent has that id.
     */
    fun setEnabled(id: String, enabled: Boolean): AgentEntry?
}

/** One registry row: the effective [definition], who contributed it, and the user's override. */
@Serializable
data class AgentEntry(
    val definition: AgentDefinition,
    /** The contributing module's name (`Vendor_Module`), or `roles` for the daemon's role catalog. */
    val contributor: String,
    /** `manifest` (module.json), `service` ([Agents.contribute]) or `roles` (the role catalog). */
    val source: String,
    /** False when the user disabled the agent; a disabled agent starts no new session. */
    val enabled: Boolean = true,
    /** The instructions a session would get NOW (its own, else its role's persona); empty = none. */
    val effectiveInstructions: String = "",
)

/** A daemon without an agent registry: lists nothing, refuses every change. */
object NoAgents : Agents {
    override fun list(): List<AgentEntry> = emptyList()
    override fun get(id: String): AgentEntry? = null
    override fun contribute(definition: AgentDefinition): AutoCloseable = error("this daemon has no agent registry")
    override fun setEnabled(id: String, enabled: Boolean): AgentEntry? = error("this daemon has no agent registry")
}
