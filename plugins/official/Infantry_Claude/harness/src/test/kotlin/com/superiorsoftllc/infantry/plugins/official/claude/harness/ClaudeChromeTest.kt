// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessAnswer
import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpServerState
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BROWSER-OWNER-1: no grant = no --chrome; grant + owner yes = --chrome; grant without the yes = start refused. */
class ClaudeChromeTest {
    private val granted = McpPlan(add = listOf(ClaudeChrome.listing(true).spec))
    private val yes = HarnessAnswer(listOf(ClaudeChrome.YES), null, true)

    @Test
    fun `no grant means no --chrome and no question`() {
        var asked = 0
        val chrome = ClaudeChrome.chromeFor(McpPlan.EMPTY, "s1", mutableSetOf()) { asked++; yes }
        assertFalse(chrome)
        assertEquals(0, asked)
        assertFalse(ClaudeCommand.command("claude", null, null, emptyList(), true, null, chrome = chrome).contains("--chrome"))
        assertFalse(ClaudeCommand.interactiveCommand("claude", null, null, emptyList(), true, null, null, chrome = chrome).contains("--chrome"))
    }

    @Test
    fun `grant plus owner yes gives --chrome, asked once per session, never in the mcp config`() {
        var asked = 0
        val confirmed = mutableSetOf<String>()
        assertTrue(ClaudeChrome.chromeFor(granted, "s1", confirmed) { asked++; yes })
        assertTrue(ClaudeChrome.chromeFor(granted, "s1", confirmed) { asked++; yes })
        assertEquals(1, asked)
        assertTrue(ClaudeCommand.command("claude", null, null, emptyList(), true, null, chrome = true).contains("--chrome"))
        assertTrue(ClaudeCommand.interactiveCommand("claude", null, null, emptyList(), true, null, null, chrome = true).contains("--chrome"))
        assertFalse(ClaudeMcp.configJson(granted) { null }.contains(ClaudeChrome.SERVER))
    }

    @Test
    fun `grant without the owner yes refuses the start`() {
        assertFailsWith<IllegalStateException> { ClaudeChrome.chromeFor(granted, "s1", mutableSetOf()) { HarnessAnswer(listOf(ClaudeChrome.NO), null, true) } }
        assertFailsWith<IllegalStateException> { ClaudeChrome.chromeFor(granted, "s1", mutableSetOf()) { HarnessAnswer.NONE } }
    }

    @Test
    fun `listing offers browser owner and fails with the connect line when the extension is missing`() {
        val missing = ClaudeChrome.listing(false)
        assertEquals(setOf("browser.owner"), missing.spec.provides)
        assertEquals(McpServerState.FAILED, missing.status.state)
        assertEquals("connect the Claude extension in Chrome", missing.status.detail)
        assertEquals(McpServerState.CONNECTED, ClaudeChrome.listing(true).status.state)
        val mcp = ClaudeMcp({ _, _ -> 0 to "" }, chromeConnected = { false })
        var names = emptyList<String>()
        val body: suspend () -> Unit = { names = mcp.list("p").map { it.spec.name } }
        body.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
        assertEquals(listOf("claude-in-chrome"), names)
        assertFalse(ClaudeMcp({ _, _ -> 0 to "" }).let { var n = 1; val b: suspend () -> Unit = { n = it.list("p").size }; b.startCoroutine(Continuation(EmptyCoroutineContext) { r -> r.getOrThrow() }); n } > 0)
    }

    @Test
    fun `extension detection is the native host registration`() {
        assertTrue(ClaudeChrome.extensionConnected(true, { it == ClaudeChrome.HOST_KEY }, { false }, "h"))
        assertFalse(ClaudeChrome.extensionConnected(true, { false }, { true }, "h"))
        assertTrue(ClaudeChrome.extensionConnected(false, { false }, { it.endsWith(".config/google-chrome/NativeMessagingHosts/com.anthropic.claude_code_browser_extension.json") }, "/home/x"))
    }
}
