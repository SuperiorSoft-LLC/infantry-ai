// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.InfantryBin
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-G2d, the CLI half. The G2 cascade was a test suite run INSIDE a session: its `infantry submit --port <fixture>`
 * calls went to the LIVE daemon, because the daemon exports `INFANTRY_URL` into every session (BUG-G2c) and the CLI let
 * that variable beat a typed `--port`. The daemon half (the pod cap) is `SpawnCascadeDaemonTest`.
 */
class CliTargetPrecedenceTest {

    /** A daemon nobody may reach: if the CLI picks this URL, the test that asserts otherwise fails. */
    private val inherited = "ws://127.0.0.1:1/ws"
    private val insideASession: (String) -> String? = { if (it == InfantryBin.URL_ENV) inherited else null }

    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    @Test
    fun aTypedPortBeatsTheInheritedDaemonUrl() {
        assertEquals("ws://127.0.0.1:9999/ws", Cli.targetUrl(arrayOf("pods", "--port", "9999"), insideASession))
        assertEquals("ws://10.0.0.5:8787/ws", Cli.targetUrl(arrayOf("pods", "--host", "10.0.0.5"), insideASession))
    }

    @Test
    fun withNothingTypedTheInheritedDaemonUrlStillWins() {
        // BUG-G2c is kept: inside a session, a bare `infantry` command reaches the daemon that spawned it.
        assertEquals(inherited, Cli.targetUrl(arrayOf("pods"), insideASession))
        assertEquals("ws://127.0.0.1:8787/ws", Cli.targetUrl(arrayOf("pods"), { null }), "no variable: the default")
        assertEquals(
            "ws://127.0.0.1:7777/ws",
            Cli.targetUrl(arrayOf("pods", "--url", "ws://127.0.0.1:7777/ws", "--port", "9999"), insideASession),
            "--url still beats everything",
        )
    }

    @Test
    fun aSubmitRunInsideASessionIsChargedToThatSessionsPod() {
        val fixture = DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-g2d-cli").toFile())
            .also { fixtures += it }
        fun submit(goal: String, env: (String) -> String?): Triple<Int, String, String> {
            val out = StringBuilder()
            val err = StringBuilder()
            val exit = Cli.run(arrayOf("submit", goal, "--port", fixture.port.toString()), out, err, env)
            return Triple(exit, out.toString(), err.toString())
        }

        // The user's pod: its one session is the root.
        assertEquals(0, submit("Implement transcript export", insideASession).first)
        val root = fixture.daemon.manager.list().single().id
        val fromRoot: (String) -> String? = { if (it == AccessPolicy.SESSION_ID_ENV) root else insideASession(it) }

        repeat(4) { i ->
            val (exit, _, err) = submit("say something $i", fromRoot)
            assertEquals(0, exit, "submit #${i + 1} from inside the session is within the cap: $err")
        }
        val sessions = fixture.daemon.manager.list().size
        val (exit, _, err) = submit("say something 5", fromRoot)
        assertTrue(exit != 0, "the fifth submit from inside the session must fail")
        assertTrue("spawn refused: pod cap" in err, "the CLI prints the daemon's fixed reason: $err")
        assertEquals(sessions, fixture.daemon.manager.list().size, "a refused submit starts no session")
    }
}
