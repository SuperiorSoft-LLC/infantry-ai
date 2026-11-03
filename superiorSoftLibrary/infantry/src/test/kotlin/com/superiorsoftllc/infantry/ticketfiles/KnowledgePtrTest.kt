package com.superiorsoftllc.infantry.ticketfiles

import com.superiorsoftllc.infantry.backlog.TicketBoard
import com.superiorsoftllc.infantry.context.ContextInput
import com.superiorsoftllc.infantry.context.TicketContextComposer
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.backlog.toInfo
import com.superiorsoftllc.infantry.persistence.TicketRow
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketLinkType
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.settings.ProjectSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** KNOWLEDGE-PTR-1 (b8ca2bf7): ticket families, the stored RELATED block, the start block's switches and caps. */
class KnowledgePtrTest {
    private val lib = "superiorSoftLibrary/infantry/src/main/kotlin/com/x/"
    private val pattern = RelatedConfig.DEFAULT.family
    private val opened = mutableListOf<SqlInfantryStore>()

    @AfterTest
    fun close() = opened.forEach { runCatching { it.close() } }

    private fun row(id: String, title: String, status: String = TicketStatus.DONE, desc: String = "") =
        TicketRow(id = id, title = title, description = desc, status = status, position = 0, createdAt = 0, updatedAt = 0).toInfo()

    private fun store(): SqlInfantryStore =
        SqlInfantryStore.open(File(Files.createTempDirectory("kptr").toFile(), "t.db").absolutePath).also { opened += it }

    // ---- (B) families ----------------------------------------------------------------------------------------------

    @Test
    fun familyLabelIsExactAndOrderedByNumber() {
        assertEquals(Family("USAGE-UI", 1), TicketFamily.of("USAGE-UI-1: the panel", pattern))
        assertEquals(Family("USAGE", 2), TicketFamily.of("USAGE-2 meters", pattern))
        assertEquals(Family("RUNTIME-LOAD", 4), TicketFamily.of("RUNTIME-LOAD-4 (F5): x", pattern))
        assertNull(TicketFamily.of("fix the board", pattern))
        assertNull(TicketFamily.of("USAGE-2", null), "no pattern = no families")

        val rows = listOf(
            row("ticket-00000002", "USAGE-2 b"), row("ticket-00000001", "USAGE-1 a"), row("ticket-00000003", "USAGE-UI-1 c"),
        )
        val members = TicketFamily.members("USAGE", rows, pattern, excludeId = null).map { it.first.id }
        assertEquals(listOf("ticket-00000001", "ticket-00000002"), members, "USAGE-UI is not USAGE")
        assertEquals("USAGE-3", TicketFamily.nextNumber("USAGE", rows, pattern, null))
        assertEquals("USAGE-UI-2", TicketFamily.nextNumber("USAGE-UI", rows, pattern, null))
    }

    @Test
    fun aTitleWithAKnownLabelAndNoNumberGetsTheNextNumberSuggested() {
        val rows = listOf(row("ticket-00000001", "USAGE-1 a"), row("ticket-00000002", "USAGE-2 b"), row("ticket-00000003", "USAGE-UI-1 c"))
        assertEquals(Family("USAGE", null), TicketFamily.resolve("USAGE: the next step", rows, pattern))
        assertEquals(Family("USAGE-UI", null), TicketFamily.resolve("USAGE-UI more", rows, pattern))
        assertNull(TicketFamily.resolve("USAGEX thing", rows, pattern))
        assertNull(TicketFamily.resolve("BILLING: thing", rows, pattern))

        val related = RelatedTickets(TicketFiles(store().database, { rows }, { null }))
        val set = related.resolve(null, "USAGE: next", "", null, rows, emptyList(), emptyList(), RelatedConfig.DEFAULT, 8, linkedFirst = false)
        assertEquals("USAGE-3", set.suggestedTitle)
        assertEquals(listOf("ticket-00000001", "ticket-00000002"), set.entries.map { it.row.id })
        val off = related.resolve(null, "USAGE: next", "", null, rows, emptyList(), emptyList(), RelatedConfig.DEFAULT.copy(suggestNumber = false), 8, false)
        assertNull(off.suggestedTitle)
        assertNull(related.resolve(null, "USAGE-3 x", "", null, rows, emptyList(), emptyList(), RelatedConfig.DEFAULT, 8, false).suggestedTitle)
    }

