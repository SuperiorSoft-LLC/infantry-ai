package com.superiorsoftllc.agenthq.prompts

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SettingRow
import com.superiorsoftllc.agenthq.protocol.ApprovalRule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * PROMPT-6 (contract 1.78.0): the remembered "Always allow" rules, per project.
 *
 *  - Durable as ONE settings row, [KEY], whose value is a JSON object `{projectId: [ApprovalRule...]}` (read at every
 *    call through [load], written through [save]; an unreadable row reads as no rules rather than failing a prompt).
 *  - A rule is a tool, plus for a shell tool ([SHELL_TOOLS]) the first word of the command ([ApprovalRule.prefix]).
 *  - [match] is deliberately conservative for a shell rule: a command carrying a shell control operator never matches,
 *    so `git status && rm -rf ~` cannot ride a `Bash git` rule. A shell call with no first word stores no rule (a
 *    prefix-less shell rule would allow every command).
 *  - A blank or null project matches nothing and stores nothing: a rule outside a project could only leak.
 */
class ApprovalRules(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()

    /** The rule of [projectId] that allows [tool] called with [input], or null. */
    fun match(projectId: String?, tool: String, input: String): ApprovalRule? {
        val project = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rules = synchronized(lock) { read()[project] }.orEmpty()
        if (tool !in SHELL_TOOLS) return rules.firstOrNull { it.tool == tool && it.prefix == null }
        if (SHELL_CONTROL.containsMatchIn(input)) return null
        val word = firstWord(input) ?: return null
        return rules.firstOrNull { it.tool == tool && it.prefix == word }
    }

    /**
     * Store the rule `allow_always` makes for [tool] / [input] in [projectId] and return it; the same rule already
     * stored is returned as it is. Null when no rule can be made (no project, or a shell call with no command word).
     */
    fun add(projectId: String?, tool: String, input: String): ApprovalRule? {
        val project = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = tool.trim().takeIf { it.isNotEmpty() } ?: return null
        val prefix = if (name in SHELL_TOOLS) firstWord(input) ?: return null else null
        return synchronized(lock) {
            val all = read().toMutableMap()
            val rules = all[project].orEmpty()
            rules.firstOrNull { it.tool == name && it.prefix == prefix }?.let { return@synchronized it }
            val rule = ApprovalRule(newId(), project, name, prefix, clock())
            all[project] = rules + rule
            write(all)
            rule
        }
    }

    /** The rules of [projectId], oldest first; null or blank = every project's, project by project. */
    fun list(projectId: String? = null): List<ApprovalRule> {
        val all = synchronized(lock) { read() }
        val project = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return all.values.flatten()
        return all[project].orEmpty()
    }

    /** Remove [ruleId] and return it; an unknown id is an [IllegalArgumentException] and changes nothing. */
    fun delete(ruleId: String): ApprovalRule = synchronized(lock) {
        val id = ruleId.trim()
        require(id.isNotEmpty()) { "ruleId is required" }
        val all = read().toMutableMap()
        val rule = all.values.flatten().firstOrNull { it.ruleId == id }
            ?: throw IllegalArgumentException("unknown rule '${id.take(80)}'")
        val left = all[rule.projectId].orEmpty().filterNot { it.ruleId == id }
        if (left.isEmpty()) all.remove(rule.projectId) else all[rule.projectId] = left
        write(all)
        rule
    }

    private fun read(): Map<String, List<ApprovalRule>> {
        val raw = runCatching { load() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching { json.decodeFromString(STORED, raw) }.getOrElse {
            System.err.println("[agentHq] $KEY unreadable, read as no rules: ${it.message}")
            emptyMap()
        }
    }

    private fun write(all: Map<String, List<ApprovalRule>>) = save(json.encodeToString(STORED, all))

    companion object {
        /** The settings-table key. */
        const val KEY = "toolApproval.rules"

        /** The tools whose rules carry a command prefix. */
        val SHELL_TOOLS: Set<String> = setOf("Bash", "PowerShell")

        /** Chaining, piping, substitution, redirection, a second line: any of them and a prefix rule does not apply. */
        private val SHELL_CONTROL = Regex("[;&|`<>\\r\\n]|\\$\\(")

        private val STORED = MapSerializer(String.serializer(), ListSerializer(ApprovalRule.serializer()))
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** The command's first word, or null for a blank command. */
        fun firstWord(command: String): String? = command.trim().split(Regex("\\s+"), limit = 2).first().takeIf { it.isNotEmpty() }

        fun inMemory(clock: () -> Long = System::currentTimeMillis, newId: () -> String = { UUID.randomUUID().toString() }): ApprovalRules {
            var stored: String? = null
            return ApprovalRules({ stored }, { stored = it }, clock, newId)
        }

        /** Backed by the daemon's `settings` table, row [KEY]. */
        fun forStore(store: AgentHqStore): ApprovalRules = ApprovalRules(
            load = { store.settings.get(KEY)?.value },
            save = { store.settings.put(SettingRow(KEY, it, System.currentTimeMillis())) },
        )
    }
}
