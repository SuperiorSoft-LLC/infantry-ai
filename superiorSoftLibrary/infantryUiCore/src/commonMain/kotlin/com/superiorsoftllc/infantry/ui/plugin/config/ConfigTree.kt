// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.OverrideBlock
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import com.superiorsoftllc.infantry.ui.plugin.CORE_SHELL_MODULE
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One field of the merged tree: declared by [module] for [node], shown under tab [group]. */
data class DeclaredField(val node: String, val module: String, val group: String, val field: ConfigFieldDeclaration) {
    val key: String get() = this.field.key
}

/** CONFIG-3: one accepted [ConfigDeclaration] and the [module] that declared it (core's is [CORE_SHELL_MODULE]). */
@Serializable
data class ModuleDeclaration(val module: String, val declaration: ConfigDeclaration)

/** A module's declarations, as the merge sees them: who, how trusted, and which node ids it may declare fields on. */
class ConfigSource(val module: String, val tier: Tier, val declarations: List<ConfigDeclaration>, val owns: (node: String) -> Boolean)

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s B): the ONE config tree. Core's `config.json` ([CoreConfig]) and every
 * loaded module's module.json `config`, merged by node id; the hierarchy ([parents]) is the block tree's. Built once per
 * factory; [problems] holds each refusal, one line (never a silent drop).
 */
class ConfigTree(
    /** Every declared field, by node id, in merge order (core first, then modules in load order). */
    val fields: Map<String, List<DeclaredField>>,
    /** Each node's parent; a root has none. */
    val parents: Map<String, String>,
    val problems: List<String> = emptyList(),
    /** CONFIG-3: the accepted declarations in merge order, each with its module: what the compiled manifest is made of. */
    val declarations: List<ModuleDeclaration> = emptyList(),
) {
    /** [node], then its parent, up to the root. */
    fun chain(node: String): List<String> {
        val out = mutableListOf(node)
        var at = parents[node]
        while (at != null && at !in out) { out += at; at = parents[at] }
        return out
    }

    /** The nearest declaration of [key] for [node]: its own, else its parent's, up to the root. */
    fun field(node: String, key: String): DeclaredField? = chain(node).firstNotNullOfOrNull { n -> fields[n]?.firstOrNull { it.key == key } }

    /** What seeds `node_config` on first load: each declared default, at its own node (the daemon never overwrites). */
    fun seed(): List<NodeConfigEntry> =
        fields.values.flatten().mapNotNull { f -> f.field.default?.let { NodeConfigEntry(f.node, f.key, it, ConfigSetBy.SEED) } }

    /** The groups in order: core's first, then each new name a module adds (6s F). */
    val groups: List<String> get() = fields.values.flatten().map { it.group }.distinct()

    companion object {
        val EMPTY = ConfigTree(emptyMap(), emptyMap())

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a `config.json` (a list of [ConfigDeclaration]); a malformed file is one problem and no fields. */
        fun parse(text: String, problems: MutableList<String>, source: String): List<ConfigDeclaration> =
            runCatching { json.decodeFromString(ListSerializer(ConfigDeclaration.serializer()), text) }
                .getOrElse { problems += "$source: config.json does not parse (${it.message?.lineSequence()?.firstOrNull()}); ignored"; emptyList() }

        /**
         * Merges [core] and [modules] (load order). A module declares only on nodes it [ConfigSource.owns]; a second
         * declaration of a key on one node, a blank key and an unknown type with no registered editor are each a line
         * (an unknown type is kept: the view shows it read-only).
         */
        fun merge(core: List<ConfigDeclaration>, modules: List<ConfigSource>, parents: Map<String, String>, knownTypes: Set<String> = ConfigFieldTypes.BUILT_IN): ConfigTree {
            val problems = mutableListOf<String>()
            val out = LinkedHashMap<String, MutableList<DeclaredField>>()
            val accepted = mutableListOf<ModuleDeclaration>()
            val sources = listOf(ConfigSource(CORE_SHELL_MODULE, Tier.CORE, core) { true }) + modules
            for (s in sources) for (d in s.declarations) {
                if (d.locked && s.tier != Tier.CORE) { problems += "${s.module}: config for ${d.node} is locked; only core declares a locked section; refused"; continue }
                if (d.block != null && accepted.any { it.declaration.block == d.block }) { problems += "${s.module}: block ${d.block} is already placed; refused"; continue }
                if (!s.owns(d.node)) { problems += "${s.module}: config for ${d.node} refused: the module neither owns that node nor is granted it"; continue }
                val list = out.getOrPut(d.node) { mutableListOf() }
                val kept = mutableListOf<ConfigFieldDeclaration>()
                for (f in d.fields) {
                    val taken = list.firstOrNull { it.key == f.key }
                    when {
                        f.key.isBlank() -> problems += "${s.module}: a config field on ${d.node} has no key; refused"
                        taken != null -> problems += "${s.module}: config key ${f.key} on ${d.node} is already declared by ${taken.module}; refused"
                        else -> {
                            if (f.type !in knownTypes) problems += "${s.module}: config key ${f.key} on ${d.node} has unknown type ${f.type}; shown read-only"
                            list += DeclaredField(d.node, s.module, d.group, f)
                            kept += f
                        }
                    }
                }
                if (kept.isNotEmpty() || d.block != null) accepted += ModuleDeclaration(s.module, d.copy(fields = kept))
            }
            return ConfigTree(out.filterValues { it.isNotEmpty() }, parents, problems, accepted)
        }

        /** Each node's parent in [roots] (a wrap's inner children count as the wrap's; a replace has none the core knows). */
        fun parentsOf(roots: List<Block>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            fun walk(b: Block) {
                val kids = contents(b)
                for (c in kids) { if (c.id !in out) out[c.id] = b.id; walk(c) }
            }
            roots.forEach(::walk)
            return out
        }

        private fun contents(b: Block): List<Block> = if (b is OverrideBlock) { if (b.mode == OverrideModes.WRAP) contents(b.inner) else emptyList() } else b.children
    }
}
