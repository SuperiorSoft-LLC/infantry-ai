// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import com.superiorsoftllc.infantry.plugin.api.RoleDeclaration
import com.superiorsoftllc.infantry.protocol.RoleSave
import java.io.File

/**
 * ROLE-PLUGIN-1 (docs/CARD-REGISTRY.md section 3, ticket 6): roles a module declares in its module.json `roles`, seeded into the
 * catalog of [RoleService], and the briefing a role's preferences add.
 *
 * - Seed: every loaded module's declared roles, in load order, through [RoleService.seed] (free id = added; the module's own
 *   unedited copy = refreshed when the declaration changed; anything else kept). The role file carries `plugin: <module>`.
 * - Uninstall: nothing is written. The role stays, and reads disabled while its module is not loaded ([RoleService] overlay).
 * - Preferences: a role's `cards` / `tools` name ids from any module; [briefing] names only the ones that are loaded at spawn
 *   time, so a removed module degrades to a shorter line, never an error. They never gate a tool or a card.
 */
object PluginRoles {
    /** A persona given as a path of a text file inside the module folder (`roles/news-agent.md`), as a card `spec` is. */
    private val PERSONA_FILE = Regex("""^[^\r\n]+\.(md|txt)$""")

    /** One sync's outcome: the ids written (added or refreshed) and the declarations that were refused, with the reason. */
    data class Outcome(val written: List<String>, val problems: List<String>)

    /** Seeds the declared roles of [modules]; never throws (a bad declaration is reported in [Outcome.problems]). */
    fun sync(service: RoleService, modules: List<LoadedModule>): Outcome {
        val written = ArrayList<String>()
        val problems = ArrayList<String>()
        for (m in modules) for (d in m.manifest.roles) {
            runCatching { service.seed(m.id, request(d, m.dir)) }
                .onSuccess { if (it != RoleService.Seeded.KEPT) written += d.id.trim() }
                .onFailure { problems += "${m.id} role '${d.id.take(40)}': ${it.message}" }
        }
        return Outcome(written, problems)
    }

    /**
     * The `role_save` shape of [d]; a blank name is the prettified id, a persona file is read from [dir]. A persona that names a
     * file which is not in the module folder is refused ([IllegalArgumentException]): the role is not seeded until it is there,
     * rather than seeded with the path as its notes. (A packaged install carries only module.json, lib/ and layout/, so a
     * published plugin writes the persona inline.)
     */
    fun request(d: RoleDeclaration, dir: File?): RoleSave = RoleSave(
        id = d.id.trim(),
        name = d.name.trim().ifEmpty { RoleCatalog.prettify(d.id.trim()) },
        color = d.color.trim().takeIf { it.isNotEmpty() },
        persona = persona(d.persona, dir)
            ?: throw IllegalArgumentException("persona file '${d.persona.trim().take(80)}' is not in the module folder (write the persona inline)"),
        icon = d.icon.trim(),
        description = d.description.trim(),
        cards = d.cards,
        tools = d.tools,
    )

    /**
     * [value] as a file inside [dir] when it is a file name (`*.md` / `*.txt`, one line), read from there (never outside it: the
     * canonical path must stay under the folder), capped at [RoleService.MAX_PERSONA_LENGTH] characters; null when that file is
     * not there. Any other value is the text itself.
     */
    fun persona(value: String, dir: File?): String? {
        val v = value.trim()
        if (!PERSONA_FILE.matches(v)) return value
        if (dir == null) return null
        val root = dir.canonicalFile
        val file = File(root, v).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
        return file.readText(Charsets.UTF_8).take(RoleService.MAX_PERSONA_LENGTH + 1)
    }

    /**
     * The text a new session of [role] receives: its persona, then (when it prefers any loaded tool or card) one short block naming
     * them. [tools] are the tool names served now (the live registry plus loaded capability ids); [cards] the loaded card types.
     */
    fun briefing(role: Role, tools: Set<String>, cards: Set<String>): String {
        val t = role.tools.filter { it in tools }
        val c = role.cards.filter { it in cards }
        if (t.isEmpty() && c.isEmpty()) return role.persona
        val block = buildString {
            append("Preferred for this role (use them when they fit; nothing here is required):")
            if (t.isNotEmpty()) append("\n- tools: ").append(t.joinToString(", "))
            if (c.isNotEmpty()) append("\n- cards: ").append(c.joinToString(", ") { "card:$it" })
                .append(" (`infantry card show <type>` gives a card's format)")
        }
        return if (role.persona.isBlank()) block else role.persona + "\n\n" + block
    }

    /** The card types and tool-like ids (capabilities, declared daemon tools) the loaded [modules] offer. */
    fun offered(modules: List<LoadedModule>): Pair<Set<String>, Set<String>> {
        val cards = modules.flatMap { m -> m.manifest.cards.map { it.type.trim() } }.toSet()
        val tools = modules.flatMap { m -> m.manifest.capabilities.map { it.id } + m.manifest.daemon?.tools.orEmpty().map { it.name } }.toSet()
        return cards to tools
    }
}
