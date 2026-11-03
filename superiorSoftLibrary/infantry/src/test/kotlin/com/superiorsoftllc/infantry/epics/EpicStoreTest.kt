// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.epics

import com.superiorsoftllc.infantry.mvp.ProjectStore
import com.superiorsoftllc.infantry.protocol.ProjectSave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * EPIC-1: an epic saved under a project is listed under that project, in creation order, and under
 * no other project.
 *
 * EPIC-5: `save` refuses a blank name and refuses a project that [ProjectStore] does not know, and
 * a refusal writes nothing.
 */
class EpicStoreTest {

    @Test
    fun epicSaveIsListedUnderProject() {
        val store = EpicStore()
        val project = "project-social-machine"
        val other = "project-other"

        val mvp = store.save(projectId = project, name = "MVP")
        val postMvp = store.save(projectId = project, name = "Post MVP")

        assertEquals(
            listOf(mvp, postMvp),
            store.list(project),
            "a project lists its epics in creation order",
        )
        assertEquals(emptyList(), store.list(other), "another project lists none of them")
    }

    /**
     * EPIC-2: a story created under an epic is appended to the END of that epic's ordered
     * [Epic.storyIds], the mirror of
     * [com.superiorsoftllc.infantry.mvp.ProjectStore.appendStoryId]. A story hangs off an EPIC, not
     * off the project directly.
     *
     * RED by design before the minimum lands: [EpicStore.appendStoryId] ships as a stub, so this
     * fails on its first assertion at runtime rather than refusing to build. An unknown epic is a
     * no-op answering null, and a replayed append never lists one story twice.
     */
    @Test
    fun storyIsListedUnderItsEpic() {
        val store = EpicStore()
        val epic = store.save(projectId = "project-social-machine", name = "MVP")
        val first = "story-first"
        val second = "story-second"

        assertEquals(
            epic.copy(storyIds = listOf(first)),
            store.appendStoryId(epic.id, first),
            "the story is appended to the epic's ordered storyIds",
        )
        assertEquals(
            epic.copy(storyIds = listOf(first, second)),
            store.appendStoryId(epic.id, second),
            "a later story lands at the END of storyIds",
        )
        assertEquals(
            epic.copy(storyIds = listOf(first, second)),
            store.appendStoryId(epic.id, first),
            "a replayed append does not list the same story twice",
        )
        assertEquals(
            null,
            store.appendStoryId("epic-unknown", second),
            "an unknown epic is a no-op answering null",
        )
    }

    /**
     * EPIC-5: a blank [name] and a [projectId] that the [ProjectStore] does not know are both
     * REFUSED with [IllegalArgumentException], and a refused save writes no epic -- the mirror of
     * [com.superiorsoftllc.infantry.mvp.WorkspaceStoreTest.workspaceSaveRefusesUnknownProject] and
     * its blank-name sibling.
     */
    @Test
    fun epicSaveRefusesBlankNameAndUnknownProject() {
        val projects = ProjectStore(idFactory = { "project-1" })
        val project = projects.save(ProjectSave(name = "Social Machine"))
        val store = EpicStore(projects = projects, idFactory = { "epic-1" })

        val blankName = assertFailsWith<IllegalArgumentException>("saving an epic with a blank name must be refused") {
            store.save(projectId = project.id, name = "   ")
        }
        assertTrue(
            blankName.message.orEmpty().contains("name is blank"),
            "the refusal names the blank name issue, got: ${blankName.message}",
        )

        val unknownProject = assertFailsWith<IllegalArgumentException>(
            "saving an epic into a project that does not exist must be refused",
        ) {
            store.save(projectId = "project-unknown", name = "MVP")
        }
        assertTrue(
            unknownProject.message.orEmpty().contains("unknown project"),
            "the refusal names the unknown project issue, got: ${unknownProject.message}",
        )

        assertEquals(emptyList(), store.list(project.id), "a refused save writes no epic")
    }
}
