// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins.manager

import com.superiorsoftllc.infantry.plugin.api.ActionResult
import com.superiorsoftllc.infantry.plugin.api.HitView
import com.superiorsoftllc.infantry.plugin.api.InspectOutcome
import com.superiorsoftllc.infantry.plugin.api.InspectView
import com.superiorsoftllc.infantry.plugin.api.PluginManagerPort
import com.superiorsoftllc.infantry.plugin.api.PluginView
import com.superiorsoftllc.infantry.plugin.api.RefreshView
import com.superiorsoftllc.infantry.plugin.api.SourceView
import com.superiorsoftllc.infantry.plugin.api.UpdateView

/** PLUGIN-UI-1: [PluginManager] as the UI's [PluginManagerPort]: the same calls the CLI makes, as plain data. */
class PluginManagerPortAdapter(private val manager: PluginManager) : PluginManagerPort {
    private fun ManagerResult.view() = ActionResult(ok, message, restartNeeded, requested)

    override fun list() = manager.list().map {
        PluginView(it.id, it.version, it.cls.label, it.bundled, it.enabled, it.license, it.signer, it.requested, it.granted, it.history.isNotEmpty(), it.yanked,
            it.description, it.contributes, it.requiredBy)
    }

    override fun updates() = manager.checkUpdates().map { UpdateView(it.id, it.from, it.to, it.newGrants) }

    override fun search(query: String): List<HitView> {
        val installed = manager.list().map { it.id }.toSet()
        return manager.search(query).map { HitView(it.entry.id, it.source, it.cls.label, it.latest?.version, it.entry.description, it.entry.license, it.entry.id in installed) }
    }

    override fun inspect(ref: String): InspectOutcome {
        val (i, refused) = manager.inspect(ref)
        return if (i == null) InspectOutcome(error = refused?.message ?: "could not check $ref")
        else InspectOutcome(InspectView(i.id, i.version, i.cls.label, i.license, i.signer, i.grants, i.warnings, i.token))
    }

    override fun install(ref: String, consent: Set<String>, token: String?) = manager.install(ref, consent, token).view()
    override fun setEnabled(id: String, enabled: Boolean) = manager.setEnabled(id, enabled).view()
    override fun update(id: String, consent: Set<String>?) = manager.update(id, consent).view()
    override fun rollback(id: String) = manager.rollback(id).view()
    override fun remove(id: String) = manager.remove(id).view()
    override fun sources() = manager.sources().map { SourceView(it.id, it.cls, it.url ?: it.dir.orEmpty(), it.enabled) }

    override fun addSource(id: String, cls: String, location: String): ActionResult {
        val web = location.startsWith("http://") || location.startsWith("https://")
        return manager.addSource(CatalogSource(id = id, cls = cls, url = location.takeIf { web }, dir = location.takeUnless { web })).view()
    }

    override fun removeSource(id: String) = manager.removeSource(id).view()
    override fun refresh() = manager.refresh().map { RefreshView(it.source, it.ok, it.stale, it.plugins, it.message) }
}