    // ---- (C) the stored description block ---------------------------------------------------------------------------

    @Test
    fun descriptionBlockKeepsTheHumanTextByteForByteAndIsIdempotent() {
        val human = "Line one\n\n  - indented item  \nend with spaces   "
        val once = RelatedDescriptionBlock.apply(human, "RELATED (auto, 1 tickets, 0 files):\n- x")
        assertTrue(once.startsWith(RelatedDescriptionBlock.BEGIN))
        assertEquals(human, RelatedDescriptionBlock.strip(once))
        val twice = RelatedDescriptionBlock.apply(once, "RELATED (auto, 1 tickets, 0 files):\n- x")
        assertEquals(once, twice)
        assertEquals(human, RelatedDescriptionBlock.apply(once, null), "no block = the human text alone")
        // A marker that is not at the top is human text.
        val quoted = "see:\n${RelatedDescriptionBlock.BEGIN}\n${RelatedDescriptionBlock.END}"
        assertEquals(quoted, RelatedDescriptionBlock.strip(quoted))
        assertTrue(RelatedDescriptionBlock.optedOut("x\nRelated: OFF\n"))
        assertFalse(RelatedDescriptionBlock.optedOut("related: off is a phrase"))
    }

    private fun boardWithFiles(cfg: () -> RelatedConfig = { RelatedConfig.DEFAULT }): Triple<TicketBoard, SqlInfantryStore, TicketFiles> {
        val s = store()
        val files = TicketFiles(s.database, { s.tickets.all().map { it.toInfo() } }, { null })
        var n = 0
        val board = TicketBoard(s, idFactory = { "ticket-%08x".format(++n) }, relatedDescription = RelatedDescriptionWriter(files, cfg))
        return Triple(board, s, files)
    }

    private fun put(s: SqlInfantryStore, ticket: String, sha: String, vararg paths: String) =
        paths.forEach { s.database.ticket_filesQueries.insertFile(ticket, it, "M", null, 3, 1, null, sha, 1) }

    @Test
    fun aSaveWritesTheBlockFamilyFirstAndAnEditKeepsTheHumanText() {
        val (board, s, _) = boardWithFiles()
        val u1 = board.save(TicketSave(title = "USAGE-1 meters", description = "first")).ticket.id
        val u2 = board.save(TicketSave(title = "USAGE-2 pacing", description = "second")).ticket.id
        put(s, u1, "abcdef1234567890", "${lib}Usage.kt", "${lib}Meter.kt")
        put(s, u2, "1234567890abcdef", "${lib}Usage.kt")
        val other = board.save(TicketSave(title = "Board polish", description = "touches ${lib}Usage.kt")).ticket.id

        // The board trims a description's ends (as before this ticket); everything inside is kept byte for byte.
        val human = "The brief.\n\n-   keep  me exactly\t\n\n\nend"
        val created = board.save(TicketSave(title = "USAGE: the next step", description = human)).ticket
        val stored = s.tickets.get(created.id)!!.description
        assertTrue(stored.startsWith(RelatedDescriptionBlock.BEGIN), stored)
        assertEquals(human, RelatedDescriptionBlock.strip(stored))
        assertTrue("next number is USAGE-3" in stored, stored)
        val lines = stored.lines()
        val familyAt = lines.indexOfFirst { "series: $u1" in it }
        assertTrue(familyAt > 0 && lines.indexOfFirst { "series: $u2" in it } > familyAt, stored)
        assertTrue("git show abcdef12" in stored, stored)
        assertTrue("infantry/Usage.kt" in stored, stored)
        assertFalse(other in stored, "the draft names no file, so no same-file ticket: $stored")

        // Saving the description it now has (block included) changes nothing; a new human text replaces only the human part.
        board.save(TicketSave(id = created.id, description = stored))
        assertEquals(stored, s.tickets.get(created.id)!!.description)
        board.save(TicketSave(id = created.id, description = "new text"))
        assertEquals("new text", RelatedDescriptionBlock.strip(s.tickets.get(created.id)!!.description))

        // A relates_to link (relatedTo) refreshes the block of the ticket that asked for it.
        board.link(TicketLinkRequest(ticketId = created.id, relatedId = other, type = TicketLinkType.RELATES_TO))
        assertTrue("linked: $other" in s.tickets.get(created.id)!!.description)
    }

