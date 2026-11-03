// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.attention.OwnerAsks
import com.superiorsoftllc.infantry.daemon.api.plugins.CatalogSummary
import com.superiorsoftllc.infantry.daemon.api.plugins.PluginCatalogReader
import com.superiorsoftllc.infantry.daemon.api.plugins.PluginRequestResult
import com.superiorsoftllc.infantry.daemon.api.plugins.PluginShowResult
import com.superiorsoftllc.infantry.daemon.api.plugins.PluginSummary
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginManager
import com.superiorsoftllc.infantry.daemon.plugins.manager.PluginRow
import java.util.concurrent.ConcurrentHashMap

/**
 * PLUGIN-MCP-1: [PluginCatalogReader] over [PluginManager] on this host's plugins root. Reads only; [request] inspects the plugin,
 * asks the owner (through [asks], the owner-question service) with the grants, licence, class and signer in the question text, and
 * installs with exactly those grants only when the owner picks [INSTALL]. The inspection token pins the bytes the owner saw.
 */
class LocalPluginCatalog(
    private val manager: PluginManager,
    private val asks: () -> OwnerAsks?,
    private val log: (String) -> Unit = {},
) : PluginCatalogReader {
    private class Pending(val ref: String, val id: String, val grants: Set<String>, val token: String)

    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var subscription: AutoCloseable? = null
    @Volatile private var subscribedTo: OwnerAsks? = null

    override fun list(): List<PluginSummary> {
        val offers = runCatching { manager.checkUpdates().associateBy { it.id } }.getOrDefault(emptyMap())
        return manager.list().map { summary(it, offers[it.id]?.to) }
    }

    override fun status(id: String): PluginSummary? {
        val row = manager.status(id) ?: return null
        return summary(row, runCatching { manager.checkUpdates() }.getOrDefault(emptyList()).firstOrNull { it.id == id }?.to)
    }

    private fun summary(r: PluginRow, updateTo: String?) = PluginSummary(
        id = r.id, version = r.version, cls = r.cls.label, tier = r.tier.name.lowercase(), bundled = r.bundled, enabled = r.enabled,
        grants = r.granted, requested = r.requested, license = r.license, source = r.source, yanked = r.yanked, updateTo = updateTo,
    )

    override fun search(query: String, tag: String): List<CatalogSummary> =
        manager.search(query, setOfNotNull(tag.trim().takeIf { it.isNotEmpty() })).map {
            CatalogSummary(
                id = it.entry.id, description = it.entry.description, tags = it.entry.tags, cls = it.cls.label, source = it.source,
                license = it.entry.license, latest = it.latest?.version, grants = it.latest?.grants.orEmpty(), signer = it.latest?.signer,
            )
        }

    override fun show(ref: String): PluginShowResult {
        val (insp, refused) = manager.inspect(ref)
        if (insp == null) return PluginShowResult(problem = refused?.let { "${it.code}: ${it.message}" } ?: "could not check $ref")
        return PluginShowResult(
            id = insp.id, version = insp.version, cls = insp.cls.label, tier = insp.tier.name.lowercase(), license = insp.license,
            signer = insp.signer, grants = insp.grants, warnings = insp.warnings, source = insp.source,
        )
    }

    override fun request(ref: String, reason: String, askerSessionId: String?): PluginRequestResult {
        val session = askerSessionId?.takeIf { it.isNotBlank() }
            ?: return PluginRequestResult(null, "no session: the request needs the asking agent's session to deliver the answer to")
        val service = asks() ?: return PluginRequestResult(null, "no owner-question service is loaded; ask the owner directly")
        val (insp, refused) = manager.inspect(ref)
        if (insp == null) return PluginRequestResult(null, refused?.let { "${it.code}: ${it.message}" } ?: "could not check $ref")
        subscribe(service)
        val grants = insp.grants
        val text = buildString {
            append("Install plugin ").append(insp.id).append(' ').append(insp.version).append("?\n")
            append("Class: ").append(insp.cls.label).append(" (tier ").append(insp.tier.name.lowercase()).append(")\n")
            append("Licence: ").append(insp.license ?: "not stated").append('\n')
            append("Signer: ").append(insp.signer ?: "unsigned").append('\n')
            append("Grants: ").append(if (grants.isEmpty()) "none" else grants.joinToString(", ")).append('\n')
            if (insp.warnings.isNotEmpty()) append("Warnings: ").append(insp.warnings.joinToString("; ")).append('\n')
            if (reason.isNotBlank()) append("Agent's reason: ").append(reason.trim().take(REASON_SHOWN))
        }
        val id = service.ask(
            session, text,
            listOf(INSTALL to "Install ${insp.id} with exactly these grants", DECLINE to "Install nothing"),
            DECLINE, "Nothing is installed unless the owner picks the install option.",
        )
        pending[id] = Pending(ref, insp.id, grants.toSet(), insp.token)
        return PluginRequestResult(id, "asked the owner (question $id); nothing is installed until it is answered")
    }

    private fun subscribe(service: OwnerAsks) {
        if (subscribedTo === service) return
        synchronized(this) {
            if (subscribedTo === service) return
            subscription?.close()
            subscribedTo = service
            subscription = service.onResolved { questionId, answer -> resolved(questionId, answer) }
        }
    }

    private fun resolved(questionId: String, answer: String?) {
        val p = pending.remove(questionId) ?: return
        if (answer != INSTALL) return
        val r = runCatching { manager.install(p.ref, p.grants, p.token) }.getOrElse { log("plugin install of ${p.id} failed: ${it.message}"); return }
        log("plugin install of ${p.id} after owner yes: ${if (r.ok) "ok" else "${r.code}: "}${r.message}${if (r.restartNeeded) " (restart needed)" else ""}")
    }

    companion object {
        const val INSTALL = "Install with these grants"
        const val DECLINE = "Do not install"
        private const val REASON_SHOWN = 400
    }
}
