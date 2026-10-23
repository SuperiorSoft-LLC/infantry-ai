package com.superiorsoftllc.agenthq.opencode

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.protocol.EventNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BUG-PERM-2: opencode (serve + run) honours the run's Tool approval, and a reject becomes the PERM-1 notice. */
class OpenCodePermissionsTest {

    private fun props(event: String): JsonObject =
        (Json.parseToJsonElement(event) as JsonObject)["properties"] as JsonObject

    /** A canned opencode 1.x `permission.asked` for [permission]. */
    private fun asked(permission: String) = props(
        """{"type":"permission.asked","properties":{"id":"per_1","sessionID":"ses_1","permission":"$permission",""" +
            """"patterns":["*"],"metadata":{},"always":["*"],"tool":{"messageID":"msg_1","callID":"call_1"}}}""",
    )

    private fun decide(permission: String, mode: ToolApproval) =
        OpenCodeServeAdapter.permissionDecision(asked(permission), mode)!!

    @Test
    fun `serve - deny rejects every permission`() {
        for (p in listOf("bash", "edit", "webfetch")) {
            val d = decide(p, ToolApproval.DENY)
            assertEquals("per_1", d.requestId)
            assertEquals("""{"reply":"reject"}""", d.replyBody, p)
            assertEquals(p, d.deniedTool)
        }
    }

    @Test
    fun `serve - allowAll answers once for every permission`() {
        for (p in listOf("bash", "edit", "webfetch")) {
            val d = decide(p, ToolApproval.ALLOW_ALL)
            assertEquals("""{"reply":"once"}""", d.replyBody, p)
            assertNull(d.deniedTool, p)
        }
    }

    @Test
    fun `serve - allowEdits allows edit permissions only`() {
        assertEquals("""{"reply":"once"}""", decide("edit", ToolApproval.ALLOW_EDITS).replyBody)
        assertEquals("""{"reply":"once"}""", decide("write", ToolApproval.ALLOW_EDITS).replyBody)
        val bash = decide("bash", ToolApproval.ALLOW_EDITS)
        assertEquals("""{"reply":"reject"}""", bash.replyBody)
        assertEquals("bash", bash.deniedTool)
    }

    @Test
    fun `serve - older permission_updated shape names the permission in type`() {
        val old = props("""{"type":"permission.updated","properties":{"id":"per_9","sessionID":"ses_1","type":"edit","pattern":"a.txt"}}""")
        val d = OpenCodeServeAdapter.permissionDecision(old, ToolApproval.ALLOW_EDITS)!!
        assertEquals("per_9", d.requestId)
        assertEquals("""{"reply":"once"}""", d.replyBody)
    }

    @Test
    fun `serve - a request without an id gets no reply`() {
        assertNull(OpenCodeServeAdapter.permissionDecision(props("""{"properties":{"permission":"bash"}}"""), ToolApproval.ALLOW_ALL))
    }

    @Test
    fun `run - argv and env per mode`() {
        val base = listOf("opencode.exe", "run", "--format", "json", "-m", "opencode/x")
        assertEquals(base, OpenCodeCommand.command("opencode.exe", "opencode/x", null, ToolApproval.DENY))
        assertEquals(base, OpenCodeCommand.command("opencode.exe", "opencode/x", null, ToolApproval.ALLOW_EDITS))
        assertEquals(base + "--auto", OpenCodeCommand.command("opencode.exe", "opencode/x", null, ToolApproval.ALLOW_ALL))
        assertEquals(base + listOf("--session", "ses_1", "--auto"), OpenCodeCommand.command("opencode.exe", "opencode/x", "ses_1", ToolApproval.ALLOW_ALL))

        // BUG-PERM-5: every mode carries the agenthq allow; only allowEdits adds edit.
        // MCP-1: plus the session's own mcp.agenthq + its tool allow, merged on top.
        val agentHqOnly = OpenCodePermissions.withAgentHqMcp("""{"permission":{"bash":{"agenthq *":"allow"}}}""", emptyMap())
        assertEquals(mapOf("OPENCODE_CONFIG_CONTENT" to agentHqOnly), OpenCodePermissions.runEnv(ToolApproval.DENY, existing = null))
        assertEquals(mapOf("OPENCODE_CONFIG_CONTENT" to agentHqOnly), OpenCodePermissions.runEnv(ToolApproval.ALLOW_ALL, existing = null))
        assertEquals(
            mapOf("OPENCODE_CONFIG_CONTENT" to OpenCodePermissions.withAgentHqMcp("""{"permission":{"edit":"allow","bash":{"agenthq *":"allow"}}}""", emptyMap())),
            OpenCodePermissions.runEnv(ToolApproval.ALLOW_EDITS, existing = null),
        )
        // The inline config is valid JSON with edit allowed.
        val cfg = Json.parseToJsonElement(OpenCodePermissions.ALLOW_EDITS_CONFIG) as JsonObject
        assertEquals("\"allow\"", (cfg["permission"] as JsonObject)["edit"].toString())
    }

