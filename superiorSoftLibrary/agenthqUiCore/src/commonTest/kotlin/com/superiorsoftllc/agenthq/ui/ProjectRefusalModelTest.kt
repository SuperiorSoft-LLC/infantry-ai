package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ProjectView
import com.superiorsoftllc.agenthq.ui.model.dismissProjectRefusal
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.selectProject
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.Project
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sidebar's project refusal: shown on a refused save, cleared by a good save, a project switch or a dismiss. */
class ProjectRefusalModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val refusal = "project_save refused: directory must be an absolute path: 'relative/dir'"

    private fun error(s: AppState, text: String, now: Long) = reduce(s, Envelope(Envelope.ERROR, text), now)

    private fun saved(s: AppState, id: String, name: String, now: Long) =
        reduce(s, Envelope(Envelope.PROJECT, json.encodeToString(Project.serializer(), Project(id, name))), now)

    private val twoProjects = AppState(projects = listOf(ProjectView("A", "Alpha"), ProjectView("B", "Beta")), activeProjectId = "A")

    @Test fun `a refused save is shown in the sidebar`() {
        val s = error(twoProjects, refusal, 5)
        assertEquals(refusal, s.projectRefusal?.text)
        assertEquals(refusal, s.projectsRefusal()?.text)
        assertEquals(refusal, s.notices.single().text, "the global strip keeps its notice too")
    }

    @Test fun `a valid save clears the pinned refusal`() {
        val s = saved(error(twoProjects, refusal, 5), "C", "Gamma", 6)
        assertNull(s.projectRefusal)
        assertNull(s.projectsRefusal())
    }

    @Test fun `switching project clears the pinned refusal`() {
        val s = error(twoProjects, refusal, 5).selectProject("B")
        assertNull(s.projectRefusal)
    }

    @Test fun `a dismissed refusal stays hidden until the next one`() {
        val dismissed = error(twoProjects, refusal, 5).dismissProjectRefusal()
        assertNull(dismissed.projectsRefusal())
        val again = error(dismissed, "workspace_list failed: no such project", 9)
        assertEquals("workspace_list failed: no such project", again.projectsRefusal()?.text)
    }

    @Test fun `a refusal of another request does not reach the sidebar`() {
        assertNull(error(twoProjects, "ticket_save refused: nope", 5).projectRefusal)
    }
}
