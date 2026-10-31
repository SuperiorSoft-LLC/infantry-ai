package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.daemon.FakeKnowledgeRecords
import com.superiorsoftllc.agenthq.memory.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DP-7b/7c: the Knowledge port over the memory store plus the decisions/issues records the Infantry_Notes plugin
 * publishes (a fake here), and the counter that reads it (absent provider = no section; absent records = no
 * decisions or issues).
 */
class LocalKnowledgeTest {
    @Test
    fun digestSectionAndSearchReadTheStoresAndRecords() {
        val memory = MemoryStore()
        val records = FakeKnowledgeRecords()
        memory.save("p1", "arch", "how it fits", "the body about widgets")
        records.addDecision("p1", "Use widgets.")
        records.openIssue("p1", "It leaks", "a/b.kt")
        val k = LocalKnowledge(memory) { records }
        assertEquals(1, k.noteCount("p1"))
        val d = k.digest("p1")!!
        assertEquals(listOf(1, 1, 1), listOf(d.notes, d.activeDecisions, d.openIssues))
        assertEquals(listOf("D1 Use widgets."), d.decisionLines)
        assertEquals("the body about widgets", k.section("p1", "arch"))
        assertNull(k.section("p1", "nope"))
        assertEquals(listOf("arch"), k.search("p1", "widgets").map { it.name })
        assertTrue(k.search("p1", " ").isEmpty())
    }

    @Test
    fun withNoRecordsProviderTheDigestOmitsDecisionsAndIssues() {
        val memory = MemoryStore()
        memory.save("p1", "arch", "how it fits", "the body")
        val d = LocalKnowledge(memory) { null }.digest("p1")!!
        assertEquals(listOf(1, 0, 0), listOf(d.notes, d.activeDecisions, d.openIssues))
        assertTrue(d.decisionLines.isEmpty())
    }

    @Test
    fun counterRendersThroughThePortAndOmitsTheSectionWhenAbsent() {
        val records = FakeKnowledgeRecords().also { it.addDecision("p1", "Use widgets.") }
        val local = LocalKnowledge(MemoryStore()) { records }
        val viaPort = ServiceKnowledgeCounter { local }
        assertEquals(renderKnowledge("p1", viaPort.counts("p1")!!), viaPort.section("p1"))
        assertTrue(viaPort.section("p1")!!.contains(KNOWLEDGE_DIGEST_HEADING))
        assertNull(ServiceKnowledgeCounter { null }.section("p1"), "no provider: no section, no error")
    }
}
