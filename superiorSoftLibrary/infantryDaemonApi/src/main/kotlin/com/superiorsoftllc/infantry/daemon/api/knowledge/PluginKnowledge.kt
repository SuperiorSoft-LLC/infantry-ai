// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.knowledge

import com.superiorsoftllc.infantry.plugin.api.KnowledgeDeclaration
import com.superiorsoftllc.infantry.plugin.api.KnowledgeDeclarations

/**
 * PK-1 (DaemonApi 1.67.0, additive; plan CAL-PLAN-2 B2, docs/wiki/plugin-knowledge.md): one piece of plugin knowledge, the run-time
 * twin of module.json `knowledge` ([KnowledgeDeclaration]). [module] is the contributing module: a provider stamps it (from
 * `services.provideScoped`, or from the manifest it read) and ignores what a contributor wrote there. [body] holds the details
 * (a declared entry's file text); the scope fields follow [KnowledgeDeclarations.inScope].
 */
data class KnowledgeEntry(
    val id: String,
    val summary: String,
    val body: String = "",
    val tools: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val skipRoles: List<String> = emptyList(),
    val projects: List<String> = emptyList(),
    val module: String = "",
) {
    /** Whether a session of [role] in [projectId] is in this entry's scope. */
    fun appliesTo(role: String?, projectId: String?): Boolean = KnowledgeDeclarations.inScope(roles, skipRoles, projects, role, projectId)

    companion object {
        /** The entry of declaration [d] of [module], with [body] (its file text, read by the caller). */
        fun of(module: String, d: KnowledgeDeclaration, body: String = ""): KnowledgeEntry =
            KnowledgeEntry(d.id.trim(), d.summary.trim(), body, d.tools.map { it.trim() }, d.roles, d.skipRoles, d.projects, module)
    }
}

/**
 * PK-1 (DaemonApi 1.67.0, additive; plan CAL-PLAN-2 B2): the registry for knowledge a plugin computes at run time (it depends on
 * config or state: "you can reach these servers"). Found with `services.find(PluginKnowledge::class)`; the provider publishes it
 * with `services.provideScoped`, so each entry is stamped with the contributing module. Null = no provider: nothing is briefed.
 */
interface PluginKnowledge {
    /** Adds (or replaces, by module and [KnowledgeEntry.id]) [entry]. Closing the handle, or unloading the contributor, removes it. */
    fun contribute(entry: KnowledgeEntry): AutoCloseable
}

/**
 * PK-1: the character budget of one briefing section ([AgentBriefing.section]). The core always passes its settings' values
 * (`limits.knowledge.plugins.*` in the core LimitRegistry, the one place the limits live); the defaults here only mirror them.
 */
data class BriefingBudget(
    /** The most characters of the whole section (setting `limits.knowledge.plugins.maxChars`); 0 = no cap. */
    val maxChars: Int = 1500,
    /** The most characters of one entry's summary (setting `limits.knowledge.plugins.summaryMaxChars`); 0 = no cap. */
    val summaryMaxChars: Int = 200,
)

/**
 * PK-1 (DaemonApi 1.67.0, additive; plan CAL-PLAN-2 B3.1): the consumer port of the plugin briefing. The core asks it once per role
 * session, at spawn, and appends the answer after the shared knowledge, frozen with the persona, so a running session does not
 * drift. Found with `services.find(AgentBriefing::class)`; no provider = no section (the same rule as [Knowledge]). A provider
 * renders with [KnowledgeBriefing.render] so every provider briefs alike.
 */
interface AgentBriefing {
    /** The PLUGIN TOOLS section for a session of [role] (null = none) in [projectId] (null = none), within [budget]; null = nothing to say. */
    fun section(role: String?, projectId: String?, budget: BriefingBudget = BriefingBudget()): String?
}

/**
 * PK-1 (DaemonApi 1.67.0; moved out of the core's SharedKnowledge): the one parser of per-section skip markers, shared by the core
 * KNOWLEDGE.md and plugin knowledge files. A `## ` section whose heading line carries `<!-- skip: roleA,roleB -->` is left out
 * for those roles; no role, or an unlisted one, gets every section. Markers never show.
 */
