package com.superiorsoftllc.agenthq.experiments.pitoolbridge

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.harness.pi.PiCommand
import com.superiorsoftllc.agenthq.harness.pi.PiLaunchExtras
import com.superiorsoftllc.agenthq.stats.SessionStatsService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pi tool bridge experiment: off = nothing attached, on = attached with the expected exposure, A/B stats recorded. */
class PiToolBridgeTest {
    private val dir: File = Files.createTempDirectory("pitoolbridge").toFile()
    private val mcpFile get() = File(dir, ".pi/mcp.json")
    private var projectOn = false

    private fun env(role: String = "fullstack", override: String? = null) = buildMap {
        put("AGENTHQ_PROJECT_ID", "p1"); put("AGENTHQ_ROLE", role); put("AGENTHQ_SESSION_ID", "s1")
        if (override != null) put(PiToolBridge.ENV, override)
    }

    @BeforeTest fun install() {
        PiToolBridge.projectSwitch = { projectOn }
    }

    @AfterTest fun cleanup() {
        PiToolBridge.projectSwitch = { false }
        PiLaunchExtras.args = { _, _ -> emptyList() }
        dir.deleteRecursively()
    }

    @Test
    fun offByDefaultAttachesNothing() {
        val args = PiToolBridge.launchArgs(dir, env())
        assertEquals(emptyList(), args)
        assertFalse(mcpFile.exists(), "off must not write a config file")
        // The pi command line itself never carries MCP or trust args: the seam is only the extras.
        val command = PiCommand.command(listOf("pi"), "m", null, rpc = true, toolApproval = ToolApproval.DENY)
        assertTrue(command.none { it.contains("mcp", ignoreCase = true) || it == "--approve" }, command.toString())
        assertEquals(emptyList(), PiLaunchExtras.args(dir, env()), "an unregistered seam adds nothing")
    }

