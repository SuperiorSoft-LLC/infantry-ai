// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.context.TicketContextComposer
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.ContextSwitch
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.settings.SettingsService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-K11 (contract 1.55.0, docs/INFANTRY-PHASE10-KNOWLEDGE.md K-D9): skill attachment on a ticket and on the project,
 * over the real SQLite store, the real [SettingsService] and the real [TicketContextComposer], so every `source` below
 * is the one a client reads off the `ticket` frame.
 */
class TicketSkillAttachmentTest {
    private val dir: File = Files.createTempDirectory("infantry-k11").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private val settings = SettingsService(store)
    private val board = run {
        val ids = ('a'..'z').map { "t-$it" }.iterator()
        var tick = 0L
        TicketBoard(store, clock = { ++tick }, idFactory = { ids.next() }, context = TicketContextComposer(settings))
    }

    @AfterTest
    fun cleanUp() {
        runCatching { store.close() }
        dir.deleteRecursively()
    }

    private fun skill(name: String): String {
        var n = 0
        return store.skills.upsertByName(name, "Use when $name", "body of $name", emptyList(), 1L) { "sk-$name-${++n}" }.id
    }

    @Test
    fun `ticket_save keeps the skills when the fields are absent and clears the list with an empty array`() {
        val a = skill("alpha")
        val b = skill("beta")
        val created = board.save(TicketSave(title = "T", skillIds = listOf(b, a), skillsMode = "replace", includeSkillsContext = false)).ticket
        assertEquals(listOf(b, a), created.skillIds)
        assertEquals("replace", created.skillsMode)
        assertEquals(false, created.includeSkillsContext)

        // A save that does not carry the fields (a title edit, like the linked-tasks switch) keeps all three.
        val edited = board.save(TicketSave(id = created.id, title = "T2")).ticket
        assertEquals(listOf(b, a), edited.skillIds, "a save without skillIds must keep the stored list")
        assertEquals("replace", edited.skillsMode)
        assertEquals(false, edited.includeSkillsContext)
        assertEquals(listOf(b, a), store.tickets.get(created.id)!!.skillIds, "kept in the database too")

        val cleared = board.save(TicketSave(id = created.id, skillIds = emptyList())).ticket
        assertEquals(emptyList(), cleared.skillIds, "[] clears the list")
        assertEquals("replace", cleared.skillsMode, "clearing the list does not touch the mode")
        assertEquals(emptyList(), store.tickets.get(created.id)!!.skillIds)
    }

    @Test
    fun `the ticket skills switch keeps and changes exactly like the linked-tasks switch`() {
        val created = board.save(TicketSave(title = "T", includeLinkedTasksContext = true, includeSkillsContext = true)).ticket
        val kept = board.save(TicketSave(id = created.id, description = "d")).ticket
        assertEquals(kept.includeLinkedTasksContext, kept.includeSkillsContext)
        assertEquals(true, kept.includeSkillsContext)
        val flipped = board.save(TicketSave(id = created.id, includeLinkedTasksContext = false, includeSkillsContext = false)).ticket
        assertEquals(false, flipped.includeLinkedTasksContext)
        assertEquals(false, flipped.includeSkillsContext)
    }

    @Test
    fun `a new ticket attaches nothing, merges and inherits`() {
        val plain = board.save(TicketSave(title = "Plain")).ticket
        assertEquals(emptyList(), plain.skillIds)
        assertEquals("merge", plain.skillsMode)
        assertNull(plain.includeSkillsContext)
    }

