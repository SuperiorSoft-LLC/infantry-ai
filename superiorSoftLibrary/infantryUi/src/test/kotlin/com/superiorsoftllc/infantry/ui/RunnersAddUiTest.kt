// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.infantry.plugins.official.runners.AddRunnerForm
import com.superiorsoftllc.infantry.plugins.official.runners.RunnerCheckRows
import com.superiorsoftllc.infantry.plugins.official.runners.addLinkRequest
import com.superiorsoftllc.infantry.plugins.official.runners.addedRunnerName
import com.superiorsoftllc.infantry.plugins.official.runners.checkRequest
import com.superiorsoftllc.infantry.plugins.official.runners.parseRunnerCheck
import com.superiorsoftllc.infantry.plugins.official.runners.runnerFailure
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNNER-ADD-UI-1 (docs/wiki/RunnerPairing-design.md 4, step 5): pairing a runner from the card with one pasted link, and
 * the health check rows RUNNER-CHECK-1 answers. The requests are pinned exactly, because the daemon reads those keys.
 */
@OptIn(ExperimentalTestApi::class)
class RunnersAddUiTest {
    /** A link as `infantry runner up` prints it (a made-up code and fingerprint: the UI never shows them again). */
    private val link = "infantry://runner?v=1&name=server-1&host=100.64.0.7&port=8790&code=ABC123DEF456GHI7890&fp=AA:BB:CC"

    /** A live link with the production policy: the gate is enabled, as in the window. */
    private val live = object : LinkContext {
        override val live = true
        override val contract = "1.200.0"
    }

    private val checkAnswer = """{"ok":true,"rows":[
        {"id":"link","ok":true,"detail":"reached wss://100.64.0.7:8790 in 41 ms"},
        {"id":"contract","ok":true,"detail":"this runner speaks contract 1.4.0"},
        {"id":"harness:claude","ok":false,"detail":"harness 'claude' is installed but not signed in; a session would start and the model could not answer","fix":"log claude in on this runner: run `claude` there once and sign in"},
        {"id":"grant:project","ok":false,"detail":"my-project is not in this runner's runner.projects (empty = none)","fix":"infantry settings set runner.projects '[\"my-project\"]' on the runner"}]}"""

    @Test
    fun `the add op is exactly the link, plus the name only when one was typed`() {
        assertEquals("""{"op":"add","link":"$link"}""", addLinkRequest(link))
        assertEquals("""{"op":"add","link":"$link","name":"box"}""", addLinkRequest("  $link ", " box "))
        assertEquals("""{"op":"add","link":"$link"}""", addLinkRequest(link, "   "), "a blank override is not sent: the link's own name wins")
        assertEquals("""{"op":"check","name":"box"}""", checkRequest("box"))
        assertEquals("""{"op":"check","name":"box","projectId":"project-1"}""", checkRequest("box", "project-1"))
    }

    @Test
    fun `the answers read back as the card needs them`() {
        val paired = """{"ok":true,"name":"box","url":"wss://100.64.0.7:8790","runnerId":"r-1","skipped":null,"enabledTurnedOn":"true"}"""
        assertEquals("box", addedRunnerName(paired))
        assertNull(runnerFailure(paired))
        // A refusal is shown verbatim (it never carries the code), and an unreadable answer is a failure, not a success.
        assertEquals("no host answered: 100.64.0.7:8790 (connect timed out)", runnerFailure("""{"ok":false,"error":"no host answered: 100.64.0.7:8790 (connect timed out)","presentedFingerprint":null}"""))
        assertTrue(runnerFailure("not an answer").orEmpty().isNotBlank())
        assertNull(addedRunnerName("""{"ok":false,"error":"x"}"""))
        val rows = assertNotNull(parseRunnerCheck(checkAnswer))
        assertEquals(listOf("link", "contract", "harness:claude", "grant:project"), rows.map { it.id })
        assertNull(rows[1].fix, "a row that passed carries no fix")
        assertTrue(rows[2].fix.orEmpty().contains("log claude in on this runner"), "a failing row carries its fix")
        assertNull(parseRunnerCheck("""{"ok":false,"error":"no paired runner 'box'"}"""))
    }

    @Test
    fun `connect sends the pasted link and nothing else`() = runComposeUiTest {
        val sent = mutableListOf<Pair<String, String?>>()
        setContent { AddRunnerForm(sending = false, error = null, onConnect = { l, n -> sent += l to n }, onDismiss = {}, gate = live) }
        onNodeWithTag("runner-add-connect").assertExists()
        onNodeWithTag("runner-add-link").performTextInput(link)
        onNodeWithTag("runner-add-name").performTextInput("box")
        onNodeWithTag("runner-add-connect").performClick()
        assertEquals(listOf<Pair<String, String?>>(link to "box"), sent)
        assertEquals("""{"op":"add","link":"$link","name":"box"}""", addLinkRequest(sent.single().first, sent.single().second))
    }

    @Test
    fun `a refusal from the daemon is shown under the fields`() = runComposeUiTest {
        val refusal = "no host answered: 100.64.0.7:8790 (connect timed out); 10.0.0.4:8790 (certificate does not match the pin)"
        setContent { AddRunnerForm(sending = false, error = refusal, onConnect = { _, _ -> }, onDismiss = {}, gate = live) }
        onNodeWithTag("runner-add-error").assertIsDisplayed()
        onNodeWithTag("runner-add-error").assertTextContains(refusal)
    }

    @Test
    fun `a check answer draws one row per id and the fix under each failure`() = runComposeUiTest {
        var again = 0
        setContent { RunnerCheckRows(parseRunnerCheck(checkAnswer), null, checking = false, onAgain = { again++ }, gate = live) }
        for (id in listOf("link", "contract", "harness:claude", "grant:project")) onNodeWithTag("runner-check-row:$id").assertIsDisplayed()
        onNodeWithTag("runner-check-detail:harness:claude").assertTextContains("not signed in", substring = true)
        onNodeWithTag("runner-check-fix:harness:claude").assertTextContains("log claude in on this runner", substring = true)
        onNodeWithTag("runner-check-fix:grant:project").assertTextContains("runner.projects", substring = true)
        // A row that passed has no fix line: there is nothing to do about it.
        onAllNodesWithTag("runner-check-fix:link").assertCountEquals(0)
        onNodeWithTag("runner-check-again").performClick()
        assertEquals(1, again)
    }

    @Test
    fun `while a check runs the rows say so`() = runComposeUiTest {
        setContent { RunnerCheckRows(null, null, checking = true, onAgain = {}, gate = live) }
        onNodeWithTag("runner-check-wait").assertIsDisplayed()
        onAllNodesWithTag("runner-check-row:link").assertCountEquals(0)
    }
}
