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
    /** PLUGIN-SOURCES-1: like [refresh] but only for sources whose cached catalog is stale; a host without one does nothing. */
    fun refreshIfStale(): List<RefreshView> = emptyList()
    /** PLUGIN-REPO-1: imports a plugin list (a JSON file path or URL) as a community source; a host without one answers not ok. */
    fun importList(location: String): ActionResult = ActionResult(false, "importing a plugin list is not supported on this host")
    /** PLUGIN-PROGRESS-1: the screen listens here for the steps of the running install / update / rollback / remove; null stops it. A host that reports none does nothing. */
    fun setProgressListener(listener: ((PluginProgress) -> Unit)?) {}
    /** PLUGIN-TREE-2b: steps into [id]'s configuration view (the hook 2c wires); a host without one does nothing. */
    fun openConfig(id: String) {}
}

/**
 * PLUGIN-PROGRESS-1: one live step of a plugin change. [step] is Downloading / Verifying / Installing / Reloading; [done] and
 * [total] are bytes while the size is known (-1 = unknown, the bar is indeterminate).
 */
data class PluginProgress(val id: String, val step: String, val done: Long = -1, val total: Long = -1)

/**
 * One installed or bundled plugin. [cls] = official / community / custom. PLUGINS-UI-1: [description] is module.json's
 * (empty = none), [contributes] what it adds to the window (`tab <id>`, `panel <side>: <id>`, `block <slot>: <name>`),
 * [requiredBy] the enabled plugins that declare it a dependency (turning it off stops them). PLUGIN-TREE-2b: [enabled] is the OWN flag.
 */
data class PluginView(
    val id: String, val version: String, val cls: String, val bundled: Boolean, val enabled: Boolean,
    val license: String?, val signer: String?, val requested: List<String>, val granted: List<String>,
    val canRollback: Boolean, val yanked: String?,
    val description: String = "", val contributes: List<String> = emptyList(), val requiredBy: List<String> = emptyList(),
    /** PLUGIN-TREE-2b: the plugin tree. [parents] / [children] are direct; [heldOffBy] = the parents that are all off, when that holds this one off (its own [enabled] flag stays as set). */
    val parents: List<String> = emptyList(), val children: List<String> = emptyList(), val heldOffBy: List<String> = emptyList(),
    /** MASCOT-LICENSE-1: the loader's reason when it refuses this plugin (it is installed but does not load); null = not refused. */
    val refused: String? = null,
)

/** A newer version offered for an installed plugin; [newGrants] non-empty = "asks for new permissions". */
data class UpdateView(val id: String, val from: String, val to: String, val newGrants: List<String>)

/**
 * One catalog / plugin list entry. PLUGIN-REPO-1: [name] = the display name (null = the id), [listName] = the list it came
 * from, [repo] = its repository, [fromRepo] = it installs from that repo's latest release ([version] is then null).
 */
data class HitView(
    val id: String, val source: String, val cls: String, val version: String?, val description: String, val license: String?, val installed: Boolean,
    val name: String? = null, val tags: List<String> = emptyList(), val repo: String? = null, val listName: String? = null, val fromRepo: Boolean = false,
)

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
