// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PLUGIN-HOTSWAP-1: an agent is never interrupted by a plugin swap. A scripted session streams while one official
 * daemon+UI plugin (Infantry_Notes) is reloaded live in the same daemon: every chunk arrives, in order, the run finishes
 * normally, and it is the same daemon (same server, same session manager) before and after. Real daemon on port 0, temp state.
 */
class SessionDuringPluginSwapTest {
    private val chunks = 40

    private val script = StubScript(
        stdoutLines = buildList {
            add("""{"type":"step_start","sessionID":"stub-hotswap"}""")
            repeat(chunks) { add("""{"type":"text","text":"swap-chunk-$it "}""") }
            add("""{"type":"step_finish"}""")
        },
    )

    @Test
    fun `a running session keeps streaming while a daemon plugin is reloaded live`() {
        val fixture = DaemonFixture.launch(script, runner = ScriptedRunner(script, delayMs = 50), pluginsRoot = TestDaemonPlugins.fullRoot())
        try {
            val client = fixture.client
            client.await(Envelope.READY)
            val daemonBefore = fixture.daemon
            val managerBefore = fixture.daemon.manager
            val host = fixture.daemon.pluginHost()
            assertEquals("loaded", host.status("Infantry_Notes"), "the official plugin to swap is running")

            client.send(Envelope(Envelope.PROMPT, "stream through a plugin swap"))
            val sessionId = client.awaitWhere("the new session", 10_000) { it.type == Envelope.SESSION && it.sessionId != null }.sessionId!!
            client.awaitWhere("the first streamed chunk", 10_000) { it.type == Envelope.TEXT_DELTA && it.sessionId == sessionId }

            val out = host.reload("Infantry_Notes")
            val seenAtSwap = client.of(Envelope.TEXT_DELTA).count { it.sessionId == sessionId }
            assertTrue(out.ok, out.message)
            assertTrue("Infantry_Notes" in out.loaded, out.message)
            assertTrue(seenAtSwap < chunks, "the swap happened while the session was still streaming ($seenAtSwap of $chunks)")

            client.awaitWhere("the session's finished frame", 20_000) { it.type == Envelope.FINISHED && it.sessionId == sessionId }
            val text = client.of(Envelope.TEXT_DELTA).filter { it.sessionId == sessionId }.joinToString("") { it.data }
            val expected = (0 until chunks).map { "swap-chunk-$it" }
            val got = Regex("swap-chunk-\\d+").findAll(text).map { it.value }.toList()
            assertEquals(expected, got, "every chunk arrived once, in order, across the swap")

            assertSame(daemonBefore, fixture.daemon)
            assertSame(managerBefore, fixture.daemon.manager, "the daemon never restarted")
            assertEquals("loaded", fixture.daemon.pluginHost().status("Infantry_Notes"))
            assertTrue(fixture.health().isNotEmpty(), "the daemon still answers")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
