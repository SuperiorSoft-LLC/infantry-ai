package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.RunPhase
import com.superiorsoftllc.infantry.protocol.StatusPayload
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 1c24fdc4 (contract 1.95.0): the real daemon puts the run activity on its `status` frames. Booted with the stub
 * provider replaying claude-shaped lines, so the phases come from the real normaliser, tracker and broadcast; only the
 * script is fake. The turn carries no reasoning: "thinking" around the tool is the turn's, not a thinking event's.
 */
class RunActivityDaemonTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val toolTurn = StubScript(
        stdoutLines = listOf(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Looking."}]}}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"a.kt"}]}}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."}]}}""",
        ),
    )

    private fun phases(fixture: DaemonFixture) = fixture.client.of(Envelope.STATUS)
        .mapNotNull { json.decodeFromString(StatusPayload.serializer(), it.data).runActivity }

    @Test
    fun aTurnReportsThinkingToolRunningThinkingThenIdleOnTheWire() {
        val fixture = DaemonFixture.launch(toolTurn)
        try {
            fixture.send(Envelope(Envelope.PROMPT, "list it"))
            fixture.client.await(Envelope.FINISHED)
            // The session leaving flight is a `session` frame after `finished`; its status carries the final idle.
            fixture.client.awaitWhere("an idle run activity") {
                it.type == Envelope.STATUS && json.decodeFromString(StatusPayload.serializer(), it.data).runActivity?.state == RunPhase.IDLE
            }

            val seen = phases(fixture)
            val collapsed = seen.map { it.state }.fold(listOf<RunPhase>()) { acc, p -> if (acc.lastOrNull() == p) acc else acc + p }
            assertEquals(
                listOf(RunPhase.THINKING, RunPhase.TOOL_RUNNING, RunPhase.THINKING, RunPhase.FINISHED, RunPhase.IDLE),
                collapsed,
                "the wire must carry the run's phases in order, got $seen",
            )
            assertEquals(listOf("Bash: ls"), seen.filter { it.state == RunPhase.TOOL_RUNNING }.map { it.label })
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
