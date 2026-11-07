// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-11 (ticket c308da4b): two REAL InfantryDaemon instances (port 0, own temp state dirs, own Infantry_RemoteRunner
 * plugin): one `--mode both` (the orchestrator) and one `--mode runner`. They are paired through the daemons' own `runner_admin`
 * frame (the path `infantry runner pair|add` takes), the orchestrator's Runners card list shows the runner, and the card's
 * pause / resume ops flip `runners.<name>.paused` that placement and the governor read.
 */
class RunnerPoolEndToEndTest {

    private fun DaemonFixture.admin(vararg fields: Pair<String, String>): JsonObject {
        val before = client.of(com.superiorsoftllc.infantry.runners.RunnerLinkService.ADMIN).size
        send(com.superiorsoftllc.infantry.runners.RunnerLinkService.ADMIN, JsonObject(fields.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) }).toString())
        val reply = client.await(com.superiorsoftllc.infantry.runners.RunnerLinkService.ADMIN, before, 60_000)
        return Json.parseToJsonElement(reply.data) as JsonObject
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun `a both-mode daemon pairs a runner-mode daemon and pauses and resumes it from the card ops`() {
        val plugins = { TestDaemonPlugins.root("official/Infantry_RemoteRunner") }
        val runner = DaemonFixture.launch(mode = DeploymentMode.RUNNER, pluginsRoot = plugins())
        val orchestrator = DaemonFixture.launch(mode = DeploymentMode.BOTH, pluginsRoot = plugins())
        try {
            val pair = runner.admin("op" to "pair", "name" to "r2")
            assertEquals("true", pair.text("ok"), pair.toString())

            val added = orchestrator.admin(
                "op" to "add", "name" to "r2", "url" to "wss://127.0.0.1:${pair.text("port")}",
                "code" to pair.text("code")!!, "fingerprint" to pair.text("fingerprint")!!, "orchestratorName" to "orch",
            )
            assertEquals("true", added.text("ok"), added.toString())

            fun rows() = (orchestrator.admin("op" to "list")["runners"] as JsonArray).map { it as JsonObject }.associateBy { it.text("name") }
            assertTrue(rows().keys.containsAll(listOf("local", "r2")), rows().toString())
            assertEquals("false", rows().getValue("r2").text("paused"))

            val paused = orchestrator.admin("op" to "pause", "name" to "r2")
            assertEquals("true", paused.text("ok"), paused.toString())
            assertEquals("true", rows().getValue("r2").text("paused"))
            assertEquals("true", orchestrator.admin("op" to "resume", "name" to "r2").text("ok"))
            assertEquals("false", rows().getValue("r2").text("paused"))
            assertEquals("false", orchestrator.admin("op" to "pause", "name" to "nope").text("ok"))

            val detached = orchestrator.admin("op" to "detach", "name" to "r2")
            assertEquals("true", detached.text("ok"), detached.toString())
            assertTrue("r2" !in rows().keys, rows().toString())
        } finally {
            orchestrator.stop()
            runner.stop()
        }
    }
}
