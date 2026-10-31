package com.superiorsoftllc.agenthq.ticketfiles

import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.backlog.toInfo
import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DAEMON-CONTEXT-1: prompt assembly (pure), the live source over the file index, and the Files read check. */
class RelatedWorkTest {
    private val lib = "superiorSoftLibrary/agentHq/src/main/kotlin/com/x/"

    private fun view(id: String, vararg paths: String) =
        RelatedTicketView(id, "Title of $id", "done", "ticket/${id.removePrefix("ticket-")}-s", "did a thing", paths.map { RelatedFile(it, "M") })

    @Test
    fun rendersTicketsWithShortPathsAndMoreCount() {
        val own = setOf("${lib}Board.kt")
        val files = (1..8).map { "${lib}F$it.kt" } + "${lib}Board.kt"
        val text = assertNotNull(RelatedBlock.render(own, listOf(view("ticket-aaaa1111", *files.toTypedArray())), { false }))
        assertTrue("ticket-aaaa1111 [done] ticket/aaaa1111-s" in text, text)
        assertTrue("M agentHq/Board.kt" in text, text)
        assertFalse("src/main/kotlin" in text, text)
        assertTrue("+4 more" in text, text) // 9 files, 5 shown
        assertTrue(text.endsWith(RelatedBlock.FOOTER))
    }

    @Test
    fun listsOnlyOverlappingOrSameModuleFilesAndDropsHubs() {
        val own = setOf("${lib}Board.kt")
        val other = "superiorSoftLibrary/agentHqUi/src/Elsewhere.kt"
        val text = assertNotNull(RelatedBlock.render(own, listOf(view("ticket-aaaa1111", "${lib}Board.kt", "${lib}Hub.kt", other)), { it.endsWith("Hub.kt") }))
        assertTrue("agentHq/Board.kt" in text)
        assertFalse("Hub.kt" in text)
        assertFalse("Elsewhere.kt" in text)
    }

    @Test
    fun capsTicketsLinesAndSize() {
        val tickets = (1..6).map { view("ticket-aaaa000$it", "${lib}A$it.kt", "${lib}B$it.kt") }
        val text = assertNotNull(RelatedBlock.render(setOf("${lib}A1.kt"), tickets, { false }, maxTickets = 3))
        assertTrue(text.length <= 1500, "about 400 tokens: ${text.length}")
        assertTrue("ticket-aaaa0004" !in text)
        // KNOWLEDGE-PTR-1: the character cap (limits.ticketFiles.briefChars) cuts tickets, never a line in half.
        val tight = assertNotNull(RelatedBlock.render(setOf("${lib}A1.kt"), tickets, { false }, maxChars = 300))
        assertTrue(tight.length <= 300, "${tight.length}: $tight")
        assertTrue("ticket-aaaa0001" in tight && "ticket-aaaa0003" !in tight, tight)
    }

    @Test
    fun noRelatedTicketsGivesNoBlock() {
        // KNOWLEDGE-PTR-1: the block appears only when related tickets exist (no "none found" line in every prompt).
        assertNull(RelatedBlock.render(emptySet(), emptyList(), { false }))
    }

    private fun db(): com.superiorsoftllc.agenthq.db.AgentHQDatabase =
        AgentHQDatabaseFactory.open(File(Files.createTempDirectory("related-work").toFile(), "t.db").absolutePath).database

    private fun row(id: String, desc: String = "", status: String = TicketStatus.DONE) =
        TicketRow(id = id, title = "T $id", description = desc, status = status, position = 0, createdAt = 0, updatedAt = 0).toInfo()

    private fun put(db: com.superiorsoftllc.agenthq.db.AgentHQDatabase, ticket: String, vararg paths: String) =
        paths.forEach { db.ticket_filesQueries.insertFile(ticket, it, "M", null, null, null, null, null, 1) }

    @Test
    fun liveSourceSeedsFromTextRanksByOverlapAndCutsHubs() {
        val db = db()
        val rows = mutableMapOf<String, TicketInfo>()
        val tf = TicketFiles(db, { rows.values.toList() }, { null })
        // 10 done tickets all touch CONTRACT.md (a hub); two also touch Board.kt, one touches Other.kt.
        for (i in 1..10) rows["ticket-aaaaaaa$i"] = row("ticket-aaaaaaa$i")
        for (i in 1..10) put(db, "ticket-aaaaaaa$i", "docs/CONTRACT.md")
        put(db, "ticket-aaaaaaa1", "${lib}Board.kt", "${lib}Extra.kt")
        put(db, "ticket-aaaaaaa2", "${lib}Board.kt")
        put(db, "ticket-aaaaaaa3", "${lib}Other.kt")
        rows["ticket-bbbbbbb1"] = row("ticket-bbbbbbb1", "Fix the Board in ${lib}Board.kt, see ticket-aaaaaaa3", TicketStatus.IN_PROGRESS)
        val source = LiveRelatedWork(tf, { rows[it] })
        val text = assertNotNull(source.block("ticket-bbbbbbb1", rows.getValue("ticket-bbbbbbb1").description))
        assertTrue("ticket-aaaaaaa3" in text, text) // named by id, first
        assertTrue("ticket-aaaaaaa1" in text && "ticket-aaaaaaa2" in text, text)
        assertFalse("CONTRACT.md" in text, text) // hub cutoff by document frequency
        assertTrue("agentHq/Board.kt" in text, text)
    }

    @Test
    fun liveSourceWithNothingKnownGivesNoBlock() {
        val rows = mapOf("ticket-bbbbbbb1" to row("ticket-bbbbbbb1", "nothing to see", TicketStatus.IN_PROGRESS))
        val source = LiveRelatedWork(TicketFiles(db(), { rows.values.toList() }, { null }), { rows[it] })
        assertNull(source.block("ticket-bbbbbbb1", "nothing to see"))
        assertNull(source.block("ticket-missing", ""))
    }

    @Test
    fun filesReadCheck() {
        assertNotNull(FilesReadCheck.refusal("Done. commit abc123, changed X.", false))
        assertNull(FilesReadCheck.refusal("Done. commit abc123.\nFiles read: a/B.kt, c/D.kt", false))
        assertNotNull(FilesReadCheck.refusal("all good", true)) // ticket done: any mail is the report
        assertNull(FilesReadCheck.refusal("which option do you prefer?", false)) // a question is not a report
        assertEquals("a/B.kt, c/D.kt", FilesReadCheck.extract("x\n**Files read:** a/B.kt, c/D.kt"))
        assertNull(FilesReadCheck.extract("Files read:"))
    }

    @Test
    fun filesReadIsStoredOnTheTicket() {
        val tf = TicketFiles(db(), { emptyList() }, { null })
        tf.saveFilesRead("ticket-aaaaaaa1", "a.kt")
        tf.saveFilesRead("ticket-aaaaaaa1", "b.kt")
        assertEquals("b.kt", tf.filesRead("ticket-aaaaaaa1"))
    }
}
