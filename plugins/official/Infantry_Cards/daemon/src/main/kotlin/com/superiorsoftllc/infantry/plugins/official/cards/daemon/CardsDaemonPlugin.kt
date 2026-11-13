// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.cards.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * CARD-3 (docs/CARD-REGISTRY.md 2.3): the chat card directory. Every loaded module's module.json `cards` descriptors, offered on
 * demand as two capabilities: `cards.list` (one line per card type) and `cards.show` (the spec an agent needs to write one).
 * They are reached through the existing `infantry_capability_call` and the `infantry card list|show` CLI, so a session that never
 * uses a card pays no tokens for them (no tool schema, no prompt list). Open to every agent and role while the card's plugin is
 * loaded; a role may name a card type in its persona, and with the plugin gone the fence simply shows as a code block.
 */
class CardsDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val maxSpec = { api.config(SPEC_MAX_BYTES_KEY)?.toIntOrNull()?.coerceAtLeast(MIN_SPEC_BYTES) ?: DEFAULT_SPEC_MAX_BYTES }
        api.capabilities.handle(LIST) { list(api.plugins.loaded()) }
        api.capabilities.handle(SHOW) { call -> show(api.plugins.loaded(), (call.args["type"] as? JsonPrimitive)?.contentOrNull, maxSpec()) }
    }

    /** One card type as the directory sees it: the first loaded module that declares [type] owns it. */
    data class Entry(val type: String, val module: String, val summary: String, val spec: String, val hosts: List<String>, val dir: File?)

    companion object {
        const val LIST = "cards.list"
        const val SHOW = "cards.show"
        /** Setting: the most bytes of a spec file `cards.show` returns (a spec is meant to be short). */
        const val SPEC_MAX_BYTES_KEY = "cards.specMaxBytes"
        const val DEFAULT_SPEC_MAX_BYTES = 16_384
        private const val MIN_SPEC_BYTES = 256
        /** A card type: the fence's `card:<type>` suffix. */
        val TYPE = Regex("^[a-z][a-z0-9_-]*$")
        private val SPEC_FILE = Regex("""^[^\r\n]+\.(md|txt|json)$""")

        /** The card types of [modules] (load order; the first declaration of a type wins, a malformed type is skipped). */
        fun entries(modules: List<LoadedModule>): List<Entry> {
            val seen = LinkedHashMap<String, Entry>()
            for (m in modules) for (c in m.manifest.cards) {
                val type = c.type.trim()
                if (!TYPE.matches(type) || type in seen) continue
                seen[type] = Entry(type, m.id, c.summary.trim(), c.spec, c.images?.hosts.orEmpty(), m.dir)
            }
            return seen.values.toList()
        }

        fun list(modules: List<LoadedModule>): JsonObject = buildJsonObject {
            put("cards", buildJsonArray {
                entries(modules).forEach { e -> add(buildJsonObject { put("type", e.type); put("module", e.module); put("summary", e.summary) }) }
            })
        }

        fun show(modules: List<LoadedModule>, type: String?, maxSpecBytes: Int): JsonObject {
            val t = type?.trim()?.removePrefix("card:").orEmpty()
            require(t.isNotEmpty()) { "type is required ($LIST names the installed card types)" }
            val all = entries(modules)
            val e = all.firstOrNull { it.type == t } ?: throw IllegalArgumentException(
                "no card type '${t.take(60)}' is installed" + if (all.isEmpty()) " (no loaded plugin declares a card)" else "; installed: ${all.joinToString(", ") { it.type }}",
            )
            return buildJsonObject {
                put("type", e.type)
                put("module", e.module)
                put("summary", e.summary)
                put("fence", "```card:${e.type}")
                put("spec", specText(e, maxSpecBytes))
                if (e.hosts.isNotEmpty()) put("imageHosts", buildJsonArray { e.hosts.forEach { add(JsonPrimitive(it)) } })
            }
        }

        /**
         * [Entry.spec] as text: a file path inside the module folder (`cards/news.md`) is read (at most [maxBytes]); any other value
         * is the spec itself. A blank spec, or a file that is not there, falls back to the summary and says so.
         */
        fun specText(e: Entry, maxBytes: Int): String {
            val spec = e.spec.trim()
            if (spec.isEmpty()) return e.summary.ifEmpty { "(no spec declared)" }
            if (!SPEC_FILE.matches(spec)) return spec
            val dir = e.dir?.canonicalFile
            val file = dir?.let { File(it, spec).canonicalFile }?.takeIf { f -> f.path.startsWith(dir.path + File.separator) && f.isFile }
                ?: return "${e.summary}\n(spec file $spec is not in ${e.module}'s folder)".trim()
            val bytes = file.inputStream().use { it.readNBytes(maxBytes + 1) }
            val text = String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8)
            return if (bytes.size > maxBytes) "$text\n(spec cut at $maxBytes bytes)" else text
        }
    }
}