    @Test
    fun `an unknown or repeated skill id is refused and nothing is written`() {
        val a = skill("alpha")
        val unknown = assertFailsWith<IllegalArgumentException> {
            board.save(TicketSave(title = "T", skillIds = listOf(a, "sk-missing")))
        }
        assertTrue(unknown.message!!.contains("unknown skill 'sk-missing'"), unknown.message)
        assertTrue(store.tickets.all().isEmpty(), "a refused create writes nothing")

        val ticket = board.save(TicketSave(title = "T", skillIds = listOf(a))).ticket
        assertFailsWith<IllegalArgumentException> { board.save(TicketSave(id = ticket.id, skillIds = listOf("nope"))) }
        val twice = assertFailsWith<IllegalArgumentException> { board.save(TicketSave(id = ticket.id, skillIds = listOf(a, a))) }
        assertTrue(twice.message!!.contains("listed twice"), twice.message)
        assertFailsWith<IllegalArgumentException> { board.save(TicketSave(id = ticket.id, skillsMode = "sometimes")) }
        assertEquals(listOf(a), store.tickets.get(ticket.id)!!.skillIds, "a refused edit leaves the stored list")
    }

    @Test
    fun `a skill deleted after it was attached stays on the ticket and does not block other edits`() {
        val a = skill("alpha")
        val ticket = board.save(TicketSave(title = "T", skillIds = listOf(a))).ticket
        assertTrue(store.skills.delete(a))
        val edited = board.save(TicketSave(id = ticket.id, title = "Still fine")).ticket
        assertEquals(listOf(a), edited.skillIds)
    }

    @Test
    fun `effectiveContext includeSkillsContext comes from the ticket, then the project, then the default`() {
        val inherits = board.save(TicketSave(title = "Inherits")).ticket
        assertEquals(ContextSwitch(true, "default"), inherits.effectiveContext!!.includeSkillsContext)

        settings.set(SettingsService.INCLUDE_SKILLS_CONTEXT, JsonPrimitive(false))
        assertEquals(ContextSwitch(false, "project"), board.get(inherits.id).effectiveContext!!.includeSkillsContext)
        // The two switches are independent: the skills setting does not move the linked-tasks one.
        assertEquals(ContextSwitch(true, "default"), board.get(inherits.id).effectiveContext!!.includeLinkedTasksContext)

        val pinned = board.save(TicketSave(title = "Pinned", includeSkillsContext = true)).ticket
        assertEquals(ContextSwitch(true, "ticket"), pinned.effectiveContext!!.includeSkillsContext)
    }

    @Test
    fun `the project baseline skillIds setting refuses unknown ids and wrong types and round-trips`() {
        assertEquals(JsonArray(emptyList()), settings.all()[SettingsService.SKILL_IDS])
        assertEquals(JsonPrimitive(true), settings.all()[SettingsService.INCLUDE_SKILLS_CONTEXT])
        val a = skill("alpha")
        val b = skill("beta")

        val unknown = assertFailsWith<IllegalArgumentException> {
            settings.set(SettingsService.SKILL_IDS, JsonArray(listOf(JsonPrimitive(a), JsonPrimitive("sk-missing"))))
        }
        assertTrue(unknown.message!!.contains("sk-missing"), unknown.message)
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.SKILL_IDS, JsonPrimitive(a)) }
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.SKILL_IDS, JsonArray(listOf(JsonPrimitive(true)))) }
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.INCLUDE_SKILLS_CONTEXT, JsonPrimitive("no")) }
        assertTrue(store.settings.all().isEmpty(), "refused writes store nothing")

        settings.set(SettingsService.SKILL_IDS, JsonArray(listOf(JsonPrimitive(b), JsonPrimitive(a))))
        assertEquals(listOf(b, a), SettingsService(store).skillIds(), "order kept and durable")
        settings.set(SettingsService.SKILL_IDS, JsonArray(emptyList()))
        assertEquals(emptyList(), settings.skillIds(), "a second write overwrites the first")
    }

    @Test
    fun `a setting written twice keeps the second value`() {
        settings.set(SettingsService.INCLUDE_SKILLS_CONTEXT, JsonPrimitive(false))
        settings.set(SettingsService.INCLUDE_SKILLS_CONTEXT, JsonPrimitive(true))
        assertEquals(true, settings.includeSkillsContext())
        settings.set(SettingsService.INCLUDE_LINKED_TASKS_CONTEXT, JsonPrimitive(false))
        settings.set(SettingsService.INCLUDE_LINKED_TASKS_CONTEXT, JsonPrimitive(true))
        assertEquals(true, settings.includeLinkedTasksContext())
    }
}
