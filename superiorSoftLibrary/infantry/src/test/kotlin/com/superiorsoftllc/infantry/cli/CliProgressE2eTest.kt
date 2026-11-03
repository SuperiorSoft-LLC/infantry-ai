// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.InfantryBin
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.session.SessionProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract 1.117.0 over a REAL daemon: the `progress` request frame and its answer, the `session` push carrying the
 * progress, and the real [Cli.run] `progress` command reaching the session named by `INFANTRY_SESSION_ID`.
 */
class CliProgressE2eTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private fun launch() = DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-progress-test").toFile())
        .also { fixtures += it; it.client.await(Envelope.READY) }

    private fun DaemonFixture.spawnSession(): String {
        val owner = connectClient()
        owner.send(Envelope(Envelope.PROMPT, "hello"))
        val session = owner.awaitWhere("a new session's turn", 10_000) { it.type == Envelope.TURN && it.sessionId != null }.sessionId!!
        owner.awaitWhere("the end of $session's first turn", 10_000) { it.type == Envelope.FINISHED && it.sessionId == session }
        return session
    }

    private fun DaemonFixture.pushedProgress(session: String, percent: Double) = client.awaitWhere("a session push with $percent%", 10_000) {
        it.type == Envelope.SESSION && it.data.contains(session) &&
            json.parseToJsonElement(it.data).jsonObject["progress"]?.jsonObject?.get("percent")?.jsonPrimitive?.content?.toDouble() == percent
    }

    @Test fun `the progress frame answers the value and the session push carries it`() {
        val f = launch()
        val session = f.spawnSession()
        f.client.send(Envelope(Envelope.PROGRESS, """{"op":"count","done":3,"total":12,"label":"tests"}""", session))
        val answer = f.client.awaitWhere("the progress answer", 10_000) { it.type == Envelope.PROGRESS && it.sessionId == session }
        val p = json.decodeFromString(SessionProgress.serializer(), answer.data)
        assertEquals("count", p.mode)
        assertEquals(25.0, p.percent)
        f.pushedProgress(session, 25.0)

        f.client.send(Envelope(Envelope.PROGRESS, """{"op":"percent"}""", session))
        val bad = f.client.awaitWhere("a refused report", 10_000) { it.type == Envelope.ERROR && it.data.startsWith("progress refused") }
        assertTrue(bad.data.contains("percent"), bad.data)
        f.client.send(Envelope(Envelope.PROGRESS, """{"op":"percent","percent":5}"""))
        f.client.awaitWhere("no session id refused", 10_000) { it.type == Envelope.ERROR && it.data.contains("sessionId") }
        f.client.send(Envelope(Envelope.PROGRESS, """{"op":"clear"}""", session))
        f.client.awaitWhere("the cleared answer", 10_000) { it.type == Envelope.PROGRESS && it.data.trim() == "null" }
    }

    @Test fun `infantry progress reaches the session over the real cli`() {
        val f = launch()
        val session = f.spawnSession()
        val env = CliTestEnv.vars + mapOf(AccessPolicy.SESSION_ID_ENV to session, InfantryBin.URL_ENV to "ws://127.0.0.1:${f.port}/ws")
        val out = StringBuilder()
        val err = StringBuilder()
        val code = Cli.run(arrayOf("progress", "40", "compile"), out, err, getenv = { env[it] }, input = { StringReader("") })
        assertEquals(0, code, "err: $err")
        assertEquals("", out.toString())
        f.pushedProgress(session, 40.0)
        val show = StringBuilder()
        Cli.run(arrayOf("progress", "show"), show, err, getenv = { env[it] }, input = { StringReader("") })
        assertTrue(show.toString().startsWith("40% - compile"), show.toString())
    }
}
