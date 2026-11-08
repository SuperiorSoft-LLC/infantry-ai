// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCRIPT-ARSENAL-2: a note saved under the global project id is found by every project's get and search, a project note of the same name wins, and the index stays per project. */
class MemoryGlobalNotesTest {
    private val g = MemoryStore.GLOBAL_PROJECT

    @Test fun globalNotesAreVisibleToEveryProjectButNotInItsIndex() {
        var n = 0
        val store = MemoryStore(idFactory = { "n${n++}" })
        store.save(g, "script-arsenal-hook-health", "Script Arsenal: alert when a script keeps failing", "body of hook-health")
        assertEquals("body of hook-health", store.get("p1", "script-arsenal-hook-health")?.body)
        assertEquals(listOf("script-arsenal-hook-health"), store.search("p2", "keeps failing").map { it.name })
        assertTrue(store.index("p1").isEmpty(), "the digest index of a project does not list global notes")
        assertNull(store.get("p1", "script-arsenal-hook-health", "role", "x"), "only project-scope notes fall back")
    }

    @Test fun aProjectNoteOfTheSameNameWinsAndOtherProjectsKeepTheGlobalOne() {
        var n = 0
        val store = MemoryStore(idFactory = { "n${n++}" })
        store.save(g, "script-arsenal-x", "global desc", "global body")
        store.save("p1", "script-arsenal-x", "project desc", "project body")
        assertEquals("project body", store.get("p1", "script-arsenal-x")?.body)
        assertEquals("global body", store.get("p2", "script-arsenal-x")?.body)
        assertEquals(listOf("project desc"), store.search("p1", "script-arsenal-x").map { it.description })
        assertEquals(listOf("global desc"), store.search("p2", "script-arsenal-x").map { it.description })
        assertTrue(store.delete(g, "script-arsenal-x"))
        assertNull(store.get("p2", "script-arsenal-x"))
    }
}
