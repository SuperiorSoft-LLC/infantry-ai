// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.orchestration.NodeSpec
import com.superiorsoftllc.infantry.orchestration.PodStatus
import com.superiorsoftllc.infantry.orchestration.PodSubmitRequest
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-G2d: one session grew its pod to 24 sessions, because nothing bounded the spawns a session makes.
 *
 * WHERE THE G2 CASCADE CAME FROM (QA run 10/11 on c066bfb2). Every burst of sub-sessions lines up with the
 * pod's session running `gradlew :infantry:test --tests "*CliTranscriptExportTest*"` INSIDE itself. That test
 * runs `Cli.run("submit", "say something", "--port", <fixture>)`; BUG-G2c exports `INFANTRY_URL` into every
 * session, and the CLI let that variable beat an explicit `--port`, so each test's `submit` reached the LIVE
 * daemon and opened a real pod with a real session. The CLI half is fixed in `CliTargetPrecedenceTest`.
 *
 * THIS file is the daemon half: whatever the route, a spawn that names the session it comes from (the envelope's
 * `sessionId` on `pod_submit` / `new`, contract 1.58.0) is charged to that session's pod. Past the pod's cap, or
 * from a session that is itself a spawn, it is refused with a fixed reason and nothing is written. A spawn that
 * names no session (the user, the UI) is never charged.
 *
 * Every test boots the REAL daemon on the stub provider and drives it over a REAL WebSocket.
 */
class SpawnCascadeDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-g2d-test").toFile()).also { fixtures += it }

    /** Send [envelope] and return the first `pod` or `error` frame that arrives AFTER it. */
    private fun podOrError(fixture: DaemonFixture, envelope: Envelope): Envelope {
        val before = fixture.client.frames.size
        fixture.send(envelope)
        val frames = fixture.client.frames
        val deadline = System.currentTimeMillis() + TestWait.defaultMs
        while (System.currentTimeMillis() < deadline) {
            frames.drop(before).firstOrNull { it.type == Envelope.POD || it.type == Envelope.ERROR }?.let { return it }
            Thread.sleep(20)
        }
        error("no pod/error answer to ${envelope.type} within ${TestWait.defaultMs}ms")
    }

    private fun submitEnvelope(goal: String, fromSession: String?): Envelope = Envelope(
        Envelope.POD_SUBMIT,
        json.encodeToString(PodSubmitRequest.serializer(), PodSubmitRequest(goal, listOf(NodeSpec("main", goal)))),
        fromSession,
    )

    /** The session the pod's single node runs in, read from the `pod` answer. */
    private fun runSessionOf(answer: Envelope): String {
        assertEquals(Envelope.POD, answer.type, "expected a pod answer, got ${answer.type}: ${answer.data}")
        val status = json.decodeFromString(PodStatus.serializer(), answer.data)
        return status.runs.values.flatten().single().sessionId
    }

    /** A pod the USER submits: its session is the root a cascade would grow from (pod c56deb66 in the G2 run). */
    private fun userPod(fixture: DaemonFixture): String =
        runSessionOf(podOrError(fixture, submitEnvelope("Implement transcript export", fromSession = null)))

    @Test
    fun aSessionOriginatedSpawnPastThePodCapIsRefusedAndNothingIsWritten() {
        val fixture = launch()
        val root = userPod(fixture)

        // The default cap is 4: four spawns from inside the pod are accepted.
        repeat(4) { i ->
            val answer = podOrError(fixture, submitEnvelope("say something $i", fromSession = root))
            assertEquals(Envelope.POD, answer.type, "spawn #${i + 1} from inside the pod must be accepted: ${answer.data}")
        }
        val sessionsAtCap = fixture.daemon.manager.list().size
        val podsAtCap = fixture.daemon.store.pods.all().size
        assertEquals(1 + 4, sessionsAtCap, "the root and the four sessions it spawned")

        // The fifth is refused with the fixed reason, and the daemon writes nothing for it.
        val refused = podOrError(fixture, submitEnvelope("say something 5", fromSession = root))
        assertEquals(
            Envelope.ERROR, refused.type,
            "a session-originated spawn past the pod cap must be refused, but the daemon answered ${refused.type}: ${refused.data.take(120)}",
        )
        assertTrue(refused.data.startsWith("spawn refused: pod cap"), "the refusal names its fixed reason: ${refused.data}")
        assertEquals(sessionsAtCap, fixture.daemon.manager.list().size, "a refused spawn creates no session")
        assertEquals(podsAtCap, fixture.daemon.store.pods.all().size, "a refused spawn writes no pod")
    }

    @Test
    fun aSessionSpawnedFromInsideAPodMayNotSpawnAgain() {
        val fixture = launch()
        val root = userPod(fixture)
        val child = runSessionOf(podOrError(fixture, submitEnvelope("say something", fromSession = root)))
        val sessions = fixture.daemon.manager.list().size

        val refused = podOrError(fixture, submitEnvelope("and spawn one more", fromSession = child))
        assertEquals(Envelope.ERROR, refused.type, "a depth-2 spawn must be refused: ${refused.data.take(120)}")
        assertTrue(refused.data.startsWith("spawn refused: depth limit"), "the refusal names its fixed reason: ${refused.data}")
        assertEquals(sessions, fixture.daemon.manager.list().size, "a refused spawn creates no session")
    }

    @Test
    fun spawnsThatNameNoSessionAreNeverCharged() {
        val fixture = launch()
        // The user and the UI send `pod_submit` with no session: six of them, past any pod cap, all run.
        repeat(6) { i ->
            val answer = podOrError(fixture, submitEnvelope("user goal $i", fromSession = null))
            assertEquals(Envelope.POD, answer.type, "a user spawn #${i + 1} must never be refused: ${answer.data}")
        }
        assertEquals(6, fixture.daemon.manager.list().size)
    }
}
