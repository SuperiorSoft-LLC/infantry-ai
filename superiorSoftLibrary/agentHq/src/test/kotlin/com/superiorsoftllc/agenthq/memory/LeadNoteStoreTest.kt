package com.superiorsoftllc.agenthq.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MVP 7: the thin lead-notes store -- durable, and isolated per project id.
 *
 * A lead note is the observation a lead records while running one story, and it belongs to exactly
 * one project. The four things that have to hold: a note written before a restart is still there
 * after one, the notes of one project never surface under another's id, a store with no file yet
 * opens empty, and a store whose file is corrupt opens empty instead of taking the daemon down.
 *
 * The store is exercised twice per durable test: the instance that writes, and a SECOND instance
 * over the same file. That second instance is the restart -- it is the only way "durable" is a
 * claim and not an intention.
 */
class LeadNoteStoreTest {

    private val dir: File = Files.createTempDirectory("agenthq-lead-notes").toFile()
    private val file = File(dir, "lead-notes.json")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // --- the rules, in memory ---------------------------------------------------------------------------

    @Test
    fun aSavedNoteGetsAGeneratedIdAndIsReadBack() {
        val store = InMemoryLeadNoteStore(idFactory = { "note-fixed" })

        val saved = store.save(LeadNoteSave(projectId = "proj-alpha", body = "tokens in: 12k"))

        assertEquals("note-fixed", saved.id)
        assertEquals("proj-alpha", saved.projectId)
        assertEquals("tokens in: 12k", saved.body)
        assertEquals(saved, store.get("note-fixed"))
    }

    @Test
    fun editingANoteKeepsItsProjectAndUpdatesItsBody() {
        val store = InMemoryLeadNoteStore(idFactory = { "note-fixed" })
        store.save(LeadNoteSave(projectId = "proj-alpha", body = "first"))

        val edited = store.save(LeadNoteSave(id = "note-fixed", body = "second"))

        assertEquals("proj-alpha", edited.projectId)
        assertEquals("second", edited.body)
        assertEquals(edited, store.get("note-fixed"))
        assertEquals(1, store.list("proj-alpha").size)
    }

    @Test
    fun anUnknownIdIsRefusedAndNothingIsWritten() {
        val store = InMemoryLeadNoteStore(idFactory = { "note-fixed" })
        store.save(LeadNoteSave(projectId = "proj-alpha", body = "first"))

        assertFailsWith<IllegalArgumentException> {
            store.save(LeadNoteSave(id = "note-missing", body = "ghost"))
        }
        assertEquals(1, store.list("proj-alpha").size)
    }

    @Test
    fun aBlankBodyIsRefused() {
        val store = InMemoryLeadNoteStore(idFactory = { "note-fixed" })

        assertFailsWith<IllegalArgumentException> {
            store.save(LeadNoteSave(projectId = "proj-alpha", body = "   \n  "))
        }
        assertEquals(emptyList(), store.list("proj-alpha"))
    }

    // --- (a) durability ---------------------------------------------------------------------------------

    @Test
    fun aSavedNoteSurvivesAReopen() {
        JsonLeadNoteStore(file).save(LeadNoteSave(projectId = "proj-alpha", body = "tokens in: 12k"))

        val afterRestart = JsonLeadNoteStore(file)

        val notes = afterRestart.list("proj-alpha")
        assertEquals(1, notes.size)
        assertEquals("tokens in: 12k", notes.single().body)
        assertEquals(notes.single(), afterRestart.get(notes.single().id))
    }

    @Test
    fun editsAndTwoProjectsBothSurviveAReopen() {
        val before = JsonLeadNoteStore(file)
        before.save(LeadNoteSave(projectId = "proj-alpha", body = "first"))
        val saved = before.save(LeadNoteSave(projectId = "proj-beta", body = "beta only"))
        before.save(LeadNoteSave(id = before.list("proj-alpha").single().id, body = "edited"))

        val afterRestart = JsonLeadNoteStore(file)

        assertEquals("edited", afterRestart.list("proj-alpha").single().body)
        assertEquals("beta only", afterRestart.list("proj-beta").single().body)
        assertEquals(saved, afterRestart.get(saved.id))
    }

