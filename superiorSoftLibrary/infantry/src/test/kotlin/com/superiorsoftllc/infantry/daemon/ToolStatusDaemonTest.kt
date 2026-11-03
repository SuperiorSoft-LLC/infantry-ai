// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.StatusPayload
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AGENT-UX-9: the daemon emits the RUNNING TOOL's NAME on the `status` frame (contract 1.25.0).
 *
 * The real daemon (Ktor/CIO + the real WebSocket route, the real orchestrator and normaliser) is
 * booted with the stub provider, which replays PROVIDER-SHAPED lines -- so the tool name is produced
 * by the real [com.superiorsoftllc.infantry.protocol.EventNormalizer], not by a parallel fake. Nothing
 * is faked except the script.
 */
class ToolStatusDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The `status` frames a TOOL CALL produced. Contract 1.64.0 (BUG-M2-11b-1) also puts the session's own per-turn
     * plumbing (`starting …`, `process started …`) on `status`, with `tool` = the provider id (`stub` here); those are
     * not tool calls, so they are left out.
     */
    private fun toolCallFrames(fixture: DaemonFixture) = fixture.client.of(Envelope.STATUS)
        .filter { json.decodeFromString(StatusPayload.serializer(), it.data).tool != STUB_PROVIDER }

    /** A turn whose provider calls one tool (`Read`), then speaks, then finishes. */
    private val toolTurn = StubScript(
        stdoutLines = listOf(
            """{"type":"step_start","sessionID":"tool-1"}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read"}]}}""",
            """{"type":"text","text":"done"}""",
            """{"type":"step_finish"}""",
        ),
    )

    @Test
    fun aProviderToolUseRidesTheStatusFrame() {
        val fixture = DaemonFixture.launch(toolTurn)
        try {
            fixture.send(Envelope(Envelope.PROMPT, "read the file"))
            fixture.client.await(Envelope.FINISHED)

            val tools = toolCallFrames(fixture)
                .map { json.decodeFromString(StatusPayload.serializer(), it.data) }
                .mapNotNull { it.tool }
            assertEquals(listOf("Read"), tools, "the tool name must ride the status frame, got $tools")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aTurnWithNoToolCallLeavesToolNullAndTheFrameUnchanged() {
        val fixture = DaemonFixture.launch(StubScript.default())
        try {
            fixture.send(Envelope(Envelope.PROMPT, "hello stub"))
            fixture.client.await(Envelope.FINISHED)

            val statuses = toolCallFrames(fixture)
                .map { json.decodeFromString(StatusPayload.serializer(), it.data) }
            assertTrue(statuses.isNotEmpty(), "a streamed turn reports status")
            assertTrue(statuses.all { it.tool == null }, "no tool call -> tool stays null: $statuses")

            // The bytes too: a no-tool status gains NO `tool` key, so a 1.24.0 client sees the exact
            // frame it always did rather than one with a null it must now explain.
            assertTrue(
                toolCallFrames(fixture).none { it.data.contains("\"tool\"") },
                "a no-tool status frame must not gain a tool key: ${toolCallFrames(fixture).map { it.data }}",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    /**
     * A client compiled against 1.24.0: `state` and `detail` only. `ignoreUnknownKeys` is what the
     * contract promises, so the ADDITIVE `tool` field is simply ignored -- this PROVES the additive
     * claim rather than asserting it.
     */
    @Serializable
    private data class OldClientStatus(val state: String, val detail: String = "")

    @Test
    fun anOldClientDecodesANewToolStatusPayload() {
        val wire = json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.BUSY, tool = "Read"))
        assertTrue(wire.contains("\"tool\":\"Read\""), "the tool name must be on the wire: $wire")
        assertEquals(
            OldClientStatus("BUSY", ""),
            json.decodeFromString(OldClientStatus.serializer(), wire),
            "a 1.24.0 client must decode a 1.25.0 status payload unchanged",
        )
    }

    // --- Contract 1.26.0 (AGENT-UX-12): the tool call's salient ARGUMENT rides the same status frame. ---

    /** A turn whose provider calls one tool WITH an argument, then speaks, then finishes. */
    private val activityTurn = StubScript(
        stdoutLines = listOf(
            """{"type":"step_start","sessionID":"act-1"}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"ls -la src"}}]}}""",
            """{"type":"text","text":"done"}""",
            """{"type":"step_finish"}""",
        ),
    )

    @Test
    fun aToolArgumentRidesTheStatusFrameBesideTheName() {
        val fixture = DaemonFixture.launch(activityTurn)
        try {
            fixture.send(Envelope(Envelope.PROMPT, "list src"))
            fixture.client.await(Envelope.FINISHED)

            val payloads = toolCallFrames(fixture)
                .map { json.decodeFromString(StatusPayload.serializer(), it.data) }
                .filter { it.tool != null }
            assertEquals(1, payloads.size, "exactly one tool status: $payloads")
            assertEquals("Bash", payloads.single().tool, "the tool NAME is still on the frame (1.25.0)")
            assertEquals(
                "ls -la src",
                payloads.single().activity,
                "the salient ARGUMENT must ride the SAME status frame, not a bare tool name",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aToolWithNoArgumentAddsNoActivityKey() {
        // The plain toolTurn's `Read` carries no input: `activity` must be absent from the bytes, so a
        // tool call that has nothing to say changes the wire not at all.
        val fixture = DaemonFixture.launch(toolTurn)
        try {
            fixture.send(Envelope(Envelope.PROMPT, "read the file"))
            fixture.client.await(Envelope.FINISHED)
            assertTrue(
                toolCallFrames(fixture).none { it.data.contains("\"activity\"") },
                "an argument-less tool call must not gain an activity key: ${toolCallFrames(fixture).map { it.data }}",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    /** A 1.25.0 client: `state`, `detail` and `tool` only. The additive `activity` must be ignored. */
    @Serializable
    private data class V125ClientStatus(val state: String, val detail: String = "", val tool: String? = null)

    @Test
    fun a125ClientDecodesAnActivityStatus() {
        val wire = json.encodeToString(
            StatusPayload.serializer(),
            StatusPayload(SessionState.BUSY, tool = "Bash", activity = "ls -la"),
        )
        assertTrue(wire.contains("\"activity\":\"ls -la\""), "the argument must be on the wire: $wire")
        assertEquals(
            V125ClientStatus("BUSY", "", "Bash"),
            json.decodeFromString(V125ClientStatus.serializer(), wire),
            "a 1.25.0 client must decode an activity-bearing status unchanged",
        )
    }

    private companion object {
        /** The stub provider's id: what `tool` carries on the session's own Activity frames. */
        const val STUB_PROVIDER = "stub"
    }
}
