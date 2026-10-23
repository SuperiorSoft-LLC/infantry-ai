package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.protocol.ProjectSave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero, slice A: the PROJECT store's local operations.
 *
 * The seam frozen `Project` / `ProjectSave` and the `project_save` / `project_get` / `project_list`
 * frames. The WIRE half of that seam lives in `ProjectDaemonTest` and is slice A3's job; this suite
 * covers only what the store does on its own: create a project, read it back, list what exists, and
 * behave when nothing does.
 *
 * RETIRED (contract 1.29.0): `Project` used to also carry `storyIds`, an ordered list I0 maintained
 * as stories were created, precisely so slice A's store and slice B's story store stayed
 * independently owned. A story's order now lives on `Epic.storyIds` instead, so this suite no
 * longer asserts anything about it.
 */
class ProjectStoreTest {

    /**
     * A store whose minted ids come from [ids] in order, so a test asserts on the id it chose
     * instead of on a random UUID.
     */
    private fun store(vararg ids: String): ProjectStore {
        val queue = ArrayDeque(listOf(*ids))
        return ProjectStore(idFactory = { queue.removeFirstOrNull() ?: error("no more ids to mint") })
    }

    @Test
    fun aProjectIsCreatedAndReadBack() {
        val store = store("project-1")

        val created = store.save(ProjectSave(name = "Sprint-Zero MVP", goal = "one story, three ways"))

        assertEquals("project-1", created.id)
        assertEquals("Sprint-Zero MVP", created.name)
        assertEquals("one story, three ways", created.goal)
        assertEquals(created, store.get("project-1"), "the project reads back exactly as it was created")
    }

    @Test
    fun aProjectWithNoStoriesBehaves() {
        val store = store("project-1")

        assertTrue(store.list().isEmpty(), "nothing was created yet: ${store.list()}")

        val created = store.save(ProjectSave(name = "Empty by design"))

        assertEquals(listOf(created), store.list(), "the listing holds exactly the one project")
    }

    @Test
    fun eachProjectGetsItsOwnIdAndListsInCreationOrder() {
        val store = store("project-1", "project-2")

        val first = store.save(ProjectSave(name = "MVP 2/11"))
        val second = store.save(ProjectSave(name = "MVP 3/11"))

        assertTrue(first.id != second.id, "two projects never share an id: '${first.id}'")
        assertEquals(listOf(first, second), store.list())
    }

    @Test
    fun anUnknownProjectIsRefusedRatherThanCreated() {
        val store = store("project-1")

        val refused = assertFailsWith<IllegalArgumentException> { store.get("no-such-project") }
        assertTrue(
            refused.message!!.startsWith("no project 'no-such-project'"),
            "the refusal names the project it looked for: ${refused.message}",
        )

        // An edit naming an unknown id must not bring a project back, exactly as the seam demands.
        val revived = assertFailsWith<IllegalArgumentException> {
            store.save(ProjectSave(id = "no-such-project", name = "Back from nowhere"))
        }
        assertTrue(
            revived.message!!.startsWith("no project 'no-such-project'"),
            "an edit of a missing project is refused, not a create: ${revived.message}",
        )
        assertTrue(store.list().isEmpty(), "the refused write stored nothing: ${store.list()}")
    }

    @Test
    fun anEditLeavesAFieldTheRequestDidNotNameAsItWas() {
        val store = store("project-1")
        val created = store.save(ProjectSave(name = "Sprint-Zero MVP", goal = "one story, three ways"))

        val renamed = store.save(ProjectSave(id = "project-1", name = "Sprint-Zero MVP, measured"))
        assertEquals("Sprint-Zero MVP, measured", renamed.name)
        assertEquals("one story, three ways", renamed.goal, "a field the request left out is left as it was")
        assertEquals(renamed, store.get("project-1"), "the edit is what a later read sees")

        val cleared = store.save(ProjectSave(id = "project-1", goal = ""))
        assertEquals("", cleared.goal, "an empty goal clears it, as ticket_save clears a description")
        assertEquals("Sprint-Zero MVP, measured", cleared.name, "a field left out stays as it was")
    }

    @Test
    fun aBlankNameIsRefusedAndStoresNothing() {
        val store = store("project-1")

        val refused = assertFailsWith<IllegalArgumentException> { store.save(ProjectSave(name = "   ")) }
        assertTrue(refused.message!!.contains("name"), "the refusal names the field: ${refused.message}")
        assertTrue(store.list().isEmpty(), "a refused create stores nothing: ${store.list()}")

        val created = store.save(ProjectSave(name = "Named project"))
        val alsoRefused = assertFailsWith<IllegalArgumentException> {
            store.save(ProjectSave(id = "project-1", name = "\t"))
        }
        assertTrue(alsoRefused.message!!.contains("name"), "the refusal names the field: ${alsoRefused.message}")
        assertEquals(created, store.get("project-1"), "the refused edit left the project as it was")
    }

    @Test
    fun projectSaveRefusesOverlongOrControlCharName() {
        val store = store("project-1")

        val overlong = "x".repeat(ProjectStore.MAX_NAME_LENGTH + 1)
        val refusedLength = assertFailsWith<IllegalArgumentException> { store.save(ProjectSave(name = overlong)) }
        assertTrue(refusedLength.message!!.contains("longer than"), "the refusal mentions length: ${refusedLength.message}")
        assertTrue(store.list().isEmpty(), "the refused create stores nothing: ${store.list()}")

        val controlChar = "valid\u0007name"
        val refusedControl = assertFailsWith<IllegalArgumentException> { store.save(ProjectSave(name = controlChar)) }
        assertTrue(refusedControl.message!!.contains("control character"), "the refusal mentions control character: ${refusedControl.message}")
        assertTrue(store.list().isEmpty(), "the refused create stores nothing: ${store.list()}")
    }
}