    // --- (b) isolation ----------------------------------------------------------------------------------

    @Test
    fun notesOfTwoProjectsStayApart() {
        val store = JsonLeadNoteStore(file)
        val first = store.save(LeadNoteSave(projectId = "proj-alpha", body = "alpha one"))
        val second = store.save(LeadNoteSave(projectId = "proj-beta", body = "beta one"))
        val third = store.save(LeadNoteSave(projectId = "proj-alpha", body = "alpha two"))

        assertEquals(listOf(first, third), store.list("proj-alpha"))
        assertEquals(listOf(second), store.list("proj-beta"))
        assertEquals(emptyList(), store.list("proj-gamma"))
    }

    @Test
    fun aNoteIsNeverMovedToAnotherProjectByAnEdit() {
        val store = JsonLeadNoteStore(file)
        val saved = store.save(LeadNoteSave(projectId = "proj-alpha", body = "stays here"))

        assertFailsWith<IllegalArgumentException> {
            store.save(LeadNoteSave(id = saved.id, projectId = "proj-beta", body = "moved"))
        }
        assertEquals(listOf(saved), store.list("proj-alpha"))
        assertEquals(emptyList(), store.list("proj-beta"))
    }

    @Test
    fun isolationSurvivesAReopen() {
        val before = JsonLeadNoteStore(file)
        before.save(LeadNoteSave(projectId = "proj-alpha", body = "alpha one"))
        before.save(LeadNoteSave(projectId = "proj-beta", body = "beta one"))

        val afterRestart = JsonLeadNoteStore(file)

        assertEquals(1, afterRestart.list("proj-alpha").size)
        assertEquals(1, afterRestart.list("proj-beta").size)
        assertEquals("alpha one", afterRestart.list("proj-alpha").single().body)
        assertEquals("beta one", afterRestart.list("proj-beta").single().body)
    }

    // --- (c) no store yet ------------------------------------------------------------------------------

    @Test
    fun anAbsentStoreOpensEmptyAndCreatesNoFile() {
        assertTrue(!file.isFile)

        val store = JsonLeadNoteStore(file)

        assertEquals(emptyList(), store.list("proj-alpha"))
        assertFailsWith<IllegalArgumentException> { store.get("note-missing") }
        assertTrue(!file.isFile)
    }

    // --- (d) a store that cannot be read ---------------------------------------------------------------

    @Test
    fun aCorruptStoreOpensEmptyInsteadOfFailing() {
        dir.mkdirs()
        file.writeText("this is not json at all { not even close")

        val store = JsonLeadNoteStore(file)

        assertEquals(emptyList(), store.list("proj-alpha"))
        // and it keeps working: the next save lands and is readable
        val saved = store.save(LeadNoteSave(projectId = "proj-alpha", body = "after repair"))
        assertEquals(saved, store.get(saved.id))
        assertEquals(listOf(saved), JsonLeadNoteStore(file).list("proj-alpha"))
    }

    @Test
    fun aTruncatedStoreOpensEmptyInsteadOfFailing() {
        dir.mkdirs()
        file.writeText("""{"version":1,"notes":[{"id":"note-x","projectId":"proj-alp""")

        val store = JsonLeadNoteStore(file)

        assertEquals(emptyList(), store.list("proj-alpha"))
    }

    @Test
    fun aStoreWhoseArrayIsMissingOpensEmptyInsteadOfFailing() {
        dir.mkdirs()
        file.writeText("""{"version":1}""")

        val store = JsonLeadNoteStore(file)

        assertEquals(emptyList(), store.list("proj-alpha"))
    }
}