object KnowledgeSections {
    private val SKIP = Regex("""<!--\s*skip:\s*([^>]*?)\s*-->""")
    private val SECTION = Regex("(?m)^(?=## )")

    /** [text] for [role]: the sections it skips removed, markers stripped, trimmed. */
    fun textFor(text: String, role: String?): String {
        val r = role?.trim()?.lowercase()
        return text.split(SECTION).filter { part ->
            val skip = SKIP.find(part.lineSequence().first())?.groupValues?.get(1)
            skip == null || r == null || skip.split(',').none { it.trim().lowercase() == r }
        }.joinToString("") { SKIP.replace(it, "").replace(" \n", "\n") }.trim()
    }
}

/**
 * PK-1 (plan CAL-PLAN-2 B3.1): renders the PLUGIN TOOLS section from knowledge entries. Pure; every [AgentBriefing] provider uses it.
 * One line per entry in scope (`- <summary> (plugin:<Module>/<id>)`, the summary cut to [BriefingBudget.summaryMaxChars]);
 * role-specific entries (their `roles` names the role itself) come first, then the rest in the given order. Lines that do not fit
 * [BriefingBudget.maxChars] are folded into one "+N more" line naming them (or, when even the names do not fit, only the count),
 * so an agent always knows more is there and how to look it up.
 */
object KnowledgeBriefing {
    const val HEADER = "PLUGIN TOOLS (from installed plugins; details: infantry memory show plugin:<Module>/<id>)"
    private const val SEARCH = "infantry memory search plugin:"
    private val SPACE = Regex("\\s+")

    /** The details note name of [entry]: `plugin:<Module>/<id>`. */
    fun noteName(entry: KnowledgeEntry): String = "plugin:${entry.module}/${entry.id}"

    /** The section for [role] in [projectId] from [entries] (out-of-scope ones are dropped); null when none is in scope. */
    fun render(entries: List<KnowledgeEntry>, role: String?, projectId: String?, budget: BriefingBudget = BriefingBudget()): String? {
        val inScope = entries.filter { it.summary.isNotBlank() && it.appliesTo(role, projectId) }
        if (inScope.isEmpty()) return null
        val (specific, general) = inScope.partition { KnowledgeDeclarations.roleSpecific(it.roles, role) }
        val ordered = specific + general
        val max = budget.maxChars.takeIf { it > 0 } ?: Int.MAX_VALUE
        val lines = ordered.map { "- " + cut(it.summary.trim().replace(SPACE, " "), budget.summaryMaxChars) + " (" + noteName(it) + ")" }
        val out = StringBuilder(HEADER)
        var shown = 0
        while (shown < lines.size) {
            val after = out.length + 1 + lines[shown].length
            val rest = ordered.size - shown - 1
            // A line goes in only when what would still follow it (its "+N more" line) fits too.
            val reserve = if (rest == 0) 0 else 1 + more(ordered.drop(shown + 1), max - after - 1).length
            if (after + reserve > max) break
            out.append('\n').append(lines[shown])
            shown++
        }
        if (shown < ordered.size) out.append('\n').append(more(ordered.drop(shown), max - out.length - 1))
        return cut(out.toString(), max)
    }

    /** The "+N more" line for [rest]: with their note names when that fits [room], else the count and the search command only. */
    private fun more(rest: List<KnowledgeEntry>, room: Int): String {
        val named = "+${rest.size} more: " + rest.joinToString(", ") { noteName(it) } + " ($SEARCH)"
        return if (named.length <= room) named else "+${rest.size} more: $SEARCH"
    }

    /** [text] cut to [max] characters (0 or less = no cap), ending with "..." when cut. */
    fun cut(text: String, max: Int): String =
        if (max <= 0 || text.length <= max) text else if (max <= 3) text.take(max) else text.take(max - 3).trimEnd() + "..."
}