    // --- BUG-PERM-5: the agenthq CLI is allowed in every mode, nothing else is ------------------------------------

    private fun bashAsk(vararg patterns: String) = props(
        """{"type":"permission.asked","properties":{"id":"per_2","sessionID":"ses_1","permission":"bash","patterns":[""" +
            patterns.joinToString(",") { "\"$it\"" } + """],"metadata":{},"always":[]}}""",
    )

    @Test
    fun `serve - an agenthq bash ask is answered once in every mode, deny included`() {
        for (mode in ToolApproval.entries) {
            val d = OpenCodeServeAdapter.permissionDecision(bashAsk("agenthq ticket create --title x"), mode)!!
            assertEquals("""{"reply":"once"}""", d.replyBody, mode.wire)
            assertNull(d.deniedTool, mode.wire)
        }
    }

    @Test
    fun `serve - git status is still rejected in deny, with the denial notice`() {
        val d = OpenCodeServeAdapter.permissionDecision(bashAsk("git status"), ToolApproval.DENY)!!
        assertEquals("""{"reply":"reject"}""", d.replyBody)
        assertEquals("bash", d.deniedTool)
        // An agenthq ask that also carries another command is not an agenthq ask.
        assertEquals("""{"reply":"reject"}""", OpenCodeServeAdapter.permissionDecision(bashAsk("agenthq ticket list", "git status"), ToolApproval.DENY)!!.replyBody)
        // A non-bash permission is never the agenthq exemption, even with an agenthq-looking pattern.
        assertEquals("""{"reply":"reject"}""", OpenCodeServeAdapter.permissionDecision(asked("edit"), ToolApproval.DENY)!!.replyBody)
    }

    @Test
    fun `isAgentHqCommand accepts a plain invocation only`() {
        for (ok in listOf("agenthq", "agenthq ticket create --title \"E2E HELLO\" --desc \"a: b/c\"", "  agenthq mail send  ")) assertTrue(OpenCodePermissions.isAgentHqCommand(ok), ok)
        for (bad in listOf(null, "", "git status", "agenthqx ticket", "echo agenthq", "agenthq x && git push", "agenthq x; rm -rf .",
            "agenthq x | sh", "agenthq x > out", "agenthq `whoami`", "agenthq $(whoami)", "agenthq x\ngit push")) assertFalse(OpenCodePermissions.isAgentHqCommand(bad), bad.toString())
    }

    @Test
    fun `the agenthq allow merges into an inherited config and never overwrites it`() {
        fun perm(json: String) = (Json.parseToJsonElement(OpenCodePermissions.mergeAllowAgentHq(json)) as JsonObject)
        val merged = perm("""{"model":"x/y","permission":{"edit":"ask","bash":{"*":"deny","git *":"ask"}}}""")
        assertEquals("\"x/y\"", merged["model"].toString())
        // Every user rule kept, ours LAST (opencode resolves the last matching pattern).
        assertEquals("""{"edit":"ask","bash":{"*":"deny","git *":"ask","agenthq *":"allow"}}""", merged["permission"].toString())
        assertEquals("""{"*":"ask","agenthq *":"allow"}""", (perm("""{"permission":{"bash":"ask"}}""")["permission"] as JsonObject)["bash"].toString())
        assertEquals("""{"*":"deny","bash":{"*":"deny","agenthq *":"allow"}}""", perm("""{"permission":"deny"}""")["permission"].toString())
        // A user rule that denies agenthq does not win: it is AgentHQ's own control plane.
        assertEquals("""{"bash":{"*":"ask","agenthq *":"allow"}}""", perm("""{"permission":{"bash":{"agenthq *":"deny","*":"ask"}}}""")["permission"].toString())
        for (bad in listOf(null, "", "not json", "[1]")) assertEquals("""{"permission":{"bash":{"agenthq *":"allow"}}}""", OpenCodePermissions.mergeAllowAgentHq(bad))
        // The allowEdits path keeps both: edit (PERM-4) and agenthq (PERM-5), and the user's keys.
        val edits = Json.parseToJsonElement(OpenCodePermissions.runEnv(ToolApproval.ALLOW_EDITS, existing = """{"username":"u"}""").getValue("OPENCODE_CONFIG_CONTENT")) as JsonObject
        assertEquals(OpenCodePermissions.withAgentHqMcp("""{"username":"u","permission":{"edit":"allow","bash":{"agenthq *":"allow"}}}""", emptyMap()), edits.toString())
    }

    @Test
    fun `a reject emits the denial notice line`() {
        val d = decide("bash", ToolApproval.DENY)
        val line = OpenCodePermissions.denialLine("ses_1", d.deniedTool!!)
        assertEquals(listOf<EventNormalizer.Line>(EventNormalizer.Line.Denial("opencode", "bash")), EventNormalizer.normalise(line))
    }

