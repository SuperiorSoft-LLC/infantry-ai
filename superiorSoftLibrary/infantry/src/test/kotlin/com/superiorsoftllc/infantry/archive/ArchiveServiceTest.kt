package com.superiorsoftllc.infantry.archive

import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.persistence.TicketLinkRow
import com.superiorsoftllc.infantry.persistence.TicketRow
import com.superiorsoftllc.infantry.ticketfiles.LiveRelatedWork
import com.superiorsoftllc.infantry.ticketfiles.RelatedConfig
import com.superiorsoftllc.infantry.ticketfiles.TicketFiles
import com.superiorsoftllc.infantry.ticketfiles.TicketInfo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ARCHIVE-1: the archive store and service over a real SQLite file. */
class ArchiveServiceTest {
    private val day = 86_400_000L
    private val now = 100 * day

    private fun store(): SqlInfantryStore {
        val file = Files.createTempFile("archive-test-", ".db").toFile().also { it.delete() }
        return SqlInfantryStore.open(file.absolutePath)
    }

    private fun SqlInfantryStore.put(id: String, status: String, pos: Int, updated: Long = now, project: String? = "p1", epic: String? = null, title: String = "T $id", desc: String = "") =
        tickets.create(TicketRow(id = id, title = title, description = desc, status = status, position = pos, createdAt = 1, updatedAt = updated, projectId = project, epicId = epic, branch = "ticket/$id", weight = 3))

    private fun service(store: SqlInfantryStore, settings: Map<String, String> = emptyMap()) =
        ArchiveService(store, { now }, epicSnapshot = { e -> "Epic $e" to """{"id":"$e"}""" }, setting = settings::get)

    @Test
    fun `archive and restore round trip keeps every field and the links, and renumbers the lane`() {
        store().use { s ->
            s.put("ticket-a", "done", 0); s.put("ticket-b", "done", 1, epic = "epic-1"); s.put("ticket-c", "done", 2); s.put("ticket-d", "todo", 0)
            s.tickets.link(TicketLinkRow("ticket-b", "ticket-c", "relates_to", 5))
            s.tickets.link(TicketLinkRow("ticket-b", "ticket-d", "blocked_by", 6))
            val before = s.tickets.get("ticket-b")!!
            val svc = service(s)

            val r = svc.apply(ArchiveRequest(op = "archive", ids = listOf("ticket-b")))
            assertEquals(1, r.count)
            assertNull(s.tickets.get("ticket-b"))
            assertEquals(listOf("ticket-a", "ticket-c"), s.tickets.all().filter { it.status == "done" }.sortedBy { it.position }.map { it.id })
            assertEquals(listOf(0, 1), s.tickets.all().filter { it.status == "done" }.map { it.position }.sorted()) // contiguous again
            assertTrue(s.tickets.links().isEmpty(), "links of an archived ticket leave the live table")

            val shown = svc.query(ArchiveRequest(op = "show", ids = listOf("b"))).items.single()
            assertEquals("Epic epic-1", shown.epicName)
            assertEquals(2, shown.links.size)

            svc.apply(ArchiveRequest(op = "restore", ids = listOf("ticket-b")))
            val after = s.tickets.get("ticket-b")!!
            assertEquals(before, after) // every field, weight, branch and POSITION
            assertEquals(listOf("ticket-a", "ticket-b", "ticket-c"), s.tickets.all().filter { it.status == "done" }.sortedBy { it.position }.map { it.id })
            assertEquals(2, s.tickets.links().size)
            assertEquals(0, s.ticketArchive.count(null))
        }
    }

    @Test
    fun `lane filter moves only done tickets older than N days and never an in-progress one`() {
        store().use { s ->
            for (i in 1..5) s.put("ticket-old$i", "done", i - 1, updated = now - 40 * day)
            s.put("ticket-new", "done", 5, updated = now - 1 * day)
            s.put("ticket-wip", "in_progress", 0, updated = now - 90 * day)
            val svc = service(s)
            assertEquals(5, svc.apply(ArchiveRequest(op = "archive", status = "done", olderThanDays = 30)).count)
            assertEquals(setOf("ticket-new", "ticket-wip"), s.tickets.all().map { it.id }.toSet())
            assertEquals(0, s.tickets.get("ticket-new")!!.position)
            assertEquals(0, svc.apply(ArchiveRequest(op = "archive", ids = listOf("ticket-wip"))).count)
            assertEquals(5, s.ticketArchive.count("p1"))
            assertEquals(5, svc.query(ArchiveRequest(op = "list", projectId = "p1")).items.size)
        }
    }

    @Test
    fun `search finds archived tickets by title description or id, and treats percent literally`() {
        store().use { s ->
            s.put("ticket-s1", "done", 0, title = "Login 100% fix", desc = "oauth flow")
            s.put("ticket-s2", "done", 1, title = "Other")
            val svc = service(s)
            svc.apply(ArchiveRequest(op = "archive", status = "done"))
            assertEquals(listOf("ticket-s1"), svc.query(ArchiveRequest(op = "search", text = "oauth")).items.map { it.id })
            assertEquals(listOf("ticket-s1"), svc.query(ArchiveRequest(op = "search", text = "100%")).items.map { it.id })
            assertEquals(listOf("ticket-s2"), svc.query(ArchiveRequest(op = "search", text = "s2")).items.map { it.id })
            assertFailsWith<IllegalArgumentException> { svc.query(ArchiveRequest(op = "search", text = " ")) }
        }
    }

