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
import com.superiorsoftllc.infantry.plugins.official.runners.addBearerRequest
import com.superiorsoftllc.infantry.plugins.official.runners.addLinkRequest
import com.superiorsoftllc.infantry.plugins.official.runners.runnerBearerError
import com.superiorsoftllc.infantry.plugins.official.runners.addedRunnerName
import com.superiorsoftllc.infantry.plugins.official.runners.checkRequest
import com.superiorsoftllc.infantry.plugins.official.runners.linkCarriesGrant
import com.superiorsoftllc.infantry.plugins.official.runners.RUNNER_TEST_COST
import com.superiorsoftllc.infantry.plugins.official.runners.parseRunnerCheck
import com.superiorsoftllc.infantry.plugins.official.runners.parseRunnerTest
import com.superiorsoftllc.infantry.plugins.official.runners.refusalAnswer
import com.superiorsoftllc.infantry.plugins.official.runners.runnerNameError
import com.superiorsoftllc.infantry.plugins.official.runners.runnerFailure
import com.superiorsoftllc.infantry.plugins.official.runners.testRequest
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.LinkReply
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
        // RUNNER-GRANT-AT-PAIR-1: the one-time grant is the same frame, and only when the owner ticked it.
        assertEquals("""{"op":"add","link":"$link","grant":"true","projectId":"project-1"}""", addLinkRequest(link, grant = true, projectId = "project-1"))
        assertEquals("""{"op":"add","link":"$link","name":"box","grant":"true","projectId":"project-1"}""", addLinkRequest(link, "box", grant = true, projectId = "project-1"))
        assertEquals("""{"op":"add","link":"$link"}""", addLinkRequest(link, grant = false, projectId = "project-1"), "an unticked grant sends neither the grant nor a project id")
        assertTrue(linkCarriesGrant(link.replace("&fp=AA:BB:CC", "&fp=AA:BB:CC&grant=1")), "grant=1 in the link means the code may grant")
        assertTrue(!linkCarriesGrant(link), "a link without grant=1 carries no authority")
        assertTrue(!linkCarriesGrant("grant=1"), "the flag is read from the query, not from anywhere in the text")
    }

    @Test
    fun `a refused request reads as a failure carrying the daemon reason, never as a pairing`() {
        // RUNNER-UI-ADD-1: the card reads the answer to ITS request (LinkContext.request); an error frame is a failure.
        val refused = LinkReply(Envelope.ERROR, "runners.admin is refused by the policy")
        assertTrue(refused.refused)
        assertEquals("runners.admin is refused by the policy", runnerFailure(refusalAnswer(refused.data)))
        assertNull(addedRunnerName(refusalAnswer(refused.data)))
        assertNotNull(runnerFailure(refusalAnswer("")), "a blank refusal still says something")
        assertTrue(!LinkReply("runner_admin", """{"ok":true,"name":"loc1"}""").refused)
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

    /**
     * RUNNER-PAIR-1 (owner's 2 steps): the form is two fields, Endpoint and Bearer, as the runner printed them; Connect sends exactly
     * those two (the bearer carries the runner's name and certificate pin), and nothing else is asked.
     */
    @Test
    fun `connect sends the endpoint and bearer and nothing else`() = runComposeUiTest {
        val bearer = "irb1.server-1.${"a".repeat(64)}.${"b".repeat(32)}"
        val sent = mutableListOf<Triple<String, String?, Boolean>>()
        setContent { AddRunnerForm(sending = false, error = null, onConnect = { l, n, g -> sent += Triple(l, n, g) }, onDismiss = {}, gate = live) }
        onAllNodesWithTag("runner-add-name").assertCountEquals(0)
        onNodeWithTag("runner-add-endpoint").performTextInput("wss://203.0.113.5:8790")
        onNodeWithTag("runner-add-bearer").performTextInput(bearer)
        onAllNodesWithTag("runner-add-grant").assertCountEquals(0)
        onNodeWithTag("runner-add-connect").performClick()
        assertEquals(listOf<Triple<String, String?, Boolean>>(Triple("wss://203.0.113.5:8790", bearer, false)), sent)
        assertEquals(
            """{"op":"add","endpoint":"wss://203.0.113.5:8790","bearer":"$bearer"}""",
            addBearerRequest(sent.single().first, sent.single().second!!),
        )
        assertNull(runnerBearerError(bearer))
        assertNotNull(runnerBearerError("XXXX-YYYY"), "a pairing code is not a bearer: the field says what to paste")
    }

    /** RUNNER-ADD-UI-1 kept: a whole pairing link pasted into Endpoint pairs as before, with no bearer field. */
    @Test
    fun `connect sends the pasted link and nothing else`() = runComposeUiTest {
        val sent = mutableListOf<Triple<String, String?, Boolean>>()
        setContent { AddRunnerForm(sending = false, error = null, onConnect = { l, n, g -> sent += Triple(l, n, g) }, onDismiss = {}, gate = live) }
        onNodeWithTag("runner-add-connect").assertExists()
        onNodeWithTag("runner-add-endpoint").performTextInput(link)
        onAllNodesWithTag("runner-add-bearer").assertCountEquals(0)
        // RUNNER-GRANT-AT-PAIR-1: a link with no grant=1 draws no grant tick at all.
        onAllNodesWithTag("runner-add-grant").assertCountEquals(0)
        onNodeWithTag("runner-add-connect").performClick()
        assertEquals(listOf<Triple<String, String?, Boolean>>(Triple(link, null, false)), sent)
        assertEquals("""{"op":"add","link":"$link"}""", addLinkRequest(sent.single().first, sent.single().second))
    }

    /**
     * RUNNER-GRANT-AT-PAIR-1: a link whose code was issued with `--grant-on-pair` draws the tick, ticked, naming this window's
     * project; connecting sends the grant, and unticking it sends none.
     */
    @Test
    fun `a grant-carrying link offers the one confirm, ticked, naming this project`() = runComposeUiTest {
        val granting = link.replace("&fp=AA:BB:CC", "&fp=AA:BB:CC&grant=1")
        val sent = mutableListOf<Triple<String, String?, Boolean>>()
        setContent {
            AddRunnerForm(
                sending = false, error = null, onConnect = { l, n, g -> sent += Triple(l, n, g) }, onDismiss = {}, gate = live,
                grantProjectId = "project-73b27dff", grantProjectName = "Infantry AI",
            )
        }
        onNodeWithTag("runner-add-endpoint").performTextInput(granting)
        onNodeWithTag("runner-add-grant").assertIsDisplayed()
        onNodeWithTag("runner-add-grant-label").assertTextContains("Infantry AI (project-73b27dff)", substring = true)
        onNodeWithTag("runner-add-connect").performClick()
        assertEquals(Triple<String, String?, Boolean>(granting, null, true), sent.single(), "the tick starts ticked: one confirm, not two")
        assertEquals(
            """{"op":"add","link":"$granting","grant":"true","projectId":"project-73b27dff"}""",
            addLinkRequest(sent.single().first, sent.single().second, sent.single().third, "project-73b27dff"),
        )
    }

    @Test
    fun `granting needs a project this window is on`() = runComposeUiTest {
        val granting = link.replace("&fp=AA:BB:CC", "&fp=AA:BB:CC&grant=1")
        val sent = mutableListOf<Triple<String, String?, Boolean>>()
        setContent { AddRunnerForm(sending = false, error = null, onConnect = { l, n, g -> sent += Triple(l, n, g) }, onDismiss = {}, gate = live) }
        onNodeWithTag("runner-add-endpoint").performTextInput(granting)
        onNodeWithTag("runner-add-grant-label").assertTextContains("no project is selected", substring = true)
        onNodeWithTag("runner-add-connect").performClick()
        assertEquals(Triple<String, String?, Boolean>(granting, null, false), sent.single(), "with no project there is nothing to grant, and nothing is sent")
    }

    @Test
    fun `a refusal from the daemon is shown under the fields`() = runComposeUiTest {
        val refusal = "no host answered: 100.64.0.7:8790 (connect timed out); 10.0.0.4:8790 (certificate does not match the pin)"
        setContent { AddRunnerForm(sending = false, error = refusal, onConnect = { _, _, _ -> }, onDismiss = {}, gate = live) }
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
    fun `the test op names the runner and only the harness or model that was chosen`() {
        assertEquals("""{"op":"test","name":"box"}""", testRequest("box"))
        assertEquals("""{"op":"test","name":"box","harness":"echo"}""", testRequest("box", " echo "))
        assertEquals("""{"op":"test","name":"box","model":"m1"}""", testRequest("box", null, "m1"))
        assertEquals("""{"op":"test","name":"box"}""", testRequest("box", "  ", " "), "a blank choice is not sent: the daemon picks")
    }

    @Test
    fun `a test answer reads back with its reply, and a failed one with its last lines`() {
        val answered = assertNotNull(parseRunnerTest("""{"ok":true,"harness":"echo","model":"","seconds":1,"ms":412,"sessionId":"s-1","reply":"echo: ready"}"""))
        assertTrue(answered.ok)
        assertEquals("echo: ready", answered.reply)
        assertEquals("echo 412 ms", answered.took)
        assertNull(answered.error)

        val timedOut = assertNotNull(
            parseRunnerTest("""{"ok":false,"error":"no answer from 'claude' on 'box' within 90 s (limits.runners.testTurnSec)","harness":"claude","model":"opus","seconds":90,"ms":90001,"lastLines":["[turn] Reply with the single word: ready"]}"""),
        )
        assertNull(timedOut.reply)
        assertTrue(!timedOut.ok)
        assertTrue(timedOut.error.orEmpty().contains("no answer from 'claude'"))
        assertEquals(listOf("[turn] Reply with the single word: ready"), timedOut.lastLines)
        assertEquals("claude opus 90.0 s", timedOut.took)
        // Neither shape is a success: a bare error still reads, and nonsense does not.
        assertNotNull(parseRunnerTest("""{"ok":false,"error":"no logged-in harness on 'box'; run `infantry runner check box`"}"""))
        assertNull(parseRunnerTest("not an answer"))
        assertNull(parseRunnerTest("""{"ok":true}"""))
    }

    @Test
    fun `the test button runs one turn and its answer is drawn under the check rows`() = runComposeUiTest {
        var tests = 0
        var again = 0
        val answer = parseRunnerTest("""{"ok":true,"harness":"echo","model":"","seconds":1,"ms":412,"sessionId":"s-1","reply":"echo: ready"}""")
        setContent {
            RunnerCheckRows(
                parseRunnerCheck(checkAnswer), null, checking = false, onAgain = { again++ }, gate = live,
                onTest = { tests++ }, test = answer,
            )
        }
        onNodeWithTag("runner-check-row:link").assertIsDisplayed()
        onNodeWithTag("runner-test-cost").assertTextContains(RUNNER_TEST_COST, substring = true)
        onNodeWithTag("runner-test-reply").assertIsDisplayed()
        onNodeWithTag("runner-test-summary").assertTextContains("answered in echo 412 ms", substring = true)
        onNodeWithTag("runner-test-text").assertTextContains("echo: ready", substring = true)
        onNodeWithTag("runner-test").performClick()
        assertEquals(1, tests)
        assertEquals(0, again)
    }

    @Test
    fun `a test with no answer draws its error and the transcript tail`() = runComposeUiTest {
        val answer = parseRunnerTest("""{"ok":false,"error":"no answer from 'claude' on 'box' within 90 s","harness":"claude","model":"opus","seconds":90,"ms":90001,"lastLines":["[turn] Reply with the single word: ready","error: rate limited"]}""")
        setContent { RunnerCheckRows(null, null, checking = false, onAgain = {}, gate = live, onTest = {}, test = answer) }
        onNodeWithTag("runner-test-error").assertIsDisplayed()
        onNodeWithTag("runner-test-summary").assertTextContains("no answer in claude opus 90.0 s", substring = true)
        onNodeWithTag("runner-test-text").assertTextContains("no answer from 'claude'", substring = true)
    }

    @Test
    fun `while a test runs the wait is drawn and the button is off`() = runComposeUiTest {
        setContent { RunnerCheckRows(null, null, checking = false, onAgain = {}, gate = live, onTest = {}, testing = true) }
        onNodeWithTag("runner-test-wait").assertIsDisplayed()
        onAllNodesWithTag("runner-test-reply").assertCountEquals(0)
    }

    @Test
    fun `while a check runs the rows say so`() = runComposeUiTest {
        setContent { RunnerCheckRows(null, null, checking = true, onAgain = {}, gate = live) }
        onNodeWithTag("runner-check-wait").assertIsDisplayed()
        onAllNodesWithTag("runner-check-row:link").assertCountEquals(0)
    }

    @Test
    fun `the name field rejects a bad name with an example`() {
        assertEquals(null, runnerNameError(""))
        assertEquals(null, runnerNameError("docker-runner"))
        assertTrue(runnerNameError("Docker Runner")!!.contains("docker-runner"))
    }
}
