package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.ToolApproval
import com.superiorsoftllc.infantry.harness.api.ApprovalReply
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
        OpenCodeServeSession.permissionDecision(asked(permission), mode)!!

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
        val d = OpenCodeServeSession.permissionDecision(old, ToolApproval.ALLOW_EDITS)!!
        assertEquals("per_9", d.requestId)
        assertEquals("""{"reply":"once"}""", d.replyBody)
    }

    @Test
    fun `serve - a request without an id gets no reply`() {
        assertNull(OpenCodeServeSession.permissionDecision(props("""{"properties":{"permission":"bash"}}"""), ToolApproval.ALLOW_ALL))
    }

    @Test
    fun `run - argv and env per mode`() {
        val base = listOf("opencode.exe", "run", "--format", "json", "-m", "opencode/x")
        assertEquals(base, OpenCodeCommand.command("opencode.exe", "opencode/x", null) + OpenCodeCommand.approvalArgs(ToolApproval.DENY))
        assertEquals(base, OpenCodeCommand.command("opencode.exe", "opencode/x", null) + OpenCodeCommand.approvalArgs(ToolApproval.ALLOW_EDITS))
        assertEquals(base + "--auto", OpenCodeCommand.command("opencode.exe", "opencode/x", null) + OpenCodeCommand.approvalArgs(ToolApproval.ALLOW_ALL))
        assertEquals(base + listOf("--session", "ses_1", "--auto"), OpenCodeCommand.command("opencode.exe", "opencode/x", "ses_1") + OpenCodeCommand.approvalArgs(ToolApproval.ALLOW_ALL))

        // BUG-PERM-5: every mode carries the infantry allow; only allowEdits adds edit.
        // MCP-1: plus the session's own mcp.infantry + its tool allow, merged on top.
        val infantryOnly = OpenCodePermissions.withInfantryMcp("""{"permission":{"bash":{"infantry *":"allow"}}}""", null)
        assertEquals(mapOf("OPENCODE_CONFIG_CONTENT" to infantryOnly), OpenCodePermissions.runEnv(ToolApproval.DENY, existing = null))
        assertEquals(mapOf("OPENCODE_CONFIG_CONTENT" to infantryOnly), OpenCodePermissions.runEnv(ToolApproval.ALLOW_ALL, existing = null))
        assertEquals(
            mapOf("OPENCODE_CONFIG_CONTENT" to OpenCodePermissions.withInfantryMcp("""{"permission":{"edit":"allow","bash":{"infantry *":"allow"}}}""", null)),
            OpenCodePermissions.runEnv(ToolApproval.ALLOW_EDITS, existing = null),
        )
        // The inline config is valid JSON with edit allowed.
        val cfg = Json.parseToJsonElement(OpenCodePermissions.ALLOW_EDITS_CONFIG) as JsonObject
        assertEquals("\"allow\"", (cfg["permission"] as JsonObject)["edit"].toString())
    }

    // --- BUG-PERM-5: the infantry CLI is allowed in every mode, nothing else is ------------------------------------

    private fun bashAsk(vararg patterns: String) = props(
        """{"type":"permission.asked","properties":{"id":"per_2","sessionID":"ses_1","permission":"bash","patterns":[""" +
            patterns.joinToString(",") { "\"$it\"" } + """],"metadata":{},"always":[]}}""",
    )

    @Test
    fun `serve - an infantry bash ask is answered once in every mode, deny included`() {
        for (mode in ToolApproval.entries) {
            val d = OpenCodeServeSession.permissionDecision(bashAsk("infantry ticket create --title x"), mode)!!
            assertEquals("""{"reply":"once"}""", d.replyBody, mode.name)
            assertNull(d.deniedTool, mode.name)
        }
    }

    @Test
    fun `serve - git status is still rejected in deny, with the denial notice`() {
        val d = OpenCodeServeSession.permissionDecision(bashAsk("git status"), ToolApproval.DENY)!!
        assertEquals("""{"reply":"reject"}""", d.replyBody)
        assertEquals("bash", d.deniedTool)
        // An infantry ask that also carries another command is not an infantry ask.
        assertEquals("""{"reply":"reject"}""", OpenCodeServeSession.permissionDecision(bashAsk("infantry ticket list", "git status"), ToolApproval.DENY)!!.replyBody)
        // A non-bash permission is never the infantry exemption, even with an infantry-looking pattern.
        assertEquals("""{"reply":"reject"}""", OpenCodeServeSession.permissionDecision(asked("edit"), ToolApproval.DENY)!!.replyBody)
    }

    @Test
    fun `isInfantryCommand accepts a plain invocation only`() {
        for (ok in listOf("infantry", "infantry ticket create --title \"E2E HELLO\" --desc \"a: b/c\"", "  infantry mail send  ")) assertTrue(OpenCodePermissions.isInfantryCommand(ok), ok)
        for (bad in listOf(null, "", "git status", "infantryx ticket", "echo infantry", "infantry x && git push", "infantry x; rm -rf .",
            "infantry x | sh", "infantry x > out", "infantry `whoami`", "infantry $(whoami)", "infantry x\ngit push")) assertFalse(OpenCodePermissions.isInfantryCommand(bad), bad.toString())
    }

    @Test
    fun `the infantry allow merges into an inherited config and never overwrites it`() {
        fun perm(json: String) = (Json.parseToJsonElement(OpenCodePermissions.mergeAllowInfantry(json)) as JsonObject)
        val merged = perm("""{"model":"x/y","permission":{"edit":"ask","bash":{"*":"deny","git *":"ask"}}}""")
        assertEquals("\"x/y\"", merged["model"].toString())
        // Every user rule kept, ours LAST (opencode resolves the last matching pattern).
        assertEquals("""{"edit":"ask","bash":{"*":"deny","git *":"ask","infantry *":"allow"}}""", merged["permission"].toString())
        assertEquals("""{"*":"ask","infantry *":"allow"}""", (perm("""{"permission":{"bash":"ask"}}""")["permission"] as JsonObject)["bash"].toString())
        assertEquals("""{"*":"deny","bash":{"*":"deny","infantry *":"allow"}}""", perm("""{"permission":"deny"}""")["permission"].toString())
        // A user rule that denies infantry does not win: it is Infantry's own control plane.
        assertEquals("""{"bash":{"*":"ask","infantry *":"allow"}}""", perm("""{"permission":{"bash":{"infantry *":"deny","*":"ask"}}}""")["permission"].toString())
        for (bad in listOf(null, "", "not json", "[1]")) assertEquals("""{"permission":{"bash":{"infantry *":"allow"}}}""", OpenCodePermissions.mergeAllowInfantry(bad))
        // The allowEdits path keeps both: edit (PERM-4) and infantry (PERM-5), and the user's keys.
        val edits = Json.parseToJsonElement(OpenCodePermissions.runEnv(ToolApproval.ALLOW_EDITS, existing = """{"username":"u"}""").getValue("OPENCODE_CONFIG_CONTENT")) as JsonObject
        assertEquals(OpenCodePermissions.withInfantryMcp("""{"username":"u","permission":{"edit":"allow","bash":{"infantry *":"allow"}}}""", null), edits.toString())
    }

    @Test
    fun `a reject emits the denial notice line`() {
        val d = decide("bash", ToolApproval.DENY)
        val line = OpenCodePermissions.denialLine("ses_1", d.deniedTool!!)
        assertEquals(listOf<com.superiorsoftllc.infantry.harness.api.HarnessEvent>(com.superiorsoftllc.infantry.harness.api.HarnessEvent.Denial("bash")), OpenCodeTranslator().translate(line).filterIsInstance<com.superiorsoftllc.infantry.harness.api.HarnessEvent.Denial>())
    }

    // --- PROMPT-4 ---------------------------------------------------------------------------------------

    @Test
    fun `serve - the owner's decision maps to once, always, reject`() {
        assertEquals("""{"reply":"once"}""", OpenCodePermissions.serveReplyBody(ApprovalReply.ALLOW_ONCE))
        assertEquals("""{"reply":"always"}""", OpenCodePermissions.serveReplyBody(ApprovalReply.ALLOW_SESSION))
        assertEquals("""{"reply":"reject"}""", OpenCodePermissions.serveReplyBody(ApprovalReply.DENY))
    }

    @Test
    fun `serve - ask config merges the ask rules, keeps other keys, infantry last`() {
        val merged = OpenCodePermissions.serveConfig(ToolApproval.ASK, """{"username":"u","permission":{"read":"allow","edit":"allow","bash":{"git *":"allow"}}}""")
        assertEquals(
            OpenCodePermissions.withInfantryMcp("""{"username":"u","permission":{"read":"allow","edit":"ask","webfetch":"ask","websearch":"ask","bash":{"*":"ask","infantry *":"allow"}}}""", null),
            merged,
        )
        // A string permission keeps its fallback under "*".
        assertEquals(
            OpenCodePermissions.withInfantryMcp("""{"permission":{"*":"allow","edit":"ask","webfetch":"ask","websearch":"ask","bash":{"*":"ask","infantry *":"allow"}}}""", null),
            OpenCodePermissions.serveConfig(ToolApproval.ASK, """{"permission":"allow"}"""),
        )
        // Every other mode adds only the infantry allow (unchanged PERM-5 behaviour).
        for (mode in listOf(ToolApproval.DENY, ToolApproval.ALLOW_EDITS, ToolApproval.ALLOW_ALL)) {
            assertEquals(OpenCodePermissions.withInfantryMcp("""{"permission":{"bash":{"infantry *":"allow"}}}""", null), OpenCodePermissions.serveConfig(mode, null), mode.name)
        }
    }

    @Test
    fun `serve - the card input is the command, else the file, else the patterns`() {
        fun p(json: String) = Json.parseToJsonElement(json) as JsonObject
        assertEquals("git status", OpenCodeServeSession.approvalInput(p("""{"patterns":["git *"],"metadata":{"command":"git status"}}""")))
        assertEquals("C:/w/a.txt", OpenCodeServeSession.approvalInput(p("""{"patterns":["a.txt"],"metadata":{"filepath":"C:/w/a.txt"}}""")))
        assertEquals("a.txt, b.txt", OpenCodeServeSession.approvalInput(p("""{"patterns":["a.txt","b.txt"],"metadata":{}}""")))
        assertEquals("", OpenCodeServeSession.approvalInput(p("""{}""")))
        assertTrue(OpenCodeServeSession.needsOwner(asked("edit"), ToolApproval.ASK))
        assertFalse(OpenCodeServeSession.needsOwner(asked("edit"), ToolApproval.DENY))
        val infantry = props("""{"properties":{"id":"per_2","permission":"bash","patterns":["infantry mail inbox"]}}""")
        assertFalse(OpenCodeServeSession.needsOwner(infantry, ToolApproval.ASK), "infantry is never prompted")
    }

    @Test
    fun `run - ask is deny, and its notice says the harness can't prompt`() {
        assertEquals(OpenCodePermissions.runFlags(ToolApproval.DENY), OpenCodePermissions.runFlags(ToolApproval.ASK))
        assertEquals(OpenCodePermissions.runEnv(ToolApproval.DENY, null), OpenCodePermissions.runEnv(ToolApproval.ASK, null))
        assertFalse(OpenCodePermissions.allows(ToolApproval.ASK, "edit"))
        val translator = OpenCodeTranslator()
        assertEquals("", (translator.translate(OpenCodePermissions.denialLine("ses_1", "bash")).filterIsInstance<com.superiorsoftllc.infantry.harness.api.HarnessEvent.Denial>().single()).reason)
        // A serve deny the owner chose says so.
        assertEquals("owner did not approve", (translator.translate(OpenCodePermissions.denialLine("ses_1", "bash", prompted = true)).filterIsInstance<com.superiorsoftllc.infantry.harness.api.HarnessEvent.Denial>().single()).reason)
    }
}
