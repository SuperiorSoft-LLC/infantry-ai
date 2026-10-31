package com.superiorsoftllc.agenthq.plugins.official.approvals.daemon

import com.superiorsoftllc.agenthq.daemon.api.storage.PluginStorage
import com.superiorsoftllc.agenthq.protocol.ApprovalRule
import java.util.UUID

/**
 * PROMPT-6 / DP-10b: the remembered "Always allow" rules, per project, in the plugin's own table [TABLE] (created through
 * `api.storage`, so the module prefix applies).
 *
 *  - A rule is a tool, plus for a shell tool ([SHELL_TOOLS]) the first word of the command ([ApprovalRule.prefix]).
 *  - [match] is deliberately conservative for a shell rule: a command carrying a shell control operator never matches,
 *    so `git status && rm -rf ~` cannot ride a `Bash git` rule. A shell call with no first word stores no rule (a
 *    prefix-less shell rule would allow every command).
 *  - A blank or null project matches nothing and stores nothing: a rule outside a project could only leak.
 */
class ApprovalRules(
    private val db: PluginStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()

    init {
        db.migrate(1, listOf(
            "CREATE TABLE IF NOT EXISTS $TABLE (rule_id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL, tool TEXT NOT NULL, prefix TEXT, created_at INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS ${TABLE}_project ON $TABLE (project_id, created_at)",
        ))
    }

    /** The rule of [projectId] that allows [tool] called with [input], or null. */
    fun match(projectId: String?, tool: String, input: String): ApprovalRule? {
        val project = projectId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rules = synchronized(lock) { rowsOf(project) }
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
            rowsOf(project).firstOrNull { it.tool == name && it.prefix == prefix }?.let { return@synchronized it }
            val rule = ApprovalRule(newId(), project, name, prefix, clock())
            db.execute(
                "INSERT INTO $TABLE (rule_id, project_id, tool, prefix, created_at) VALUES (?, ?, ?, ?, ?)",
                listOf(rule.ruleId, project, name, prefix, rule.createdAt),
            )
            rule
        }
    }

    /** The rules of [projectId], oldest first; null or blank = every project's. */
    fun list(projectId: String? = null): List<ApprovalRule> = synchronized(lock) {
        val project = projectId?.trim()?.takeIf { it.isNotEmpty() }
        if (project != null) rowsOf(project)
        else db.query("SELECT * FROM $TABLE ORDER BY project_id, created_at, rule_id").map(::rule)
    }

    /** Remove [ruleId] and return it; an unknown id is an [IllegalArgumentException] and changes nothing. */
    fun delete(ruleId: String): ApprovalRule = synchronized(lock) {
        val id = ruleId.trim()
        require(id.isNotEmpty()) { "ruleId is required" }
        val found = db.query("SELECT * FROM $TABLE WHERE rule_id = ?", listOf(id)).firstOrNull()?.let(::rule)
            ?: throw IllegalArgumentException("unknown rule '${id.take(80)}'")
        db.execute("DELETE FROM $TABLE WHERE rule_id = ?", listOf(id))
        found
    }

    private fun rowsOf(project: String): List<ApprovalRule> =
        db.query("SELECT * FROM $TABLE WHERE project_id = ? ORDER BY created_at, rule_id", listOf(project)).map(::rule)

    private fun rule(row: Map<String, Any?>) = ApprovalRule(
        ruleId = row["rule_id"] as String,
        projectId = row["project_id"] as String,
        tool = row["tool"] as String,
        prefix = row["prefix"] as String?,
        createdAt = (row["created_at"] as Number).toLong(),
    )

    companion object {
        /** The table (module prefix `infantry_approvals_`). */
        const val TABLE = "infantry_approvals_rules"

        /** The tools whose rules carry a command prefix. */
        val SHELL_TOOLS: Set<String> = setOf("Bash", "PowerShell")

        /** Chaining, piping, substitution, redirection, a second line: any of them and a prefix rule does not apply. */
        private val SHELL_CONTROL = Regex("[;&|`<>\\r\\n]|\\$\\(")

        /** The command's first word, or null for a blank command. */
        fun firstWord(command: String): String? = command.trim().split(Regex("\\s+"), limit = 2).first().takeIf { it.isNotEmpty() }
    }
}