    @Test
    fun projectSwitchOnAttachesWithTheExpectedExposure() {
        projectOn = true
        assertEquals(listOf("--approve"), PiToolBridge.launchArgs(dir, env()))
        val root = Json.parseToJsonElement(mcpFile.readText()) as JsonObject
        assertContains(root.toString(), PiToolBridge.MARKER)
        val server = ((root["mcpServers"] as JsonObject)["agenthq"]) as JsonObject
        assertEquals("deferred", (server["exposure"] as kotlinx.serialization.json.JsonPrimitive).content)
        val exposure = server["toolExposure"] as JsonObject
        PiToolBridge.DIRECT.forEach { assertEquals("direct", (exposure[it] as kotlinx.serialization.json.JsonPrimitive).content, it) }
        assertEquals("hidden", (exposure["agenthq_spawn"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("hidden", (exposure["agenthq_ticket_update"] as kotlinx.serialization.json.JsonPrimitive).content, "a worker may not mutate tickets")
    }

    @Test
    fun leadRolesKeepTicketToolsButNeverDestructiveOnes() {
        projectOn = true
        PiToolBridge.launchArgs(dir, env(role = "pm"))
        val exposure = (((Json.parseToJsonElement(mcpFile.readText()) as JsonObject)["mcpServers"] as JsonObject)["agenthq"] as JsonObject)["toolExposure"] as JsonObject
        assertNull(exposure["agenthq_ticket_update"])
        assertNotNull(exposure["agenthq_kill"])
    }

    @Test
    fun envOverrideBeatsTheProjectSetting() {
        projectOn = true
        assertEquals(emptyList(), PiToolBridge.launchArgs(dir, env(override = "off")))
        projectOn = false
        assertEquals(listOf("--approve"), PiToolBridge.launchArgs(dir, env(override = "on")))
    }

    @Test
    fun flippingOffRemovesOurFileButNeverAUsersOwn() {
        projectOn = true
        PiToolBridge.launchArgs(dir, env())
        assertTrue(mcpFile.isFile)
        projectOn = false
        PiToolBridge.launchArgs(dir, env())
        assertFalse(mcpFile.exists(), "the next start after a flip to off removes the bridge's file")

        mcpFile.parentFile.mkdirs()
        mcpFile.writeText("""{"mcpServers":{"mine":{}}}""")
        PiToolBridge.launchArgs(dir, env())
        assertEquals("""{"mcpServers":{"mine":{}}}""", mcpFile.readText(), "off leaves a user's own file alone")
        projectOn = true
        assertEquals(emptyList(), PiToolBridge.launchArgs(dir, env()), "on does not overwrite a user's own file either")
        assertEquals("""{"mcpServers":{"mine":{}}}""", mcpFile.readText())
    }

    @Test
    fun registrationInstallsTheHooksAndNothingOutsideTheSwitch() {
        val stats = SessionStatsService(dir)
        PiToolBridgeRegistration.register({ it == "p1" }, stats)
        assertEquals(listOf("--approve"), PiLaunchExtras.args(dir, env()))
        assertEquals(emptyList(), PiLaunchExtras.args(dir, env().plus("AGENTHQ_PROJECT_ID" to "other")))
    }

    @Test
    fun statsTagTheBridgeSessionAndRecordTheABMeasures() {
        val stats = SessionStatsService(dir)
        stats.addTagger(PiToolBridge::tagOf)
        val t0 = Instant.parse("2026-10-24T10:00:00.000Z").toEpochMilli()
        fun iso(ms: Long) = Instant.ofEpochMilli(t0 + ms).toString()
        fun usage(input: Int) = """"usage":{"input":$input,"output":10,"cacheRead":0,"cacheWrite":0,"totalTokens":${input + 10},"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}"""
        val sessions = File(dir, ".pi/agent/sessions/--proj--").apply { mkdirs() }
        File(sessions, "2026-10-24T10-00-00-000Z_pi-ab.jsonl").writeText(listOf(
            """{"type":"session","version":3,"id":"pi-ab","timestamp":"${iso(0)}","cwd":"/w"}""",
            """{"type":"message","timestamp":"${iso(0)}","message":{"role":"system","content":"","sections":{"mcp_servers":"agenthq: deferred"}}}""",
            """{"type":"message","timestamp":"${iso(100)}","message":{"role":"user","content":[{"type":"text","text":"go"}]}}""",
            """{"type":"message","timestamp":"${iso(1000)}","message":{"role":"assistant","content":[{"type":"toolCall","id":"c1","name":"read","arguments":{}}],"provider":"x","model":"m",${usage(100)},"timestamp":${t0 + 1000}}}""",
            """{"type":"message","timestamp":"${iso(1500)}","message":{"role":"toolResult","toolCallId":"c1","content":[{"type":"text","text":"abcdefghij"}],"timestamp":${t0 + 1500}}}""",
            """{"type":"message","timestamp":"${iso(3500)}","message":{"role":"assistant","content":[{"type":"toolCall","id":"c2","name":"edit","arguments":{}}],"provider":"x","model":"m",${usage(200)},"timestamp":${t0 + 3500}}}""",
            """{"type":"message","timestamp":"${iso(3600)}","message":{"role":"toolResult","toolCallId":"c2","content":[{"type":"text","text":"ok"}],"timestamp":${t0 + 3600}}}""",
            """{"type":"message","timestamp":"${iso(4000)}","message":{"role":"assistant","content":[{"type":"text","text":"done"}],"provider":"x","model":"m",${usage(300)},"timestamp":${t0 + 4000}}}""",
        ).joinToString("\n", postfix = "\n"))

        val s = stats.read("hq-ab", "pi", "pi-ab", "idle")
        assertEquals(listOf(PiToolBridge.TAG), s.tags)
        assertEquals(100L, s.firstContextTokens)
        assertEquals(1, s.toolCallsBeforeFirstEdit, "one read before the edit")
        assertEquals(3500L, s.msToFirstEdit)
        assertEquals(2, s.toolResults)
        assertEquals(6L, s.avgToolResultChars)
        assertEquals(10L, s.maxToolResultChars)
        assertEquals(2000L, s.stepMsP90, "steps were 2000ms and 400ms")
        assertEquals(1200L, s.stepMsMean)
    }
}