    @Test
    fun noBlockWhenNothingResolvesTheOffSwitchAndTheOptOut() {
        val (board, s, _) = boardWithFiles()
        val lone = board.save(TicketSave(title = "Something new", description = "plain")).ticket.id
        assertEquals("plain", s.tickets.get(lone)!!.description, "nothing related = no block")

        board.save(TicketSave(title = "USAGE-1 a", description = "a"))
        val opted = board.save(TicketSave(title = "USAGE-2 b", description = "b\nrelated: off")).ticket.id
        assertEquals("b\nrelated: off", s.tickets.get(opted)!!.description)

        var on = true
        val (offBoard, s2, _) = boardWithFiles { RelatedConfig.DEFAULT.copy(descriptionBlock = on) }
        offBoard.save(TicketSave(title = "USAGE-1 a", description = "a"))
        on = false
        val untouched = offBoard.save(TicketSave(title = "USAGE-2 b", description = "  b  ")).ticket.id
        assertEquals("b", s2.tickets.get(untouched)!!.description, "off = stored exactly as before this feature (trimmed by the board)")
    }

    // ---- (E) personas -------------------------------------------------------------------------------------------------

    @Test
    fun pmAndArchitectPersonasCarryTheNewTicketRule() {
        val rule = "- New ticket: use the RELATED (auto) block the daemon writes atop its description, pass relatedTo with the ids " +
            "you already know, and reuse the series label of its family (USAGE-1, USAGE-2: the daemon suggests the next number)."
        for (role in listOf("pm", "architect")) {
            val text = assertNotNull(javaClass.getResource("/roles/$role.md"), role).readText().replace("\r\n", "\n")
            assertEquals(1, text.lines().count { it == rule }, "$role.md:\n$text")
        }
    }

    // ---- (A) the start block ----------------------------------------------------------------------------------------

    private val TICKETS = RelatedConfig.DEFAULT.copy(mode = RelatedConfig.MODE_TICKETS)

    private object NoSettings : ProjectSettings {
        override fun all() = JsonObject(emptyMap())
        override fun set(key: String, value: JsonElement) = all()
        override fun includeLinkedTasksContext(): Boolean? = null
    }

    private fun input(id: String, title: String, desc: String) = ContextInput(
        instructions = null, title = title, description = desc, links = emptyList(), includeLinkedOverride = false, ticketId = id,
    )

    @Test
    fun theStartMessageCarriesTheBlockAndInstructionByDefaultAndNotWhenOff() {
        val s = store()
        val files = TicketFiles(s.database, { s.tickets.all().map { it.toInfo() } }, { null })
        val rows = mutableMapOf<String, TicketInfo>()
        rows["ticket-00000001"] = row("ticket-00000001", "USAGE-1 meters")
        rows["ticket-00000002"] = row("ticket-00000002", "USAGE-2 pacing")
        rows["ticket-00000009"] = row("ticket-00000009", "USAGE-3 next", TicketStatus.IN_PROGRESS,
            desc = RelatedDescriptionBlock.apply("human brief", "RELATED (auto, 2 tickets, 1 files; x):\n- stored line"))
        // Done tickets whose worktrees are gone: only the index rows remain.
        put(s, "ticket-00000001", "abcdef1234567890", "${lib}Usage.kt")
        put(s, "ticket-00000002", "1234567890abcdef", "${lib}Pace.kt")
        var cfg = TICKETS
        val source = LiveRelatedWork(files, { rows[it] }, rows = { rows.values }, config = { cfg })
        val composer = TicketContextComposer(NoSettings, related = source)
        val t = rows.getValue("ticket-00000009")

        val composed = composer.compose(input(t.id, t.title, t.description))
        val block = composed.prompt.substringAfter(RelatedBlock.HEADER, "")
        assertTrue(block.isNotEmpty(), composed.prompt)
        assertTrue("series: ticket-00000001" in block && "series: ticket-00000002" in block, block)
        assertTrue("infantry/Usage.kt" in block && "git show abcdef12" in block, block)
        assertTrue(RelatedConfig.DEFAULT_INSTRUCTION in composed.prompt)
        assertFalse("stored line" in composed.prompt, "the live block replaces the stored one in the prompt")
        assertTrue("human brief" in composed.prompt)

        cfg = TICKETS.copy(brief = false)
        val off = composer.compose(input(t.id, t.title, t.description))
        assertFalse(RelatedBlock.HEADER in off.prompt)
        assertFalse(RelatedConfig.DEFAULT_INSTRUCTION in off.prompt)
        assertTrue("stored line" in off.prompt, "with the block off the description is the prompt's, as stored")

        cfg = TICKETS.copy(briefTickets = 1, instruction = "")
        val capped = assertNotNull(source.block(t.id, t.description))
        assertTrue("ticket-00000002" in capped && "ticket-00000001" !in capped, "nearest number first: $capped")
        assertFalse(RelatedConfig.DEFAULT_INSTRUCTION in capped)

        cfg = TICKETS
        assertNull(source.block(t.id, "human brief\nrelated: off"), "per-ticket opt-out")
    }

