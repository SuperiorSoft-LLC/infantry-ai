// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.memory.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-K1 (docs/INFANTRY-PHASE10-KNOWLEDGE.md, K-D5): `memory_save` modes in the store.
 *
 * - `replace` is today's behaviour (the whole body; creates a missing note).
 * - `append` adds `content` after the body, separated by exactly ONE blank line, and never creates.
 * - `edit` replaces the single occurrence of `find` (counted WITH overlap, byte for byte) by `content`;
 *   `content = ""` removes the passage; it never creates.
 * - Every refusal starts with its code (`anchor_not_found`, `anchor_ambiguous`, `note_not_found`) and
 *   leaves the stored note byte-identical, `updatedAt` included.
 */
class MemoryStoreModesTest {

    /** Each save gets a distinct, increasing `updatedAt`, so "unchanged" is a real check, not clock luck. */
    private fun seeded(body: String = "alpha\nbeta", folder: String = "ops"): Pair<MemoryStore, MemoryNote> {
        val store = MemoryStore()
        val note = store.save("P", "runbook", "how to run it", body, folder = folder)
        return store to note
    }

    private fun assertRefused(code: String, block: () -> Unit) {
        val e = assertFailsWith<IllegalArgumentException> { block() }
        assertTrue(e.message!!.startsWith("$code: "), "the refusal must start with '$code: ', got '${e.message}'")
    }

    @Test
    fun replaceIsTheDefaultAndStillCreates() {
        val store = MemoryStore()
        val created = store.save("P", "fresh", "cue", "body one")
        assertEquals("body one", created.body)
        val replaced = store.save("P", "fresh", "cue 2", "body two")
        assertEquals(created.id, replaced.id)
        assertEquals("body two", replaced.body)
        assertEquals("cue 2", replaced.description)
    }

    @Test
    fun replaceWithoutAFolderKeepsTheNoteWhereItIs() {
        val (store, _) = seeded(folder = "ops")
        assertEquals("ops", store.save("P", "runbook", "cue", "new body").folder)
        assertEquals("", store.save("P", "brand-new", "cue", "x").folder, "a new note without a folder goes to the root")
        assertEquals("moved", store.save("P", "runbook", "cue", "x", folder = "moved").folder)
    }

    @Test
    fun appendSeparatesWithExactlyOneBlankLine() {
        val (store, before) = seeded(body = "alpha\nbeta")
        val after = store.append("P", "runbook", "gamma")
        assertEquals("alpha\nbeta\n\ngamma", after.body)
        assertEquals(before.id, after.id)
        assertEquals("how to run it", after.description, "append without a description keeps the stored cue")
        assertEquals("ops", after.folder, "append without a folder keeps the folder")
    }

    @Test
    fun appendNormalisesTrailingNewlinesToOneBlankLine() {
        val (store, _) = seeded(body = "alpha\n\n\n")
        assertEquals("alpha\n\ngamma", store.append("P", "runbook", "gamma").body)
    }

    @Test
    fun appendToAnEmptyBodyHasNoLeadingSeparator() {
        val (store, _) = seeded(body = "")
        assertEquals("gamma", store.append("P", "runbook", "gamma").body)
    }

    @Test
    fun appendCanChangeTheCueAndFolder() {
        val (store, _) = seeded()
        val after = store.append("P", "runbook", "gamma", description = "new cue", folder = "elsewhere")
        assertEquals("new cue", after.description)
        assertEquals("elsewhere", after.folder)
    }

    @Test
    fun appendNeverCreates() {
        val store = MemoryStore()
        assertRefused("note_not_found") { store.append("P", "ghost", "x") }
        assertNull(store.get("P", "ghost"), "a refused append must not create the note")
        assertTrue(store.index("P").isEmpty())
    }

    @Test
    fun editReplacesTheSingleOccurrence() {
        val (store, before) = seeded(body = "step one\nstep two\nstep three")
        val after = store.edit("P", "runbook", find = "step two", content = "step 2")
        assertEquals("step one\nstep 2\nstep three", after.body)
        assertEquals(before.id, after.id)
        assertEquals("how to run it", after.description)
        assertEquals("ops", after.folder)
    }

    @Test
    fun editWithEmptyContentRemovesThePassage() {
        val (store, _) = seeded(body = "keep this. DROP THIS. keep that.")
        assertEquals("keep this. keep that.", store.edit("P", "runbook", find = "DROP THIS. ", content = "").body)
    }

    @Test
    fun editRefusesAMissingAnchorAndLeavesTheNoteByteIdentical() {
        val (store, before) = seeded(body = "alpha\nbeta")
        assertRefused("anchor_not_found") { store.edit("P", "runbook", find = "gamma", content = "x") }
        assertEquals(before, store.get("P", "runbook"), "body, cue, folder and updatedAt must be untouched")
    }

    @Test
    fun editMatchesByteForByteWithNoTrimmingOrCaseFolding() {
        val (store, before) = seeded(body = "Alpha beta")
        assertRefused("anchor_not_found") { store.edit("P", "runbook", find = "alpha", content = "x") }
        assertRefused("anchor_not_found") { store.edit("P", "runbook", find = " Alpha", content = "x") }
        assertEquals(before, store.get("P", "runbook"))
    }

    @Test
    fun editRefusesAnAmbiguousAnchorWithItsCount() {
        val (store, before) = seeded(body = "x y x y x")
        val e = assertFailsWith<IllegalArgumentException> { store.edit("P", "runbook", find = "x", content = "z") }
        assertTrue(e.message!!.startsWith("anchor_ambiguous: "), e.message)
        assertTrue("3 times" in e.message!!, "the refusal names the count, got '${e.message}'")
        assertEquals(before, store.get("P", "runbook"))
    }

    @Test
    fun anOverlappingMatchIsAmbiguous() {
        val (store, before) = seeded(body = "aaa")
        assertRefused("anchor_ambiguous") { store.edit("P", "runbook", find = "aa", content = "b") }
        assertEquals(before, store.get("P", "runbook"))
    }

    @Test
    fun editNeverCreates() {
        val store = MemoryStore()
        assertRefused("note_not_found") { store.edit("P", "ghost", find = "a", content = "b") }
        assertNull(store.get("P", "ghost"))
    }

    @Test
    fun editRefusesAnEmptyFind() {
        val (store, before) = seeded()
        assertFailsWith<IllegalArgumentException> { store.edit("P", "runbook", find = "", content = "b") }
        assertEquals(before, store.get("P", "runbook"))
    }

    @Test
    fun aNoteOfAnotherProjectIsNotFound() {
        val (store, before) = seeded()
        assertRefused("note_not_found") { store.append("Q", "runbook", "x") }
        assertRefused("note_not_found") { store.edit("Q", "runbook", find = "alpha", content = "x") }
        assertEquals(before, store.get("P", "runbook"))
    }

    @Test
    fun modesPersistThroughTheSurface() {
        val persistence = InMemoryMemoryPersistence()
        val store = MemoryStore(persistence)
        store.save("P", "runbook", "cue", "alpha")
        store.append("P", "runbook", "beta")
        store.edit("P", "runbook", find = "alpha", content = "ALPHA")
        assertEquals("ALPHA\n\nbeta", MemoryStore(persistence).get("P", "runbook")!!.body)
    }
}
