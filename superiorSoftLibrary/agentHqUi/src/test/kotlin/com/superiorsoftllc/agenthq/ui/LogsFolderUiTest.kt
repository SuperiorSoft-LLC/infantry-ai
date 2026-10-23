package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** PKG-3: Configuration > Logs shows the installed app's log folder and opens it through the platform; no folder, no section. */
@OptIn(ExperimentalTestApi::class)
class LogsFolderUiTest {
    @Test fun `the logs section shows the folder path and Open logs folder asks the platform to open it`() {
        var opened = 0
        runDesktopComposeUiTest(900, 900) {
            setContent { ConfigDialog(UiFlagsDefault, onFlags = {}, onDismiss = {}, logs = LogsFolder("C:/AgentHQ/logs") { opened++; false }) }
            onNodeWithTag(LOGS_PATH_TAG).assertTextEquals("C:/AgentHQ/logs")
            onNodeWithTag(LOGS_OPEN_FAILED_TAG).assertDoesNotExist()
            onNodeWithTag(LOGS_OPEN_TAG).performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag(LOGS_OPEN_FAILED_TAG).assertExists() // the platform said it could not: the user is told
        }
        assertEquals(1, opened)
    }

    @Test fun `no log folder (dev, Android) means no logs section`() {
        runDesktopComposeUiTest(900, 900) {
            setContent { ConfigDialog(UiFlagsDefault, onFlags = {}, onDismiss = {}) }
            onNodeWithTag(LOGS_PATH_TAG).assertDoesNotExist()
            onNodeWithTag(LOGS_OPEN_TAG).assertDoesNotExist()
        }
    }

    private companion object {
        val UiFlagsDefault = com.superiorsoftllc.agenthq.ui.model.UiFlags()
    }
}
