// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeWriter

/** What a script's header comment says (SCRIPT-ARSENAL-2). Every field but [name] and [purpose] may be empty. */
data class ScriptDoc(
    val name: String,
    val purpose: String,
    val scenario: String,
    val args: List<String>,
    val hook: String,
    val gotchas: List<String>,
    val example: String,
)

/**
 * SCRIPT-ARSENAL-2: reads the comment block at the top of a script. Layout (the 16 bundled scripts follow it):
 * line 1 `-- <name>: <purpose>`, then sections `Scenario:`, `Args:` (one `  name (type, default): meaning` per line), `Hook:`,
 * `Gotchas:` and optionally `Example:`; a continuation line starts with spaces. The shared `GOTCHAS (all scripts)` line is skipped.
 */
object ScriptHeader {
    private val SECTIONS = listOf("Scenario", "Args", "Hook", "Gotchas", "Example")

    private fun stripComment(line: String): String? {
        val t = line.trimEnd('\r')
        for (p in listOf("--", "//", "#")) if (t.startsWith(p)) return t.removePrefix(p).removePrefix(" ")
        return null
    }

    /** The doc of [source] under script name [name], or null when it has no header comment with a purpose. */
    fun parse(name: String, source: String): ScriptDoc? {
        val lines = source.lines().takeWhile { stripComment(it) != null }.mapNotNull { stripComment(it) }
        val first = lines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val purpose = if (first.startsWith("$name:")) first.removePrefix("$name:").trim() else first
        if (purpose.isEmpty()) return null
        val sec = LinkedHashMap<String, MutableList<String>>()
        var cur: String? = null
        for (raw in lines.drop(1)) {
            val head = SECTIONS.firstOrNull { raw.startsWith("$it:") }
            if (head != null) { cur = head; sec.getOrPut(head) { ArrayList() }.add(raw.removePrefix("$head:").trim()); continue }
            if (raw.trim().startsWith("GOTCHAS (all scripts)")) continue
            cur?.let { sec.getOrPut(it) { ArrayList() }.add(raw.trim()) }
        }
        fun one(k: String) = sec[k].orEmpty().filter { it.isNotEmpty() }.joinToString(" ")
        val hook = one("Hook")
        val argsJson = Regex("""args\s+(\{.*})""").find(hook)?.groupValues?.get(1)
        val example = one("Example").ifEmpty { "infantry_script_run name=$name args=${argsJson ?: "{}"}" }
        return ScriptDoc(name, purpose, one("Scenario").ifEmpty { purpose }, sec["Args"].orEmpty().filter { it.isNotEmpty() }, hook, sec["Gotchas"].orEmpty().filter { it.isNotEmpty() }, example)
    }

    /** The markdown table of docs/wiki/script-arsenal.md: name, purpose, hook, scenario, in name order. */
    fun indexTable(docs: List<ScriptDoc>): String = buildString {
        fun cell(t: String) = t.replace("|", "\\|").replace("\n", " ").ifEmpty { "-" }
        append("| Script | Purpose | Hook | Scenario |\n|---|---|---|---|\n")
        docs.sortedBy { it.name }.forEach { append("| `${it.name}` | ${cell(it.purpose)} | ${cell(it.hook)} | ${cell(it.scenario)} |\n") }
    }

    /** The generated note body. [shadowedBy] = project ids whose own copy hides the global one (global entries only). */
    fun render(doc: ScriptDoc, scope: String, project: String?, shadowedBy: List<String>): String = buildString {
        append("# ${doc.name} (Script Arsenal)\n\n")
        append("Generated from the header of the ${if (scope == ScriptStore.GLOBAL) "global script in the Script Arsenal" else "script of project $project"}. Do not edit this note: edit the script and it is regenerated.\n\n")
        append("**Purpose:** ${doc.purpose}\n\n**Scenario (when to use it):** ${doc.scenario}\n\n")
        append("**Suggested hook:** ${doc.hook.ifEmpty { "none" }}\n\n")
        append("**Args:**\n")
        if (doc.args.isEmpty()) append("- none\n") else doc.args.forEach { append("- $it\n") }
        append("\n**Gotchas:** ${doc.gotchas.joinToString(" ").ifEmpty { "none" }}\n\n")
        append("**Example run:** `${doc.example}`\n\n")
        if (scope == ScriptStore.GLOBAL) append("**Shadowed by project copies:** ${shadowedBy.joinToString().ifEmpty { "none" }}\n")
    }
}

/**
 * SCRIPT-ARSENAL-2: keeps one generated knowledge note per script, through the daemon API's [KnowledgeWriter] seam. Global scripts
 * go to [KnowledgeWriter.GLOBAL_PROJECT] (every project's note search finds them); a project script with a header gets a note in its
 * own project. Entries are named `script-arsenal-<name>`. Nothing here ever throws into a save: a missing writer is a no-op that
 * [syncAll] repairs later.
 */
class ArsenalKnowledge(
    private val writer: () -> KnowledgeWriter?,
    private val store: ScriptStore,
    private val projects: () -> List<String>,
    private val log: (String) -> Unit = {},
) {
    private fun entryName(name: String) = PREFIX + name

    private fun shadows(name: String): List<String> = projects().filter { runCatching { store.hasProjectCopy(it, name) }.getOrDefault(false) }

    private fun describe(doc: ScriptDoc) = "Script Arsenal: ${doc.purpose}. Use when: ${doc.scenario}".take(500)

    /** (Re)writes the global entry of [name], or removes it when the script or its header is gone. */
    fun refreshGlobal(name: String) {
        val w = writer() ?: return
        runCatching {
            val script = store.global?.read(name)
            val doc = script?.let { ScriptHeader.parse(name, it.source) }
            if (doc == null) { w.delete(KnowledgeWriter.GLOBAL_PROJECT, entryName(name)); return }
            w.save(KnowledgeWriter.GLOBAL_PROJECT, entryName(name), describe(doc), ScriptHeader.render(doc, ScriptStore.GLOBAL, null, shadows(name)), FOLDER)
        }.onFailure { log("script arsenal entry $name not written: ${it.message}") }
    }

    /** A project script was saved or deleted: its own entry follows, and the global entry of that name lists the shadow. */
    fun refreshProject(project: String, name: String) {
        val w = writer() ?: return
        runCatching {
            val script = store.read(project, name)?.takeIf { store.hasProjectCopy(project, name) }
            val doc = script?.let { ScriptHeader.parse(name, it.source) }
            if (doc == null) w.delete(project, entryName(name))
            else w.save(project, entryName(name), describe(doc), ScriptHeader.render(doc, ScriptStore.PROJECT, project, emptyList()), FOLDER)
        }.onFailure { log("script arsenal project entry $name not written: ${it.message}") }
        refreshGlobal(name)
    }

    /** Every global entry, plus removal of entries whose script is gone. Run at start, after seeding. Returns the count, or -1 with no writer. */
    fun syncAll(): Int {
        val w = writer() ?: return -1
        val names = store.global?.names().orEmpty()
        names.forEach { refreshGlobal(it) }
        runCatching { w.names(KnowledgeWriter.GLOBAL_PROJECT, PREFIX).filter { it.removePrefix(PREFIX) !in names }.forEach { w.delete(KnowledgeWriter.GLOBAL_PROJECT, it) } }
        return names.size
    }

    companion object {
        const val PREFIX = "script-arsenal-"
        const val FOLDER = "script-arsenal"
    }
}
