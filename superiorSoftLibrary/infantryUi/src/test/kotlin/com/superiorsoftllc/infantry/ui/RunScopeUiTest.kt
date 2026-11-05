// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.run.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RUN-SCOPE-1: the Run tab lists only the selected project's commands, switching projects switches the list, Start names
 * the project, an unassigned command is shown apart with "Assign", and a project with no folder says so with "Set project
 * folder". A real [Controller] and the real pane over a fake link: the assertions are what it drew and which frame it sent.
 */
@OptIn(ExperimentalTestApi::class)
class RunScopeUiTest {
    private val json = Json { encodeDefaults = true }
    private val commands = """[
        {"id":"alpha-dev","name":"Alpha dev","argv":["x"],"graceMillis":5000,"project":"p1"},
        {"id":"beta-site","name":"Beta site","cwd":"vue","argv":["y"],"graceMillis":5000,"project":"p2","rooted":false},
        {"id":"loose","name":"Loose one","cwd":"tools","argv":["z"],"graceMillis":5000}
    ]"""

    private fun withPane(body: ComposeUiTest.(ChangesFakeLink, Controller) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 700) {
                setContent {
                    val state by controller.state.collectAsState()
                    Box(Modifier.width(900.dp).fillMaxHeight()) { RunPane(state, controller) }
                }
                link.push(Envelope(Envelope.READY, "contract=1.136.0 provider=stub instance=i1"))
                val projects = listOf(Project("p1", "Alpha", directory = "/srv/alpha"), Project("p2", "Beta", directory = ""))
                link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), projects)))
                link.push(Envelope(Envelope.COMMANDS, commands))
                waitUntil(timeoutMillis = 10_000) {
                    val s = controller.state.value
                    s.connection.isLive && s.runs.commandsRevision > 0 && s.activeProject?.id == "p1"
                }
                waitForIdle()
                body(link, controller)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ChangesFakeLink.lastData(type: String): JsonObject = Json.parseToJsonElement(sentOf(type).last().data).jsonObject

    @Test fun `only the selected project's commands are listed and Start names the project`() = withPane { link, _ ->
        onNodeWithTag(RUN_COMMAND_PICKER_TAG).assertTextContains("Alpha dev")
        onNodeWithTag(RUN_COMMAND_PICKER_TAG).performClick()
        onNodeWithTag("run-command-alpha-dev").assertExists()
        onNodeWithTag("run-command-beta-site").assertDoesNotExist()
        onNodeWithTag("run-command-alpha-dev").performClick()
        onNodeWithTag(RUN_FOLDER_TAG).assertDoesNotExist()
        onNodeWithTag(RUN_START_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.RUN_START).isNotEmpty() }
        val start = link.lastData(Envelope.RUN_START)
        assertEquals("alpha-dev", start["commandId"]?.jsonPrimitive?.content)
        assertEquals("p1", start["project"]?.jsonPrimitive?.content)
    }

    @Test fun `switching projects switches the list and a project without a folder says so`() = withPane { link, controller ->
        runBlocking { controller.selectProject("p2") }
        waitUntil(timeoutMillis = 5_000) { controller.state.value.activeProject?.id == "p2" }
        waitForIdle()
        onNodeWithTag(RUN_COMMAND_PICKER_TAG).assertTextContains("Beta site")
        // Its project has no folder: it cannot start, and the tab says why in plain words with a way to fix it.
        onNodeWithTag(RUN_START_TAG).assertIsNotEnabled()
        onNodeWithTag(RUN_FOLDER_TAG).assertExists()
        onNodeWithText("no project folder", substring = true).assertExists()
        onNodeWithTag(RUN_SET_FOLDER_TAG).assertIsEnabled().performClick()
        onNodeWithTag(RUN_FOLDER_PATH_TAG).performTextInput("/srv/beta")
        onNodeWithTag(RUN_FOLDER_SAVE_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.PROJECT_SAVE).isNotEmpty() }
        val save = link.lastData(Envelope.PROJECT_SAVE)
        assertEquals("p2", save["id"]?.jsonPrimitive?.content)
        assertEquals("/srv/beta", save["directory"]?.jsonPrimitive?.content)
    }

    @Test fun `an unassigned command is shown apart and Assign saves it into the selected project`() = withPane { link, _ ->
        onNodeWithTag(RUN_UNASSIGNED_TAG).assertExists()
        onNodeWithText("Loose one").assertExists()
        onNodeWithTag("$RUN_ASSIGN_TAG-loose").assertTextContains("Assign to Alpha").performClick()
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.COMMAND_SAVE).isNotEmpty() }
        val save = link.lastData(Envelope.COMMAND_SAVE)
        assertEquals("loose", save["id"]?.jsonPrimitive?.content)
        assertEquals("p1", save["project"]?.jsonPrimitive?.content)
        assertEquals("tools", save["cwd"]?.jsonPrimitive?.content)
    }

    @Test fun `the raw no-root refusal is shown in plain words`() = withPane { link, controller ->
        link.push(Envelope(Envelope.ERROR, "command_save refused: no working directory can be checked: the project has no registered path and the daemon has no INFANTRY_REPO. Register the path with infantry_project_config_set (directory), or start the daemon with INFANTRY_REPO set"))
        waitUntil(timeoutMillis = 5_000) { controller.state.value.runs.commandError != null }
        waitForIdle()
        onNodeWithText("\"Alpha\" has no project folder yet", substring = true).assertExists()
        onNodeWithText("INFANTRY_REPO", substring = true).assertDoesNotExist()
    }
}