    @Test
    fun relatedShapeModesDefaultIsOneCommandLineNoTicketsAndLinkedListOnlyInTicketsMode() {
        val s = store()
        val files = TicketFiles(s.database, { s.tickets.all().map { it.toInfo() } }, { null })
        val rows = mutableMapOf<String, TicketInfo>()
        rows["ticket-00000001"] = row("ticket-00000001", "USAGE-1 meters")
        rows["ticket-00000002"] = row("ticket-00000002", "USAGE-2 pacing")
        rows["ticket-00000009"] = row("ticket-00000009", "USAGE-3 next", TicketStatus.IN_PROGRESS, desc = "human brief")
        put(s, "ticket-00000001", "abcdef1234567890", "${lib}Usage.kt")
        put(s, "ticket-00000002", "1234567890abcdef", "${lib}Pace.kt")
        var cfg = RelatedConfig.DEFAULT
        val source = LiveRelatedWork(files, { rows[it] }, rows = { rows.values }, config = { cfg })
        val composer = TicketContextComposer(NoSettings, related = source)
        val t = rows.getValue("ticket-00000009")
        val link = com.superiorsoftllc.infantry.context.LinkLine("relates", "ticket-00000001", "USAGE-1 meters", "done")
        fun prompt() = composer.compose(input(t.id, t.title, t.description).copy(links = listOf(link), includeLinkedOverride = true)).prompt

        // default: no block, no ids/titles/shas, no linked list, the one line
        val d = prompt()
        assertTrue(LiveRelatedWork.FILES_COMMAND_LINE in d, d)
        assertFalse(RelatedBlock.HEADER in d)
        assertFalse("ticket-00000001" in d || "USAGE-1 meters" in d || "git show" in d, d)
        assertFalse("Before you start, check the related tickets" in d)

        cfg = RelatedConfig.DEFAULT.copy(mode = RelatedConfig.MODE_FILES_INLINE)
        val inline = prompt()
        assertTrue("Files changed by related work (" !in inline || "ticket-00000001" !in inline, inline)
        assertFalse(RelatedBlock.HEADER in inline)

        cfg = TICKETS
        val old = prompt()
        assertTrue(RelatedBlock.HEADER in old && "ticket-00000001 | USAGE-1 meters" in old, old)
        assertTrue("Before you start, check the related tickets" in old)
    }

    @Test
    fun relatedModeAndFormatSettingsAcceptOnlyTheirValues() {
        val s = com.superiorsoftllc.infantry.settings.SettingsService(store())
        assertEquals("files-command", s.relatedConfig().mode)
        assertEquals("paths", s.relatedConfig().filesFormat)
        for (m in RelatedConfig.MODES) { s.set("brief.related.mode", kotlinx.serialization.json.JsonPrimitive(m)); assertEquals(m, s.relatedConfig().mode) }
        for (f in RelatedConfig.FORMATS) { s.set("related.files.format", kotlinx.serialization.json.JsonPrimitive(f)); assertEquals(f, s.relatedConfig().filesFormat) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { s.set("brief.related.mode", kotlinx.serialization.json.JsonPrimitive("nope")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { s.set("related.files.format", kotlinx.serialization.json.JsonPrimitive("nope")) }
    }
}
