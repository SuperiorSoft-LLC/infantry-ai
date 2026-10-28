package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.run.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.CommandSpecView
import com.superiorsoftllc.agenthq.ui.model.ConnState
import com.superiorsoftllc.agenthq.ui.model.RunLine
import com.superiorsoftllc.agenthq.ui.model.RunState
import com.superiorsoftllc.agenthq.ui.model.RunsState
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test

/** T6: the run panel renders the output, gates Start/Stop on the run state, and never shows an argv. */
@OptIn(ExperimentalTestApi::class)
class RunPaneUiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cmd = CommandSpecView("echo-hello", "Echo hello", null)

    private fun state(run: RunState?, live: Boolean = true): AppState {
        val base = AppState()
        return base.copy(
            connection = base.connection.copy(state = if (live) ConnState.CONNECTED else ConnState.RECONNECTING),
            runs = RunsState(commands = listOf(cmd), runs = run?.let { mapOf(it.runId to it) } ?: emptyMap(), currentRunId = run?.runId),
        )
    }

    @Test fun `the panel lists the command name and the run output lines`() {
        val run = RunState("r1", "echo-hello", "RUNNING", lines = listOf(RunLine(1, "stdout", "hello", false), RunLine(2, "stderr", "warn", false)), lastSequence = 2)
        runDesktopComposeUiTest(900, 500) {
            setContent { RunPane(state(run), Controller(scope)) }
            onNodeWithTag(RUN_PANE_TAG).assertExists()
            // the name shows twice now: on the command picker and on the run pill
            onNodeWithTag(RUN_COMMAND_PICKER_TAG).assert(hasText("Echo hello", substring = true))
            onNodeWithTag("run-pill-r1").assert(hasText("Echo hello", substring = true))
            onNodeWithText("hello").assertExists()
            onNodeWithText("warn").assertExists()
        }
    }

    @Test fun `stop is live only while a run is in flight and start only when none is`() {
        val running = RunState("r1", "echo-hello", "RUNNING")
        runDesktopComposeUiTest(900, 500) {
            setContent { RunPane(state(running), Controller(scope)) }
            onNodeWithTag(RUN_STOP_TAG).assertIsEnabled()
            onNodeWithTag(RUN_START_TAG).assertIsNotEnabled()
        }
        runDesktopComposeUiTest(900, 500) {
            setContent { RunPane(state(null), Controller(scope)) }
            onNodeWithTag(RUN_STOP_TAG).assertIsNotEnabled()
            onNodeWithTag(RUN_START_TAG).assertIsEnabled()
        }
        runDesktopComposeUiTest(900, 500) {
            setContent { RunPane(state(null, live = false), Controller(scope)) }
            onNodeWithTag(RUN_START_TAG).assertIsNotEnabled()
        }
    }
}
