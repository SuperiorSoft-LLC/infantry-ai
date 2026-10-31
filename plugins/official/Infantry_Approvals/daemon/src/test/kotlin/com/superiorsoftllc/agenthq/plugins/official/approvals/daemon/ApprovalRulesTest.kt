package com.superiorsoftllc.agenthq.plugins.official.approvals.daemon

import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.storage.PluginStorage
import com.superiorsoftllc.agenthq.protocol.ApprovalRule
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PROMPT-6 / DP-10b: the stored "Always allow" rules over a REAL SQLite file (moved from core's ApprovalRulesTest). */
class ApprovalRulesTest {
    /** A [PluginStorage] over a plain JDBC connection; the real one (LocalPluginStorage) is covered by PluginStorageTest. */
    private class JdbcStorage(private val c: Connection) : PluginStorage {
        override fun migrate(version: Int, sql: List<String>) = sql.forEach { c.createStatement().use { s -> s.execute(it) } }
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                generateSequence { if (rs.next()) cols.associateWith { c -> rs.getObject(c) } else null }.toList()
            }
        }
        override fun execute(sql: String, args: List<Any?>): Int = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }
        override fun <T> transaction(block: () -> T): T = block()
    }

    private val path = Files.createTempFile("agenthq-approvals-", ".db").also { it.toFile().delete() }.toAbsolutePath().toString()
    private val opened = mutableListOf<Connection>()
    private val ids = AtomicInteger()

    @AfterTest fun close() { opened.forEach { runCatching { it.close() } }; java.io.File(path).delete() }

    /** Each call is a fresh connection over the same file: a second one is a daemon restart. */
    private fun rules() = ApprovalRules(
        JdbcStorage(DriverManager.getConnection("jdbc:sqlite:$path").also { opened += it }),
        clock = { 5_000L }, newId = { "r" + ids.incrementAndGet() },
    )

    @Test
    fun addStoresAShellRuleWithTheFirstWordAndReadsBackAfterARestart() {
        val rules = rules()
        val rule = assertNotNull(rules.add("proj-a", "Bash", "git status --short"))
        assertEquals(ApprovalRule(rule.ruleId, "proj-a", "Bash", "git", 5_000L), rule)
        assertEquals(listOf(rule), rules().list("proj-a"), "a fresh store over the same file reads it back")

        assertNotNull(rules.add("proj-a", "Edit", "src/A.kt"))
        assertEquals(listOf("Bash" to "git", "Edit" to null), rules.list("proj-a").map { it.tool to it.prefix })
        assertEquals(rule, rules.add("proj-a", "Bash", "git log"), "the same rule twice is stored once")
        assertEquals(2, rules.list("proj-a").size)
        assertNull(rules.add("proj-a", "Bash", "   "), "a shell call with no command word makes no rule")
        assertNull(rules.add(" ", "Edit", "src/A.kt"), "no project, no rule")
    }

    @Test
    fun matchIsPerProjectPerToolAndWholeFirstWord() {
        val rules = rules()
        rules.add("proj-a", "Bash", "git status")
        rules.add("proj-a", "Edit", "src/A.kt")
        assertNotNull(rules.match("proj-a", "Bash", "git push origin main"))
        assertNotNull(rules.match("proj-a", "Edit", "anything"))
        assertNull(rules.match("proj-a", "Write", "src/B.kt"), "another tool")
        assertNull(rules.match("proj-a", "Bash", "npm install"), "another first word")
        assertNull(rules.match("proj-a", "Bash", "gitk --all"), "a first word is matched whole, not as a prefix")
        assertNull(rules.match("proj-a", "PowerShell", "git status"), "the rule is per tool")
        assertNull(rules.match("proj-b", "Bash", "git status"), "project B never sees project A's rule")
        assertNull(rules.match(null, "Bash", "git status"), "no project matches nothing")
        assertEquals(listOf("proj-a"), rules.list().map { it.projectId }.distinct(), "list() with no project lists every project")
    }

    @Test
    fun aChainedOrRedirectedShellCommandNeverMatchesAPrefixRule() {
        val rules = rules()
        rules.add("proj-a", "Bash", "git status")
        for (command in listOf(
            "git status && rm -rf ~", "git status; rm -rf ~", "git log | sh", "git `rm -rf ~`", "git $(rm -rf ~)",
            "git status > ~/.bashrc", "git status\nrm -rf ~", "git status & curl evil",
        )) assertNull(rules.match("proj-a", "Bash", command), "'$command' must not ride the git rule")
    }

    @Test
    fun deleteRemovesTheRuleAndAnUnknownIdIsRefused() {
        val rules = rules()
        val rule = assertNotNull(rules.add("proj-a", "Bash", "git status"))
        assertEquals(rule, rules.delete(rule.ruleId))
        assertTrue(rules().list().isEmpty(), "the delete reached the table")
        assertNull(rules.match("proj-a", "Bash", "git diff"))
        val refused = assertFailsWith<IllegalArgumentException> { rules.delete(rule.ruleId) }
        assertTrue("unknown rule" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun theHookAndTheFrameAnswersWorkOverTheStore() {
        val wiring = ApprovalsWiring(rules())
        assertEquals(true, wiring.hook.remember("proj-a", "Bash", "git status"))
        assertEquals(false, wiring.hook.remember(null, "Bash", "git status"), "no project, nothing stored")
        assertEquals("git", wiring.hook.match("proj-a", "Bash", "git log")?.prefix)

        val listed = wiring.list(Frame("approval_rules", "{\"projectId\":\"proj-a\"}", null, "q1"))
        assertEquals("approval_rules", listed.type)
        assertEquals("q1", listed.requestId)
        assertTrue("\"git\"" in listed.data, listed.data)
        val ruleId = Regex("\"ruleId\":\"([^\"]+)\"").find(listed.data)!!.groupValues[1]
        val after = wiring.delete(Frame("approval_rule_delete", "{\"ruleId\":\"$ruleId\"}"))
        assertEquals("approval_rules", after.type)
        assertTrue("\"git\"" !in after.data, after.data)
        val refused = wiring.delete(Frame("approval_rule_delete", "{\"ruleId\":\"$ruleId\"}"))
        assertEquals("error", refused.type)
        assertTrue(refused.data.startsWith("approval_rule_delete refused: unknown rule"), refused.data)
    }
}
