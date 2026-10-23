package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.RunsState
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RUN-CMD-2: the Run tab's Add / Edit / Delete. A real [Controller] and the real [RunPane] over a fake link, so the
 * assertions are exactly "which frame the pane sent" and "what it drew". The daemon side of command_save /
 * command_delete is covered by CommandSaveDaemonTest (RUN-CMD-1).
 */
@OptIn(ExperimentalTestApi::class)
class RunCommandEditorUiTest {
    private val build = """{"id":"build-web","name":"Build web","cwd":"web","argv":["gradlew.bat","build"],"graceMillis":7000}"""

    private fun withPane(
        commands: String,
        width: Int = 900,
        contract: String = "1.94.0",
        body: ComposeUiTest.(ChangesFakeLink, Controller) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 700) {
                setContent {
                    val state by controller.state.collectAsState()
                    Box(Modifier.width(width.dp).fillMaxHeight()) { RunPane(state, controller) }
                }
                link.push(Envelope(Envelope.READY, "contract=$contract provider=stub instance=i1"))
                link.push(Envelope(Envelope.COMMANDS, commands))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive && controller.state.value.runs.commandsRevision > 0 }
                waitForIdle()
                body(link, controller)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ChangesFakeLink.lastData(type: String): JsonObject = Json.parseToJsonElement(sentOf(type).last().data).jsonObject

    // RED on base: the empty state had only the "No saved commands" text, no way to add one.
    @Test fun `an empty command list offers an Add command button`() {
        val base = AppState()
        val empty = base.copy(connection = base.connection.copy(state = ConnState.CONNECTED), runs = RunsState())
        runDesktopComposeUiTest(900, 500) {
            setContent { RunPane(empty, Controller(CoroutineScope(SupervisorJob() + Dispatchers.Default))) }
            onNodeWithText("No saved commands on this daemon.").assertExists()
            onNodeWithTag("run-add").assertExists().assertIsEnabled()
        }
    }