    // --- PROMPT-4 ---------------------------------------------------------------------------------------

    @Test
    fun `serve - the owner's decision maps to once, always, reject`() {
        assertEquals("""{"reply":"once"}""", OpenCodePermissions.serveReplyBody(com.superiorsoftllc.agenthq.prompts.Decision.ALLOW_ONCE))
        assertEquals("""{"reply":"always"}""", OpenCodePermissions.serveReplyBody(com.superiorsoftllc.agenthq.prompts.Decision.ALLOW_SESSION))
        assertEquals("""{"reply":"reject"}""", OpenCodePermissions.serveReplyBody(com.superiorsoftllc.agenthq.prompts.Decision.DENY))
    }

    @Test
    fun `serve - ask config merges the ask rules, keeps other keys, agenthq last`() {
        val merged = OpenCodePermissions.serveConfig(ToolApproval.ASK, """{"username":"u","permission":{"read":"allow","edit":"allow","bash":{"git *":"allow"}}}""")
        assertEquals(
            OpenCodePermissions.withAgentHqMcp("""{"username":"u","permission":{"read":"allow","edit":"ask","webfetch":"ask","websearch":"ask","bash":{"*":"ask","agenthq *":"allow"}}}""", emptyMap()),
            merged,
        )
        // A string permission keeps its fallback under "*".
        assertEquals(
            OpenCodePermissions.withAgentHqMcp("""{"permission":{"*":"allow","edit":"ask","webfetch":"ask","websearch":"ask","bash":{"*":"ask","agenthq *":"allow"}}}""", emptyMap()),
            OpenCodePermissions.serveConfig(ToolApproval.ASK, """{"permission":"allow"}"""),
        )
        // Every other mode adds only the agenthq allow (unchanged PERM-5 behaviour).
        for (mode in listOf(ToolApproval.DENY, ToolApproval.ALLOW_EDITS, ToolApproval.ALLOW_ALL)) {
            assertEquals(OpenCodePermissions.withAgentHqMcp("""{"permission":{"bash":{"agenthq *":"allow"}}}""", emptyMap()), OpenCodePermissions.serveConfig(mode, null), mode.wire)
        }
    }

    @Test
    fun `serve - the card input is the command, else the file, else the patterns`() {
        fun p(json: String) = Json.parseToJsonElement(json) as JsonObject
        assertEquals("git status", OpenCodeServeAdapter.approvalInput(p("""{"patterns":["git *"],"metadata":{"command":"git status"}}""")))
        assertEquals("C:/w/a.txt", OpenCodeServeAdapter.approvalInput(p("""{"patterns":["a.txt"],"metadata":{"filepath":"C:/w/a.txt"}}""")))
        assertEquals("a.txt, b.txt", OpenCodeServeAdapter.approvalInput(p("""{"patterns":["a.txt","b.txt"],"metadata":{}}""")))
        assertEquals("", OpenCodeServeAdapter.approvalInput(p("""{}""")))
        assertTrue(OpenCodeServeAdapter.needsOwner(asked("edit"), ToolApproval.ASK))
        assertFalse(OpenCodeServeAdapter.needsOwner(asked("edit"), ToolApproval.DENY))
        val agentHq = props("""{"properties":{"id":"per_2","permission":"bash","patterns":["agenthq mail inbox"]}}""")
        assertFalse(OpenCodeServeAdapter.needsOwner(agentHq, ToolApproval.ASK), "agenthq is never prompted")
    }

    @Test
    fun `run - ask is deny, and its notice says the harness can't prompt`() {
        assertEquals(OpenCodePermissions.runFlags(ToolApproval.DENY), OpenCodePermissions.runFlags(ToolApproval.ASK))
        assertEquals(OpenCodePermissions.runEnv(ToolApproval.DENY, null), OpenCodePermissions.runEnv(ToolApproval.ASK, null))
        assertFalse(OpenCodePermissions.allows(ToolApproval.ASK, "edit"))
        val run = EventNormalizer.normalise(OpenCodePermissions.denialLine("ses_1", "bash")).single() as EventNormalizer.Line.Denial
        assertTrue(com.superiorsoftllc.agenthq.session.ToolApprovalNotice.CANNOT_PROMPT in
            com.superiorsoftllc.agenthq.session.ToolApprovalNotice.text(run.harness, run.tool, ToolApproval.ASK, run.prompted))
        // A serve deny the owner chose names the prompt instead.
        val serve = EventNormalizer.normalise(OpenCodePermissions.denialLine("ses_1", "bash", prompted = true)).single() as EventNormalizer.Line.Denial
        val text = com.superiorsoftllc.agenthq.session.ToolApprovalNotice.text(serve.harness, serve.tool, ToolApproval.ASK, serve.prompted)
        assertEquals("opencode denied tool bash (Tool approval = ask: not approved at the prompt).", text)
    }
}
