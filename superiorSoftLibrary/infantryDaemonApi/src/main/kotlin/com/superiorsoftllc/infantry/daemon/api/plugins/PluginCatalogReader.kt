// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.plugins

/**
 * PLUGIN-MCP-1 (DaemonApi 1.36.0, additive; docs/PLUGIN-MANAGER.md section 10): a READ-ONLY view of the plugin manager on this host's
 * plugins root, plus [request], the only way an agent asks for an install. Nothing here installs, enables or grants by itself: [request]
 * raises an owner question and the daemon installs exactly the grants it showed, only on the owner's yes. Official / core tier, or a
 * community module with the grant `plugins.read`. A daemon without a manager answers [NoPluginCatalog] (empty / refused).
 */
interface PluginCatalogReader {
    /** Every plugin on this host (bundled and installed). */
    fun list(): List<PluginSummary>

    /** Catalog entries matching [query] (id, description or tag) and carrying every tag in [tag] (blank = any). */
    fun search(query: String = "", tag: String = ""): List<CatalogSummary>

    /** What [ref] (`[source:]id[@version]`, a path or a URL) is, checked and NOT installed; null with [PluginShowResult.problem] when it does not check out. */
    fun show(ref: String): PluginShowResult

    /** One installed plugin, or null. */
    fun status(id: String): PluginSummary?

    /**
     * Asks the owner to install [ref] with exactly the grants it requests. [askerSessionId] is the session that hears the answer.
     * Returns at once: [PluginRequestResult.questionId] names the owner question; nothing is installed until it is answered "Install with these grants".
     */
    fun request(ref: String, reason: String, askerSessionId: String?): PluginRequestResult
}

/** One plugin on this host. [updateTo] = the newer catalog version on offer, null = none. */
data class PluginSummary(
    val id: String,
    val version: String,
    val cls: String,
    val tier: String,
    val bundled: Boolean,
    val enabled: Boolean,
    val grants: List<String>,
    val requested: List<String>,
    val license: String?,
    val source: String?,
    val yanked: String? = null,
    val updateTo: String? = null,
)

/** One catalog entry's latest compatible version. */
data class CatalogSummary(
    val id: String,
    val description: String,
    val tags: List<String>,
    val cls: String,
    val source: String,
    val license: String?,
    val latest: String?,
    val grants: List<String>,
    val signer: String?,
)

/** An inspection (nothing installed). [problem] != null = refused, the other fields are blank. */
data class PluginShowResult(
    val id: String = "",
    val version: String = "",
    val cls: String = "",
    val tier: String = "",
    val license: String? = null,
    val signer: String? = null,
    val grants: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val source: String = "",
    val problem: String? = null,
)

/** [questionId] null = no question was raised ([message] says why). */
data class PluginRequestResult(val questionId: String?, val message: String)

/** A daemon without a plugin manager. */
object NoPluginCatalog : PluginCatalogReader {
    override fun list(): List<PluginSummary> = emptyList()
    override fun search(query: String, tag: String): List<CatalogSummary> = emptyList()
    override fun show(ref: String) = PluginShowResult(problem = "this daemon has no plugin manager")
    override fun status(id: String): PluginSummary? = null
    override fun request(ref: String, reason: String, askerSessionId: String?) = PluginRequestResult(null, "this daemon has no plugin manager")
}
