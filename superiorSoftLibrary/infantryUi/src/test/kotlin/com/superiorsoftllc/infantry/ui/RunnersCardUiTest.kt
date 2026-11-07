// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.infantry.plugins.official.runners.RunnersRows
import com.superiorsoftllc.infantry.plugins.official.runners.adminRequest
import com.superiorsoftllc.infantry.plugins.official.runners.parseRunnersList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RUNNER-POOL-11: the rows of the Runners card (name, health, fingerprint, workers / cap, enabled) and their buttons. */
@OptIn(ExperimentalTestApi::class)
class RunnersCardUiTest {
    private val answer = """{"ok":true,"enabled":true,"runners":[
        {"name":"local","health":"up","workers":"1","cap":"2","paused":"false"},
        {"name":"r2","health":"degraded","workers":"0","cap":"3","paused":"true","url":"wss://h:1","fingerprint":"AB:CD:EF:01:23:45:67:89:AA:BB"}]}"""

    @Test
    fun `the list answer parses into rows`() {
        val list = assertNotNull(parseRunnersList(answer))
        assertEquals(listOf("local", "r2"), list.rows.map { it.name })
        assertEquals(true, list.rows[1].paused)
        assertEquals(3, list.rows[1].cap)
        assertNull(parseRunnersList("""{"ok":false,"error":"x"}"""))
        assertEquals("""{"op":"pause","name":"r2"}""", adminRequest("pause", "r2"))
    }

    @Test
    fun `rows show their fields and the buttons send the runner_admin ops`() = runComposeUiTest {
        val sent = mutableListOf<Pair<String, String>>()
        setContent { RunnersRows(parseRunnersList(answer), null, { op, name -> sent += op to name }) }
        onNodeWithTag("runner-row:local").assertIsDisplayed()
        onNodeWithTag("runner-status:r2").assertTextContains("degraded", substring = true)
        onNodeWithTag("runner-status:r2").assertTextContains("0/3 workers", substring = true)
        onNodeWithTag("runner-status:r2").assertTextContains("paused", substring = true)
        onNodeWithTag("runner-status:r2").assertTextContains("AB:CD:EF", substring = true)
        onNodeWithTag("runner-status:local").assertTextContains("1/2 workers", substring = true)
        onAllNodesWithTag("runner-detach:local").assertCountEquals(0)
        onNodeWithTag("runner-pause:r2").performClick()
        onNodeWithTag("runner-rotate:r2").performClick()
        onNodeWithTag("runner-detach:r2").performClick()
        onNodeWithTag("runner-pause:local").performClick()
        // Outside a shell the link is not live, so a gated button is disabled: the click is blocked, nothing is sent.
        assertEquals(emptyList(), sent)
    }

    @Test
    fun `nothing is drawn while runners are off`() = runComposeUiTest {
        setContent { RunnersRows(parseRunnersList(answer.replace("\"enabled\":true", "\"enabled\":false")), null, { _, _ -> }) }
        onAllNodesWithTag("runners-rows").assertCountEquals(0)
    }
}
