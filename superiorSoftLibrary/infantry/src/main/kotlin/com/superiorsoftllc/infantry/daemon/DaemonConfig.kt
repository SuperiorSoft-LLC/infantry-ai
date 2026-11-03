// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.plugin.api.ConfigChangedFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigSchemaFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigScopes
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import com.superiorsoftllc.infantry.plugin.api.ConfigSetFrame
import com.superiorsoftllc.infantry.plugin.api.ConfigValuesFrame
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.host.Resolution
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.persistence.SettingRow
import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.settings.SettingsService
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.atomic.AtomicLong

/**
 * CONFIG-8 (docs/CONFIG-MANIFEST-DESIGN.md section 6): the daemon's own config manifest (host = daemon). It compiles the
 * `daemon.config` of every loaded module into [declarations] (only `scope:"daemon"` fields; a field from a community
 * module is marked `readOnly`), serves them as `config_schema` / `config_values`, and writes a value through
 * [SettingsService.set], so the declared field's validator is the one that checks it. A declared key is a flat
 * settings key; one the daemon already owns, or one an earlier module declared, is dropped.
 */
class DaemonConfig(private val settings: SettingsService, private val store: InfantryStore) {
    private val revision = AtomicLong(System.currentTimeMillis())
    @Volatile private var declared: List<ConfigDeclaration> = emptyList()
    @Volatile private var fields: Map<String, Pair<String, ConfigFieldDeclaration>> = emptyMap()
    /** DAEMON-PLUGINS-1: which module declared each field, so a daemon plugin reads only its own ([value]). */
    @Volatile private var owners: Map<String, String> = emptyMap()

    /** Told after a write with the frame to push to every other client; the daemon wires it to its client list. */
    @Volatile var onChanged: ((ConfigChangedFrame) -> Unit)? = null

    val rev: Long get() = revision.get()

    init { settings.declaredField = { key -> fields[key]?.second } }

    /** Replaces the schema from a plugin resolution; bumps [rev] when it differs. Returns the dropped-claim log lines. */
    fun load(resolution: Resolution): List<String> {
        val log = mutableListOf<String>()
        val byKey = LinkedHashMap<String, Pair<String, ConfigFieldDeclaration>>()
        val ownerOf = HashMap<String, String>()
        val out = mutableListOf<ConfigDeclaration>()
        for (status in resolution.loaded) {
            val readOnly = status.tier == Tier.COMMUNITY
            for (decl in status.manifest?.daemon?.config.orEmpty()) {
                val kept = decl.fields.filter { f ->
                    when {
                        f.scope != null && f.scope != ConfigScopes.DAEMON -> false
                        f.key in byKey || settings.isBuiltIn(f.key) -> { log += "[config] ${status.name}: daemon field '${f.key}' is already declared, ignored"; false }
                        else -> true
                    }
                }.map { it.copy(scope = ConfigScopes.DAEMON) }
                if (kept.isEmpty() && decl.block == null) continue
                kept.forEach { byKey[it.key] = decl.node to it; ownerOf[it.key] = status.name }
                out += decl.copy(fields = kept, readOnly = readOnly, locked = false)
            }
        }
        owners = ownerOf
        if (out != declared) { fields = byKey; declared = out; revision.incrementAndGet() }
        return log
    }

    fun schema() = ConfigSchemaFrame(rev, declared)

    /** DAEMON-PLUGINS-1: the stored value of field [key] as text, when [module] declared it; else null. */
    fun value(module: String, key: String): String? =
        if (owners[key] != module) null else settings.storedDeclared(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: it.toString() }

    fun values(): ConfigValuesFrame = ConfigValuesFrame(rev, fields.entries.mapNotNull { (key, nodeField) ->
        settings.storedDeclared(key)?.let { NodeConfigEntry(nodeField.first, key, it, ConfigSetBy.USER, store.settings.get(key)?.updatedAt ?: 0) }
    })

    /**
     * Writes [frame]: refused (IllegalArgumentException) when the key is not declared on that node, belongs to a read-only
     * (community) declaration, [ConfigSetFrame.expectRev] is stale, or the declared validator rejects the value. A null value resets.
     */
    fun set(frame: ConfigSetFrame, by: String = ConfigSetBy.USER): ConfigValuesFrame {
        val (node, field) = fields[frame.key] ?: throw IllegalArgumentException("unknown setting '${frame.key}'")
        require(node == frame.node) { "setting '${frame.key}' is not on node '${frame.node}'" }
        require(declared.none { it.node == node && it.readOnly && it.fields.any { f -> f.key == frame.key } }) { "setting '${frame.key}' comes from an unsigned plugin and is read-only" }
        synchronized(this) {
            require(frame.expectRev == null || frame.expectRev == rev) { "stale config write: expected rev ${frame.expectRev}, daemon is at $rev" }
            val value = frame.value
            if (value == null || value is JsonNull) {
                store.settings.put(SettingRow(key = frame.key, value = JsonNull.toString(), updatedAt = System.currentTimeMillis()))
            } else {
                settings.set(frame.key, value)
            }
            revision.incrementAndGet()
        }
        onChanged?.invoke(ConfigChangedFrame(node, frame.key, frame.value?.takeIf { it !is JsonNull }, by, rev))
        return values()
    }
}
