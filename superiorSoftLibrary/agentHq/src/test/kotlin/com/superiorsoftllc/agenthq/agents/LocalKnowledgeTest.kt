package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.daemon.api.knowledge.Knowledge
import com.superiorsoftllc.agenthq.decisions.DecisionStore
import com.superiorsoftllc.agenthq.issues.IssueStore
import com.superiorsoftllc.agenthq.memory.MemoryStore
import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.db.AgentHQDatabaseHandle
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DP-7b: the Knowledge port over the real stores, and the counter that reads it (absent provider = no section). */
class LocalKnowledgeTest {
    private fun fixture(): Triple<MemoryStore, DecisionStore, IssueStore> {
        val dir = Files.createTempDirectory("local-knowledge").toFile()
        val store = SqlAgentHqStore(AgentHQDatabaseFactory.open(File(dir, "agenthq.db").absolutePath).let {
            AgentHQDatabaseHandle(AgentHQDatabaseFactory.newDatabase(it.driver), it.driver)
        })
        return Triple(MemoryStore(), DecisionStore(store), IssueStore(store))
    }

    @Test
    fun digestSectionAndSearchReadTheStores() {
        val (memory, decisions, issues) = fixture()
        memory.save("p1", "arch", "how it fits", "the body about widgets")
        decisions.add("p1", "Use widgets.")
        issues.open("p1", "It leaks", "a/b.kt")
        val k = LocalKnowledge(memory, decisions, issues)
        assertEquals(1, k.noteCount("p1"))
        val d = k.digest("p1")!!
        assertEquals(listOf(1, 1, 1), listOf(d.notes, d.activeDecisions, d.openIssues))
        assertEquals(KnowledgeLines.decisionTitles(decisions.list("p1")), d.decisionLines)
        assertEquals("the body about widgets", k.section("p1", "arch"))
        assertNull(k.section("p1", "nope"))
        assertEquals(listOf("arch"), k.search("p1", "widgets").map { it.name })
        assertTrue(k.search("p1", " ").isEmpty())
    }

    @Test
    fun counterRendersThroughThePortAndOmitsTheSectionWhenAbsent() {
        val (memory, decisions, issues) = fixture()
        decisions.add("p1", "Use widgets.")
        val local = LocalKnowledge(memory, decisions, issues)
        val viaPort = ServiceKnowledgeCounter { local }
        assertEquals(renderKnowledge("p1", viaPort.counts("p1")!!), viaPort.section("p1"))
        assertTrue(viaPort.section("p1")!!.contains(KNOWLEDGE_DIGEST_HEADING))
        assertNull(ServiceKnowledgeCounter { null }.section("p1"), "no provider: no section, no error")
    }
}
