// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.infantry.plugins.official.runners.ConnectRunnerForm
import com.superiorsoftllc.infantry.plugins.official.runners.RunnersCard
import com.superiorsoftllc.infantry.plugins.official.runners.connectRequest
import com.superiorsoftllc.infantry.plugins.official.runners.runnerPortError
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RUNNER-PAIR-1: the one-step Connect a runner form on the Runners card, and the exact `connect` op it sends. */
@OptIn(ExperimentalTestApi::class)
class RunnersConnectUiTest {
    private val live = object : LinkContext {
        override val live = true
        override val contract = "1.200.0"
    }

    @Test
    fun `the connect op carries the name and command, and the address only when typed`() {
        assertEquals("""{"op":"connect","name":"box","via":"docker exec efd-runner2"}""", connectRequest(" box ", " docker exec efd-runner2 "))
        assertEquals(
            """{"op":"connect","name":"box","via":"ssh me@server","host":"localhost","port":"18921"}""",
            connectRequest("box", "ssh me@server", "localhost", "18921"),
        )
        assertEquals("""{"op":"connect","name":"box","via":"x"}""", connectRequest("box", "x", "  ", ""))
        assertNull(runnerPortError(""))
        assertNull(runnerPortError("18921"))
        assertNotNull(runnerPortError("0"))
        assertNotNull(runnerPortError("abc"))
    }

    @Test
    fun `the card offers Connect a runner next to Add runner`() = runComposeUiTest {
        var opened = 0
        setContent { RunnersCard(null, null, onAdd = {}, onOp = { _, _ -> }, onCheck = {}, onConnect = { opened++ }) }
        onNodeWithTag("runners-connect").assertIsDisplayed()
        onNodeWithTag("runners-add").assertIsDisplayed()
        onNodeWithTag("runners-connect").performClick()
        assertEquals(0, opened, "outside a shell the link is not live, so the gated Connect button is disabled like Add")
    }

    @Test
    fun `connect stays off until a valid name and a command are typed`() = runComposeUiTest {
        setContent { ConnectRunnerForm(sending = false, error = null, onConnect = { _, _, _, _ -> }, onDismiss = {}, gate = live) }
        onNodeWithTag("runner-connect-go").assertIsNotEnabled()
        onNodeWithTag("runner-connect-name").performTextInput("Bad Name")
        onNodeWithTag("runner-connect-name-error", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("runner-connect-via").performTextInput("docker exec efd-runner2")
        onNodeWithTag("runner-connect-go").assertIsNotEnabled()
    }

    @Test
    fun `a bad port is said at once and turns connect off`() = runComposeUiTest {
        setContent { ConnectRunnerForm(sending = false, error = null, onConnect = { _, _, _, _ -> }, onDismiss = {}, gate = live) }
        onNodeWithTag("runner-connect-name").performTextInput("efd-runner2")
        onNodeWithTag("runner-connect-via").performTextInput("docker exec efd-runner2")
        onNodeWithTag("runner-connect-go").assertIsEnabled()
        onNodeWithTag("runner-connect-port").performTextInput("99999")
        onNodeWithTag("runner-connect-port-error").assertIsDisplayed()
        onNodeWithTag("runner-connect-go").assertIsNotEnabled()
    }

    @Test
    fun `connect sends name, command, address and port`() = runComposeUiTest {
        val sent = mutableListOf<List<String?>>()
        setContent { ConnectRunnerForm(sending = false, error = null, onConnect = { n, v, h, p -> sent += listOf(n, v, h, p) }, onDismiss = {}, gate = live) }
        onNodeWithTag("runner-connect-name").performTextInput("efd-runner2")
        onNodeWithTag("runner-connect-via").performTextInput("docker exec efd-runner2")
        onNodeWithTag("runner-connect-host").performTextInput("localhost")
        onNodeWithTag("runner-connect-port").performTextInput("18921")
        onNodeWithTag("runner-connect-go").performClick()
        assertEquals(listOf<String?>("efd-runner2", "docker exec efd-runner2", "localhost", "18921"), sent.single())
    }

    @Test
    fun `while sending the wait shows and a refusal is printed as the daemon said it`() = runComposeUiTest {
        setContent { ConnectRunnerForm(sending = true, error = "that runner is already paired with another orchestrator", onConnect = { _, _, _, _ -> }, onDismiss = {}, gate = live) }
        onNodeWithTag("runner-connect-wait").assertIsDisplayed()
        onNodeWithTag("runner-connect-error").assertIsDisplayed()
        onNodeWithTag("runner-connect-go").assertIsNotEnabled()
        onNodeWithTag("runner-connect-cancel").assertIsNotEnabled()
    }
}
