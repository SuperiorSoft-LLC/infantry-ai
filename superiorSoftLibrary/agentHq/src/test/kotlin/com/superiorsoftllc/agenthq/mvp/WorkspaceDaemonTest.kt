package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** PW-5: `workspace_save` / `workspace_list`, driven over the real daemon socket like [ProjectDaemonTest]. */
class WorkspaceDaemonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun workspaceListFrameReturnsProjectWorkspaces() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope.PROJECT_SAVE, """{"name":"P"}""")
            val p = json.parseToJsonElement(fixture.client.await(Envelope.PROJECT, timeoutMs = 4_000).data)
                .jsonObject["id"]!!.jsonPrimitive.content
            fixture.send(Envelope.PROJECT_SAVE, """{"name":"Other"}""")
            val other = json.parseToJsonElement(
                fixture.client.await(Envelope.PROJECT, occurrence = 1, timeoutMs = 4_000).data,
            ).jsonObject["id"]!!.jsonPrimitive.content

            fixture.send(Envelope.WORKSPACE_SAVE, """{"projectId":"$p","name":"main"}""")
            fixture.client.await(Envelope.WORKSPACE, timeoutMs = 4_000)

            fixture.send(Envelope.WORKSPACE_LIST, p)
            val listed = json.parseToJsonElement(fixture.client.await(Envelope.WORKSPACES, timeoutMs = 4_000).data).jsonArray
            assertEquals(listOf("main"), listed.map { it.jsonObject["name"]!!.jsonPrimitive.content })

            fixture.send(Envelope.WORKSPACE_LIST, other)
            val none = json.parseToJsonElement(
                fixture.client.await(Envelope.WORKSPACES, occurrence = 1, timeoutMs = 4_000).data,
            ).jsonArray
            assertEquals(0, none.size)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
