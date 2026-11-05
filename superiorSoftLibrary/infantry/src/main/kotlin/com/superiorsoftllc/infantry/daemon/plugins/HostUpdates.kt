// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.updates.Credential
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateApplier
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateComponent
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateSourceType
import com.superiorsoftllc.infantry.daemon.api.updates.UpdateState
import com.superiorsoftllc.infantry.daemon.api.updates.Updates
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.api.UpdateSourceRef
import com.superiorsoftllc.infantry.update.UpdateEngine
import java.io.File

/**
 * UPDATE-ENGINE-1 (docs/wiki/Updates.md): the host side of `DaemonApi.updates`. It owns the one [UpdateEngine], builds the
 * component list (the app, then every resolved module, by name) and applies the rules a plugin is held to:
 *
 *  - a source type is registered only when the module declared it in `daemon.updateSources` (a community module also needs
 *    the grant `daemon.updateSource:<type>`); appliers are official / core tier only;
 *  - a module sets the source of its own component only; the app's only an official module;
 *  - a credential is looked up only under names the component may use: for the app any; for a module the env / secret
 *    names it declared in `daemon.env` / `daemon.secrets` (community: and was granted), or names carrying its vendor prefix (`ACME_...` env,
 *    `acme...` secret for `Acme_Notes`). A community module never gets the app's own `INFANTRY` names that way.
 */
class HostUpdates(
    private val modules: () -> List<Module>,
    private val coreVersion: () -> String,
    private val env: (String) -> String?,
    private val secret: (String) -> String?,
    stagingRoot: File,
    log: (String) -> Unit,
) {
    /** One resolved module as the engine needs it. */
    data class Module(val name: String, val tier: Tier, val manifest: ModuleManifest, val allow: Set<String> = emptySet())

    val engine = UpdateEngine(
        declared = ::components,
        credential = ::credential,
        stagingRoot = stagingRoot,
        log = log,
    )

    private fun components(): List<UpdateComponent> =
        listOf(UpdateComponent(UpdateComponent.CORE_ID, UpdateComponent.KIND_CORE, "Infantry AI", coreVersion(), source = null)) +
            modules().map { m -> UpdateComponent(m.name, UpdateComponent.KIND_PLUGIN, m.name, m.manifest.version, m.manifest.update?.source) }

    private fun credential(c: UpdateComponent, ref: UpdateSourceRef): Credential? {
        val names = ref.credential ?: return null
        val module = modules().firstOrNull { it.name == c.id }
        val envName = names.env?.trim()?.takeIf { it.isNotEmpty() && (module == null && c.kind == UpdateComponent.KIND_CORE || mayUseEnv(module, it)) }
        val secretName = names.secret?.trim()?.takeIf { it.isNotEmpty() && (module == null && c.kind == UpdateComponent.KIND_CORE || mayUseSecret(module, it)) }
        val value = envName?.let(env)?.takeIf { it.isNotBlank() } ?: secretName?.let(secret)?.takeIf { it.isNotBlank() }
        return value?.let(::Credential)
    }

    /** A plugin's own view: [api] checks its declarations before it reaches the engine. */
    fun scoped(moduleId: String, tier: Tier, declaredTypes: List<String>, allow: Set<String>): Updates = object : Updates {
        override fun registerSource(source: UpdateSourceType): AutoCloseable {
            check(source.type in declaredTypes) { "update source type '${source.type}' is not declared in daemon.updateSources" }
            check(tier != Tier.COMMUNITY || "daemon.updateSource:${source.type}" in allow) { "missing grant 'daemon.updateSource:${source.type}' in modules.json" }
            return engine.registerSource(source)
        }

        override fun registerApplier(applier: UpdateApplier): AutoCloseable {
            check(tier != Tier.COMMUNITY) { "a community module may not install updates (registerApplier)" }
            return engine.registerApplier(applier)
        }

        override fun setSource(componentId: String, source: UpdateSourceRef?) {
            val own = componentId == moduleId
            val app = componentId == UpdateComponent.CORE_ID && tier != Tier.COMMUNITY
            check(own || app) { "$moduleId may set the update source of its own component only" }
            engine.setSource(componentId, source)
        }

        override fun sourceTypes(): List<String> = engine.sourceTypes()
        override fun components(): List<UpdateComponent> = engine.components()
        override fun check(componentId: String): UpdateState = engine.check(componentId)
        override fun checkAll(): List<UpdateState> = engine.checkAll()
        override fun fetch(componentId: String): UpdateState = engine.fetch(componentId)
        override fun apply(componentId: String): UpdateState {
            check(tier != Tier.COMMUNITY) { "a community module may not install updates (apply)" }
            return engine.apply(componentId)
        }
    }

    companion object {
        private const val APP_VENDOR = "infantry"

        private fun vendor(module: Module): String? =
            module.name.substringBefore('_', "").lowercase().takeIf { it.isNotEmpty() && !(module.tier == Tier.COMMUNITY && it == APP_VENDOR) }

        fun mayUseEnv(module: Module?, name: String): Boolean {
            module ?: return false
            if (name in module.manifest.daemon?.env.orEmpty() && (module.tier != Tier.COMMUNITY || "daemon.env:$name" in module.allow)) return true
            val v = vendor(module) ?: return false
            return name.startsWith(v.uppercase() + "_")
        }

        fun mayUseSecret(module: Module?, name: String): Boolean {
            module ?: return false
            if (name in module.manifest.daemon?.secrets.orEmpty() && (module.tier != Tier.COMMUNITY || "daemon.secret:$name" in module.allow)) return true
            val v = vendor(module) ?: return false
            return name.lowercase().startsWith(v)
        }
    }
}
