// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

/**
 * PLUGIN-UI-1 (docs/PLUGIN-MANAGER.md section 4.3): the plugin manager as a UI plugin sees it. Plain data and blocking calls
 * (the screen runs them off the UI thread). The desktop host supplies one over the manager of this host's plugins root; a
 * host that cannot load jars (web, Android) supplies none, and the Plugins screen is then not drawn.
 */
interface PluginManagerPort {
    fun list(): List<PluginView>
    fun updates(): List<UpdateView>
    fun search(query: String): List<HitView>
    /** Checks [ref] (`[source:]id[@version]`, a `.ihq` path or URL, or a folder) and installs nothing. */
    fun inspect(ref: String): InspectOutcome
    /** Installs [ref] with exactly [consent] granted; [token] binds the consent to the inspected bytes. */
    fun install(ref: String, consent: Set<String>, token: String?): ActionResult
    fun setEnabled(id: String, enabled: Boolean): ActionResult
    /** Updates [id]; [consent] must cover every grant the new version asks for beyond the ones already consented. */
    fun update(id: String, consent: Set<String>?): ActionResult
    fun rollback(id: String): ActionResult
    fun remove(id: String): ActionResult
    fun sources(): List<SourceView>
    fun addSource(id: String, cls: String, location: String): ActionResult
    fun removeSource(id: String): ActionResult
    fun refresh(): List<RefreshView>
}

/** One installed or bundled plugin. [cls] = official / community / custom. */
data class PluginView(
    val id: String, val version: String, val cls: String, val bundled: Boolean, val enabled: Boolean,
    val license: String?, val signer: String?, val requested: List<String>, val granted: List<String>,
    val canRollback: Boolean, val yanked: String?,
)

/** A newer version offered for an installed plugin; [newGrants] non-empty = "asks for new permissions". */
data class UpdateView(val id: String, val from: String, val to: String, val newGrants: List<String>)

data class HitView(val id: String, val source: String, val cls: String, val version: String?, val description: String, val license: String?, val installed: Boolean)

/** What the consent dialog shows. */
data class InspectView(
    val id: String, val version: String, val cls: String, val license: String?, val signer: String?,
    val grants: List<String>, val warnings: List<String>, val token: String,
)

/** Exactly one of [view] and [error] is set. */
data class InspectOutcome(val view: InspectView? = null, val error: String? = null)

/** What a change did. [restartNeeded] = it waits for a restart; [message] says why. */
data class ActionResult(val ok: Boolean, val message: String, val restartNeeded: Boolean = false, val requested: List<String> = emptyList())

data class SourceView(val id: String, val cls: String, val location: String, val enabled: Boolean)

data class RefreshView(val source: String, val ok: Boolean, val stale: Boolean, val plugins: Int, val message: String)
