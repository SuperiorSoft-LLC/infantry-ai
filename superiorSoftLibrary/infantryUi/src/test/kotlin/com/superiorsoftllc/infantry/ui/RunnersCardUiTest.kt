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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.plugins.official.runners.RUNNERS_CHANGED_FRAME
import com.superiorsoftllc.infantry.plugins.official.runners.RUNNER_ADMIN_FRAME
import com.superiorsoftllc.infantry.plugins.official.runners.RunnersCard
import com.superiorsoftllc.infantry.plugins.official.runners.RunnersCardBlock
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.shell.LinkContext
import com.superiorsoftllc.infantry.ui.shell.LinkReply
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.SessionContext
import com.superiorsoftllc.infantry.ui.shell.ShellContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
        val checked = mutableListOf<String>()
        setContent { RunnersRows(parseRunnersList(answer), null, { op, name -> sent += op to name }, { checked += it }) }
        onNodeWithTag("runner-row:local").assertIsDisplayed()
        onNodeWithTag("runner-status:r2").assertTextContains("degraded", substring = true)
        onNodeWithTag("runner-status:r2").assertTextContains("0/3 workers", substring = true)
        onNodeWithTag("runner-status:r2").assertTextContains("paused", substring = true)
        onNodeWithTag("runner-status:r2").assertTextContains("AB:CD:EF", substring = true)
        onNodeWithTag("runner-status:local").assertTextContains("1/2 workers", substring = true)
        onAllNodesWithTag("runner-detach:local").assertCountEquals(0)
        // This machine is not paired: there is nothing to check over a link.
        onAllNodesWithTag("runner-check:local").assertCountEquals(0)
        onNodeWithTag("runner-pause:r2").performClick()
        onNodeWithTag("runner-rotate:r2").performClick()
        onNodeWithTag("runner-detach:r2").performClick()
        onNodeWithTag("runner-check:r2").performClick()
        onNodeWithTag("runner-pause:local").performClick()
        // Outside a shell the link is not live, so a gated button is disabled: the click is blocked, nothing is sent.
        assertEquals(emptyList(), sent)
        assertEquals(emptyList(), checked)
    }

    /**
     * RUNNER-ADD-UI-1: the card used to be invisible while runners were off, so a new owner could not find where to add
     * one. Its heading and Add runner now always show, with the command to run on the other machine until one is paired.
     */
    @Test
    fun `the card keeps Add runner and the hint while runners are off`() = runComposeUiTest {
        // Only this machine: nothing is paired yet, which is exactly the state the hint is for.
        val fresh = """{"ok":true,"enabled":false,"runners":[{"name":"local","health":"up","workers":"0","cap":"1","paused":"false"}]}"""
        var added = 0
        setContent { RunnersCard(parseRunnersList(fresh), null, { added++ }, { _, _ -> }, {}) }
        onNodeWithTag("runners-add").assertIsDisplayed()
        onNodeWithTag("runners-hint").assertIsDisplayed()
        onNodeWithTag("runners-hint-command").assertTextContains("docker run", substring = true) // RUNNER-PAIR-1: step 1 is one docker run
        // The pool's own rows stay hidden until runners are on (RUNNER-POOL-9).
        onAllNodesWithTag("runners-rows").assertCountEquals(0)
        onNodeWithTag("runners-add").performClick()
        assertEquals(0, added, "outside a shell the link is not live, so the gated Add button is disabled")
    }

    /**
     * RUNNER-CARD-STALE-1: a runner the daemon marks up after the card listed it `down` turns `up` in place when the daemon pushes
     * `runners_changed`, without reopening the card (a new runner read `down` behind its passing check until then).
     */
    @Test
    fun `the row follows a runners_changed push without reopening the card`() = runComposeUiTest {
        var health = "down"
        var lists = 0
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val link = object : LinkContext {
            override val live = true
            override val contract = "1.200.0"
            override fun frames(type: String): Flow<String> = if (type == RUNNERS_CHANGED_FRAME) pushes else kotlinx.coroutines.flow.emptyFlow()
            override suspend fun request(type: String, data: String): LinkReply? {
                assertEquals(RUNNER_ADMIN_FRAME, type)
                lists++
                return LinkReply(type, """{"ok":true,"enabled":true,"runners":[{"name":"efd2","health":"$health","workers":"0","cap":"1","paused":"false"}]}""")
            }
        }
        val session = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SessionContext::class.java)) { _, _, _ -> null } as SessionContext
        val shell = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ShellContext::class.java)) { _, m, _ ->
            when (m.name) { "getLink" -> link; "getSession" -> session; else -> null }
        } as ShellContext
        setContent { CompositionLocalProvider(LocalShellContext provides shell) { RunnersCardBlock().Render(BlockScope(Modifier, null)) } }
        waitForIdle()
        onNodeWithTag("runner-status:efd2").assertTextContains("down ·", substring = true)
        health = "up"
        pushes.tryEmit("{}")
        waitUntil(timeoutMillis = 5_000) { lists >= 2 }
        waitForIdle()
        onNodeWithTag("runner-status:efd2").assertTextContains("up ·", substring = true)
    }
}