    @Test
    fun `an epic is archived with its tickets`() {
        store().use { s ->
            s.put("ticket-e1", "done", 0, epic = "epic-1"); s.put("ticket-e2", "todo", 0, epic = "epic-1"); s.put("ticket-x", "done", 1)
            val svc = service(s)
            assertEquals(2, svc.apply(ArchiveRequest(op = "archive", epicId = "epic-1")).count)
            assertEquals(listOf("ticket-x"), s.tickets.all().map { it.id })
            assertEquals(2, svc.apply(ArchiveRequest(op = "restore", epicId = "epic-1")).count)
            assertEquals(3, s.tickets.all().size)
        }
    }

    @Test
    fun `auto archive is off by default, and a project value wins over the global one`() {
        store().use { s ->
            s.put("ticket-p1old", "done", 0, updated = now - 20 * day, project = "p1")
            s.put("ticket-p2old", "done", 0, updated = now - 20 * day, project = "p2")
            s.put("ticket-p1new", "done", 1, updated = now - 2 * day, project = "p1")
            assertEquals(0, service(s).autoArchive(), "off by default")
            val svc = service(s, mapOf("board.archive.doneAfterDays" to "30", "board.archive.doneAfterDays.p1" to "10"))
            assertEquals(1, svc.autoArchive())
            assertEquals(setOf("ticket-p2old", "ticket-p1new"), s.tickets.all().map { it.id }.toSet())
            assertEquals(1, service(s, mapOf("board.archive.doneAfterDays" to "10")).autoArchive()) // p2old now due
        }
    }

    @Test
    fun `related files of an archived ticket are still found, the same as a live one`() {
        store().use { s ->
            val db = s.database
            s.put("ticket-arch", "done", 0, title = "Archived work", desc = "did a thing\nsee Board")
            for (i in 1..9) s.put("ticket-f$i", "done", i, title = "filler $i")
            val path = "superiorSoftLibrary/infantry/src/main/kotlin/com/x/Board.kt"
            db.ticket_filesQueries.insertFile("ticket-arch", path, "M", null, 4, 1, null, null, 1)
            val live = s.tickets.get("ticket-arch")!!.let { TicketInfo(it.id, it.title, it.description, it.status, it.projectId, it.branch, it.base) }
            service(s).apply(ArchiveRequest(op = "archive", ids = listOf("ticket-arch")))
            assertNull(s.tickets.get("ticket-arch"))

            val cur = TicketInfo("ticket-cur", "Current", "touch $path", "in_progress", "p1", null, null)
            fun infos() = s.ticketArchive.infos(null).map { TicketInfo(it.id, it.title, it.description, it.status, it.projectId, it.branch, it.base) }
            val files = TicketFiles(db, { infos() }, { null })
            val source = LiveRelatedWork(files, ticket = { id -> if (id == cur.id) cur else s.ticketArchive.info(id)?.let { a -> TicketInfo(a.id, a.title, a.description, a.status, a.projectId, a.branch, a.base) } },
                rows = { listOf(cur) + infos() }, config = { RelatedConfig.DEFAULT.copy(mode = RelatedConfig.MODE_TICKETS) })
            val text = assertNotNull(source.block("ticket-cur", cur.description))
            assertTrue("ticket-arch" in text, text)
            assertTrue("Board.kt" in text, text)
            assertEquals(1, files.forTicket("ticket-arch").size, "ticket_files rows are untouched by archiving")
            assertEquals(live.id, "ticket-arch")
        }
    }

    @Test
    fun `a ticket whose epic was archived restores on its own with its epic id and place`() {
        store().use { s ->
            s.put("ticket-e1", "done", 0, epic = "epic-1"); s.put("ticket-e2", "done", 1, epic = "epic-1"); s.put("ticket-x", "done", 2)
            val svc = service(s)
            svc.apply(ArchiveRequest(op = "archive", epicId = "epic-1"))
            svc.apply(ArchiveRequest(op = "restore", ids = listOf("ticket-e2")))
            assertEquals("epic-1", s.tickets.get("ticket-e2")!!.epicId)
            assertEquals(listOf("ticket-x", "ticket-e2"), s.tickets.all().sortedBy { it.position }.map { it.id })
            assertEquals("Epic epic-1", svc.query(ArchiveRequest(op = "show", ids = listOf("ticket-e1"))).items.single().epicName)
        }
    }

    @Test
    fun `cost of a board read with 1000 done tickets before and after archiving them`() {
        store().use { s ->
            for (i in 1..1000) s.put("ticket-%08x".format(i), "done", i - 1, updated = now - 40 * day, desc = "x".repeat(2000))
            for (i in 1..20) s.put("ticket-live%03d".format(i), "todo", i - 1)
            for (i in 2..1000 step 2) s.tickets.link(TicketLinkRow("ticket-%08x".format(i), "ticket-%08x".format(i - 1), "relates_to", 1))
            fun read(): Long { val t = System.nanoTime(); repeat(20) { s.tickets.all(); s.tickets.links() }; return (System.nanoTime() - t) / 20 / 1000 }
            read()
            val before = read()
            val moved = service(s).apply(ArchiveRequest(op = "archive", status = "done", olderThanDays = 30)).count
            read()
            val after = read()
            println("ARCHIVE-1 board read (tickets.all + links), 1000 done + 20 live: before=${before}us after=${after}us moved=$moved")
            assertEquals(1000, moved)
            assertTrue(after < before, "after $after must be below before $before")
        }
    }
}