    @Test fun `Add derives the id from the name and Save sends command_save with argv one item per line`() = withPane("[]") { link, _ ->
        onNodeWithTag(RUN_ADD_TAG).performClick()
        onNodeWithTag(RUN_FORM_TAG).assertExists()
        onNodeWithText("Not a shell command", substring = true).assertExists()
        onNodeWithTag(RUN_FORM_NAME_TAG).performTextInput("Build Web!")
        onNodeWithTag(RUN_FORM_ID_TAG).assertTextContains("build-web")
        onNodeWithTag(RUN_FORM_ARGV_TAG).performTextInput("gradlew.bat\n  build \n\n--info")
        onNodeWithTag(RUN_FORM_CWD_TAG).performTextInput("web")
        onNodeWithTag(RUN_FORM_GRACE_TAG).assertTextContains("5000")
        onNodeWithTag(RUN_FORM_SAVE_TAG).performScrollTo().performClick()

        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.COMMAND_SAVE).isNotEmpty() }
        val save = link.lastData(Envelope.COMMAND_SAVE)
        assertEquals("build-web", save["id"]?.jsonPrimitive?.content, "$save")
        assertEquals("Build Web!", save["name"]?.jsonPrimitive?.content, "$save")
        assertEquals(listOf("gradlew.bat", "build", "--info"), save["argv"]!!.jsonArray.map { it.jsonPrimitive.content }, "argv is a list, never a shell string: $save")
        assertEquals("web", save["cwd"]?.jsonPrimitive?.content, "$save")
        assertEquals(5000L, save["graceMillis"]?.jsonPrimitive?.content?.toLong(), "$save")

        // The daemon's answer, the new list, closes the form and the command is pickable.
        link.push(Envelope(Envelope.COMMANDS, """[{"id":"build-web","name":"Build Web!","cwd":"web","argv":["gradlew.bat","build","--info"],"graceMillis":5000}]"""))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagCount(RUN_FORM_TAG) == 0 }
        onNodeWithTag(RUN_COMMAND_PICKER_TAG).assertTextContains("Build Web!")
    }

    @Test fun `a daemon refusal shows inline and keeps the form open, a retry that is answered closes it`() = withPane("[]") { link, _ ->
        onNodeWithTag(RUN_ADD_TAG).performClick()
        onNodeWithTag(RUN_FORM_NAME_TAG).performTextInput("Out")
        onNodeWithTag(RUN_FORM_ARGV_TAG).performTextInput("cmd.exe")
        onNodeWithTag(RUN_FORM_CWD_TAG).performTextInput("../elsewhere")
        onNodeWithTag(RUN_FORM_SAVE_TAG).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.COMMAND_SAVE).size == 1 }

        link.push(Envelope(Envelope.ERROR, "command_save refused: command ('out') has a cwd outside the repo"))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagCount(RUN_FORM_ERROR_TAG) == 1 }
        onNodeWithTag(RUN_FORM_ERROR_TAG).assertTextContains("cwd outside the repo", substring = true)
        onNodeWithTag(RUN_FORM_TAG).assertExists()
        onNodeWithTag(RUN_FORM_SAVE_TAG).assertIsEnabled()

        onNodeWithTag(RUN_FORM_SAVE_TAG).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.COMMAND_SAVE).size == 2 }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagCount(RUN_FORM_ERROR_TAG) == 0 }
        link.push(Envelope(Envelope.COMMANDS, """[{"id":"out","name":"Out","argv":["cmd.exe"],"graceMillis":5000}]"""))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagCount(RUN_FORM_TAG) == 0 }
    }

    @Test fun `Edit prefills the picked command and keeps its id`() = withPane("[$build]") { link, _ ->
        onNodeWithTag(RUN_EDIT_TAG).performClick()
        onNodeWithTag(RUN_FORM_NAME_TAG).assertTextContains("Build web")
        onNodeWithTag(RUN_FORM_ID_TAG).assertTextContains("build-web")
        onNodeWithTag(RUN_FORM_ARGV_TAG).assertTextContains("gradlew.bat\nbuild")
        onNodeWithTag(RUN_FORM_CWD_TAG).assertTextContains("web")
        onNodeWithTag(RUN_FORM_GRACE_TAG).assertTextContains("7000")
        // Renaming does not re-derive an edited command's id.
        onNodeWithTag(RUN_FORM_NAME_TAG).performTextInput(" fast")
        onNodeWithTag(RUN_FORM_SAVE_TAG).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.COMMAND_SAVE).isNotEmpty() }
        val save = link.lastData(Envelope.COMMAND_SAVE)
        assertEquals("build-web", save["id"]?.jsonPrimitive?.content, "$save")
        assertEquals(7000L, save["graceMillis"]?.jsonPrimitive?.content?.toLong(), "$save")
    }

    @Test fun `Delete asks an inline confirm first, then sends command_delete`() = withPane("[$build]") { link, _ ->
        onNodeWithTag(RUN_DELETE_TAG).performClick()
        onNodeWithText("Delete \"Build web\"?").assertExists()
        assertTrue(link.sentOf(Envelope.COMMAND_DELETE).isEmpty(), "nothing is sent before the confirm")
        onNodeWithTag(RUN_DELETE_CANCEL_TAG).performClick()
        onNodeWithText("Delete \"Build web\"?").assertDoesNotExist()

        onNodeWithTag(RUN_DELETE_TAG).performClick()
        onNodeWithTag(RUN_DELETE_CONFIRM_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.COMMAND_DELETE).isNotEmpty() }
        assertEquals("build-web", link.lastData(Envelope.COMMAND_DELETE)["id"]?.jsonPrimitive?.content)
    }

    @Test fun `Add, Edit and Delete are off while a run is in flight`() = withPane("[$build]") { link, _ ->
        onNodeWithTag(RUN_ADD_TAG).assertIsEnabled()
        link.push(Envelope(Envelope.RUN, """{"runId":"r1","commandId":"build-web","state":"RUNNING"}"""))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagCount(RUN_STOP_TAG) == 1 && isEnabled(RUN_STOP_TAG) }
        onNodeWithTag(RUN_ADD_TAG).assertIsNotEnabled()
        onNodeWithTag(RUN_EDIT_TAG).assertIsNotEnabled()
        onNodeWithTag(RUN_DELETE_TAG).assertIsNotEnabled()
    }

    @Test fun `Add, Edit and Delete are off when not live`() {
        val base = AppState()
        val offline = base.copy(
            connection = base.connection.copy(state = ConnState.RECONNECTING),
            runs = RunsState(commands = listOf(com.superiorsoftllc.agenthq.ui.model.CommandSpecView("a", "A"))),
        )
        runDesktopComposeUiTest(900, 500) {
            setContent { RunPane(offline, Controller(CoroutineScope(SupervisorJob() + Dispatchers.Default))) }
            onNodeWithTag(RUN_ADD_TAG).assertIsNotEnabled()
            onNodeWithTag(RUN_EDIT_TAG).assertIsNotEnabled()
            onNodeWithTag(RUN_DELETE_TAG).assertIsNotEnabled()
        }
    }

    @Test fun `the compact phone width shows every button and a reachable Save`() = withPane("[$build]", width = 360) { link, _ ->
        listOf(RUN_COMMAND_PICKER_TAG, RUN_START_TAG, RUN_STOP_TAG, RUN_ADD_TAG, RUN_EDIT_TAG, RUN_DELETE_TAG).forEach {
            onNodeWithTag(it).assertIsDisplayed()
        }
        onNodeWithTag(RUN_ADD_TAG).performClick()
        onNodeWithTag(RUN_FORM_NAME_TAG).performTextInput("Lint")
        onNodeWithTag(RUN_FORM_ARGV_TAG).performTextInput("npm\nrun\nlint")
        onNodeWithTag(RUN_FORM_SAVE_TAG).performScrollTo().assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.COMMAND_SAVE).isNotEmpty() }
        assertEquals("lint", link.lastData(Envelope.COMMAND_SAVE)["id"]?.jsonPrimitive?.content)
    }

    @Test fun `a daemon older than 1_94 refuses Save inline without sending`() = withPane("[]", contract = "1.93.0") { link, _ ->
        onNodeWithTag(RUN_ADD_TAG).performClick()
        onNodeWithTag(RUN_FORM_NAME_TAG).performTextInput("X")
        onNodeWithTag(RUN_FORM_ARGV_TAG).performTextInput("x")
        onNodeWithTag(RUN_FORM_SAVE_TAG).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagCount(RUN_FORM_ERROR_TAG) == 1 }
        onNodeWithTag(RUN_FORM_ERROR_TAG).assertTextContains("1.94.0", substring = true)
        assertTrue(link.sentOf(Envelope.COMMAND_SAVE).isEmpty())
    }

    private fun ComposeUiTest.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun ComposeUiTest.isEnabled(tag: String) =
        onNodeWithTag(tag).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()
}
