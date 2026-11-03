package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import com.superiorsoftllc.infantry.ui.protocol.Workspace
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-WS-3: the UI encodes the project / workspace requests the daemon's router answers (contracts
 * 1.8.0 and 1.18.0), and a `workspaces` frame replaces only the project it names, so listing one
 * project never drops another's workspaces.
 */
class ProjectWorkspaceRequestsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private fun workspaces(vararg w: Workspace) =
        Envelope(Envelope.WORKSPACES, json.encodeToString(ListSerializer(Workspace.serializer()), w.toList()))

    /** (a) Each encoder produces the contract frame: type, and payload byte for byte. */
    @Test fun encodersProduceTheContractFrames() {
        val list = WireRequests.projectList()
        assertEquals(Envelope.PROJECT_LIST, list.type, "project_list names its frame")
        assertEquals("", list.data, "project_list carries no payload")

        val create = WireRequests.projectSave("Alpha")
        assertEquals(Envelope.PROJECT_SAVE, create.type, "project_save names its frame")
        assertEquals("""{"name":"Alpha"}""", create.data, "a create sends the name and omits a null goal")

        val withGoal = WireRequests.projectSave("Alpha", "ship it")
        assertEquals("""{"name":"Alpha","goal":"ship it"}""", withGoal.data, "a goal is sent when given")

        val wsList = WireRequests.workspaceList("A")
        assertEquals(Envelope.WORKSPACE_LIST, wsList.type, "workspace_list names its frame")
        assertEquals("A", wsList.data, "workspace_list carries the raw project id as payload")

        val wsCreate = WireRequests.workspaceSave("A", "one")
        assertEquals(Envelope.WORKSPACE_SAVE, wsCreate.type, "workspace_save names its frame")
        assertEquals("""{"projectId":"A","name":"one"}""", wsCreate.data, "a create sends projectId and name, omitting a null path")

        val wsPath = WireRequests.workspaceSave("A", "one", "/srv/one")
        assertEquals("""{"projectId":"A","name":"one","path":"/srv/one"}""", wsPath.data, "a path is sent when given")
    }

    /**
     * (b) A `workspaces` frame is one project's slice: it replaces exactly the projects it names and
     * keeps every other project's workspaces. Listing B after A keeps A's; a second A list replaces
     * only A's.
     */
    @Test fun aWorkspaceListFrameReplacesOnlyItsProject() {
        val afterA = feed(workspaces(Workspace("A1", "A", "one"), Workspace("A2", "A", "two")))
        assertEquals(listOf("A1", "A2"), afterA.workspaces.map { it.id }, "precondition: A's workspaces are loaded")

        val afterB = feed(workspaces(Workspace("B1", "B", "one")), from = afterA)
        assertEquals(setOf("A1", "A2", "B1"), afterB.workspaces.map { it.id }.toSet(), "listing B keeps A's workspaces")

        val afterSecondA = feed(workspaces(Workspace("A3", "A", "three")), from = afterB)
        assertEquals(
            setOf("A3", "B1"),
            afterSecondA.workspaces.map { it.id }.toSet(),
            "a second A list replaces only A's workspaces",
        )
        assertEquals(
            listOf("B1"),
            afterSecondA.workspaces.filter { it.projectId == "B" }.map { it.id },
            "B's workspaces survive A's replacement",
        )
    }
}
